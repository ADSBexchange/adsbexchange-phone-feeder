package com.adsbexchange.feeder.mlat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The MLAT sync line the service logs every 10 minutes. Same text as the desktop's. */
class MlatSyncLogTest {
    private val connected = MlatClient.Stats(state = MlatClient.State.CONNECTED, peerCount = 7, outlierPercent = 1.53,
        badSyncTimeout = 0, requested = 6)

    @Test
    fun reportsTheServersViewAndWhatWasSent() {
        assertEquals("mlat: synced with 7 nearby feeders, sync outliers 1.5%, bad-sync timeout 0; " +
            "last 10 min: 925 sync pairs and 463 MLAT messages sent, 6 aircraft requested",
            MlatSyncLog.line(connected, 925, 463, 10))
    }

    @Test
    fun beforeTheServerReportsSync() {
        val s = MlatClient.Stats(state = MlatClient.State.CONNECTED, peerCount = null)
        assertEquals("mlat: the server hasn't reported sync yet; last 10 min: 0 sync pairs and 0 MLAT messages sent, " +
            "0 aircraft requested", MlatSyncLog.line(s, 0, 0, 10))
        assertEquals("mlat: synced with 1 nearby feeder; last 10 min: 0 sync pairs and 0 MLAT messages sent, 0 aircraft requested",
            MlatSyncLog.line(s.copy(peerCount = 1), 0, 0, 10))
    }

    @Test
    fun everyTenMinutesWithWhatWasSentSinceTheLastLine() {
        val t0 = 1_000_000L
        val log = MlatSyncLog().apply { restart(t0) }
        assertNull("not yet", log.next(connected.copy(syncsSent = 500, mlatSent = 200), t0 + 9 * 60_000L))
        assertEquals(MlatSyncLog.line(connected, 1000, 400, 10),
            log.next(connected.copy(syncsSent = 1000, mlatSent = 400), t0 + 10 * 60_000L))
        assertNull("just logged", log.next(connected.copy(syncsSent = 1100, mlatSent = 450), t0 + 11 * 60_000L))
        assertEquals("only what was sent since the last line", MlatSyncLog.line(connected, 900, 300, 10),
            log.next(connected.copy(syncsSent = 1900, mlatSent = 700), t0 + 20 * 60_000L))
    }

    @Test
    fun nothingWhileNotConnected() {
        val log = MlatSyncLog().apply { restart(0) }
        assertNull(log.next(connected.copy(state = MlatClient.State.CONNECTING), 60 * 60_000L))
        assertNull(log.next(connected.copy(state = MlatClient.State.DISCONNECTED), 60 * 60_000L))
    }

    @Test
    fun aNewClientStartsTheCountAgain() {
        val log = MlatSyncLog().apply { restart(0) }
        log.next(connected.copy(syncsSent = 5000, mlatSent = 2000), 10 * 60_000L)
        // The client was recreated (settings changed, or a new readsb run): its counters restart at 0.
        log.restart(12 * 60_000L)
        assertNull(log.next(connected.copy(syncsSent = 100), 15 * 60_000L))
        assertEquals(MlatSyncLog.line(connected, 300, 50, 10),
            log.next(connected.copy(syncsSent = 300, mlatSent = 50), 22 * 60_000L))
    }
}
