package com.adsbexchange.feeder.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.io.File

/**
 * Identifies one physical plug-in of a USB device, so a "device attached" launch is acted on once.
 * Any app can read the attached devices without permission and replay an attach to our exported
 * activity (security review 2026-10-11, C2); a replay names the same attach, so it's ignored.
 *
 * Android numbers devices afresh on each attach ([android.hardware.usb.UsbDevice.getDeviceId]),
 * but the numbering restarts at boot, so the key includes which boot this is.
 *
 * The last attach acted on is kept in a file, not SharedPreferences, because Stop records it from
 * the feeder process too ([markAttachedSeen]) and SharedPreferences don't sync across processes.
 */
object UsbAttach {
    /** [boot] identifies the boot: the boot count where Android provides it. */
    fun key(boot: String, deviceId: Int): String = "$boot/$deviceId"

    /** Whether [key] is a new attach, given the last one acted on. */
    fun isNew(key: String, lastActedOn: String?): Boolean = key != lastActedOn

    /** Which boot this is: Android's boot count, or failing that the boot time to the minute. */
    fun bootId(context: Context): String =
        runCatching { "count:" + Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }
            .getOrElse { "time:" + (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000 }

    fun lastActedOn(context: Context): String? = readTextIfExists(file(context))?.trim()?.ifEmpty { null }

    fun actedOn(context: Context, key: String) {
        runCatching { file(context).writeAtomically(key) }
    }

    /**
     * At Stop: the SDR attached now counts as acted on, so replaying its attach can't undo the Stop.
     * Only a new plug-in starts feeding again (security report MF-SEC-06).
     */
    fun markAttachedSeen(context: Context) {
        val device = RtlSdrDevices.findAttached(context) ?: return
        actedOn(context, key(bootId(context), device.deviceId))
    }

    private fun file(context: Context) = File(context.filesDir, "usb_last_attach")
}
