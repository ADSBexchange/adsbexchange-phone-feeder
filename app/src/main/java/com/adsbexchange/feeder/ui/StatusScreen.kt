package com.adsbexchange.feeder.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.adsbexchange.feeder.R
import com.adsbexchange.feeder.data.FeedState
import com.adsbexchange.feeder.data.FeederStatus
import com.adsbexchange.feeder.data.PhoneHealth
import com.adsbexchange.feeder.ui.theme.Brand
import java.util.Locale

@Composable
fun StatusScreen(state: UiState, vm: MainViewModel, onShowAircraft: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCard(state, vm)
        if (state.feeding) LiveFeedCard(state, vm, onShowAircraft)
        HealthCard(state)
        LinksCard(state)
    }
    state.coach?.let { CoachDialog(it, receiving = state.feeding, vm = vm) }
}

private enum class Tone { GOOD, NEUTRAL, PROBLEM }

private data class Hero(
    val title: String,
    val body: String,
    val icon: ImageVector,
    val tone: Tone,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
    val actionOutlined: Boolean = false,
    val busy: Boolean = false,
)

/** The one card that always says what's going on and what (if anything) to do next. */
@Composable
private fun HeroCard(state: UiState, vm: MainViewModel) {
    val hero = hero(state, vm)
    val colors = when (hero.tone) {
        Tone.GOOD -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Tone.NEUTRAL -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        Tone.PROBLEM -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(hero.icon, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(hero.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Text(hero.body, style = MaterialTheme.typography.bodyLarge)
            if (hero.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (hero.action != null && hero.actionLabel != null) {
                if (hero.actionOutlined) {
                    OutlinedButton(onClick = hero.action) { Text(hero.actionLabel) }
                } else {
                    Button(onClick = hero.action) { Text(hero.actionLabel) }
                }
            }
        }
    }
}

@Composable
private fun hero(state: UiState, vm: MainViewModel): Hero {
    val r = state.receiver
    val stop = stringResource(R.string.action_stop)
    return when (val run = state.run) {
        RunState.Running -> {
            val count = r?.aircraft?.size ?: 0
            val rate = r?.messagesPerSecond?.let { "%.0f".format(Locale.US, it) } ?: "–"
            val body = stringResource(R.string.hero_running_body, count, rate)
            when {
                !state.feedingAdsbx -> Hero(stringResource(R.string.hero_receiving_title), body, Icons.Filled.Info, Tone.NEUTRAL, stop, vm::stop, true)
                state.feed.state == FeedState.CONNECTED -> Hero(stringResource(R.string.hero_feeding_title), body, Icons.Filled.CheckCircle, Tone.GOOD, stop, vm::stop, true)
                state.feed.state == FeedState.DISCONNECTED -> Hero(stringResource(R.string.hero_feed_retry_title), stringResource(R.string.hero_feed_retry_body), Icons.Filled.Warning, Tone.NEUTRAL, stop, vm::stop, true)
                else -> Hero(stringResource(R.string.hero_feed_connecting_title), body, Icons.Filled.Info, Tone.NEUTRAL, stop, vm::stop, true, busy = true)
            }
        }
        RunState.Starting -> Hero(
            stringResource(R.string.hero_starting_title), stringResource(R.string.hero_starting_body),
            Icons.Filled.Info, Tone.NEUTRAL, stop, vm::stop, actionOutlined = true, busy = true,
        )
        is RunState.Failed -> Hero(
            stringResource(R.string.hero_failed_title),
            run.error ?: stringResource(R.string.hero_failed_body, run.exitCode?.toString() ?: "?"),
            Icons.Filled.Warning, Tone.PROBLEM,
            stringResource(R.string.action_try_again).takeIf { state.sdr is SdrState.Ready },
            vm::restart,
        )
        is RunState.Paused -> {
            val (title, body) = when (run.reason) {
                FeederStatus.Reason.HOT -> R.string.hero_hot_title to R.string.hero_hot_body
                FeederStatus.Reason.MOBILE_DATA -> R.string.hero_mobile_data_title to R.string.hero_mobile_data_body
                else -> R.string.hero_low_battery_title to R.string.hero_low_battery_body
            }
            Hero(
                stringResource(title), stringResource(body),
                Icons.Filled.Warning, Tone.PROBLEM, stop, vm::stop, actionOutlined = true,
            )
        }
        is RunState.Stopped -> if (run.reason == FeederStatus.Reason.SDR_FAILING && state.sdr is SdrState.Ready) Hero(
            stringResource(R.string.hero_sdr_failing_title), stringResource(R.string.hero_sdr_failing_body),
            Icons.Filled.Warning, Tone.PROBLEM, stringResource(R.string.action_try_again), vm::start,
        ) else when (val sdr = state.sdr) {
            SdrState.NoUsbHost -> Hero(
                stringResource(R.string.hero_no_usb_title), stringResource(R.string.hero_no_usb_body),
                Icons.Filled.Warning, Tone.PROBLEM,
            )
            SdrState.NotConnected -> Hero(
                stringResource(
                    if (run.reason == FeederStatus.Reason.SDR_UNPLUGGED) R.string.hero_unplugged_title
                    else R.string.hero_plug_in_title
                ),
                stringResource(R.string.hero_plug_in_body),
                Icons.Filled.Info, Tone.NEUTRAL,
            )
            is SdrState.NeedsPermission -> Hero(
                stringResource(R.string.hero_permission_title), stringResource(R.string.hero_permission_body, sdr.name),
                Icons.Filled.Info, Tone.NEUTRAL, stringResource(R.string.action_allow), vm::requestUsbPermission,
            )
            is SdrState.Ready -> Hero(
                stringResource(if (state.settings.userStopped) R.string.hero_paused_title else R.string.hero_ready_title),
                stringResource(R.string.hero_ready_body, sdr.name),
                Icons.Filled.Info, Tone.NEUTRAL, stringResource(R.string.action_start), vm::start,
            )
        }
    }
}

/**
 * Proof that this phone is feeding: the feed connection and how long it has been up, what
 * readsb has sent, and what it's decoding, updated live. All counted on the phone itself.
 */
@Composable
private fun LiveFeedCard(state: UiState, vm: MainViewModel, onShowAircraft: () -> Unit) {
    val r = state.receiver ?: return
    val f = state.feed
    SectionCard(stringResource(if (state.feedingAdsbx) R.string.live_feed_title else R.string.reception_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val now = System.currentTimeMillis()
            val (color, text) = when {
                !state.feedingAdsbx -> Brand.Warn to stringResource(R.string.feed_off)
                f.state == FeedState.CONNECTED -> Brand.Good to buildString {
                    append(stringResource(R.string.feed_connected_for, Format.duration(now - (f.connectedSinceMs ?: now))))
                    if (f.reconnects > 0) append(" · ").append(pluralStringResource(R.plurals.feed_drops, f.reconnects, f.reconnects))
                }
                f.state == FeedState.DISCONNECTED -> Brand.Bad to stringResource(R.string.feed_disconnected)
                else -> Brand.Warn to stringResource(R.string.feed_connecting)
            }
            StatusDot(color)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.feedingAdsbx) {
                StatTile(f.uploadBytesPerSec?.let { Format.rate(it) } ?: "–", stringResource(R.string.stat_sent_now), Modifier.weight(1f))
            }
            StatTile(r.messagesPerSecond?.let { "%.0f".format(Locale.US, it) } ?: "–",
                stringResource(R.string.stat_msgs), Modifier.weight(1f))
            StatTile("${r.aircraft.size}", stringResource(R.string.stat_aircraft), Modifier.weight(1f))
        }

        Sparkline(f.rateHistory, capacity = MainViewModel.HISTORY_SECONDS)
        Text(
            stringResource(R.string.sparkline_caption, "%.0f".format(Locale.US, f.rateHistory.maxOrNull() ?: 0f)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.feedingAdsbx) {
            DetailRow(stringResource(R.string.detail_sent), f.bytesSent?.let { Format.bytes(it) } ?: "–")
        }
        DetailRow(stringResource(R.string.detail_messages), f.messagesTotal?.let { Format.count(it) } ?: "–")
        DetailRow(stringResource(R.string.detail_aircraft_seen), f.aircraftSeen?.let { Format.count(it) } ?: "–")
        DetailRow(stringResource(R.string.detail_positions), "${r.withPosition}")
        DetailRow(stringResource(R.string.detail_max_range),
            r.maxRangeNm?.let { stringResource(R.string.aircraft_distance, "%.0f".format(Locale.US, it)) } ?: "–")
        if (r.signalDbfs != null && r.noiseDbfs != null) {
            DetailRow(stringResource(R.string.detail_signal_noise),
                "%.1f / %.1f dBFS".format(Locale.US, r.signalDbfs, r.noiseDbfs))
        }
        f.startedMs?.let {
            DetailRow(stringResource(R.string.detail_running_for), Format.duration(System.currentTimeMillis() - it))
        }
        if (r.receiverLat != null && r.receiverLon != null) {
            DetailRow(
                stringResource(if (state.liveLocation != null) R.string.detail_position_live else R.string.detail_position),
                // About 1 km: enough to see readsb has the position (and that it follows the phone in
                // mobile mode). This screen is the one people screenshot; Settings shows it exactly.
                "%.2f, %.2f".format(Locale.US, r.receiverLat, r.receiverLon),
            )
        } else {
            WarningRow(stringResource(R.string.warn_no_position))
        }
        r.samplesDropped?.takeIf { it > 0 }?.let { WarningRow(stringResource(R.string.warn_dropped, it)) }
        if (state.feedingAdsbx) MlatBlock(state, vm)
        if (state.feedingAdsbx) state.stats?.let { s ->
            DetailRow(stringResource(R.string.detail_stats), when {
                s.error != null -> stringResource(R.string.stats_failing, s.error)
                s.lastOkMs != null -> stringResource(R.string.stats_sent_ago, Format.duration(System.currentTimeMillis() - s.lastOkMs))
                else -> stringResource(R.string.stats_starting)
            })
        }
        if (state.feedingAdsbx) {
            Text(stringResource(R.string.feed_counted_note, state.settings.uuid.take(8)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = vm::openCoach) { Text(stringResource(R.string.coach_open)) }
            TextButton(onClick = onShowAircraft) { Text(stringResource(R.string.action_see_aircraft)) }
        }
    }
}

@Composable
private fun HealthCard(state: UiState) {
    val h = state.health ?: return
    val context = LocalContext.current
    SectionCard(stringResource(R.string.health_title)) {
        val charger = when {
            h.charger == PhoneHealth.Charger.NONE -> stringResource(R.string.health_not_charging)
            // On a charger isn't the same as charging: a pad can stay connected and deliver nothing.
            !h.batteryCharging -> stringResource(when (h.charger) {
                PhoneHealth.Charger.WIRELESS -> R.string.health_idle_wireless
                PhoneHealth.Charger.USB -> R.string.health_idle_usb
                else -> R.string.health_idle
            })
            else -> when (h.charger) {
                PhoneHealth.Charger.AC -> stringResource(R.string.health_charging_ac)
                PhoneHealth.Charger.USB -> stringResource(R.string.health_charging_usb)
                PhoneHealth.Charger.WIRELESS -> stringResource(R.string.health_charging_wireless)
                else -> stringResource(R.string.health_charging)
            }
        }
        Text(stringResource(R.string.health_battery, h.batteryPercent ?: 0, charger), style = MaterialTheme.typography.bodyLarge)
        val details = buildList {
            h.batteryTempC?.let { add(stringResource(R.string.health_temp, "%.1f".format(Locale.US, it))) }
            h.batteryPowerW?.let {
                add(
                    if (it >= 0) stringResource(R.string.health_power_in, "%.1f".format(Locale.US, it))
                    else stringResource(R.string.health_power_out, "%.1f".format(Locale.US, -it))
                )
            }
        }
        if (details.isNotEmpty()) {
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.chargeProblem) WarningRow(stringResource(R.string.warn_charge_problem))
        else if (!h.onCharger && state.feeding) WarningRow(stringResource(R.string.warn_on_battery))
        if (h.hot) WarningRow(stringResource(R.string.warn_hot))
        if (!h.ignoringBatteryOptimizations) {
            WarningRow(stringResource(R.string.warn_battery_optimised))
            OutlinedButton(onClick = { requestBatteryExemption(context) }) {
                Text(stringResource(R.string.action_allow_background))
            }
        }
        Text(stringResource(R.string.health_tip_charge_limit), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


@Composable
private fun LinksCard(state: UiState) {
    val loc = state.liveLocation ?: state.settings.location
    val mlat = state.settings.mlatEnabled && state.settings.feedAdsbx
    if (loc == null && !mlat) return
    val context = LocalContext.current
    SectionCard(stringResource(R.string.links_title)) {
        if (loc != null) {
            TextButton(onClick = { openUrl(context, Links.map(loc.lat, loc.lon)) }) { Text(stringResource(R.string.link_map)) }
        }
        if (mlat) {
            TextButton(onClick = { openUrl(context, Links.MLAT_SYNC) }) { Text(stringResource(R.string.link_mlat_sync)) }
            if (!state.settings.mlatPrivacy) {
                TextButton(onClick = { openUrl(context, Links.MLAT_MAP) }) { Text(stringResource(R.string.link_mlat_map)) }
            }
        }
    }
}


/**
 * Opens this app's App info page, where the user turns battery optimisation off (Battery >
 * Unrestricted, or "Don't optimise" on older versions). Falls back to the system's battery
 * optimisation list. The app doesn't hold REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, the one-tap
 * dialog: Play restricts it to certain kinds of app, and it only matters on battery (Doze doesn't
 * apply on a charger), so it isn't worth a review risk.
 */
fun requestBatteryExemption(context: android.content.Context) {
    val opened = runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
    }.isSuccess
    if (!opened) runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
