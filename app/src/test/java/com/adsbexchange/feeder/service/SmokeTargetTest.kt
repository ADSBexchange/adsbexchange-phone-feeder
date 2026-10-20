package com.adsbexchange.feeder.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmokeTargetTest {
    @Test
    fun hostPortAllowsOnlyThePcAndThisDevice() {
        assertEquals("10.0.2.2" to 31090, SmokeTarget.hostPort("10.0.2.2,31090"))
        assertEquals("127.0.0.1" to 30101, SmokeTarget.hostPort("127.0.0.1,30101"))
        assertNull(SmokeTarget.hostPort("feed1.adsbexchange.com,30004"))
        assertNull(SmokeTarget.hostPort("127.0.0.1.evil.host,30004"))
        assertNull(SmokeTarget.hostPort("127.0.0.1,30004,extra"))
        assertNull(SmokeTarget.hostPort("127.0.0.1,0"))
        assertNull(SmokeTarget.hostPort("127.0.0.1,port"))
        assertNull("the feed sink: this device only", SmokeTarget.hostPort("10.0.2.2,30101", setOf("127.0.0.1")))
        assertNull(SmokeTarget.hostPort(null))
    }

    @Test
    fun httpUrlIsParsedNotPrefixMatched() {
        assertEquals("http://10.0.2.2:8123/api/receive/", SmokeTarget.httpUrl("http://10.0.2.2:8123/api/receive/"))
        assertNull("user info hiding another host", SmokeTarget.httpUrl("http://127.0.0.1:1@evil.host/"))
        assertNull(SmokeTarget.httpUrl("http://127.0.0.1.evil.host:80/"))
        assertNull(SmokeTarget.httpUrl("https://adsbexchange.com/api/receive/"))
        assertNull("no port", SmokeTarget.httpUrl("http://127.0.0.1/"))
        assertNull(SmokeTarget.httpUrl("not a url"))
        assertNull(SmokeTarget.httpUrl(null))
    }
}
