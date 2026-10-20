package com.adsbexchange.feeder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsbexchange.feeder.R
import com.adsbexchange.feeder.data.FeederSettings
import com.adsbexchange.feeder.data.RtlSdrDevices

/**
 * Resumes feeding after the phone restarts or the app is updated, unless the user stopped it.
 *
 * Starting a connectedDevice foreground service from BOOT_COMPLETED is allowed. If the SDR isn't
 * attached there's nothing to do: plugging it in launches the app, which starts feeding.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = FeederSettings.load(context)
        if (!settings.onboardingDone || !settings.autoStart || settings.userStopped) return
        val device = RtlSdrDevices.findAttached(context) ?: return
        if (!RtlSdrDevices.hasPermission(context, device)) {
            Notifications.alert(context, context.getString(R.string.alert_permission_title),
                context.getString(R.string.alert_permission_text))
            return
        }
        try {
            FeederService.appendLog(context, "resuming after ${intent.action?.substringAfterLast('.')}")
            FeederService.start(context)
        } catch (e: Exception) {
            FeederService.appendLog(context, "could not resume: $e")
            Notifications.alert(context, context.getString(R.string.alert_resume_title),
                context.getString(R.string.alert_resume_text))
        }
    }
}
