package com.adsbexchange.feeder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adsbexchange.feeder.R
import com.adsbexchange.feeder.data.Aircraft
import java.util.Locale

/** What this receiver is hearing right now, nearest first. Tap to open on the ADSBx map. */
@Composable
fun AircraftScreen(state: UiState) {
    val list = state.receiver?.aircraft.orEmpty()
    if (!state.feeding || list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(if (state.feeding) R.string.aircraft_empty_listening else R.string.aircraft_empty_not_running),
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.aircraft_header, list.size, list.count { it.lat != null }),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        items(list, key = { it.hex }) { a ->
            ListItem(
                headlineContent = { Text(a.callsign ?: a.hex.uppercase(Locale.US), fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text(details(a)) },
                trailingContent = {
                    a.distanceNm?.let { Text(stringResource(R.string.aircraft_distance, "%.0f".format(Locale.US, it))) }
                },
                modifier = Modifier.clickable { openUrl(context, Links.aircraft(a.hex)) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun details(a: Aircraft): String = buildList {
    if (a.callsign != null) add(a.hex.uppercase(Locale.US))
    when {
        a.onGround -> add(stringResource(R.string.aircraft_ground))
        a.altitudeFt != null -> add(stringResource(R.string.aircraft_altitude, "%,d".format(Locale.US, a.altitudeFt)))
    }
    a.groundSpeedKt?.let { add(stringResource(R.string.aircraft_speed, "%.0f".format(Locale.US, it))) }
    if (a.lat == null) add(stringResource(R.string.aircraft_no_position))
}.joinToString(" · ")
