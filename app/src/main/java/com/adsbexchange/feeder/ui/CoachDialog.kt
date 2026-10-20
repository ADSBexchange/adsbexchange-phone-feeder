package com.adsbexchange.feeder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adsbexchange.feeder.R
import java.util.Locale

/** The antenna coach (see [AntennaCoach]): live numbers, and a 30 s measurement of each spot to compare. */
@Composable
fun CoachDialog(coach: AntennaCoach.State, receiving: Boolean, vm: MainViewModel) {
    var label by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = vm::closeCoach, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.coach_open), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(8.dp))
                    TextButton(onClick = vm::closeCoach) { Text(stringResource(R.string.action_close)) }
                }
                HorizontalDivider()
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.coach_intro), style = MaterialTheme.typography.bodyMedium)
                    if (!receiving) {
                        WarningRow(stringResource(R.string.coach_not_running))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(coach.messagesPerSecond?.let { "%.0f".format(Locale.US, it) } ?: "–",
                            stringResource(R.string.coach_msgs), Modifier.weight(1f))
                        StatTile("${coach.aircraft}", stringResource(R.string.coach_aircraft), Modifier.weight(1f))
                    }
                    StatTile(coach.rangeNm?.let { stringResource(R.string.aircraft_distance, "%.0f".format(Locale.US, it)) } ?: "–",
                        stringResource(R.string.coach_range), Modifier.fillMaxWidth())

                    val measuring = coach.measuring
                    if (measuring != null) {
                        Text(stringResource(R.string.coach_measuring, coach.measuringLabel.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { measuring }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = vm::cancelMeasurement) { Text(stringResource(R.string.action_cancel)) }
                    } else {
                        OutlinedTextField(
                            value = label, onValueChange = { label = it.take(40) }, singleLine = true,
                            label = { Text(stringResource(R.string.coach_label)) },
                            placeholder = { Text(stringResource(R.string.coach_label_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { vm.measureSpot(label); label = "" }, enabled = receiving,
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.coach_measure)) }
                    }

                    if (coach.spots.isNotEmpty()) {
                        SectionCard(stringResource(R.string.coach_spots)) {
                            coach.spots.forEachIndexed { i, s ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                        Text(
                                            stringResource(R.string.coach_spot_value, "%.0f".format(Locale.US, s.messagesPerSecond),
                                                s.aircraft, s.rangeNm?.let { stringResource(R.string.aircraft_distance, "%.0f".format(Locale.US, it)) } ?: "–"),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (i == 0 && coach.spots.size > 1) {
                                            Text(stringResource(R.string.coach_best), style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    TextButton(onClick = { vm.removeSpot(s) }) { Text(stringResource(R.string.action_remove)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
