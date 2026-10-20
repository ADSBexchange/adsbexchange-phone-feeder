package com.adsbexchange.feeder.data

import com.adsbexchange.feeder.data.FeedWatchdog.Action
import com.adsbexchange.feeder.data.FeedWatchdog.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedWatchdogTest {
    private val min = 60_000L

    @Test
    fun alertsOnceWhenTheFeedHasBeenDownFor15MinutesAndClearsWhenItsBack() {
        val w = FeedWatchdog()
        assertNull(w.tick(running = true, feeding = true, feedConnected = false, messagesTotal = 10, nowMs = 0))
        assertNull(w.tick(true, true, false, 20, 14 * min))
        assertEquals(Action.Alert(Problem.FEED_DOWN), w.tick(true, true, false, 30, 15 * min))
        assertNull("only once", w.tick(true, true, false, 40, 20 * min))
        assertEquals(Action.Clear, w.tick(true, true, true, 50, 21 * min))
        assertNull(w.tick(true, true, true, 60, 22 * min))
    }

    @Test
    fun aShortWifiBlipIsNotAnAlert() {
        val w = FeedWatchdog()
        w.tick(true, true, false, 1, 0)
        w.tick(true, true, true, 2, 10 * min)
        assertNull(w.tick(true, true, false, 3, 12 * min))
        assertNull("the clock restarted at the new drop", w.tick(true, true, false, 4, 26 * min))
        assertEquals(Action.Alert(Problem.FEED_DOWN), w.tick(true, true, false, 5, 27 * min))
    }

    @Test
    fun alertsAfterTwoHoursWithNoMessages() {
        val w = FeedWatchdog()
        w.tick(true, true, true, 500, 0)
        assertNull(w.tick(true, true, true, 500, 119 * min))
        assertEquals(Action.Alert(Problem.NO_AIRCRAFT), w.tick(true, true, true, 500, 120 * min))
        assertEquals("messages again", Action.Clear, w.tick(true, true, true, 501, 121 * min))
    }

    @Test
    fun notFeedingADSBExchangeIsNotAFeedProblem() {
        val w = FeedWatchdog()
        w.tick(true, feeding = false, feedConnected = false, messagesTotal = 1, nowMs = 0)
        assertNull(w.tick(true, false, false, 2, 60 * min))
    }

    @Test
    fun stoppingClearsAnAlert() {
        val w = FeedWatchdog()
        w.tick(true, true, false, 1, 0)
        w.tick(true, true, false, 2, 15 * min)
        assertEquals(Action.Clear, w.tick(running = false, feeding = false, feedConnected = false, messagesTotal = null, nowMs = 16 * min))
        assertNull(w.clear())
    }
}
