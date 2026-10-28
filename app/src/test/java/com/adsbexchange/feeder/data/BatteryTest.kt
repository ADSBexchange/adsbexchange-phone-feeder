package com.adsbexchange.feeder.data

import com.adsbexchange.feeder.data.FeederStatus.Reason
import com.adsbexchange.feeder.data.PhoneHealth.Thermal
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.TimeZone

class BatteryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun health(percent: Int? = 80, charging: Boolean = true, tempC: Double? = 30.0, thermal: Thermal? = Thermal.NONE) =
        PhoneHealth(
            batteryPercent = percent, charger = if (charging) PhoneHealth.Charger.AC else PhoneHealth.Charger.NONE,
            batteryCharging = charging, batteryTempC = tempC, batteryPowerW = null, thermal = thermal,
            ignoringBatteryOptimizations = true,
        )

    @Test
    fun feedsWhenTheBatteryIsFine() {
        assertNull(BatteryGuard.hold(health()))
        assertNull(BatteryGuard.hold(health(percent = 11, charging = false)))
        assertNull(BatteryGuard.hold(health(percent = 5, charging = true)))
        assertNull("warm is a warning, not a pause", BatteryGuard.hold(health(tempC = 44.9, thermal = Thermal.MODERATE)))
        assertNull("no readings at all", BatteryGuard.hold(health(percent = null, tempC = null, thermal = null)))
    }

    @Test
    fun pausesWhenHot() {
        assertEquals(Reason.HOT, BatteryGuard.hold(health(tempC = 45.0)))
        assertEquals(Reason.HOT, BatteryGuard.hold(health(thermal = Thermal.SEVERE)))
        assertEquals("hot first: cooling down matters more", Reason.HOT, BatteryGuard.hold(health(percent = 5, charging = false, tempC = 46.0)))
    }

    @Test
    fun resumesOnlyOnceProperlyCool() {
        assertEquals(Reason.HOT, BatteryGuard.hold(health(tempC = 42.0), Reason.HOT))
        assertEquals(Reason.HOT, BatteryGuard.hold(health(tempC = 35.0, thermal = Thermal.MODERATE), Reason.HOT))
        assertNull(BatteryGuard.hold(health(tempC = 39.9, thermal = Thermal.LIGHT), Reason.HOT))
    }

    @Test
    fun pausesOnLowBatteryUntilCharging() {
        assertEquals(Reason.LOW_BATTERY, BatteryGuard.hold(health(percent = 10, charging = false)))
        assertEquals(Reason.LOW_BATTERY, BatteryGuard.hold(health(percent = 12, charging = false), Reason.LOW_BATTERY))
        assertNull(BatteryGuard.hold(health(percent = 9, charging = true), Reason.LOW_BATTERY))
        assertEquals("charging but hot", Reason.HOT, BatteryGuard.hold(health(percent = 9, charging = true, tempC = 47.0), Reason.LOW_BATTERY))
    }

    @Test
    fun holdSurvivesInAFile() {
        val paths = FeederPaths(tmp.root)
        assertNull(BatteryGuard.Hold.read(paths))
        BatteryGuard.Hold.write(paths, Reason.LOW_BATTERY)
        assertEquals(Reason.LOW_BATTERY, BatteryGuard.Hold.read(paths))
        paths.holdFile.writeText(Reason.USER_STOPPED.name)
        assertNull("only battery reasons are holds", BatteryGuard.Hold.read(paths))
        BatteryGuard.Hold.clear(paths)
        assertNull(BatteryGuard.Hold.read(paths))
    }

    @Test
    fun mobileDataPausesFeedingUnlessAllowed() {
        val s = FeederSettings(uuid = "u", name = "", location = null)
        assertTrue(NetworkGuard.wouldUseMobileData(metered = true, feeding = true, settings = s))
        assertFalse("Wi-Fi", NetworkGuard.wouldUseMobileData(false, true, s))
        assertFalse("no network: nothing is sent", NetworkGuard.wouldUseMobileData(null, true, s))
        assertFalse("receive only", NetworkGuard.wouldUseMobileData(true, false, s))
        assertFalse("allowed in Settings", NetworkGuard.wouldUseMobileData(true, true, s.copy(feedOverMobileData = true)))
    }

    @Test
    fun mobileDataPauseSurvivesInAFile() {
        val paths = FeederPaths(tmp.root)
        BatteryGuard.Hold.write(paths, Reason.MOBILE_DATA)
        assertEquals(Reason.MOBILE_DATA, BatteryGuard.Hold.read(paths))
        assertNull("not a battery reason", BatteryGuard.hold(health(), Reason.MOBILE_DATA))
    }

    @Test
    fun restartBudgetGivesUpOnTheFifthExitInFifteenMinutes() {
        val budget = RestartBudget(tmp.newFile())
        val t0 = 1_000_000_000L
        repeat(4) { assertTrue(budget.allow(t0 + it * 60_000L)) }
        assertFalse(budget.allow(t0 + 4 * 60_000L))
    }

    @Test
    fun restartBudgetForgetsOldExitsAndResets() {
        val file = tmp.newFile()
        val t0 = 1_000_000_000L
        val budget = RestartBudget(file)
        repeat(4) { assertTrue(budget.allow(t0 + it * 60_000L)) }
        assertTrue("the first exits are now over 15 minutes old", RestartBudget(file).allow(t0 + 16 * 60_000L))
        budget.reset()
        repeat(4) { assertTrue(budget.allow(t0 + 17 * 60_000L)) }
    }

    @Test
    fun recordsPowerOnBatteryPerDay() {
        val utc = TimeZone.getTimeZone("UTC")
        val day0 = 20_000 * HistoryRecorder.DAY_MS
        val r = HistoryRecorder(HistoryStore.Loaded(History(), HashSet(), null, HashSet()), utc)
        // 20 minutes at 2.5 W, sampled every 10 s, then a charger (not counted) and a gap.
        var t = day0
        repeat(121) { r.onBattery(2.5, contributing = true, nowMs = t); t += 10_000 }
        r.onBattery(null, contributing = true, nowMs = t)
        t += 10 * 60_000
        r.onBattery(4.0, contributing = true, nowMs = t)
        r.onBattery(4.0, contributing = false, nowMs = t + 10_000)
        val d = r.snapshot(t).days.single()
        assertEquals(20 * 60_000L, d.batteryMs)
        assertEquals(2.5, d.batteryMeanW!!, 1e-9)

        // Saved and read back; older files without the fields read as no data.
        val back = History.fromJson(JSONObject(r.snapshot(t).toJson().toString())).days.single()
        assertEquals(d.batteryMs, back.batteryMs)
        assertEquals(2.5, back.batteryMeanW!!, 0.01)
        val old = History.fromJson(JSONObject("""{"days":[[20000,5,6,7,8]]}""")).days.single()
        assertNull(old.batteryMeanW)
        assertEquals(8, old.aircraft)
    }

    @Test
    fun tooLittleTimeOnBatteryGivesNoAverage() {
        assertNull(DayStats(1, batteryMs = 5 * 60_000L, batteryWs = 600.0).batteryMeanW)
    }
}
