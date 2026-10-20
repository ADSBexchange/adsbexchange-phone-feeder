package com.adsbexchange.feeder.mlat

import java.util.Locale

/**
 * Every [everyMs] while MLAT is connected, a log line with the MLAT server's view of this
 * feeder's timing (nearby feeders in sync, outliers, bad-sync timeout) and what was sent since
 * the last line. The MLAT card shows these live; the log keeps them, so a long unattended run
 * leaves a record of whether the phone's timing held up (screen off, Doze, heat).
 *
 * From the desktop feeder (`FeederEngine.logMlatSync`, 0.5.8), where this log found a timing
 * problem in Windows' USB driver (the desktop README, "MLAT timing on Windows").
 */
class MlatSyncLog(private val everyMs: Long = EVERY_MS) {
    private var sinceMs = 0L
    private var syncs = 0L
    private var mlat = 0L

    /** A new MLAT client: its counters start from zero, so the next line counts from [nowMs]. */
    fun restart(nowMs: Long) {
        sinceMs = nowMs
        syncs = 0
        mlat = 0
    }

    /** The line to log now, or null if MLAT isn't connected or it isn't time yet. */
    fun next(s: MlatClient.Stats, nowMs: Long): String? {
        if (s.state != MlatClient.State.CONNECTED || nowMs - sinceMs < everyMs) return null
        val l = line(s, (s.syncsSent - syncs).coerceAtLeast(0), (s.mlatSent - mlat).coerceAtLeast(0),
            ((nowMs - sinceMs) / 60_000).toInt())
        sinceMs = nowMs
        syncs = s.syncsSent
        mlat = s.mlatSent
        return l
    }

    companion object {
        const val EVERY_MS = 10 * 60_000L

        /**
         * e.g. "mlat: synced with 7 nearby feeders, sync outliers 1.5%, bad-sync timeout 0; last 10
         * min: 925 sync pairs and 463 MLAT messages sent, 6 aircraft requested". Same as the desktop's.
         */
        fun line(s: MlatClient.Stats, syncs: Long, mlat: Long, minutes: Int): String = buildString {
            append("mlat: ")
            if (s.peerCount == null) append("the server hasn't reported sync yet")
            else append("synced with ${s.peerCount} nearby feeder${if (s.peerCount == 1) "" else "s"}")
            s.outlierPercent?.let { append(", sync outliers %.1f%%".format(Locale.US, it)) }
            s.badSyncTimeout?.let { append(", bad-sync timeout $it") }
            append("; last $minutes min: $syncs sync pairs and $mlat MLAT messages sent, ${s.requested} aircraft requested")
        }
    }
}
