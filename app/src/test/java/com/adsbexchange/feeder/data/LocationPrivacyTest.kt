package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The receiver position is usually someone's home: it must not reach the log or diagnostics. */
class LocationPrivacyTest {
    private val home = StationLocation(
        lat = 43.123456, lon = -76.654321, altitudeM = 120.0, accuracyM = 5f,
        timeMs = 0, source = StationLocation.Source.GPS,
    )
    private val settings = FeederSettings(uuid = "00000000-0000-4000-8000-000000000000", name = "", location = home)

    @Test
    fun readsbCommandLineHasNoCoordinates() {
        val args = settings.readsbArgs(FeederPaths(File("files")), gpsdPort = 40000, feedTo = FeederSettings.ADSBX_FEED)
        val line = args.joinToString(" ")
        assertFalse(line, "--lat" in args || "--lon" in args)
        assertFalse(line, "43.12" in line || "76.65" in line)
        // The position still reaches readsb, over gpsd.
        assertTrue(line, "127.0.0.1,40000,gpsd_in" in args)
    }

    @Test
    fun readsbCommandLineHasNoFeederId() {
        val paths = FeederPaths(File("files"))
        val args = settings.readsbArgs(paths, gpsdPort = 40000, feedTo = FeederSettings.ADSBX_FEED)
        val line = args.joinToString(" ")
        assertFalse(line, settings.uuid in line || "uuid=" in line)
        // readsb reads it from the file the service writes, and sends it on the feed from there.
        assertTrue(line, args.windowed(2).any { it == listOf("--uuid-file", paths.uuidFile.absolutePath) })
    }

    @Test
    fun mapLinkIsRoundedToAboutAKilometre() {
        val url = com.adsbexchange.feeder.ui.Links.map(home.lat, home.lon)
        assertTrue(url, "lat=43.12&" in url && "lon=-76.65&" in url)
    }

    @Test
    fun redactsReadsbPositionMessages() {
        val cases = mapOf(
            "readsb --lat 43.12346 --lon -76.65432 --quiet" to "readsb --lat [hidden] --lon [hidden] --quiet",
            "Using lat: 43.123456, lon: -76.654321 (location accuracy: exact)" to
                "Using lat: [hidden], lon: [hidden] (location accuracy: exact)",
            "[2026-09-30 10:24:29] Autodetected receiver location: 43.12346, -76.65432 at 120m AMSL" to
                "[2026-09-30 10:24:29] Autodetected receiver location: [hidden] at 120m AMSL",
            "gpsdebug: parsed lat,lon:   43.123456, -76.654321 (alt: 120 m)" to "gpsdebug: parsed lat,lon:   [hidden] (alt: 120 m)",
            "setLatLon: lat: 43.123456 lon: -76.654321" to "setLatLon: lat: [hidden] lon: [hidden]",
            """{"lat":43.123456,"lon":-76.654321}""" to """{"lat":[hidden],"lon":[hidden]}""",
            // Formats readsb doesn't print in this set-up today (security report MF-LOG-11).
            "lat: 43,12345 lon: -76,54321" to "lat: [hidden] lon: [hidden]",
            """{"rr_lat":43.1,"rr_lon":-76.5}""" to """{"rr_lat":[hidden],"rr_lon":[hidden]}""",
            """{"user_lat":43.12345,"user_lon":-76.54321}""" to """{"user_lat":[hidden],"user_lon":[hidden]}""",
            "lat: +43 lon: -76" to "lat: [hidden] lon: [hidden]",
            "id:0000000000000000 #posCounter:        5 refLoc:   43, -76 lat:   43 to   44 lon:  -77 to  -76" to
                "id:0000000000000000 #posCounter:        5 refLoc:   [hidden]",
            "receiver at 43.12345, -76.54321 now" to "receiver at [hidden] now",
            "receiver at 43.12345 -76.54321 now" to "receiver at [hidden] now",
            "receiver at 43.123N 76.543W now" to "receiver at [hidden] now",
        )
        for ((line, expected) in cases) assertEquals(expected, redactLocation(line))
    }

    @Test
    fun leavesOtherLinesAlone() {
        for (line in listOf(
            "BeastReduce TCP output: Connection established: 127.0.0.1 port 30100",
            "readsb version: 3.16.17 android-poc",
            "[feeder] mlat: handshake complete: compression zlib2, split sync disabled",
            "--net-connector feed1.adsbexchange.com,30004,beast_reduce_plus_out,uuid=0000",
        )) assertEquals(line, redactLocation(line))
    }
}
