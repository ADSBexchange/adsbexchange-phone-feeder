package com.adsbexchange.feeder.service

import java.net.URI

/**
 * Where a debug smoke test (tools/smoke-test.ps1) may point the feeder: only at the PC running it
 * (10.0.2.2, as the emulator sees it) or at this device (127.0.0.1), never anywhere else. The
 * addresses are parsed, not prefix-matched, so "http://127.0.0.1:1@other.host/" is refused
 * (security review 2026-10-11, C6). Debug builds only; release builds never read these extras.
 */
internal object SmokeTarget {
    private val HOSTS = setOf("10.0.2.2", "127.0.0.1")

    /** "host,port", for the MLAT stand-in and the feed sink; [hosts] narrows the allowed hosts. */
    fun hostPort(value: String?, hosts: Set<String> = HOSTS): Pair<String, Int>? {
        val parts = value?.split(",") ?: return null
        if (parts.size != 2 || parts[0] !in hosts) return null
        val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return parts[0] to port
    }

    /** An http:// URL on an allowed host with an explicit port, for the stats stand-in. */
    fun httpUrl(value: String?): String? {
        val uri = runCatching { URI(value ?: return null) }.getOrNull() ?: return null
        if (uri.scheme != "http" || uri.rawUserInfo != null || uri.host !in HOSTS || uri.port !in 1..65535) return null
        return value
    }
}
