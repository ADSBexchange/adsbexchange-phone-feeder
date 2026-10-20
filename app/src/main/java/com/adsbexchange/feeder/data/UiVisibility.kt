package com.adsbexchange.feeder.data

/**
 * Whether someone is looking at the app, shared from the UI process to the `:feeder` process so
 * the feeder can skip work that only matters for the screen (per-second aircraft.json, UI-only
 * counters). The UI marks itself visible on resume and refreshes the mark every
 * [REFRESH_MS] while visible; a mark older than [STALE_MS] counts as hidden, so a crashed UI
 * can't leave the feeder doing screen work forever.
 */
object UiVisibility {
    const val REFRESH_MS = 30_000L
    private const val STALE_MS = 90_000L

    fun write(paths: FeederPaths, visible: Boolean) {
        runCatching { paths.uiVisibleFile.writeText(if (visible) "1" else "0") }
    }

    fun read(paths: FeederPaths): Boolean {
        val f = paths.uiVisibleFile
        if (System.currentTimeMillis() - f.lastModified() > STALE_MS) return false
        return runCatching { f.readText().trim() == "1" }.getOrDefault(false)
    }
}
