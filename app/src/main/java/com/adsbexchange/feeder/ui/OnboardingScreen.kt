package com.adsbexchange.feeder.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsbexchange.feeder.R

/**
 * One screen, one button: what you need, a name, and the permissions. Plugging the SDR in
 * afterwards is what starts feeding.
 */
@Composable
fun OnboardingScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(state.settings.name) }
    var antennaWithin15m by rememberSaveable { mutableStateOf(state.settings.antennaWithin15m) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.finishOnboarding(name, antennaWithin15m)
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BrandHeader()
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium)

                SectionCard(stringResource(R.string.onboarding_need_title)) {
                    NeedRow(1, stringResource(R.string.onboarding_need_sdr))
                    NeedRow(2, stringResource(R.string.onboarding_need_antenna))
                    NeedRow(3, stringResource(R.string.onboarding_need_power))
                }

                FeederNameField(name, onChange = { name = it })

                LabeledSwitch(
                    stringResource(R.string.mlat_antenna_confirm), stringResource(R.string.onboarding_antenna_note),
                    antennaWithin15m, onChange = { antennaWithin15m = it },
                )

                Text(stringResource(R.string.onboarding_privacy), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { openUrl(context, Links.PRIVACY_POLICY) }) { Text(stringResource(R.string.action_privacy_policy)) }
            }
            // Pinned below the scrolling content, so the one button is always on screen.
            HorizontalDivider()
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 24.dp, vertical = 12.dp)) {
                Button(
                    onClick = {
                        permissions.launch(buildList {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_get_started)) }
            }
        }
    }
}

@Composable
private fun NeedRow(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
