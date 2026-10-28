package com.adsbexchange.feeder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsbexchange.feeder.R
import com.adsbexchange.feeder.data.Aircraft
import com.adsbexchange.feeder.data.Airports
import com.adsbexchange.feeder.data.RadarTrails
import com.adsbexchange.feeder.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** An aircraft with a position, placed relative to the receiver. */
private class Blip(val aircraft: Aircraft, val distanceNm: Double, val bearingDeg: Double) {
    val stale get() = (aircraft.seenPosSecondsAgo ?: 0.0) > RadarMath.STALE_POSITION_S
}

/**
 * What this receiver hears, around it, north at the top. Positions only (no map underneath, so it
 * doesn't show where the receiver is), with the coverage outline from History and short trails.
 * The range is a fixed choice or Auto; tapping an aircraft shows its details.
 */
@Composable
fun RadarScreen(state: UiState, vm: MainViewModel) {
    val r = state.receiver
    val rxLat = r?.receiverLat
    val rxLon = r?.receiverLon
    if (!state.feeding || r == null) return CenteredMessage(stringResource(R.string.radar_not_running))
    if (rxLat == null || rxLon == null) return CenteredMessage(stringResource(R.string.radar_no_position))

    val blips = r.aircraft.mapNotNull { a ->
        val lat = a.lat ?: return@mapNotNull null
        val lon = a.lon ?: return@mapNotNull null
        if ((a.seenPosSecondsAgo ?: 0.0) > RadarMath.MAX_POSITION_AGE_S) return@mapNotNull null
        val (d, b) = RadarMath.distanceBearing(rxLat, rxLon, lat, lon)
        Blip(a, d, b)
    }
    var autoNm by remember { mutableStateOf<Int?>(null) }
    val auto = RadarMath.autoRange(blips.maxOfOrNull { it.distanceNm }, autoNm)
    SideEffect { autoNm = auto }
    val chosen = state.settings.radarRangeNm
    val rangeNm = chosen.takeIf { it > 0 } ?: auto
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedBlip = blips.firstOrNull { it.aircraft.hex == selected }
    // Until most
    // directions have heard something it's a star of spokes rather than an outline, so wait.
    val coverage = state.history?.rangeNm?.takeIf { it.count { nm -> nm > 0 } * 2 >= it.size }
    // Airports (OurAirports, bundled): read once, off the main thread (half a megabyte), so they
    // appear a moment after the radar. As the desktop feeder (0.5.18).
    val context = LocalContext.current
    val airportList by produceState<Airports?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { Airports.bundled(context) }.getOrNull() }
    }
    val showAirports = state.settings.radarAirports
    val airports = remember(airportList, rxLat, rxLon, rangeNm, showAirports) {
        val list = airportList
        if (list == null || !showAirports) emptyList() else RadarMath.airportsToShow(list, rxLat, rxLon, rangeNm)
    }

    Column(Modifier.fillMaxSize()) {
        RangeChips(chosen, auto, vm::setRadarRange, showAirports, vm::setRadarAirports)
        val beyond = blips.count { it.distanceNm > rangeNm }
        Text(
            buildString {
                append(stringResource(R.string.radar_header, r.aircraft.size, blips.size - beyond))
                if (beyond > 0) append(" · ").append(stringResource(R.string.radar_beyond, beyond, rangeNm))
            },
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RadarCanvas(blips, state.trails, coverage, airports, rangeNm, selected, rxLat, rxLon, onSelect = { selected = it })
            if (blips.isEmpty()) {
                Text(
                    stringResource(if (r.aircraft.isEmpty()) R.string.radar_listening else R.string.radar_no_positions),
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }
        }
        AltitudeLegend()
        selectedBlip?.let { DetailCard(it, onClose = { selected = null }) }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RangeChips(chosen: Int, auto: Int, onChoose: (Int) -> Unit, showAirports: Boolean, onSetAirports: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Brand.GreenDeep, selectedLabelColor = Brand.Green,
        )
        val isAuto = chosen !in RadarMath.CHOICES_NM
        FilterChip(
            selected = isAuto, onClick = { onChoose(0) }, colors = colors,
            label = { Text(if (isAuto) stringResource(R.string.radar_auto_range, auto) else stringResource(R.string.radar_auto)) },
        )
        RadarMath.CHOICES_NM.forEach { nm ->
            FilterChip(
                selected = chosen == nm, onClick = { onChoose(nm) }, colors = colors,
                label = { Text(stringResource(R.string.radar_range, nm)) },
            )
        }
        FilterChip(
            selected = showAirports, onClick = { onSetAirports(!showAirports) }, colors = colors,
            label = { Text(stringResource(R.string.radar_airports)) },
        )
    }
}

