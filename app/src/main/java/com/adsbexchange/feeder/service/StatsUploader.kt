package com.adsbexchange.feeder.service

import com.adsbexchange.feeder.BuildConfig
import com.adsbexchange.feeder.data.readReadsbJson
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import kotlin.math.floor
import kotlin.random.Random

/**
 * ADS-B Exchange's stats package (github.com/ADSBexchange/adsbexchange-stats, its `json-status`
 * script) as part of the app, ported from the desktop feeder: every 5 s, readsb's aircraft.json
 * with the feeder UUID and signal figures added, gzipped and posted to ADS-B Exchange.
 *
 * Without it ADS-B Exchange lists the feeder as "beast-only": it gets the feed, but the feeder's
 * statistics (aircraft seen, positions, range, uptime), its stats page ([statsPageUrl]), the
 * leaderboard and the Feeder Hub all come from these uploads.
 *
 * Same request as the script: same URL, headers and body, so ADS-B Exchange can't tell the two
 * apart beyond the UUID and the User-Agent ([USER_AGENT]). One difference: readsb's per-aircraft
 * distance and bearing from the receiver (r_dst, r_dir), from which the receiver's position can be
 * worked out, are only sent while MLAT already sends ADS-B Exchange the position ([includeRange]).
 *
 * Runs on a thread of its own, so a slow upload (up to 10 s) never holds up the feeder service.
 */
