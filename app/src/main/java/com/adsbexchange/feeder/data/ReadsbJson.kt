package com.adsbexchange.feeder.data

import java.io.File

/**
 * readsb's JSON files (aircraft.json, stats.json, receiver.json) are read whole, every 1–5 s, by
 * the screen, the history and the stats uploads. Radio input is untrusted: a
 * transmitter faking thousands of aircraft could make aircraft.json huge. Above this size it's
 * skipped instead (security review 2026-10-11, C7). A busy site's aircraft.json is well under 1 MB;
 * 4 MB is about 6,000 aircraft.
 */
const val MAX_READSB_JSON_BYTES = 4L * 1024 * 1024

/** The file's text, or null if it's larger than [maxBytes]. */
fun File.readReadsbJson(maxBytes: Long = MAX_READSB_JSON_BYTES): String? = if (length() > maxBytes) null else readText()