@Composable
private fun RadarCanvas(
    blips: List<Blip>, trails: Map<String, List<RadarTrails.Point>>, coverage: List<Double>?,
    airports: List<RadarMath.PlacedAirport>, rangeNm: Int,
    selected: String?, rxLat: Double, rxLon: Double, onSelect: (String?) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val ringColor = Brand.Outline
    val labelColor = Brand.TextSecondary
    val groundLabel = stringResource(R.string.radar_label_ground)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val center = Offset(w / 2, h / 2)
        val radius = (min(w, h) / 2 - with(density) { 20.dp.toPx() }).coerceAtLeast(1f)
        val pxPerNm = radius / rangeNm
        fun toScreen(nm: Double, bearingDeg: Double): Offset {
            val a = Math.toRadians(bearingDeg)
            return Offset(center.x + (nm * pxPerNm * sin(a)).toFloat(), center.y - (nm * pxPerNm * cos(a)).toFloat())
        }
        // Nearest first: they get the labels when space runs out. The selected one goes first of all.
        val placed = blips.filter { it.distanceNm <= rangeNm }
            .sortedWith(compareBy<Blip> { it.aircraft.hex != selected }.thenBy { it.distanceNm })
            .map { it to toScreen(it.distanceNm, it.bearingDeg) }
        val hits by rememberUpdatedState(placed)
        val select by rememberUpdatedState(onSelect)
        val tapRadius = with(density) { 28.dp.toPx() }
        val icon = remember(density) { planePath(with(density) { 9.dp.toPx() }) }

        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { tap ->
                    val hit = hits.minByOrNull { (it.second - tap).getDistance() }
                        ?.takeIf { (it.second - tap).getDistance() <= tapRadius }
                    select(hit?.first?.aircraft?.hex)
                }
            }
        ) {
            val disc = Path().apply { addOval(Rect(center, radius)) }
            val ringLabels = drawRings(measurer, center, radius, rangeNm, ringColor, labelColor)
            clipPath(disc) {
                coverage?.let { drawCoverage(it, ::toScreen) }
                // Under the trails and aircraft, so the traffic stays on top.
                for (a in airports) drawAirport(a.airport, toScreen(a.distanceNm, a.bearingDeg))
                for ((blip, p) in placed) {
                    val trail = trails[blip.aircraft.hex] ?: continue
                    if (trail.size < 2 && blip.stale) continue
                    val line = Path()
                    trail.forEachIndexed { i, t ->
                        val (d, b) = RadarMath.distanceBearing(rxLat, rxLon, t.lat, t.lon)
                        val q = toScreen(d, b)
                        if (i == 0) line.moveTo(q.x, q.y) else line.lineTo(q.x, q.y)
                    }
                    line.lineTo(p.x, p.y)
                    drawPath(line, aircraftColor(blip.aircraft).copy(alpha = 0.45f),
                        style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            // The receiver.
            drawCircle(Brand.Green, 3.dp.toPx(), center)
            drawCircle(Brand.Green, 7.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            for ((blip, p) in placed.asReversed()) drawAircraft(blip, p, icon, blip.aircraft.hex == selected)
            // Aircraft labels keep clear of the ring labels and the receiver.
            val receiver = Rect(center, 8.dp.toPx())
            val taken = drawLabels(measurer, placed, selected, groundLabel, ringLabels + receiver)
            // Airport codes keep clear of all that and of the aircraft themselves, and are the ones
            // left out when space runs out.
            val icons = placed.map { (_, p) -> Rect(p, 10.dp.toPx()) }
            drawAirportLabels(measurer, airports.map { it.airport.code to toScreen(it.distanceNm, it.bearingDeg) }, taken + icons)
        }
    }
}

/** Draws the range rings and compass points; returns where the ring labels went. */
private fun DrawScope.drawRings(
    measurer: TextMeasurer, center: Offset, radius: Float, rangeNm: Int, ringColor: Color, labelColor: Color,
): List<Rect> {
    val step = RadarMath.ringStepNm(rangeNm)
    val style = TextStyle(color = labelColor, fontSize = 10.sp)
    val labels = ArrayList<Rect>()
    var nm = step
    while (nm <= rangeNm + 1e-9) {
        val r = (nm / rangeNm * radius).toFloat()
        val outer = nm > rangeNm - step + 1e-9
        drawCircle(ringColor, r, center, style = Stroke(if (outer) 1.5.dp.toPx() else 1.dp.toPx()))
        val label = measurer.measure(if (nm % 1.0 == 0.0) "%.0f".format(Locale.US, nm) else "%.1f".format(Locale.US, nm), style)
        // Along the north-east diagonal, just inside the ring.
        val d = r * 0.7071f
        val topLeft = Offset(center.x + d + 2f, center.y - d - label.size.height)
        drawText(label, topLeft = topLeft)
        labels += Rect(topLeft, Size(label.size.width.toFloat(), label.size.height.toFloat()))
        nm += step
    }
    drawLine(ringColor.copy(alpha = 0.6f), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1f)
    drawLine(ringColor.copy(alpha = 0.6f), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1f)
    val compass = TextStyle(color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (text, bearing) ->
        val layout = measurer.measure(text, if (text == "N") compass.copy(color = Brand.Green) else compass)
        val a = Math.toRadians(bearing)
        val gap = 11.dp.toPx()
        val p = Offset(center.x + ((radius + gap) * sin(a)).toFloat(), center.y - ((radius + gap) * cos(a)).toFloat())
        drawText(layout, topLeft = p - Offset(layout.size.width / 2f, layout.size.height / 2f))
    }
    return labels
}

/** The furthest each direction has been heard (History's range plot), as a faint outline. */
private fun DrawScope.drawCoverage(rangeNm: List<Double>, toScreen: (Double, Double) -> Offset) {
    val sector = 360.0 / rangeNm.size
    val shape = Path()
    rangeNm.forEachIndexed { i, nm ->
        val p = toScreen(nm, (i + 0.5) * sector)
        if (i == 0) shape.moveTo(p.x, p.y) else shape.lineTo(p.x, p.y)
    }
    shape.close()
    drawPath(shape, Brand.Green.copy(alpha = 0.06f))
    drawPath(shape, Brand.Green.copy(alpha = 0.3f), style = Stroke(1.dp.toPx()))
}

private fun DrawScope.drawAircraft(blip: Blip, p: Offset, icon: Path, selected: Boolean) {
    val a = blip.aircraft
    val alpha = if (blip.stale) 0.45f else 1f
    val color = aircraftColor(a).copy(alpha = alpha)
    val track = a.trackDeg
    when {
        a.onGround -> {
            val s = 3.dp.toPx()
            drawRect(color, Offset(p.x - s, p.y - s), Size(2 * s, 2 * s))
        }
        track == null -> {
            drawCircle(Color.Black.copy(alpha = alpha), 5.dp.toPx(), p)
            drawCircle(color, 4.dp.toPx(), p)
        }
        else -> translate(p.x, p.y) {
            rotate(track.toFloat(), pivot = Offset.Zero) {
                drawPath(icon, Color.Black.copy(alpha = alpha), style = Stroke(1.5.dp.toPx()))
                drawPath(icon, color)
            }
        }
    }
    if (selected) drawCircle(Color.White, 14.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
}

/**
 * Callsign (or hex) and altitude beside each aircraft, nearest first; a label that would overlap
 * one already drawn tries the other side, and is left out if that's taken too.
 */
private fun DrawScope.drawLabels(
    measurer: TextMeasurer, placed: List<Pair<Blip, Offset>>, selected: String?, ground: String, avoid: List<Rect>,
): List<Rect> {
    val shadow = Shadow(Color.Black, blurRadius = 4f)
    val taken = ArrayList(avoid)
    val gap = 11.dp.toPx()
    val bounds = Rect(0f, 0f, size.width, size.height)
    for ((blip, p) in placed) {
        val a = blip.aircraft
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = if (a.hex == selected) Color.White else Brand.Text, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, shadow = shadow)) {
                append(a.callsign ?: a.hex.uppercase(Locale.US))
            }
            altitudeText(a, ground)?.let {
                append("\n")
                withStyle(SpanStyle(color = Brand.TextSecondary, fontSize = 10.sp, shadow = shadow)) { append(it) }
            }
        }
        val layout = measurer.measure(text, TextStyle(lineHeight = 12.sp))
        val lw = layout.size.width.toFloat()
        val lh = layout.size.height.toFloat()
        val spot = listOf(
            Offset(p.x + gap, p.y - lh / 2),
            Offset(p.x - gap - lw, p.y - lh / 2),
            Offset(p.x - lw / 2, p.y + gap),
        ).map { Rect(it, Size(lw, lh)) }
            .firstOrNull { r -> taken.none { it.overlaps(r) } && r.left >= bounds.left && r.right <= bounds.right && r.top >= bounds.top && r.bottom <= bounds.bottom }
            ?: continue
        taken += spot
        drawText(layout, topLeft = spot.topLeft)
    }
    return taken
}

