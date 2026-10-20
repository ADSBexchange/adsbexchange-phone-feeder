package com.adsbexchange.feeder.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsbexchange.feeder.R
import com.adsbexchange.feeder.ui.theme.Brand

@Composable
fun AppRoot(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.settings.onboardingDone) {
        OnboardingScreen(state, vm)
    } else {
        MainScaffold(state, vm)
    }
}

private enum class Tab(val label: Int, val icon: ImageVector) {
    STATUS(R.string.tab_status, Icons.Filled.Home),
    RADAR(R.string.tab_radar, RadarIcon),
    AIRCRAFT(R.string.tab_aircraft, Icons.AutoMirrored.Filled.List),
    HISTORY(R.string.tab_history, Icons.Filled.Star),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

/** Two range rings, a sweep line and a blip. Material's core icons have no radar. */
private val RadarIcon: ImageVector by lazy {
    ImageVector.Builder("Radar", 24.dp, 24.dp, 24f, 24f).apply {
        val ring = SolidColor(Color.Black)
        path(stroke = ring, strokeLineWidth = 2f) {
            moveTo(12f, 2f); arcToRelative(10f, 10f, 0f, true, true, 0f, 20f); arcToRelative(10f, 10f, 0f, true, true, 0f, -20f)
        }
        path(stroke = ring, strokeLineWidth = 1.5f) {
            moveTo(12f, 7f); arcToRelative(5f, 5f, 0f, true, true, 0f, 10f); arcToRelative(5f, 5f, 0f, true, true, 0f, -10f)
        }
        path(stroke = ring, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) { moveTo(12f, 12f); lineTo(18.5f, 5.5f) }
        path(fill = ring) {
            moveTo(14f, 15.5f); arcToRelative(1.6f, 1.6f, 0f, true, true, 3.2f, 0f); arcToRelative(1.6f, 1.6f, 0f, true, true, -3.2f, 0f); close()
        }
    }.build()
}

@Composable
private fun MainScaffold(state: UiState, vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.STATUS) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            vm.messageShown()
            snackbar.showSnackbar(it)
        }
    }
    Scaffold(
        topBar = { BrandHeader() },
        bottomBar = {
            NavigationBar(containerColor = Brand.Black) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Brand.Black,
                            selectedTextColor = Brand.Green,
                            indicatorColor = Brand.Green,
                            unselectedIconColor = Brand.TextSecondary,
                            unselectedTextColor = Brand.TextSecondary,
                        ),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.STATUS -> StatusScreen(state, vm, onShowAircraft = { tab = Tab.AIRCRAFT })
                Tab.RADAR -> RadarScreen(state, vm)
                Tab.AIRCRAFT -> AircraftScreen(state)
                Tab.HISTORY -> HistoryScreen(state, vm)
                Tab.SETTINGS -> SettingsScreen(state, vm)
            }
        }
    }
}
