package com.adsbexchange.feeder.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlin.math.abs

/** Battery, charging, power and thermal state — the things that decide whether 24/7 feeding is safe. */
data class PhoneHealth(
    val batteryPercent: Int?,
    /** What the phone is plugged into, if anything. Connected doesn't mean charging: see [batteryCharging]. */
    val charger: Charger,
    /** The battery is actually charging, or full. A phone can sit on a charger and not charge (heat, a pad that stops delivering, a charge limit). */
    val batteryCharging: Boolean,
    val batteryTempC: Double?,
    /** Net battery power in watts: positive = charging, negative = draining. */
    val batteryPowerW: Double?,
    val thermal: Thermal?,
    val ignoringBatteryOptimizations: Boolean,
    /** The batterys coulomb counter, in microamp-hours, where the phone has one. */
    val chargeCounterUah: Long? = null,
    val voltageMv: Int? = null,
) {
    enum class Charger { NONE, AC, USB, WIRELESS, OTHER }
    enum class Thermal { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }

    val onCharger get() = charger != Charger.NONE
    val hot get() = (batteryTempC ?: 0.0) >= HOT_BATTERY_C ||
        (thermal != null && thermal >= Thermal.MODERATE)

    companion object {
        const val HOT_BATTERY_C = 40.0

        fun read(context: Context): PhoneHealth {
            val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val temp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
            val voltageMv = b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val charger = when (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
                0 -> Charger.NONE
                BatteryManager.BATTERY_PLUGGED_AC -> Charger.AC
                BatteryManager.BATTERY_PLUGGED_USB -> Charger.USB
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> Charger.WIRELESS
                else -> Charger.OTHER
            }

            val bm = context.getSystemService(BatteryManager::class.java)
            val rawCurrent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?.takeIf { it != Int.MIN_VALUE && it != 0 }
            // Documented as microamps (positive = into the battery), but some phones report mA.
            val currentA = rawCurrent?.let { if (abs(it) > 20_000) it / 1_000_000.0 else it / 1_000.0 }
            val powerW = if (currentA != null && voltageMv > 0) currentA * voltageMv / 1000.0 else null

            val pm = context.getSystemService(PowerManager::class.java)
            val thermal = if (Build.VERSION.SDK_INT >= 29 && pm != null) {
                when (pm.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> Thermal.NONE
                    PowerManager.THERMAL_STATUS_LIGHT -> Thermal.LIGHT
                    PowerManager.THERMAL_STATUS_MODERATE -> Thermal.MODERATE
                    PowerManager.THERMAL_STATUS_SEVERE -> Thermal.SEVERE
                    else -> Thermal.CRITICAL
                }
            } else null

            return PhoneHealth(
                batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
                charger = charger,
                batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                batteryTempC = temp,
                batteryPowerW = powerW,
                thermal = thermal,
                ignoringBatteryOptimizations = pm?.isIgnoringBatteryOptimizations(context.packageName) == true,
                chargeCounterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    ?.takeIf { it != Int.MIN_VALUE && it > 0 }?.toLong(),
                voltageMv = voltageMv.takeIf { it > 0 },
            )
        }
    }
}
