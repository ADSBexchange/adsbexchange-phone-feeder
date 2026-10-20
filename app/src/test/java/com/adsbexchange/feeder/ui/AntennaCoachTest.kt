package com.adsbexchange.feeder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AntennaCoachTest {
    @Test
    fun measuresASpotAndRanksSpotsBestFirst() {
        val c = AntennaCoach(samplesPerSpot = 3)
        c.measure("Shelf")
        listOf(10.0, 12.0, 14.0).forEach { c.sample(it, aircraftWithPosition = 2, furthestNm = 30.0) }
        assertNull("done", c.state().measuring)
        c.measure("  ")
        c.sample(40.0, 5, 80.0)
        assertEquals(1f / 3, c.state().measuring!!, 1e-6f)
        c.sample(null, 0, null) // the receiver dropped out: not counted
        c.sample(30.0, 6, 85.0)
        c.sample(35.0, 4, 70.0)
        val spots = c.state().spots
        assertEquals(listOf("Spot 2", "Shelf"), spots.map { it.label })
        assertEquals(35.0, spots[0].messagesPerSecond, 1e-9)
        assertEquals(6, spots[0].aircraft)
        assertEquals(85.0, spots[0].rangeNm!!, 1e-9)
        assertEquals(12.0, spots[1].messagesPerSecond, 1e-9)
    }

    @Test
    fun liveRateIsSmoothed() {
        val c = AntennaCoach()
        c.sample(10.0, 1, null)
        c.sample(20.0, 1, null)
        assertEquals(13.0, c.state().messagesPerSecond!!, 1e-9)
    }

    @Test
    fun cancelAndRemove() {
        val c = AntennaCoach(samplesPerSpot = 1)
        c.measure("A")
        c.cancel()
        c.sample(5.0, 1, null)
        assertEquals(0, c.state().spots.size)
        c.measure("B")
        c.sample(5.0, 1, null)
        c.remove(c.state().spots.single())
        assertEquals(0, c.state().spots.size)
    }
}
