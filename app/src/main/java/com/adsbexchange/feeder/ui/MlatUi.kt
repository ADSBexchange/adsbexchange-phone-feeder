package com.adsbexchange.feeder.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsbexchange.feeder.R
import com.adsbexchange.feeder.data.MLAT_ACCURACY_M
import com.adsbexchange.feeder.data.MlatReadiness
import com.adsbexchange.feeder.data.mlatUser
import com.adsbexchange.feeder.ui.theme.Brand
import java.util.Locale

/** What MLAT is waiting for, or null when there's nothing to say. */
@Composable
fun mlatReadinessText(r: MlatReadiness, accuracyM: Float?): String? = when (r) {
    MlatReadiness.READY, MlatReadiness.OFF -> null
    MlatReadiness.NOT_FEEDING -> stringResource(R.string.mlat_wait_not_feeding)
    MlatReadiness.MOBILE_MODE -> stringResource(R.string.mlat_wait_mobile)
    MlatReadiness.NO_LOCATION -> stringResource(R.string.mlat_wait_location)
    MlatReadiness.IMPRECISE -> stringResource(
        R.string.mlat_wait_imprecise, "%.0f".format(Locale.US, MLAT_ACCURACY_M),
        accuracyM?.let { "%.0f".format(Locale.US, it) } ?: "?",
    )
    MlatReadiness.NO_ALTITUDE -> stringResource(R.string.mlat_wait_altitude)
    MlatReadiness.NOT_CONFIRMED -> stringResource(R.string.mlat_wait_confirm)
}

/** MLAT part of the Live feed card: state, sync with nearby feeders, and what it's sending. */
@Composable
fun MlatBlock(state: UiState, vm: MainViewModel) {
    val m = state.mlat ?: return
    if (m.readiness == MlatReadiness.OFF) {
        DetailRow(stringResource(R.string.mlat_title), stringResource(R.string.mlat_off))
        return
    }
    HorizontalDivider()
    Text(stringResource(R.string.mlat_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    val now = System.currentTimeMillis()
    val (color, text) = when {
        m.readiness != MlatReadiness.READY ->
            Brand.Warn to (mlatReadinessText(m.readiness, state.settings.location?.accuracyM) ?: "")
        m.state == "CONNECTED" -> Brand.Good to buildString {
            append(stringResource(R.string.mlat_connected_for, Format.duration(now - (m.connectedSinceMs ?: now))))
            m.peerCount?.let { append(" · ").append(pluralStringResource(R.plurals.mlat_peers, it, it)) }
        }
        m.state == "DENIED" -> Brand.Bad to stringResource(R.string.mlat_denied, m.message ?: "")
        m.state == "DISCONNECTED" -> Brand.Bad to stringResource(R.string.mlat_reconnecting)
        else -> Brand.Warn to stringResource(R.string.mlat_connecting)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
    when (m.readiness) {
        MlatReadiness.NOT_CONFIRMED -> FilledTonalButton(onClick = { vm.setAntennaConfirmed(true) }) {
            Text(stringResource(R.string.action_confirm_antenna))
        }
        MlatReadiness.IMPRECISE, MlatReadiness.NO_LOCATION -> FilledTonalButton(onClick = vm::updateLocationFromGps) {
            Text(stringResource(R.string.action_update_gps))
        }
        else -> Unit
    }
    if (m.readiness == MlatReadiness.READY && m.state == "CONNECTED") {
        // The server's bad-sync timeout: above 0, it has found this receiver's timing unreliable
        // against its peers and isn't using it for positions until the score decays.
        if ((m.badSyncTimeout ?: 0) > 0) WarningRow(stringResource(R.string.mlat_bad_sync))
        DetailRow(stringResource(R.string.mlat_requested), "${m.requested}")
        DetailRow(stringResource(R.string.mlat_syncs), Format.count(m.syncsSent))
        DetailRow(stringResource(R.string.mlat_messages), Format.count(m.mlatSent))
        m.outlierPercent?.let { DetailRow(stringResource(R.string.mlat_outliers), "%.1f%%".format(Locale.US, it)) }
        DetailRow(stringResource(R.string.mlat_sent), Format.bytes(m.bytesSent))
        DetailRow(stringResource(R.string.mlat_shown_as),
            if (state.settings.mlatPrivacy) stringResource(R.string.mlat_shown_as_private, state.settings.mlatUser())
            else state.settings.mlatUser())
    }
}