/** Airport markers: a ring, larger for larger airports, with a dot in the large ones. */
private fun DrawScope.drawAirport(airport: Airports.Airport, p: Offset) {
    val r = when (airport.size) {
        Airports.Size.LARGE -> 5.dp
        Airports.Size.MEDIUM -> 4.dp
        Airports.Size.SMALL -> 3.dp
    }.toPx()
    drawCircle(AIRPORT_COLOR, r, p, style = Stroke(1.5.dp.toPx()))
    if (airport.size == Airports.Size.LARGE) drawCircle(AIRPORT_COLOR, 1.5.dp.toPx(), p)
}

/**
 * Each airport's code beside its marker, in the order given; left out where it would overlap [avoid],
 * another airport's marker, or a code already placed.
 */
private fun DrawScope.drawAirportLabels(measurer: TextMeasurer, airports: List<Pair<String, Offset>>, avoid: List<Rect>) {
    val style = TextStyle(color = AIRPORT_COLOR, fontSize = 10.sp, shadow = Shadow(Color.Black, blurRadius = 3f))
    val taken = ArrayList(avoid)
    val markers = airports.map { (_, p) -> Rect(p, 6.dp.toPx()) }
    val bounds = Rect(0f, 0f, size.width, size.height)
    val gap = 8.dp.toPx()
    for ((i, airport) in airports.withIndex()) {
        val (code, p) = airport
        val layout = measurer.measure(code, style)
        val lw = layout.size.width.toFloat()
        val lh = layout.size.height.toFloat()
        val spot = listOf(
            Offset(p.x + gap, p.y - lh / 2),
            Offset(p.x - gap - lw, p.y - lh / 2),
            Offset(p.x - lw / 2, p.y + gap),
        ).map { Rect(it, Size(lw, lh)) }
            .firstOrNull { r ->
                taken.none { it.overlaps(r) } && markers.withIndex().none { (j, m) -> j != i && m.overlaps(r) } &&
                    r.left >= bounds.left && r.right <= bounds.right && r.top >= bounds.top && r.bottom <= bounds.bottom
            }
            ?: continue
        taken += spot
        drawText(layout, topLeft = spot.topLeft)
    }
}

