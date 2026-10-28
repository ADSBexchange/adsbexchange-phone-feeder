package com.adsbexchange.feeder.data

import com.adsbexchange.feeder.data.FeederStatus.Reason
import java.io.File

/**
 * Pauses feeding to protect the battery of a phone left running for months: when it gets hot
 * (constant decoding on a charger in the sun is how old batteries swell), and when it's about to
 * run flat on battery (deep discharge ages it, and the sudden shutdown loses history). Feeding
 * resumes on its own once the phone has cooled down or is charging.
 *
 * The stop thresholds sit above the resume ones, so a phone hovering around a limit doesn't
 * start and stop every few seconds.
 */
object BatteryGuard {
    /** Pause at this battery temperature or above. [PhoneHealth.HOT_BATTERY_C] (40 °C) only warns. */
    const val HOT_STOP_C = 45.0
    const val COOL_RESUME_C = 40.0
    const val LOW_STOP_PERCENT = 10

    /**
     * Whether feeding should be paused now, and why. [current] is the reason it's paused already,
     * if it is: that reason then lasts until its resume condition is met.
     */
    fun hold(h: PhoneHealth, current: Reason? = null): Reason? {
        val hot = if (current == Reason.HOT) {
            (h.batteryTempC ?: 0.0) >= COOL_RESUME_C || (h.thermal ?: PhoneHealth.Thermal.NONE) >= PhoneHealth.Thermal.MODERATE
        } else {
            (h.batteryTempC ?: 0.0) >= HOT_STOP_C || (h.thermal ?: PhoneHealth.Thermal.NONE) >= PhoneHealth.Thermal.SEVERE
        }
        if (hot) return Reason.HOT
        val low = if (current == Reason.LOW_BATTERY) !h.batteryCharging
            else !h.batteryCharging && h.batteryPercent != null && h.batteryPercent <= LOW_STOP_PERCENT
        return if (low) Reason.LOW_BATTERY else null
    }

    /**
     * The pause, kept in a file because pausing ends the `:feeder` process (readsb can't restart
     * within one) and the service that Android restarts has to know it's paused, not crashed.
     */
    object Hold {
        /** The reasons that pause rather than stop: [BatteryGuard]'s, and [NetworkGuard]'s. */
        val PAUSE_REASONS = setOf(Reason.HOT, Reason.LOW_BATTERY, Reason.MOBILE_DATA)

        fun read(paths: FeederPaths): Reason? = runCatching {
            Reason.valueOf(paths.holdFile.readText().trim())
        }.getOrNull()?.takeIf { it in PAUSE_REASONS }

        fun write(paths: FeederPaths, reason: Reason) = paths.holdFile.writeAtomically(reason.name)

        fun clear(paths: FeederPaths) {
            paths.holdFile.delete()
        }
    }
}

/**
 * Keeps the feed off mobile data unless the user allows it (security review B4). A phone at home
 * with a SIM falls back to mobile data whenever Wi-Fi drops, and a busy site's feed is a few GB a
 * month of someone's data allowance. So feeding pauses, the same way as [BatteryGuard]'s pauses,
 * and resumes when the phone is back on Wi-Fi (or any unmetered network). A run that doesn't
 * feed at all carries on.
 */
object NetworkGuard {
    /** Mobile data for this long before pausing, so a brief Wi-Fi drop doesn't restart readsb. */
    const val PAUSE_AFTER_MS = 30_000L

    /**
     * Whether feeding now would use mobile data that the settings don't allow. [metered] is the
     * default network's: true for mobile data or a metered Wi-Fi, null with no network at all
     * (nothing is sent then).
     */
    fun wouldUseMobileData(metered: Boolean?, feeding: Boolean, settings: FeederSettings): Boolean =
        metered == true && feeding && !settings.feedOverMobileData
}

/**
 * Stops a crash loop. readsb ends its process on any fatal error and Android restarts the
 * service, which tries the SDR again: a failing dongle or cable would otherwise cycle for ever,
 * costing power while the app only ever says "starting". Too many unplanned restarts in a short
 * time and the service gives up with [Reason.SDR_FAILING].
 */
class RestartBudget(private val file: File) {
    /** Counts an unplanned restart; false means this makes [MAX_EXITS] exits within [WINDOW_MS]: give up. */
    fun allow(nowMs: Long): Boolean {
        val recent = read().filter { nowMs - it in 0 until WINDOW_MS } + nowMs
        runCatching { file.writeAtomically(recent.joinToString("\n")) }
        return recent.size < MAX_EXITS
    }

    /** A deliberate start (the user, a plug-in, boot) begins with a fresh budget. */
    fun reset() {
        file.delete()
        expectedFile.delete()
    }

    /** The service is about to end readsb on purpose (see [PowerTest]): the next restart isn't a failure. */
    fun expectRestart() {
        runCatching { expectedFile.writeText("1") }
    }

    /** Whether this restart was announced with [expectRestart]; consumes the announcement. */
    fun consumeExpected(): Boolean = expectedFile.exists().also { expectedFile.delete() }

    private val expectedFile get() = File(file.parentFile, file.name + ".expected")

    private fun read(): List<Long> =
        runCatching { file.readLines().mapNotNull { it.trim().toLongOrNull() } }.getOrDefault(emptyList())

    companion object {
        const val MAX_EXITS = 5
        const val WINDOW_MS = 15 * 60_000L
    }
}
