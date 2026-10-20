package com.adsbexchange.feeder.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The licenses screen has to cover everything compiled into the app (unit tests run in app/). */
class LicensesTest {
    private val assets = File("src/main/assets/licenses")

    @Test
    fun everyLicenseFileIsBundled() {
        for (c in OSS_COMPONENTS) for (f in c.licenseFiles) {
            val file = File(assets, f)
            assertTrue("${c.name}: $file missing", file.isFile && file.length() > 1000)
        }
    }

    @Test
    fun everyPinnedCLibraryIsListed() {
        val urls = Regex("""^fetch_dep\(\S+\s+(\S+)\s""", RegexOption.MULTILINE)
            .findAll(File("src/main/cpp/cmake/deps.cmake").readText())
            .map { it.groupValues[1].removeSuffix(".git") }.toList()
        assertTrue("no fetch_dep lines found", urls.size >= 4)
        for (url in urls) assertTrue("$url is not on the licenses screen", OSS_COMPONENTS.any { it.url == url })
    }

    @Test
    fun theAppComesFirstWithItsSource() {
        val app = OSS_COMPONENTS.first()
        assertTrue(app.license.startsWith("GPL-3.0") && app.url.startsWith("https://"))
    }
}
