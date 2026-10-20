package com.adsbexchange.feeder.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.adsbexchange.feeder.MainActivity
import com.adsbexchange.feeder.R

/** The feeder's ongoing notification, plus "needs your attention" alerts (unplugged, permission). */
object Notifications {
    private const val CHANNEL_STATUS = "feeder"
    private const val CHANNEL_ALERTS = "alerts"
    const val ID_FEEDER = 1
    private const val ID_ALERT = 2
    /** Feeding stopped working (FeedWatchdog): separate, so clearing it leaves other alerts alone. */
    const val ID_FEED_PROBLEM = 3
    /** The battery is running down on a charger (ChargeWatchdog). */
    const val ID_CHARGE_PROBLEM = 4
    /** Feeding paused to protect the battery (BatteryGuard). */
    const val ID_PAUSED = 5
    /** A power test finished (see PowerTest). */
    const val ID_POWER_TEST = 6

    fun ensureChannels(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_STATUS, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_status)).build()
        )
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ALERTS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.channel_alerts)).build()
        )
    }

    fun feeder(context: Context, text: String): Notification {
        ensureChannels(context)
        val stop = PendingIntent.getService(
            context, 1, Intent(context, FeederService::class.java).setAction(FeederService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_feeder)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.action_stop), stop)
            .build()
    }

    /** Silently does nothing without the notification permission (Android 13+). */
    @SuppressLint("MissingPermission") // checked via areNotificationsEnabled()
    fun alert(context: Context, title: String, text: String, id: Int = ID_ALERT) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_feeder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    fun clearAlert(context: Context, id: Int = ID_ALERT) = NotificationManagerCompat.from(context).cancel(id)

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )
}
