package com.adsbexchange.feeder.data

/**
 * Warns when the battery is running down although the phone has been on a charger, so a dead
 * battery doesn't quietly end feeding. Driven by the feeder service's tick.
 *
 * "Not charging" alone isn't enough: phones pause charging for a minute when warm, and hold at a
 * charge limit (e.g. 80%) without anything being wrong. What matters is the battery level falling.
 * So the alert fires once the phone has been on a charger during this run, isn't charging, and has
 * dropped [DROP_PERCENT] points since it stopped. That covers both a charger that is still
 * connected but not delivering (seen with a wireless pad: two hours of "not charging") and one that
 * has dropped out.
 */
class ChargeWatchdog {
    enum class Change { ALERT, CLEAR }

    private var chargerSeen = false
    private var levelWhenStopped: Int? = null

    var alerted = false
        private set

    /**
     * @param running readsb is running.
     * @param onCharger something is plugged in (USB, AC or a wireless pad).
     * @param charging the battery is actually charging, or full.
     * @return a change to the alert, or null.
     */
    fun tick(running: Boolean, onCharger: Boolean, charging: Boolean, levelPercent: Int?): Change? {
        if (!running) {
            chargerSeen = false
            levelWhenStopped = null
            return clear()
        }
        if (onCharger) chargerSeen = true
        if (charging) {
            levelWhenStopped = null
            return clear()
        }
        if (!chargerSeen || levelPercent == null) return null
        val start = levelWhenStopped ?: levelPercent.also { levelWhenStopped = it }
        if (!alerted && start - levelPercent >= DROP_PERCENT) {
            alerted = true
            return Change.ALERT
        }
        return null
    }

    /** For turning alerts off: forgets the current alert so it can be cleared. */
    fun clear(): Change? = if (alerted) Change.CLEAR.also { alerted = false } else null

    companion object {
        /** About 15 minutes of feeding on battery (measured: ~12% an hour on a Pixel 7 Pro). */
        const val DROP_PERCENT = 3
    }
}
