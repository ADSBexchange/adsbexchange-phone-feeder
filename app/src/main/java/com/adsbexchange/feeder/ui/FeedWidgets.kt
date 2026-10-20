package com.adsbexchange.feeder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * A minimal area chart: [values] oldest to newest across the full width, scaled to [maxValue]
 * (or the data's own maximum). Flat, no axes; the caption beside it carries the numbers.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    capacity: Int = values.size,
) {
    val baseline = MaterialTheme.colorScheme.outline
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        drawLine(baseline, start = androidx.compose.ui.geometry.Offset(0f, size.height),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height), strokeWidth = 1f)
        if (values.size < 2) return@Canvas
        val max = values.max().coerceAtLeast(1f)
        val slots = capacity.coerceAtLeast(values.size) - 1
        val step = size.width / slots
        // Right-align so the newest sample is always at the right edge.
        val x0 = size.width - step * (values.size - 1)
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = x0 + i * step
            val y = size.height - (v / max) * (size.height - 4f)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(x0, size.height)
            close()
        }
        drawPath(area, color.copy(alpha = 0.18f))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * A label on the left and its value on the right. A long value wraps within 60% of the width, so
 * the label always keeps the rest. Unbounded, a value such as a feeder name plus "(hidden from the
 * MLAT map)" took the whole row and squeezed the label to one letter per line.
 */
@Composable
fun DetailRow(label: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val valueMax = maxWidth * 0.6f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = valueMax))
        }
    }
}

object Format {
    fun bytes(b: Long): String = when {
        b < 1_000 -> "$b B"
        b < 1_000_000 -> "%.1f kB".format(Locale.US, b / 1e3)
        b < 1_000_000_000 -> "%.1f MB".format(Locale.US, b / 1e6)
        else -> "%.2f GB".format(Locale.US, b / 1e9)
    }

    fun rate(bytesPerSec: Double): String = when {
        bytesPerSec < 1_000 -> "%.0f B/s".format(Locale.US, bytesPerSec)
        else -> "%.1f kB/s".format(Locale.US, bytesPerSec / 1e3)
    }

    fun count(n: Long): String = "%,d".format(Locale.US, n)

    /** For tiles: "9,876", "12.3 k", "4.56 M", "1.20 B". */
    fun compact(n: Long): String = when {
        n < 10_000 -> count(n)
        n < 1_000_000 -> "%.1f k".format(Locale.US, n / 1e3)
        n < 1_000_000_000 -> "%.2f M".format(Locale.US, n / 1e6)
        else -> "%.2f B".format(Locale.US, n / 1e9)
    }

    /** "45 s", "12 min", "2 h 13 min", "3 d 4 h". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            s < 86_400 -> "${s / 3600} h ${s % 3600 / 60} min"
            else -> "${s / 86_400} d ${s % 86_400 / 3600} h"
        }
    }
}