class StatsUploader(
    private val config: Config,
    /** Asked before each upload: whether MLAT is sending ADS-B Exchange the receiver position. */
    private val includeRange: () -> Boolean,
    private val log: (String) -> Unit,
) {
    data class Config(
        val uuid: String,
        val aircraftJson: File,
        val url: String = UPLOAD_URL,
        val intervalMs: Long = INTERVAL_MS,
    )

    /** For the Status screen. */
    data class Stats(
        val uploads: Long = 0,
        val bytesSent: Long = 0,
        val lastOkMs: Long? = null,
        /** Why the latest attempt failed; null once one succeeds. */
        val error: String? = null,
    )

    @Volatile var stats = Stats()
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::run, "stats-upload").apply { isDaemon = true; start() }
        log("stats: sending ADS-B Exchange this feeder's stats every ${config.intervalMs / 1000} s")
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        try {
            // As the script: a random start within the first interval spreads feeders' uploads out.
            Thread.sleep(Random.nextLong(config.intervalMs))
            while (running) {
                val began = System.nanoTime()
                // An unexpected error counts as a failed round rather than ending the uploads, or
                // crashing the feeder with them (as the desktop feeder, code review DF-CQ-01).
                val ok = try {
                    uploadOnce()
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    fail("unexpected error: ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
                // After a failure, wait a little longer (5-20 s in the script).
                val extra = if (ok) 0 else config.intervalMs + Random.nextLong(config.intervalMs * 3)
                val elapsedMs = (System.nanoTime() - began) / 1_000_000
                Thread.sleep((config.intervalMs + extra - elapsedMs).coerceAtLeast(0))
            }
        } catch (_: InterruptedException) {
            // stop()
        }
    }

    /** One upload. False if it failed (and should be retried after a longer pause). */
    private fun uploadOnce(): Boolean {
        val file = config.aircraftJson
        val age = System.currentTimeMillis() - file.lastModified()
        // Nothing current to send (readsb is starting, or stuck): skip rather than send stale data.
        if (!file.isFile || age > STALE_MS) return true
        val body = try {
            // Oversized (thousands of faked aircraft, see MAX_READSB_JSON_BYTES): skip this round.
            payload(file.readReadsbJson() ?: return true, config.uuid, includeRange())
        } catch (e: Exception) {
            // Usually readsb replacing the file at that moment; the next round reads the new one.
            return true
        } ?: run {
            fail("aircraft.json not in the expected format; not sent")
            return false
        }
        val error = try {
            post(body)
        } catch (e: IOException) {
            // The connection is kept between uploads (see post), and ADS-B Exchange's end closes
            // connections every 5 minutes or so. An upload that goes out just as that happens gets
            // no reply. Sending the stats twice is harmless, so try once more straight away: the
            // failed connection has been dropped, so this one is fresh.
            try {
                post(body)
            } catch (e2: IOException) {
                e2.message?.takeIf { it.isNotBlank() } ?: e2.javaClass.simpleName
            }
        }
        if (error != null) {
            fail(error)
            return false
        }
        if (stats.error != null) log("stats: sending again")
        stats = stats.copy(uploads = stats.uploads + 1, bytesSent = stats.bytesSent + body.size,
            lastOkMs = System.currentTimeMillis(), error = null)
        return true
    }

    /** One POST of [body]: null if accepted, else "HTTP <code>". Network failures throw. */
    private fun post(body: ByteArray): String? {
        val conn = URL(config.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("adsbx-uuid", config.uuid)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            // Spelt as the script spells it. ADS-B Exchange's server expects this request as is.
            conn.setRequestProperty("Content_Encoding", "gzip")
            // What curl --data-binary sends.
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            // Read the reply to the end, so the connection can be kept and reused next time rather
            // than set up again (with TLS) every 5 s, which would cost the phone more power.
            runCatching { (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } }
            return if (code in 200..299) null else "HTTP $code"
        } catch (e: IOException) {
            conn.disconnect() // don't hand a broken connection back for reuse
            throw e
        } finally {
            if (!running) conn.disconnect()
        }
    }

    /** Logged once per run of failures, not every 5 s. */
    private fun fail(error: String) {
        if (stats.error == null) log("stats: not sent ($error); trying again")
        stats = stats.copy(error = error)
    }

    companion object {
        /** From adsbexchange-stats' json-status (REMOTE_URL). */
        const val UPLOAD_URL = "https://adsbexchange.com/api/receive/"
        /** json-status' WAIT_TIME. */
        const val INTERVAL_MS = 5_000L
        /** readsb rewrites aircraft.json every few seconds; older than this, it isn't running properly. */
        private const val STALE_MS = 30_000L
        private const val TIMEOUT_MS = 10_000
        /**
         * The app and its version only. Android's default ("Dalvik/2.1.0 (Linux; U; Android 14;
         * Pixel 7 Pro Build/…)") would send ADS-B Exchange the phone's model and Android version.
         */
        internal val USER_AGENT = "ADS-B-Exchange-Phone-Feeder/${BuildConfig.VERSION_NAME}"

        /** The feeder's stats page, as adsbexchange-stats' `adsbexchange-showurl` prints it. */
        fun statsPageUrl(uuid: String) = "https://www.adsbexchange.com/api/feeders/?feed=$uuid"

        /** readsb's form for these (json_out.c): `,"r_dst":%.3f,"r_dir":%.1f`. */
        private val RANGE_FIELDS = Regex(""","r_dst":-?[0-9.]+,"r_dir":-?[0-9.]+""")

        /**
         * readsb's estimate of the receiver's position from an aircraft, `,"rr_lat":%.1f,"rr_lon":%.1f`
         * (json_out.c). Not produced in this app's set-up, but left out with r_dst and r_dir if it
         * ever is (security report MF-LOG-11).
         */
        private val RECEIVER_FIELDS = Regex(""","rr_lat":-?[0-9.]+,"rr_lon":-?[0-9.]+""")
        private val LEFT_OUT = listOf("\"r_dst\"", "\"r_dir\"", "\"rr_lat\"", "\"rr_lon\"")

        /**
         * The gzipped upload: [aircraftJson] as readsb wrote it, plus what json-status' jq adds
         * (uuid, v, rssi, rssi-min, rssi-max). null if it isn't readsb's aircraft.json, or if r_dst
         * is to be left out and couldn't be (so a format change can't leak it).
         */
        internal fun payload(aircraftJson: String, uuid: String, includeRange: Boolean): ByteArray? {
            val json = payloadJson(aircraftJson, uuid, includeRange) ?: return null
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            return out.toByteArray()
        }

        /**
         * readsb's text is kept as it is (re-encoding it would change how numbers are written) and
         * the new fields go in before its closing brace, which is what jq's output amounts to.
         */
        internal fun payloadJson(aircraftJson: String, uuid: String, includeRange: Boolean): String? {
            val parsed = JSONObject(aircraftJson)
            val aircraft = parsed.optJSONArray("aircraft") ?: return null
            val text = if (includeRange) aircraftJson else aircraftJson.replace(RANGE_FIELDS, "").replace(RECEIVER_FIELDS, "")
            if (!includeRange && LEFT_OUT.any { it in text }) return null
            val end = text.lastIndexOf('}')
            if (end < 0) return null
            // jq: (.aircraft[].rssi | add / length | floor), min | floor, max | floor; 0 with no aircraft.
            val rssi = (0 until aircraft.length())
                .mapNotNull { aircraft.optJSONObject(it)?.optDouble("rssi")?.takeUnless(Double::isNaN) }
            fun whole(d: Double?) = d?.let { floor(it).toLong() } ?: 0L
            val extra = JSONObject()
                .put("uuid", uuid)
                // The script's Raspberry Pi throttling flags (vcgencmd); a phone has none.
                .put("v", "")
                .put("rssi", whole(rssi.takeIf { it.isNotEmpty() }?.average()))
                .put("rssi-min", whole(rssi.minOrNull()))
                .put("rssi-max", whole(rssi.maxOrNull()))
            return buildString {
                append(text, 0, end)
                append(',').append(extra.toString().removePrefix("{").removeSuffix("}"))
                append(text, end, text.length)
            }
        }
    }
}
