package com.adsbexchange.feeder.data

/**
 * Decides when to tell the user that feeding has stopped working, driven by the feeder service's
 * tick. One alert per problem: it fires once when the problem has lasted long enough, and is
 * cleared once it's over. Unplugging the SDR has its own alert in the service.
 *
 * The thresholds are generous on purpose: a rural receiver can hear nothing for an hour at night,
 * and Wi-Fi blips for a few minutes, and neither is worth waking anyone up for.
 */
class FeedWatchdog {
    enum class Problem { FEED_DOWN, NO_AIRCRAFT }

    sealed interface Action {
        data class Alert(val problem: Problem) : Action
        data object Clear : Action
    }

    private var feedDownSinceMs: Long? = null
    private var lastMessages: Long? = null
    private var quietSinceMs: Long? = null

    /** The problem currently alerted, if any. */
    var alerted: Problem? = null
        private set

    /**
     * @param running readsb is running (not stopped by the user, not waiting for the SDR).
     * @param feeding this run sends to ADS-B Exchange.
     * @param messagesTotal readsb's running message count, when known (it's sampled about once a minute).
     * @return what to do with the alert now, or null to leave it as it is.
     */
    fun tick(running: Boolean, feeding: Boolean, feedConnected: Boolean, messagesTotal: Long?, nowMs: Long): Action? {
        if (!running) {
            feedDownSinceMs = null
            lastMessages = null
            quietSinceMs = null
            return clear()
        }
        feedDownSinceMs = if (feeding && !feedConnected) feedDownSinceMs ?: nowMs else null
        if (messagesTotal != null && messagesTotal != lastMessages) {
            lastMessages = messagesTotal
            quietSinceMs = nowMs
        }
        if (quietSinceMs == null) quietSinceMs = nowMs

        val problem = when {
            feedDownSinceMs?.let { nowMs - it >= FEED_DOWN_MS } == true -> Problem.FEED_DOWN
            quietSinceMs?.let { nowMs - it >= NO_AIRCRAFT_MS } == true -> Problem.NO_AIRCRAFT
            else -> null
        }
        return when {
            problem == alerted -> null
            problem == null -> clear()
            else -> Action.Alert(problem).also { alerted = problem }
        }
    }

    /** For turning alerts off: forgets the current alert so it can be cleared. */
    fun clear(): Action? = if (alerted != null) Action.Clear.also { alerted = null } else null

    companion object {
        const val FEED_DOWN_MS = 15 * 60_000L
        const val NO_AIRCRAFT_MS = 2 * 60 * 60_000L
    }
}
