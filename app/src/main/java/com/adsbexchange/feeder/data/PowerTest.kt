package com.adsbexchange.feeder.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The settings a [PowerTest] compares. [BASELINE] is what the app normally runs.
 *
 * The first test (Pixel 7 Pro, 2026-10-03, security review B1/B2) compared the Wi-Fi lock and the
 * feed timing: 1.89 W with the lock and readsb's default timing, 1.89 W without the lock, 1.81 W
 * without the lock and with the feed batched, and MLAT made no measurable difference. So the
 * app dropped the lock and now times the feed as ADS-B Exchange's feeder kit does.
 *
 * The second asks whether the feeder needs its partial wake lock. Google Play counts wake locks
 * held for 2 hours a day against an app, foreground services included. A continuously streaming
 * USB device may keep a phone awake by itself, at least on a charger; [NO_WAKE_LOCK] finds out.
 */
enum class PowerVariant(
    /** Hold a partial wake lock while feeding, so the phone's CPU never sleeps. */
    val wakeLock: Boolean,
    /** Run MLAT if the settings ask for it. */
    val mlat: Boolean = true,
) {
    BASELINE(wakeLock = true),
    NO_WAKE_LOCK(wakeLock = false),
}

/**
 * One variant's measurement. Counted time is screen off and feeding; power needs the phone on
 * battery as well, so it's worked out over [batteryMs] only.
 */
data class PowerPhase(
    val variant: PowerVariant,
    /** Time that counted: screen off, feeding, past the warm-up; on a charger or on battery. */
    val countedMs: Long = 0,
    /** The part of [countedMs] on battery, over which the power figures are measured. */
    val batteryMs: Long = 0,
    /** From the battery's coulomb counter: charge used, and that times the voltage. */
    val chargeUah: Long = 0,
    val counterJ: Double = 0.0,
    /** From the instantaneous power readings, every 10 s: rougher, but available on every phone. */
    val sampledJ: Double = 0.0,
    /**
     * Gaps of over a minute between the service's 10-second readings, with the screen off and
     * feeding: the phone's CPU slept (or the app was held up). None should happen with a wake lock.
     */
    val stalls: Int = 0,
    val stalledMs: Long = 0,
    /** readsb's totals for the run, and how often the feed connection dropped. */
    val runMs: Long = 0,
    val messages: Long = 0,
    val feedBytes: Long = 0,
    val reconnects: Int = 0,
    /** Samples the SDR delivered that readsb couldn't take in time (readsb's total for the run). */
    val samplesDropped: Long = 0,
    /** The worst MLAT bad-sync timeout the server reported: above 0, MLAT stopped using the timing. */
    val maxBadSync: Int = 0,
) {
    val counterW: Double? get() = if (batteryMs > 0 && chargeUah > 0) counterJ * 1000 / batteryMs else null
    val sampledW: Double? get() = if (batteryMs > 0) sampledJ * 1000 / batteryMs else null

    fun toJson(): JSONObject = JSONObject()
        .put("variant", variant.name).put("countedMs", countedMs).put("batteryMs", batteryMs)
        .put("chargeUah", chargeUah).put("counterJ", counterJ).put("sampledJ", sampledJ)
        .put("stalls", stalls).put("stalledMs", stalledMs).put("runMs", runMs)
        .put("messages", messages).put("feedBytes", feedBytes).put("reconnects", reconnects)
        .put("samplesDropped", samplesDropped).put("maxBadSync", maxBadSync)

    companion object {
        fun fromJson(o: JSONObject): PowerPhase? = runCatching {
            PowerPhase(
                variant = PowerVariant.valueOf(o.getString("variant")),
                countedMs = o.optLong("countedMs"), batteryMs = o.optLong("batteryMs"),
                chargeUah = o.optLong("chargeUah"),
                counterJ = o.optDouble("counterJ", 0.0), sampledJ = o.optDouble("sampledJ", 0.0),
                stalls = o.optInt("stalls"), stalledMs = o.optLong("stalledMs"),
                runMs = o.optLong("runMs"), messages = o.optLong("messages"),
                feedBytes = o.optLong("feedBytes"), reconnects = o.optInt("reconnects"),
                samplesDropped = o.optLong("samplesDropped"), maxBadSync = o.optInt("maxBadSync"),
            )
        }.getOrNull()
    }
}

/**
 * Compares the feeder's settings ([PowerVariant]) on a real phone, without adb: polling the phone
 * over wireless adb would itself keep it awake, which skews what's measured. The feeder service
 * runs each variant in turn, [PHASE_MS] of counted time each, restarting readsb in between, and
 * the app shows the results.
 *
 * Only representative time counts: screen off, feeding, and past each run's [WARMUP_MS]. On a
 * charger it measures how the feeder holds up (message rate, stalls, dropped samples, feed drops,
 * MLAT sync); on battery, its power as well. The plan ends with the baseline again, so a drift
 * over the test (the evening getting busier, the phone warming up) shows as a difference between
 * the first and last phase.
 */
