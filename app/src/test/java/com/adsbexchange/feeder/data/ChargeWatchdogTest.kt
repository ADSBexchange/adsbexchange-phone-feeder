package com.adsbexchange.feeder.data

import com.adsbexchange.feeder.data.ChargeWatchdog.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeWatchdogTest {
    @Test
    fun alertsWhenTheBatteryRunsDownOnAChargerThatStoppedCharging() {
        val w = ChargeWatchdog()
        assertNull(w.tick(running = true, onCharger = true, charging = true, levelPercent = 74))
        assertNull(w.tick(true, onCharger = true, charging = false, levelPercent = 74)) // pad connected, not charging
        assertNull(w.tick(true, true, false, 72))
        assertEquals(Change.ALERT, w.tick(true, true, false, 71))
        assertNull("only once", w.tick(true, true, false, 65))
        assertEquals(Change.CLEAR, w.tick(true, true, true, 65))
    }

    @Test
    fun aChargerThatDropsOutCountsToo() {
        val w = ChargeWatchdog()
        w.tick(true, onCharger = true, charging = true, levelPercent = 81)
        w.tick(true, onCharger = false, charging = false, levelPercent = 81)
        assertEquals(Change.ALERT, w.tick(true, false, false, 78))
    }

    @Test
    fun aChargeLimitOrABriefPauseIsNotAProblem() {
        val w = ChargeWatchdog()
        repeat(100) { assertNull(w.tick(true, onCharger = true, charging = false, levelPercent = 80)) } // holding at 80%
        val v = ChargeWatchdog()
        v.tick(true, true, false, 64)
        v.tick(true, true, false, 63)
        assertNull("charging again before 3 points", v.tick(true, true, true, 63))
        assertNull("counting starts over", v.tick(true, true, false, 61))
    }

    @Test
    fun runningOnBatteryFromTheStartIsLeftToTheInAppWarning() {
        val w = ChargeWatchdog()
        assertNull(w.tick(true, onCharger = false, charging = false, levelPercent = 90))
        assertNull(w.tick(true, false, false, 50))
    }

    @Test
    fun stoppingClearsIt() {
        val w = ChargeWatchdog()
        w.tick(true, true, false, 60)
        w.tick(true, true, false, 57)
        assertEquals(Change.CLEAR, w.tick(running = false, onCharger = true, charging = false, levelPercent = 57))
        assertNull(w.clear())
    }
}
