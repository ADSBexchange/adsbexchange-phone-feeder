package com.adsbexchange.feeder.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsbexchange.feeder.BuildConfig
import com.adsbexchange.feeder.R
import com.adsbexchange.feeder.data.FeederSettings
import com.adsbexchange.feeder.data.StationLocation
import com.adsbexchange.feeder.data.TypedNumber
import com.adsbexchange.feeder.service.StatsUploader
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FeederSection(state, vm)
        LocationSection(state, vm)
        MlatSection(state, vm)
        FeedingSection(state, vm)
        HelpSection(state, vm)
        if (state.developer || state.powerTest != null) PowerTestSection(state, vm)
        AboutSection(vm)
    }
}

@Composable
private fun FeederSection(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    var name by rememberSaveable(state.settings.name) { mutableStateOf(state.settings.name) }
    SectionCard(stringResource(R.string.settings_feeder)) {
        FeederNameField(name, onChange = { name = it })
        if (!state.settings.mlatEnabled) {
            WarningRow(stringResource(R.string.feeder_name_mlat_off))
        }
        if (name != state.settings.name) {
            FilledTonalButton(onClick = { vm.rename(name) }) { Text(stringResource(R.string.action_save)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.feeder_uuid, state.settings.uuid),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Feeder UUID", state.settings.uuid))
            }) { Text(stringResource(R.string.action_copy)) }
        }
    }
}

