package com.adsbexchange.feeder.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Replaces this file with [text] so readers see either the old or the new contents, never a mix,
 * and a power cut can't leave it empty: written to a sibling `.tmp`, flushed to storage, then
 * renamed over the original.
 */
internal fun File.writeAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    FileOutputStream(tmp).use {
        it.write(text.toByteArray())
        it.fd.sync()
    }
    // Atomic on Android. Only on other systems (unit tests on Windows) can it refuse to replace.
    // If it still can't, say so: a save that silently didn't happen is worse than one that failed.
    if (!tmp.renameTo(this)) {
        delete()
        if (!tmp.renameTo(this)) {
            tmp.delete()
            throw IOException("couldn't replace $name")
        }
    }
}

/**
 * [file]'s text, or null if it doesn't exist. A file that exists but can't be read is tried again
 * for about a second, then the IOException is thrown, so callers can tell "missing" from
 * "unreadable" and never mistake one for the other (as the desktop feeder, code review DF-CQ-03).
 */
internal fun readTextIfExists(file: File): String? = readIfExists(file) { it.readText() }

/** As [readTextIfExists], with [read] doing the reading. */
internal fun <T> readIfExists(file: File, read: (File) -> T): T? {
    var attempt = 0
    while (true) {
        if (!file.exists()) return null
        try {
            return read(file)
        } catch (e: IOException) {
            if (++attempt >= READ_ATTEMPTS) throw e
            Thread.sleep(20L * attempt)
        }
    }
}

/** 20, 40, ... 180 ms between tries: about a second in all. */
private const val READ_ATTEMPTS = 10

/**
 * Keeps a copy of [file] beside it as `<name>.unreadable-<date-time>`, before it's replaced because
 * it couldn't be understood, so what was in it can still be recovered. Returns the copy, or null if
 * even copying failed.
 */
internal fun keepUnreadableCopy(file: File): File? = runCatching {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    file.copyTo(File(file.parentFile, "${file.name}.unreadable-$stamp"), overwrite = true)
}.getOrNull()

/**
 * Appends a `[feeder]` line to readsb.log, so the app's lines and readsb's read in order and go out
 * with Share diagnostics. The log starts again once it passes [MAX_LOG_BYTES], as readsb's own
 * output does (jni_bridge.c): readsb runs --quiet, so most lines are the app's, and the MLAT
 * server's text among them mustn't be able to fill the phone (security report MF-LOG-01).
 */
internal fun appendAppLog(paths: FeederPaths, message: String) {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    runCatching {
        paths.root.mkdirs()
        if (paths.logFile.length() > MAX_LOG_BYTES) RandomAccessFile(paths.logFile, "rw").use { it.setLength(0) }
        paths.logFile.appendText("[$time] [feeder] $message\n")
    }
}

/** As MAX_LOG_BYTES in jni_bridge.c. */
internal const val MAX_LOG_BYTES = 512 * 1024L
