package com.adsbexchange.feeder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsbexchange.feeder.R
import com.adsbexchange.feeder.data.PowerPhase
import com.adsbexchange.feeder.data.PowerTest
import com.adsbexchange.feeder.data.PowerVariant
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Developer options: the [PowerTest] that compares the feeder with and without its wake lock. */
@Composable
fun PowerTestSection(state: UiState, vm: MainViewModel) {
    val test = state.powerTest
    SectionCard(stringResource(R.string.power_test_title)) {
        Text(stringResource(R.string.power_test_intro), style = MaterialTheme.typography.bodyMedium)
        when {
            test == null -> OutlinedButton(onClick = vm::startPowerTest) { Text(stringResource(R.string.action_start_power_test)) }
            test.running -> {
                val p = test.phases.last()
                Text(
                    stringResource(
                        R.string.power_test_progress, test.index + 1, test.plan.size, variantLabel(p.variant),
                        p.countedMs / 60_000, PowerTest.PHASE_MS / 60_000,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!state.feeding) {
                    Text(stringResource(R.string.power_test_waiting), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Results(test.phases.dropLast(1))
                OutlinedButton(onClick = vm::cancelPowerTest) { Text(stringResource(R.string.action_cancel_power_test)) }
            }
            else -> {
                Text(
                    test.stopped?.let { stringResource(R.string.power_test_stopped, it) }
                        ?: stringResource(R.string.power_test_finished, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(test.finishedMs!!))),
                    style = MaterialTheme.typography.titleSmall,
                )
                Results(test.phases)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::startPowerTest) { Text(stringResource(R.string.action_run_again)) }
                    TextButton(onClick = vm::clearPowerTest) { Text(stringResource(R.string.action_clear)) }
                }
            }
        }
    }
}

@Composable
private fun Results(phases: List<PowerPhase>) {
    if (phases.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        phases.forEachIndexed { i, p ->
            Column(Modifier) {
                Text("${i + 1}. ${variantLabel(p.variant)}", style = MaterialTheme.typography.bodyMedium)
                Text(phaseLine(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.power_test_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun variantLabel(v: PowerVariant): String = stringResource(
    when (v) {
        PowerVariant.BASELINE -> R.string.power_variant_baseline
        PowerVariant.NO_WAKE_LOCK -> R.string.power_variant_no_wake_lock
    }
)

private fun watts(w: Double?) = w?.let { "%.2f W".format(Locale.US, it) } ?: "–"

/**
 * e.g. "18 min (12 on battery) · 2.41 W (counter) · 2.38 W (readings) · 45 msg/s · 1.2 kB/s ·
 * stalls: 0 · dropped: 0 · feed drops: 0 · bad-sync: 0"
 */
private fun phaseLine(p: PowerPhase): String {
    val runS = p.runMs / 1000.0
    val rate = if (runS > 0) "%.0f msg/s".format(Locale.US, p.messages / runS) else "–"
    val data = if (runS > 0) "%.1f kB/s".format(Locale.US, p.feedBytes / runS / 1000) else "–"
    val stalls = if (p.stalls > 0) "${p.stalls} (${p.stalledMs / 60_000} min)" else "0"
    return "${p.countedMs / 60_000} min (${p.batteryMs / 60_000} on battery) · ${watts(p.counterW)} (counter) · " +
        "${watts(p.sampledW)} (readings) · $rate · $data · stalls: $stalls · dropped: ${p.samplesDropped} · " +
        "feed drops: ${p.reconnects} · bad-sync: ${p.maxBadSync}"
}

/** One line for Share diagnostics. */
fun powerTestSummary(t: PowerTest): String = buildString {
    append(if (t.running) "running, phase ${t.index + 1} of ${t.plan.size}" else t.stopped?.let { "stopped: $it" } ?: "finished")
    t.phases.forEach { append(" | ${it.variant.name}: ${phaseLine(it)}, ${it.chargeUah} uAh") }
}
