package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.concurrent.thread

class FeederSettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val paths by lazy { FeederPaths(tmp.root) }

    @Test
    fun firstRunCreatesAPhoneUuidAndKeepsIt() {
        val first = FeederSettings.load(paths)
        assertTrue(first.uuid.endsWith(FeederSettings.PHONE_UUID_TAG))
        assertEquals(first.uuid, FeederSettings.load(paths).uuid)
        assertEquals(first.uuid, paths.uuidFile.readText())
    }

    @Test
    fun emptySettingsFileKeepsTheFeederId() {
        val uuid = FeederSettings.load(paths).uuid
        paths.settingsFile.writeText("") // what a power cut mid-write could leave behind
        assertEquals(uuid, FeederSettings.load(paths).uuid)
    }

    @Test
    fun missingSettingsFileKeepsTheFeederId() {
        val uuid = FeederSettings.update(paths) { it.copy(name = "Kitchen window") }.uuid
        paths.settingsFile.delete()
        val s = FeederSettings.load(paths)
        assertEquals(uuid, s.uuid)
        assertEquals("", s.name)
    }

    @Test
    fun blankUuidIsRecoveredAndSaved() {
        val uuid = FeederSettings.update(paths) { it.copy(name = "Attic") }.uuid
        paths.settingsFile.writeText(paths.settingsFile.readText().replace(uuid, ""))
        val s = FeederSettings.load(paths)
        assertEquals(uuid, s.uuid)
        assertEquals("Attic", s.name)
        assertTrue(paths.settingsFile.readText().contains(uuid))
    }

    @Test
    fun unreadableFeederIdGivesANewUuid() {
        paths.root.mkdirs()
        paths.uuidFile.writeText("not a uuid")
        val s = FeederSettings.load(paths)
        assertNotEquals("not a uuid", s.uuid)
        assertTrue(s.uuid.endsWith(FeederSettings.PHONE_UUID_TAG))
        assertEquals(s.uuid, paths.uuidFile.readText())
    }

    @Test
    fun updatesPersistWithoutLeftoverFiles() {
        FeederSettings.update(paths) { it.copy(userStopped = true, gain = "42.1") }
        val s = FeederSettings.load(paths)
        assertTrue(s.userStopped)
        assertEquals("42.1", s.gain)
        assertEquals(emptyList<String>(), tmp.root.walk().filter { it.name.endsWith(".tmp") }.map { it.name }.toList())
    }

    /** Every writer's change survives, as with the UI and the service writing at once. */
    @Test
    fun concurrentUpdatesAreNotLost() {
        FeederSettings.load(paths)
        val threads = List(4) {
            thread {
                repeat(50) { FeederSettings.update(paths) { s -> s.copy(rangeResetMs = (s.rangeResetMs ?: 0) + 1) } }
            }
        }
        threads.forEach(Thread::join)
        assertEquals(200L, FeederSettings.load(paths).rangeResetMs)
    }
    /** A settings.json that can't be understood is kept as a copy before it's replaced (desktop code review DF-CQ-03). */
    @Test
    fun damagedSettingsAreCopiedAside() {
        val uuid = FeederSettings.update(paths) { it.copy(name = "Attic") }.uuid
        paths.settingsFile.writeText("{\"name\": \"Attic\", \"loc")
        val s = FeederSettings.load(paths)
        assertEquals(uuid, s.uuid)
        val copies = tmp.root.listFiles { f -> f.name.startsWith("settings.json.unreadable-") }!!.toList()
        assertEquals(1, copies.size)
        assertEquals("{\"name\": \"Attic\", \"loc", copies[0].readText())
    }

    /** Unreadable even after the retries: defaults for now, keeping the feeder ID, and nothing written over it. */
    @Test
    fun anUnreadableSettingsFileIsntWrittenOver() {
        val saved = FeederSettings.update(paths) { it.copy(name = "Attic") }
        val text = paths.settingsFile.readText()
        // Make settings.json exist but be unreadable, on any system: move it aside and put a folder
        // in its place, then restore it afterwards.
        val real = java.io.File(tmp.root, "settings.real")
        paths.settingsFile.renameTo(real)
        paths.settingsFile.mkdirs()
        val s = FeederSettings.load(paths)
        assertEquals("the feeder ID from readsb's copy", saved.uuid, s.uuid)
        assertEquals("", s.name)
        val changed = FeederSettings.update(paths) { it.copy(userStopped = true) }
        assertTrue(changed.userStopped)
        assertTrue("not written over", paths.settingsFile.isDirectory)
        paths.settingsFile.delete()
        real.renameTo(paths.settingsFile)
        assertEquals(text, paths.settingsFile.readText())
        assertEquals("Attic", FeederSettings.load(paths).name)
    }

    /** Unreadable, and readsb's copy of the ID gone too: a blank ID, which the service refuses to feed under. */
    @Test
    fun anUnreadableSettingsFileWithoutReadsbsCopyGivesABlankId() {
        FeederSettings.update(paths) { it.copy(name = "Attic") }
        paths.uuidFile.delete()
        paths.settingsFile.delete()
        paths.settingsFile.mkdirs()
        assertEquals("", FeederSettings.load(paths).uuid)
    }

    /** As the desktop feeder's test of the same name. */
    @Test
    fun biasTeeIsOffByDefaultAndOnlyWhenAsked() {
        val s = FeederSettings(uuid = "u", name = "", location = null)
        assertTrue("off by default", "--enable-biastee" !in s.readsbArgs(paths, 1, null))
        assertTrue("on when asked", "--enable-biastee" in s.copy(biasTee = true).readsbArgs(paths, 1, null))
        assertTrue("never with a capture file", "--enable-biastee" !in
            s.copy(biasTee = true).readsbArgs(paths, 1, null, inputFile = java.io.File("capture.iq")))
        assertTrue(!FeederSettings.load(paths).biasTee)
        FeederSettings.update(paths) { it.copy(biasTee = true) }
        assertTrue("saved", FeederSettings.load(paths).biasTee)
    }

    @Test
    fun radarAirportsDefaultOnAndSaveOff() {
        assertTrue(FeederSettings.load(paths).radarAirports)
        FeederSettings.update(paths) { it.copy(radarAirports = false) }
        assertTrue(!FeederSettings.load(paths).radarAirports)
    }

    /** As the desktop feeder (0.5.21): MLAT privacy off and the antenna box ticked, for a new install. */
    @Test
    fun aNewInstallStartsWithPrivacyOffAndTheAntennaBoxTicked() {
        val s = FeederSettings.load(paths)
        assertTrue("privacy off, as on ADS-B Exchange's own feeders", !s.mlatPrivacy)
        assertTrue("antenna within 15 m", s.antennaWithin15m)
    }

    @Test
    fun anOlderSettingsFileKeepsItsPrivacyButNotTheOldAntennaBoxOrStatsSwitch() {
        val uuid = FeederSettings.load(paths).uuid
        paths.settingsFile.writeText(
            """{"uuid":"$uuid","name":"","mlatPrivacy":true,"antennaConfirmed":false,"statsUploads":false}"""
        )
        val s = FeederSettings.load(paths)
        assertTrue("privacy chosen before is kept", s.mlatPrivacy)
        assertTrue("1.0.0's unticked box is ignored", s.antennaWithin15m)
        FeederSettings.update(paths) { it }
        assertTrue("the old stats switch isn't saved again", "statsUploads" !in paths.settingsFile.readText())
    }
}