/** The optional feeder name, with the hint that it's used (only) through MLAT. Shared with setup. */
@Composable
fun FeederNameField(name: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = { onChange(it.take(FeederSettings.MAX_NAME_LENGTH)) },
        label = { Text(stringResource(R.string.feeder_name)) },
        placeholder = { Text(stringResource(R.string.feeder_name_placeholder)) },
        supportingText = { Text(stringResource(R.string.feeder_name_note)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MlatSection(state: UiState, vm: MainViewModel) {
    val s = state.settings
    val gpsAlt = s.location?.altitudeM
    var alt by rememberSaveable(s.antennaAltitudeM) {
        mutableStateOf(s.antennaAltitudeM?.let { "%.0f".format(Locale.US, it) } ?: "")
    }
    SectionCard(stringResource(R.string.settings_mlat)) {
        LabeledSwitch(stringResource(R.string.mlat_switch), stringResource(R.string.mlat_switch_note), s.mlatEnabled, vm::setMlatEnabled)
        if (s.mlatEnabled) {
            LabeledSwitch(stringResource(R.string.mlat_privacy), stringResource(R.string.mlat_privacy_note), s.mlatPrivacy, vm::setMlatPrivacy)
            LabeledSwitch(stringResource(R.string.mlat_antenna_confirm), stringResource(R.string.mlat_antenna_confirm_note),
                s.antennaWithin15m, vm::setAntennaWithin15m)
            OutlinedTextField(
                value = alt, onValueChange = { alt = it },
                label = { Text(stringResource(R.string.mlat_altitude)) },
                placeholder = { gpsAlt?.let { Text(stringResource(R.string.mlat_altitude_gps, "%.0f".format(Locale.US, it))) } },
                supportingText = { Text(stringResource(R.string.mlat_altitude_note)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            val savedAlt = s.antennaAltitudeM?.let { "%.0f".format(Locale.US, it) } ?: ""
            if (alt != savedAlt) {
                FilledTonalButton(onClick = { vm.setAntennaAltitude(alt) }) { Text(stringResource(R.string.action_save)) }
            }
            state.mlat?.readiness?.let { r ->
                mlatReadinessText(r, s.location?.accuracyM)?.let { WarningRow(it) }
            }
        }
    }
}


@Composable
private fun LocationSection(state: UiState, vm: MainViewModel) {
    var manual by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onLocationPermissionResult()
    }
    SectionCard(stringResource(R.string.settings_location)) {
        Text(describe(state.settings.location), style = MaterialTheme.typography.bodyLarge)
        state.locating?.let { p ->
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                if (p.bestAccuracyM != null) stringResource(R.string.locating_progress, "%.0f".format(Locale.US, p.bestAccuracyM))
                else stringResource(R.string.locating_waiting),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!state.locationPermission) {
            WarningRow(stringResource(R.string.location_permission_needed))
            FilledTonalButton(onClick = {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text(stringResource(R.string.action_allow_location)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::updateLocationFromGps, enabled = state.locationPermission && state.locating == null) {
                Text(stringResource(R.string.action_update_gps))
            }
            OutlinedButton(onClick = { manual = true }) { Text(stringResource(R.string.action_enter_manually)) }
        }
        Text(stringResource(R.string.stationary_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (manual) ManualLocationDialog(state.settings.location, onDismiss = { manual = false }) { lat, lon ->
        manual = false
        vm.setManualLocation(lat, lon)
    }
}

@Composable
private fun describe(loc: StationLocation?): String {
    if (loc == null) return stringResource(R.string.location_not_set)
    val coords = "%.5f, %.5f".format(Locale.US, loc.lat, loc.lon)
    val source = stringResource(
        when (loc.source) {
            StationLocation.Source.GPS -> R.string.location_source_gps
            StationLocation.Source.NETWORK -> R.string.location_source_network
            StationLocation.Source.MANUAL -> R.string.location_source_manual
        }
    )
    val acc = loc.accuracyM?.let { " ±%.0f m".format(Locale.US, it) } ?: ""
    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(loc.timeMs))
    return "$coords$acc\n$source · $time"
}

@Composable
private fun ManualLocationDialog(current: StationLocation?, onDismiss: () -> Unit, onSave: (Double, Double) -> Unit) {
    var lat by rememberSaveable { mutableStateOf(current?.lat?.let { "%.5f".format(Locale.US, it) } ?: "") }
    var lon by rememberSaveable { mutableStateOf(current?.lon?.let { "%.5f".format(Locale.US, it) } ?: "") }
    val latD = TypedNumber.coordinate(lat)
    val lonD = TypedNumber.coordinate(lon)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manual_location_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.manual_location_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(lat, { lat = it }, label = { Text(stringResource(R.string.latitude)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(lon, { lon = it }, label = { Text(stringResource(R.string.longitude)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(latD!!, lonD!!) }, enabled = latD != null && lonD != null) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun FeedingSection(state: UiState, vm: MainViewModel) {
    var gain by rememberSaveable(state.settings.gain) { mutableStateOf(state.settings.gain) }
    SectionCard(stringResource(R.string.settings_feeding)) {
        LabeledSwitch(stringResource(R.string.feed_adsbx), stringResource(R.string.feed_adsbx_note),
            state.settings.feedAdsbx, vm::setFeedAdsbx)
        LabeledSwitch(stringResource(R.string.auto_start), stringResource(R.string.auto_start_note),
            state.settings.autoStart, vm::setAutoStart)
        // Stats are part of feeding ADS-B Exchange: no switch, just what they are and the stats page.
        Text(stringResource(R.string.stats_uploads), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.stats_uploads_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val context = LocalContext.current
        TextButton(onClick = { openUrl(context, StatsUploader.statsPageUrl(state.settings.uuid)) }) {
            Text(stringResource(R.string.action_stats_page))
        }
        LabeledSwitch(stringResource(R.string.feed_mobile_data), stringResource(R.string.feed_mobile_data_note),
            state.settings.feedOverMobileData, vm::setFeedOverMobileData)
        LabeledSwitch(stringResource(R.string.feed_alerts), stringResource(R.string.feed_alerts_note),
            state.settings.feedAlerts, vm::setFeedAlerts)
        OutlinedTextField(
            value = gain, onValueChange = { gain = it },
            label = { Text(stringResource(R.string.gain)) },
            supportingText = { Text(stringResource(R.string.gain_note)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (gain != state.settings.gain) {
            FilledTonalButton(onClick = { vm.setGain(gain) }) { Text(stringResource(R.string.action_save)) }
        }
        LabeledSwitch(stringResource(R.string.bias_tee), stringResource(R.string.bias_tee_note),
            state.settings.biasTee, vm::setBiasTee)
        if (state.feeding) {
            OutlinedButton(onClick = vm::restart) { Text(stringResource(R.string.action_restart)) }
        }
    }
}

@Composable
private fun HelpSection(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    var showLog by rememberSaveable { mutableStateOf(false) }
    val subject = stringResource(R.string.diagnostics_subject)
    SectionCard(stringResource(R.string.settings_help)) {
        Text(stringResource(R.string.help_setup), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.help_bias_tee), style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = { openUrl(context, BuildConfig.SUPPORT_URL) }) { Text(stringResource(R.string.action_discord)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, subject)
                    .putExtra(Intent.EXTRA_TEXT, vm.diagnostics())
                context.startActivity(Intent.createChooser(send, null))
            }) { Text(stringResource(R.string.action_share_diagnostics)) }
            OutlinedButton(onClick = { showLog = !showLog }) {
                Text(stringResource(if (showLog) R.string.action_hide_log else R.string.action_show_log))
            }
        }
        if (showLog) {
            val lines = state.receiver?.logTail.orEmpty().takeLast(80)
            Text(
                lines.joinToString("\n").ifBlank { stringResource(R.string.log_empty) },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                modifier = Modifier.heightIn(max = 400.dp).horizontalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun AboutSection(vm: MainViewModel) {
    val context = LocalContext.current
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    var versionTaps by rememberSaveable { mutableIntStateOf(0) }
    SectionCard(stringResource(R.string.settings_about)) {
        // Tapped 7 times: developer options (the power test), as in Android's own settings.
        Text(
            stringResource(R.string.about_version,
                BuildConfig.VERSION_NAME, BuildConfig.GIT_COMMIT),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable { if (++versionTaps >= 7) vm.unlockDeveloper() },
        )
        Text(stringResource(R.string.about_shared_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.onboarding_privacy), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The GPL's "appropriate legal notices": copyright, no warranty, the license, and the source.
        Text(OSS_COMPONENTS.first().copyright, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.about_license_notice), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openUrl(context, BuildConfig.SOURCE_URL) }) {
                Text(stringResource(R.string.action_source_code))
            }
            OutlinedButton(onClick = { showLicenses = true }) { Text(stringResource(R.string.action_licenses)) }
        }
    }
    if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })
}
