package com.adsbexchange.feeder.service

import com.adsbexchange.feeder.data.StationLocation
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A tiny gpsd look-alike on 127.0.0.1 that tells readsb where the receiver is.
 *
 * readsb supports `--net-connector host,port,gpsd_in` and updates its receiver position from
 * every gpsd TPV report it gets, without a restart. That lets the app move the receiver (mobile
 * mode, or a corrected fix) while readsb keeps running. readsb uses the position for local CPR
 * decoding and to reject implausibly distant aircraft (300 nm by default), so a stale position
 * after the phone has travelled would silently drop real aircraft.
 *
 * The position is usually someone's home, and any app on the phone can connect to 127.0.0.1, so
 * connections are accepted only when [isOwnConnection] (peer port, server port) says they come from
 * this process, i.e. from readsb. Anything else is closed before a byte is sent.
 */
class GpsdServer(private val isOwnConnection: (peerPort: Int, serverPort: Int) -> Boolean) : Closeable {
    // Explicitly IPv4: on Android InetAddress.getLoopbackAddress() is ::1, and readsb dials 127.0.0.1.
    // nosemgrep: kotlin.lang.security.unencrypted-socket.unencrypted-socket - loopback, readsb only
    private val server = ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val clients = CopyOnWriteArrayList<Socket>()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var current: StationLocation? = null

    val port: Int get() = server.localPort

    fun start() {
        thread(name = "gpsd-accept", isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                // One bad connection mustn't end the accept loop, or crash the feeder (as the
                // desktop feeder, code review DF-CQ-01).
                try {
                    if (!isOwnConnection(socket.port, port)) {
                        runCatching { socket.close() }
                        continue
                    }
                    clients += socket
                    drain(socket)
                    send(socket, VERSION)
                    current?.let { send(socket, tpv(it)) }
                } catch (e: Exception) {
                    drop(socket)
                }
            }
        }
        // Re-send periodically so a reconnecting readsb always has a position. A scheduled task
        // that throws is never run again, silently, so it can't be allowed to.
        timer.scheduleWithFixedDelay({ runCatching { current?.let { broadcast(tpv(it)) } } }, 30, 30, TimeUnit.SECONDS)
    }

    fun update(location: StationLocation) {
        current = location
        broadcast(tpv(location))
    }

    override fun close() {
        timer.shutdownNow()
        runCatching { server.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun broadcast(line: String) = clients.forEach { send(it, line) }

    private fun send(socket: Socket, line: String) {
        synchronized(socket) {
            runCatching {
                socket.getOutputStream().apply { write(line.toByteArray()); flush() }
            }.onFailure { drop(socket) }
        }
    }

    /** readsb sends `?WATCH=...` after each report; read and discard so its socket never backs up. */
    private fun drain(socket: Socket) = thread(name = "gpsd-client", isDaemon = true) {
        runCatching {
            val input = socket.getInputStream()
            val buf = ByteArray(512)
            while (input.read(buf) >= 0) Unit
        }
        drop(socket)
    }

    private fun drop(socket: Socket) {
        clients -= socket
        runCatching { socket.close() }
    }

    private companion object {
        const val VERSION =
            "{\"class\":\"VERSION\",\"release\":\"3.25\",\"rev\":\"adsbx-phone-feeder\",\"proto_major\":3,\"proto_minor\":14}\n"

        fun tpv(l: StationLocation): String {
            val alt = l.altitudeM?.let { String.format(Locale.US, ",\"alt\":%.1f", it) } ?: ""
            return String.format(Locale.US, "{\"class\":\"TPV\",\"mode\":3,\"lat\":%.7f,\"lon\":%.7f%s}\n",
                l.lat, l.lon, alt)
        }
    }
}