data class PowerTest(
    val startedMs: Long,
    val plan: List<PowerVariant> = PLAN,
    /** Completed phases, then the one in progress. */
    val phases: List<PowerPhase> = emptyList(),
    val finishedMs: Long? = null,
    /** Why it stopped early, if it did. */
    val stopped: String? = null,
) {
    val running: Boolean get() = finishedMs == null
    /** The phase in progress (completed phases come first in [phases]). */
    val index: Int get() = (phases.size - 1).coerceAtLeast(0)
    val current: PowerVariant? get() = if (running) plan.getOrNull(index) else null

    fun withCurrent(phase: PowerPhase): PowerTest = copy(phases = phases.dropLast(1) + phase)

    /** Whether the phase in progress has what it needs; [runStartMs] is when this run of readsb began. */
    fun phaseDone(nowMs: Long, runStartMs: Long): Boolean {
        val p = phases.lastOrNull() ?: return true
        return p.countedMs >= PHASE_MS || nowMs - runStartMs >= MAX_PHASE_MS
    }

    /** On to the next variant, or finished after the last. */
    fun next(nowMs: Long): PowerTest {
        val n = phases.size
        return if (n < plan.size) copy(phases = phases + PowerPhase(plan[n])) else copy(finishedMs = nowMs)
    }

    fun stop(nowMs: Long, why: String): PowerTest = if (running) copy(finishedMs = nowMs, stopped = why) else this

    fun toJson(): JSONObject = JSONObject()
        .put("startedMs", startedMs)
        .put("plan", JSONArray(plan.map { it.name }))
        .put("phases", JSONArray().apply { phases.forEach { put(it.toJson()) } })
        .put("finishedMs", finishedMs ?: JSONObject.NULL)
        .put("stopped", stopped ?: JSONObject.NULL)

    companion object {
        val PLAN = listOf(
            PowerVariant.BASELINE, PowerVariant.NO_WAKE_LOCK, PowerVariant.BASELINE,
        )
        const val PHASE_MS = 18 * 60_000L
        /** A phase also ends after this long, counted or not (the screen was on a lot, say). */
        const val MAX_PHASE_MS = 45 * 60_000L
        const val WARMUP_MS = 2 * 60_000L
        const val MIN_START_PERCENT = 40

        fun start(nowMs: Long) = PowerTest(startedMs = nowMs, phases = listOf(PowerPhase(PLAN.first())))

        fun read(paths: FeederPaths): PowerTest? = runCatching {
            val o = JSONObject(paths.powerTestFile.readText())
            val plan = o.getJSONArray("plan").let { a -> List(a.length()) { PowerVariant.valueOf(a.getString(it)) } }
            val phases = o.optJSONArray("phases")?.let { a -> (0 until a.length()).mapNotNull { PowerPhase.fromJson(a.getJSONObject(it)) } }.orEmpty()
            PowerTest(
                startedMs = o.getLong("startedMs"), plan = plan, phases = phases,
                finishedMs = o.optDoubleOrNull("finishedMs")?.toLong(),
                stopped = o.optStringOrNull("stopped"),
            )
        }.getOrNull()

        fun write(paths: FeederPaths, test: PowerTest) = paths.powerTestFile.writeAtomically(test.toJson().toString(2))

        fun clear(paths: FeederPaths) {
            paths.powerTestFile.delete()
        }
    }
}

/**
 * Adds one run's readings to its [PowerPhase]; the feeder service calls [sample] every 10 s. An
 * interval counts only if it counted at both ends, so turning the screen on or losing the feed
 * leaves that stretch out entirely; power is added only when the phone was on battery at both
 * ends.
 */
class PowerMeter(private val runStartMs: Long) {
    private class Reading(val ms: Long, val chargeUah: Long?, val voltageMv: Int?, val drainW: Double?,
                          val counts: Boolean, val onBattery: Boolean)

    private var last: Reading? = null

    /**
     * [drainW] is the power the battery gives (positive while discharging). [counts]: screen off
     * and feeding. [onBattery]: not on a charger.
     */
    fun sample(phase: PowerPhase, nowMs: Long, chargeUah: Long?, voltageMv: Int?, drainW: Double?,
               counts: Boolean, onBattery: Boolean): PowerPhase {
        val prev = last
        val now = Reading(nowMs, chargeUah, voltageMv, drainW, counts && nowMs - runStartMs >= WARMUP_MS, onBattery)
        last = now
        if (prev == null || !prev.counts || !now.counts) return phase
        val dt = nowMs - prev.ms
        if (dt <= 0) return phase
        // The service reads every 10 s while it runs. A much longer gap means it didn't run: the
        // phone's CPU slept, or something held the app up. Recorded, not counted.
        if (dt > MAX_GAP_MS) return phase.copy(stalls = phase.stalls + 1, stalledMs = phase.stalledMs + dt)
        var p = phase.copy(countedMs = phase.countedMs + dt)
        if (!prev.onBattery || !now.onBattery) return p
        p = p.copy(batteryMs = p.batteryMs + dt)
        if (prev.drainW != null && now.drainW != null) p = p.copy(sampledJ = p.sampledJ + (prev.drainW + now.drainW) / 2 * dt / 1000)
        val used = if (prev.chargeUah != null && now.chargeUah != null) prev.chargeUah - now.chargeUah else null
        // The counter only goes down on battery; a rise means it was recalibrated, so skip that step.
        if (used != null && used >= 0) {
            val volts = listOfNotNull(prev.voltageMv, now.voltageMv).average().takeIf { !it.isNaN() }?.div(1000)
            if (volts != null) p = p.copy(chargeUah = p.chargeUah + used, counterJ = p.counterJ + used * 3.6e-3 * volts)
        }
        return p
    }

    companion object {
        private const val WARMUP_MS = PowerTest.WARMUP_MS
        /** A longer gap between readings (the phone suspended, a stall) isn't trusted. */
        private const val MAX_GAP_MS = 60_000L
    }
}
