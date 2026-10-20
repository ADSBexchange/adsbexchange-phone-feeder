package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PowerTestTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_000_000_000L
    private val warm = t0 + PowerTest.WARMUP_MS

    /** 3.85 V, 2.5 W drawn: 649.35 mA, i.e. 1803.75 uAh per 10 s. */
    private fun run(
        meter: PowerMeter, from: Long, seconds: Int, counts: (Long) -> Boolean = { true },
        onBattery: (Long) -> Boolean = { true }, startUah: Long = 4_000_000L,
    ): PowerPhase {
        var p = PowerPhase(PowerVariant.BASELINE)
        var t = from
        var charge = startUah.toDouble()
        while (t <= from + seconds * 1000L) {
            p = meter.sample(p, t, charge.toLong(), 3850, 2.5, counts(t), onBattery(t))
            t += 10_000
            charge -= 2.5 / 3.85 * 1e6 * 10 / 3600
        }
        return p
    }

    @Test
    fun measuresPowerFromTheCounterAndTheReadings() {
        val p = run(PowerMeter(t0), warm, 600)
        assertEquals(600_000L, p.countedMs)
        assertEquals(600_000L, p.batteryMs)
        assertEquals(2.5, p.sampledW!!, 1e-9)
        assertEquals(2.5, p.counterW!!, 0.01)
    }

    @Test
    fun skipsTheWarmUp() {
        val p = run(PowerMeter(t0), t0, 300)
        assertEquals("only what came after 2 minutes", 180_000L, p.countedMs)
    }

    @Test
    fun leavesOutIntervalsThatDidNotCount() {
        // Screen on for a minute in the middle: that minute, and the edges, drop out.
        val p = run(PowerMeter(t0), warm, 600, counts = { it !in (warm + 200_000)..(warm + 260_000) })
        assertEquals(600_000L - 80_000L, p.countedMs)
        assertEquals(2.5, p.sampledW!!, 1e-9)
        assertEquals(2.5, p.counterW!!, 0.01)
    }

    @Test
    fun countsChargerTimeButMeasuresPowerOnlyOnBattery() {
        // On a charger for a minute in the middle: the time counts, the power doesn't.
        val p = run(PowerMeter(t0), warm, 600, onBattery = { it !in (warm + 200_000)..(warm + 260_000) })
        assertEquals(600_000L, p.countedMs)
        assertEquals(600_000L - 80_000L, p.batteryMs)
        assertEquals(2.5, p.sampledW!!, 1e-9)
        assertEquals(2.5, p.counterW!!, 0.01)

        val charging = run(PowerMeter(t0), warm, 600, onBattery = { false })
        assertEquals(600_000L, charging.countedMs)
        assertNull("no power figures without time on battery", charging.sampledW)
        assertNull(charging.counterW)
    }

    @Test
    fun recordsStallsAndIgnoresCounterJumps() {
        val m = PowerMeter(t0)
        var p = PowerPhase(PowerVariant.BASELINE)
        p = m.sample(p, warm, 4_000_000, 3850, 2.0, true, true)
        p = m.sample(p, warm + 120_000, 3_990_000, 3850, 2.0, true, true) // 2 minutes: the phone slept
        assertEquals(0L, p.countedMs)
        assertEquals(1, p.stalls)
        assertEquals(120_000L, p.stalledMs)
        p = m.sample(p, warm + 130_000, 4_100_000, 3850, 2.0, true, true) // recalibrated upwards
        assertEquals(10_000L, p.countedMs)
        assertEquals(0L, p.chargeUah)
        assertNull("no counter figure without counter data", p.counterW)
        assertEquals(2.0, p.sampledW!!, 1e-9)
    }

    @Test
    fun aGapWithTheScreenOnIsNotAStall() {
        val m = PowerMeter(t0)
        var p = PowerPhase(PowerVariant.BASELINE)
        p = m.sample(p, warm, 4_000_000, 3850, 2.0, false, true)
        p = m.sample(p, warm + 120_000, 3_990_000, 3850, 2.0, true, true)
        assertEquals(0, p.stalls)
    }

    @Test
    fun runsThePlanAndEndsWithTheBaselineAgain() {
        var t = PowerTest.start(t0)
        assertEquals(PowerVariant.BASELINE, t.current)
        val seen = mutableListOf<PowerVariant>()
        while (t.running) {
            seen += t.current!!
            assertFalse(t.phaseDone(t0, t0))
            t = t.withCurrent(t.phases.last().copy(countedMs = PowerTest.PHASE_MS))
            assertTrue(t.phaseDone(t0, t0))
            t = t.next(t0)
        }
        assertEquals(PowerTest.PLAN, seen)
        assertEquals(listOf(PowerVariant.BASELINE, PowerVariant.NO_WAKE_LOCK, PowerVariant.BASELINE), seen)
        assertEquals(PowerTest.PLAN.size, t.phases.size)
        assertNull(t.current)
    }

    @Test
    fun aPhaseAlsoEndsAfterTooLong() {
        val t = PowerTest.start(t0)
        assertTrue(t.phaseDone(t0 + PowerTest.MAX_PHASE_MS, t0))
    }

    @Test
    fun savesAndReadsBack() {
        val paths = FeederPaths(tmp.root)
        val t = PowerTest.start(t0).let {
            it.withCurrent(it.phases.last().copy(
                countedMs = 60_000, batteryMs = 50_000, chargeUah = 10_000, counterJ = 138.6, messages = 5,
                stalls = 2, stalledMs = 150_000, samplesDropped = 7, maxBadSync = 3,
            ))
        }.next(t0 + 1).stop(t0 + 2, "cancelled")
        PowerTest.write(paths, t)
        assertEquals(t, PowerTest.read(paths))
        PowerTest.clear(paths)
        assertNull(PowerTest.read(paths))
    }

    /** A test saved by the previous version (a variant this one doesn't know) reads as nothing, not a crash. */
    @Test
    fun anOldTestWithAnUnknownVariantIsIgnored() {
        val paths = FeederPaths(tmp.root)
        paths.powerTestFile.parentFile?.mkdirs()
        paths.powerTestFile.writeText("""{"startedMs":1,"plan":["BASELINE","PREVIOUS","BASELINE"],"phases":[],"finishedMs":null,"stopped":null}""")
        assertNull(PowerTest.read(paths))
    }

    /** The feed is timed as ADS-B Exchange's feeder kit times it. */
    @Test
    fun feedTimingMatchesTheKit() {
        val s = FeederSettings(uuid = "u", name = "", location = null)
        val paths = FeederPaths(tmp.root)
        val normal = s.readsbArgs(paths, 1, FeederSettings.ADSBX_FEED)
        assertEquals("0.5", normal[normal.indexOf("--net-beast-reduce-interval") + 1])
        assertEquals("0.2", normal[normal.indexOf("--net-ro-interval-beast-reduce") + 1])
        assertFalse("not the global flush interval, which the MLAT loopback uses",
            normal.contains("--net-ro-interval"))
        assertFalse("no feed, nothing to time", s.readsbArgs(paths, 1, null).contains("--net-beast-reduce-interval"))
    }
}
