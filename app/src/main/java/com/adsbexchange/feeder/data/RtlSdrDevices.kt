package com.adsbexchange.feeder.data

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.adsbexchange.feeder.R
import org.xmlpull.v1.XmlPullParser

/** Finds attached RTL-SDR dongles using the VID/PID list in res/xml/device_filter.xml. */
object RtlSdrDevices {
    @Volatile private var known: Set<Pair<Int, Int>>? = null

    /** False on the (rare) phones that can't act as a USB host at all. */
    fun phoneSupportsUsbHost(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    fun findAttached(context: Context): UsbDevice? {
        val usb = context.getSystemService(UsbManager::class.java) ?: return null
        val ids = knownIds(context)
        return usb.deviceList.values.firstOrNull { isRtlSdr(it, ids) }
    }

    fun isRtlSdr(context: Context, device: UsbDevice): Boolean = isRtlSdr(device, knownIds(context))

    fun hasPermission(context: Context, device: UsbDevice): Boolean =
        context.getSystemService(UsbManager::class.java)?.hasPermission(device) == true

    /** e.g. "Nooelec NESDR Nano 3" or "RTL-SDR (0bda:2838)" when the dongle has no strings. */
    fun describe(device: UsbDevice): String {
        val name = listOfNotNull(device.manufacturerName, device.productName)
            .joinToString(" ").trim()
        return name.ifBlank { "RTL-SDR (%04x:%04x)".format(device.vendorId, device.productId) }
    }

    private fun isRtlSdr(device: UsbDevice, ids: Set<Pair<Int, Int>>) =
        (device.vendorId to device.productId) in ids

    private fun knownIds(context: Context): Set<Pair<Int, Int>> {
        known?.let { return it }
        val ids = mutableSetOf<Pair<Int, Int>>()
        context.resources.getXml(R.xml.device_filter).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "usb-device") {
                    val vid = parser.getAttributeValue(null, "vendor-id")?.toIntOrNull()
                    val pid = parser.getAttributeValue(null, "product-id")?.toIntOrNull()
                    if (vid != null && pid != null) ids += vid to pid
                }
            }
        }
        return ids.also { known = it }
    }
}
