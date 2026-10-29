package com.adsbexchange.feeder.data

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppLogTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The app's own lines can't grow readsb.log without limit (security report MF-LOG-01). */
    @Test
    fun theLogStartsAgainPastItsCap() {
        val paths = FeederPaths(tmp.root)
        paths.root.mkdirs()
        paths.logFile.writeBytes(ByteArray((MAX_LOG_BYTES + 1).toInt()) { 'x'.code.toByte() })
        appendAppLog(paths, "hello")
        val text = paths.logFile.readText()
        assertTrue("${text.length} bytes", text.length < 100 && text.endsWith("[feeder] hello\n"))
        appendAppLog(paths, "again")
        assertTrue("under the cap it appends", paths.logFile.readText().contains("hello"))
    }
}
