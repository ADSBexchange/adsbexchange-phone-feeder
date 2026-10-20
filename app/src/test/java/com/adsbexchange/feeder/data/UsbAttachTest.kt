package com.adsbexchange.feeder.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAttachTest {
    @Test
    fun aReplayedAttachIsNotNew() {
        val first = UsbAttach.key("count:12", 1003)
        assertTrue("never acted on", UsbAttach.isNew(first, null))
        assertFalse("the same attach again: a replay", UsbAttach.isNew(UsbAttach.key("count:12", 1003), first))
        assertTrue("unplugged and plugged in again: a new id", UsbAttach.isNew(UsbAttach.key("count:12", 1004), first))
        assertTrue("after a reboot the ids restart, but the boot differs", UsbAttach.isNew(UsbAttach.key("count:13", 1003), first))
    }
}
