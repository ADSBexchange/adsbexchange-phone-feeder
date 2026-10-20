package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadsbJsonTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun readsUpToTheLimitAndSkipsAnythingBigger() {
        val f = tmp.newFile("aircraft.json")
        f.writeText("""{"aircraft":[]}""")
        assertEquals("""{"aircraft":[]}""", f.readReadsbJson())
        f.writeText("x".repeat(1001))
        assertNull("over the limit: skipped, not read", f.readReadsbJson(maxBytes = 1000))
        assertEquals(1001, f.readReadsbJson(maxBytes = 1001)?.length)
    }

    /** The screen, history and stats readers all treat an oversized file as no data. */
    @Test
    fun anOversizedAircraftListReadsAsNothing() {
        val paths = FeederPaths(tmp.root)
        paths.jsonDir.mkdirs()
        paths.aircraftJson.outputStream().use { out ->
            out.write("""{"now":1,"aircraft":[""".toByteArray())
            val one = """{"hex":"a1b2c3","lat":45.0,"lon":-75.0},""".toByteArray()
            repeat((MAX_READSB_JSON_BYTES / one.size + 1).toInt()) { out.write(one) }
            out.write("""{"hex":"ffffff"}]}""".toByteArray())
        }
        assertNull(HistorySamples.aircraft(paths))
        assertEquals(0, ReceiverStatusReader(paths).read().aircraft.size)
    }
}
