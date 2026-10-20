package com.adsbexchange.feeder.data

/**
 * Identifies one physical plug-in of a USB device, so a "device attached" launch is acted on once.
 * Any app can read the attached devices without permission and replay an attach to our exported
 * activity (security review 2026-10-11, C2); a replay names the same attach, so it's ignored.
 *
 * Android numbers devices afresh on each attach ([android.hardware.usb.UsbDevice.getDeviceId]),
 * but the numbering restarts at boot, so the key includes which boot this is.
 */
object UsbAttach {
    /** [boot] identifies the boot: the boot count where Android provides it. */
    fun key(boot: String, deviceId: Int): String = "$boot/$deviceId"

    /** Whether [key] is a new attach, given the last one acted on. */
    fun isNew(key: String, lastActedOn: String?): Boolean = key != lastActedOn
}