/** Airports in a muted blue-grey, apart from the altitude colours. */
private val AIRPORT_COLOR = Color(0xFF8FA6B8)

private fun altitudeText(a: Aircraft, ground: String): String? = when {
    a.onGround -> ground
    a.altitudeFt != null -> "%,d ft".format(Locale.US, a.altitudeFt)
    else -> null
}

/** The altitude colour; grey on the ground or with no altitude. */
private fun aircraftColor(a: Aircraft): Color {
    val alt = a.altitudeFt
    return when {
        a.onGround -> Color(0xFF8C8C8C)
        alt == null -> Color(0xFFBDBDBD)
        else -> altitudeColor(alt)
    }
}

private fun altitudeColor(altitudeFt: Int): Color {
    val hue = RadarMath.altitudeHue(altitudeFt).coerceIn(0f, 360f)
    return Color.hsl(hue, 0.88f, RadarMath.lightness(hue))
}

/** A plane seen from above, nose up (rotate by the track), about [half] px from the centre to the nose. */
private fun planePath(half: Float): Path {
    // Right half, nose to tail, in units of [half]; mirrored for the left.
    val right = listOf(
        0f to -1f, 0.13f to -0.78f, 0.13f to -0.25f, 1f to 0.2f, 1f to 0.36f, 0.13f to 0.12f,
        0.1f to 0.62f, 0.42f to 0.84f, 0.42f to 0.96f, 0f to 0.86f,
    )
    val outline = right + right.dropLast(1).drop(1).reversed().map { (x, y) -> -x to y }
    return Path().apply {
        outline.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * half, y * half) else lineTo(x * half, y * half) }
        close()
    }
}

