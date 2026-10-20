package com.adsbexchange.feeder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsbexchange.feeder.R
import com.adsbexchange.feeder.data.DayStats
import com.adsbexchange.feeder.data.History
import com.adsbexchange.feeder.data.HistoryRecorder
import com.adsbexchange.feeder.data.Milestones
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Lifetime contribution to ADS-B Exchange, from the feeder service's [History]. */
@Composable
fun HistoryScreen(state: UiState, vm: MainViewModel) {
    val h = state.history?.takeIf { it.firstFedMs != null }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (h == null) {
            SectionCard(stringResource(R.string.history_title)) {
                Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Totals(h)
            MilestonesCard(h)
            LastDays(h)
            // A reset the service hasn't applied yet already shows as an empty plot.
            val resetPending = state.settings.rangeResetMs?.let { it > (h.rangeSinceMs ?: 0) } == true
            RangeCard(if (resetPending) List(History.RANGE_BEARINGS) { 0.0 } else h.rangeNm,
                if (resetPending) null else h.rangeSinceMs, onReset = vm::resetRange)
            Records(h)
            if (h.mlatSyncs > 0 || h.mlatMessages > 0) {
                SectionCard(stringResource(R.string.history_mlat)) {
                    DetailRow(stringResource(R.string.mlat_syncs), Format.count(h.mlatSyncs))
                    DetailRow(stringResource(R.string.mlat_messages), Format.count(h.mlatMessages))
                    Text(stringResource(R.string.history_mlat_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(stringResource(R.string.history_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun Totals(h: History) {
    SectionCard(stringResource(R.string.history_title)) {
        Text(stringResource(R.string.history_since, dateOf(h.firstFedMs!!)), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Format.compact(h.messages), stringResource(R.string.history_messages), Modifier.weight(1f))
            StatTile(Format.compact(h.uniqueAircraft.toLong()), stringResource(R.string.history_aircraft), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Format.duration(h.feedingMs), stringResource(R.string.history_time), Modifier.weight(1f))
            StatTile(Format.bytes(h.bytesSent), stringResource(R.string.history_sent), Modifier.weight(1f))
        }
    }
}

/** Each series' latest milestone and progress to the next. */
@Composable
private fun MilestonesCard(h: History) {
    SectionCard(stringResource(R.string.history_milestones)) {
        Milestones.progress(h).forEach { p ->
            val reached = p.reached
            val next = p.next
            Text(
                when {
                    next == null -> stringResource(R.string.milestone_all, milestoneLabel(reached!!))
                    reached != null -> stringResource(R.string.milestone_reached, milestoneLabel(reached))
                    else -> stringResource(R.string.milestone_first, milestoneLabel(next))
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (next != null) {
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                if (reached != null) {
                    Text(stringResource(R.string.milestone_next, milestoneLabel(next)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun milestoneLabel(m: Milestones.Milestone): String = when (m.kind) {
    Milestones.Kind.MESSAGES -> stringResource(R.string.milestone_messages, roundNumber(m.target))
    Milestones.Kind.AIRCRAFT -> stringResource(R.string.milestone_aircraft, roundNumber(m.target))
    Milestones.Kind.FEEDING -> (m.target / HistoryRecorder.DAY_MS).toInt().let { pluralStringResource(R.plurals.milestone_days, it, it) }
}

/** "10,000", "1 million", "1 billion": milestones are round numbers. */
private fun roundNumber(n: Long): String = when {
    n >= 1_000_000_000 && n % 1_000_000_000 == 0L -> "${n / 1_000_000_000} billion"
    n >= 1_000_000 && n % 1_000_000 == 0L -> "${n / 1_000_000} million"
    else -> Format.count(n)
}

@Composable
private fun LastDays(h: History) {
    val today = epochDayToday()
    val byDay = h.days.associateBy { it.day }
    val last = (today - 29..today).map { byDay[it] ?: DayStats(it) }
    SectionCard(stringResource(R.string.history_last_30)) {
        DayBars(last.map { it.messages.toFloat() })
        val t = last.last()
        Text(stringResource(R.string.history_today, Format.count(t.messages), Format.count(t.aircraft.toLong())),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // What this phone and SDR draw, measured whenever it fed off the charger (on a charger the
        // battery only shows the difference). Useful for comparing dongles or settings.
        last.lastOrNull { it.batteryMeanW != null }?.let { d ->
            Text(stringResource(R.string.history_battery, dateOfDay(d.day), d.batteryMeanW!!, Format.duration(d.batteryMs)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RangeCard(rangeNm: List<Double>, sinceMs: Long?, onReset: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val any = rangeNm.any { it > 0 }
    SectionCard(stringResource(R.string.history_range)) {
        RangePlot(rangeNm)
        Text(
            if (any && sinceMs != null) stringResource(R.string.history_range_since, dateOf(sinceMs))
            else stringResource(R.string.history_range_empty),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (any) TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.action_reset)) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.history_range_reset_title)) },
            text = { Text(stringResource(R.string.history_range_reset_body)) },
            confirmButton = { TextButton(onClick = { confirm = false; onReset() }) { Text(stringResource(R.string.action_reset)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * Polar plot of [rangeNm], north up: one spoke per sector, joined into a shape, inside range rings.
 * No map underneath, so it doesn't show where the receiver is.
 */
@Composable
private fun RangePlot(rangeNm: List<Double>) {
    val fill = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 11.sp)
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 - 14.dp.toPx()
        val max = rangeNm.maxOrNull()?.takeIf { it > 0 } ?: 100.0
        val step = RING_STEPS_NM.firstOrNull { max / it <= 4 } ?: RING_STEPS_NM.last()
        val outer = ceil(max / step).coerceAtLeast(1.0) * step
        fun point(bearingDeg: Double, nm: Double): Offset {
            val r = (nm / outer * radius).toFloat()
            val a = Math.toRadians(bearingDeg)
            return Offset(center.x + r * sin(a).toFloat(), center.y - r * cos(a).toFloat())
        }
        var ringNm = step
        while (ringNm <= outer + 1e-9) {
            val r = (ringNm / outer * radius).toFloat()
            drawCircle(ring, r, center, style = Stroke(1.dp.toPx()))
            drawText(measurer, "%.0f nm".format(Locale.US, ringNm), point(45.0, ringNm) + Offset(4f, -14f), labelStyle)
            ringNm += step
        }
        listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, bearing) ->
            val layout = measurer.measure(label, labelStyle)
            val p = point(bearing, outer * 1.09)
            drawText(layout, topLeft = p - Offset(layout.size.width / 2f, layout.size.height / 2f))
        }
        if (rangeNm.none { it > 0 }) return@Canvas
        val sector = 360.0 / rangeNm.size
        val shape = Path()
        rangeNm.forEachIndexed { i, nm ->
            val p = point((i + 0.5) * sector, nm)
            if (i == 0) shape.moveTo(p.x, p.y) else shape.lineTo(p.x, p.y)
        }
        shape.close()
        drawPath(shape, fill.copy(alpha = 0.25f))
        drawPath(shape, fill, style = Stroke(2.dp.toPx()))
    }
}

private val RING_STEPS_NM = listOf(10.0, 25.0, 50.0, 100.0, 150.0, 200.0)

@Composable
private fun Records(h: History) {
    SectionCard(stringResource(R.string.history_records)) {
        DetailRow(stringResource(R.string.history_longest_run), Format.duration(h.longestRunMs))
        val far = h.furthestNm
        val farMs = h.furthestMs
        if (far != null && farMs != null) {
            DetailRow(stringResource(R.string.history_furthest), stringResource(R.string.history_furthest_value, far, dateOf(farMs)))
        }
        h.busiestDay?.let { d ->
            DetailRow(stringResource(R.string.history_busiest_day),
                stringResource(R.string.history_busiest_value, Format.compact(d.messages), dateOfDay(d.day)))
        }
    }
}

/** One bar per value, oldest on the left; empty days show as a gap on the baseline. */
@Composable
private fun DayBars(values: List<Float>) {
    val bar = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val slot = size.width / values.size
        values.forEachIndexed { i, v ->
            if (v <= 0f) return@forEachIndexed
            val height = (v / max) * (size.height - 2f)
            drawRect(bar, Offset(i * slot + slot * 0.15f, size.height - height), Size(slot * 0.7f, height))
        }
    }
}

private fun epochDayToday(): Int = HistoryRecorder.epochDay(System.currentTimeMillis())

private fun dateOf(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

/** A local epoch day as a date: midnight UTC of that day, formatted in UTC. */
private fun dateOfDay(day: Int): String = DateFormat.getDateInstance(DateFormat.MEDIUM)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(Date(day * HistoryRecorder.DAY_MS))
