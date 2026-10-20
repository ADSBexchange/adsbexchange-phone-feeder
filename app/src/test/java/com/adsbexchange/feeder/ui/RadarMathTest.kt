package com.adsbexchange.feeder.ui

import com.adsbexchange.feeder.data.Airports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RadarMathTest {
    @Test
    fun autoRangeFitsTheFurthestAircraftWithRoom() {
        assertEquals(RadarMath.DEFAULT_NM, RadarMath.autoRange(null, null))
        assertEquals(10, RadarMath.autoRange(5.0, null))
        assertEquals("9.5 nm with 10% room needs more than 10", 25, RadarMath.autoRange(9.5, null))
        assertEquals(100, RadarMath.autoRange(60.0, null))
        assertEquals("beyond the largest range", 300, RadarMath.autoRange(400.0, null))
    }

    @Test
    fun autoRangeGrowsAtOnceButShrinksOnlyWithRoomToSpare() {
        assertEquals("grows", 150, RadarMath.autoRange(120.0, 100))
        assertEquals("48 nm would fit 50, but only just: stays at 100", 100, RadarMath.autoRange(40.0, 100))
        assertEquals("well inside 50: shrinks", 50, RadarMath.autoRange(30.0, 100))
        assertEquals("nothing heard: keeps the current range", 150, RadarMath.autoRange(null, 150))
    }

    @Test
    fun ringsAreTidyStepsAndAtMostFive() {
        for (range in RadarMath.RANGES_NM) {
            val step = RadarMath.ringStepNm(range)
            assertTrue("$range nm: ${range / step} rings", range / step <= 5)
            assertTrue("$range nm: at least 2 rings", range / step >= 2)
        }
        assertEquals(10.0, RadarMath.ringStepNm(50), 0.0)
        assertEquals(50.0, RadarMath.ringStepNm(200), 0.0)
    }

    @Test
    fun distanceAndBearing() {
        // One degree of latitude north is 60 nm.
        val (dn, bn) = RadarMath.distanceBearing(45.0, -75.0, 46.0, -75.0)
        assertEquals(60.0, dn, 0.1)
        assertEquals(0.0, bn, 0.01)
        // East along the equator.
        val (de, be) = RadarMath.distanceBearing(0.0, 0.0, 0.0, 1.0)
        assertEquals(60.04, de, 0.1)
        assertEquals(90.0, be, 0.01)
        // South-west.
        val (_, bsw) = RadarMath.distanceBearing(0.0, 0.0, -0.5, -0.5)
        assertEquals(225.0, bsw, 0.1)
        assertEquals(0.0, RadarMath.distanceBearing(10.0, 10.0, 10.0, 10.0).first, 1e-9)
    }

    @Test
    fun altitudeColoursFollowTheMapScale() {
        assertEquals("orange on the ground", 20f, RadarMath.altitudeHue(0), 0f)
        assertEquals(140f, RadarMath.altitudeHue(11_000), 0f)
        assertEquals("between stops", 220f, RadarMath.altitudeHue(25_500), 0.5f)
        assertEquals(300f, RadarMath.altitudeHue(40_000), 0f)
        assertEquals("tops out at red", 360f, RadarMath.altitudeHue(60_000), 0f)
        assertEquals(20f, RadarMath.altitudeHue(-500), 0f)
        for (h in 0..360 step 10) assertTrue(RadarMath.lightness(h.toFloat()) in 0.4f..0.7f)
    }

    @Test
    fun smallAirportsOnlyCloseInAndLargerThenNearerFirst() {
        val a = Airports.parse(sequenceOf(
            "SMALL\tS\t43.1\t-76.0", "MEDNEAR\tM\t43.2\t-76.0", "MEDFAR\tM\t43.8\t-76.0", "LARGE\tL\t44.0\t-76.0",
        ))
        assertEquals("small fields only at 50 nm and closer; large first", listOf("LARGE", "MEDNEAR", "MEDFAR"),
            RadarMath.airportsToShow(a, 43.0, -76.0, 100).map { it.airport.code })
        assertEquals(listOf("MEDNEAR", "SMALL"), RadarMath.airportsToShow(a, 43.0, -76.0, 25).map { it.airport.code })
    }

    @Test
    fun atMostThirtyAirports() {
        val many = Airports.parse((0 until 50).asSequence().map { "A$it\tM\t" + "%.2f".format(java.util.Locale.US, 43.0 + it * 0.01) + "\t-76.0" })
        assertEquals(RadarMath.MAX_AIRPORTS, RadarMath.airportsToShow(many, 43.0, -76.0, 100).size)
    }

    @Test
    fun theBundledListAroundSyracuse() {
        val bundled = File("src/main/assets/" + Airports.ASSET).bufferedReader().useLines { Airports.parse(it) }
        val shown = RadarMath.airportsToShow(bundled, 43.0, -76.0, 100).map { it.airport.code }
        assertEquals("KSYR", shown.first())
        assertTrue(shown.toString(), shown.containsAll(listOf("KITH", "KBGM", "KRME")))
    }
}