/** The altitude scale, as on the ADS-B Exchange map, with a swatch for aircraft on the ground. */
@Composable
private fun AltitudeLegend() {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = Brand.TextSecondary, fontSize = 10.sp)
    val ground = stringResource(R.string.radar_ground)
    Canvas(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 16.dp, vertical = 4.dp)) {
        val groundLabel = measurer.measure(ground, style)
        val swatch = 10.dp.toPx()
        drawRect(Color(0xFF8C8C8C), Offset(0f, 0f), Size(swatch, swatch))
        drawText(groundLabel, topLeft = Offset(0f, swatch + 2.dp.toPx()))
        val left = maxOf(swatch, groundLabel.size.width.toFloat()) + 12.dp.toPx()
        val width = size.width - left - 12.dp.toPx()
        val top = RadarMath.LEGEND_TOP_FT
        val colors = (0..24).map { altitudeColor(it * top / 24) }
        drawRect(Brush.horizontalGradient(colors, startX = left, endX = left + width), Offset(left, 0f), Size(width, swatch))
        RadarMath.LEGEND_FT.forEach { ft ->
            val text = if (ft == 0) "0" else "${ft / 1000}k" + if (ft == RadarMath.LEGEND_FT.last()) " ft" else ""
            val layout = measurer.measure(text, style)
            val x = left + width * ft / top - if (ft == 0) 0f else layout.size.width / 2f
            drawText(layout, topLeft = Offset(x, swatch + 2.dp.toPx()))
        }
    }
}

@Composable
private fun DetailCard(blip: Blip, onClose: () -> Unit) {
    val a = blip.aircraft
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Brand.Panel),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append(a.callsign ?: a.hex.uppercase(Locale.US))
                        if (a.callsign != null) append("  ").append(a.hex.uppercase(Locale.US))
                    },
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) }
            }
            Text(details(blip), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { openUrl(context, Links.aircraft(a.hex)) }, modifier = Modifier.padding(top = 2.dp)) {
                Text(stringResource(R.string.radar_open_map))
            }
        }
    }
}

@Composable
private fun details(blip: Blip): String {
    val a = blip.aircraft
    return buildList {
        when {
            a.onGround -> add(stringResource(R.string.aircraft_ground))
            a.altitudeFt != null -> add(stringResource(R.string.aircraft_altitude, "%,d".format(Locale.US, a.altitudeFt)))
        }
        a.groundSpeedKt?.let { add(stringResource(R.string.aircraft_speed, "%.0f".format(Locale.US, it))) }
        a.trackDeg?.let { add(stringResource(R.string.radar_track, "%03.0f".format(Locale.US, it))) }
        add(stringResource(R.string.radar_where, "%.0f".format(Locale.US, blip.distanceNm), compassPoint(blip.bearingDeg)))
        a.rssi?.let { add(stringResource(R.string.radar_signal, "%.1f".format(Locale.US, it))) }
        a.seenPosSecondsAgo?.takeIf { it > RadarMath.STALE_POSITION_S }?.let { add(stringResource(R.string.radar_position_age, it.toInt())) }
    }.joinToString(" · ")
}

private fun compassPoint(bearingDeg: Double): String =
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((bearingDeg + 22.5) % 360) / 45).toInt()]
