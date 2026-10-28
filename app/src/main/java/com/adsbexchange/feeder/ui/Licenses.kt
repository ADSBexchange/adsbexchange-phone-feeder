package com.adsbexchange.feeder.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adsbexchange.feeder.BuildConfig
import com.adsbexchange.feeder.R

/**
 * An open-source component of the app. Its license texts are bundled in assets/licenses/, as the
 * GPL and the other licenses require them to be shipped with the app.
 */
internal data class OssComponent(
    val name: String,
    @param:StringRes val use: Int,
    val license: String,
    val copyright: String,
    val url: String,
    val licenseFiles: List<String>,
)

/** Everything in the APK: the C libraries from cpp/cmake/deps.cmake and the runtime Maven packages. */
internal val OSS_COMPONENTS = listOf(
    // The app itself, first. The copyright holder is JETNET, L.L.C. (confirmed by Legal, October 2026).
    OssComponent(
        "ADS-B Exchange Phone Feeder", R.string.oss_app, "GPL-3.0-or-later",
        "Copyright © 2026 JETNET, L.L.C.", BuildConfig.SOURCE_URL, listOf("GPL-3.0.txt"),
    ),
    OssComponent(
        "readsb", R.string.oss_readsb, "GPL-3.0-or-later",
        "Copyright © 2020 Matthias Wirth, © 2019 Michael Wolf, and others; includes dump1090 code " +
            "© 2012 Salvatore Sanfilippo (BSD)",
        "https://github.com/wiedehopf/readsb", listOf("readsb-NOTICE.txt", "GPL-3.0.txt"),
    ),
    OssComponent(
        "mlat-client", R.string.oss_mlat, "GPL-3.0-or-later",
        "Copyright © 2015 Oliver Jowett",
        "https://github.com/adsbexchange/mlat-client", listOf("GPL-3.0.txt"),
    ),
    OssComponent(
        "librtlsdr (RTL-SDR Blog)", R.string.oss_rtlsdr, "GPL-2.0-or-later",
        "Copyright © 2012-2014 Steve Markgraf, © 2012 Dimitri Stolnikov, and others",
        "https://github.com/rtlsdrblog/rtl-sdr-blog", listOf("GPL-2.0.txt"),
    ),
    OssComponent(
        "libusb", R.string.oss_libusb, "LGPL-2.1-or-later",
        "Copyright © 2001 Johannes Erdfelt, © 2007-2009 Daniel Drake, © 2010-2012 Peter Stuge, " +
            "© 2008-2016 Nathan Hjelm, and others",
        "https://github.com/libusb/libusb", listOf("LGPL-2.1.txt"),
    ),
    OssComponent(
        "Zstandard", R.string.oss_zstd, "BSD-3-Clause",
        "Copyright © Meta Platforms, Inc. and affiliates",
        "https://github.com/facebook/zstd", listOf("zstd-BSD.txt"),
    ),
    OssComponent(
        "AndroidX and Jetpack Compose", R.string.oss_androidx, "Apache-2.0",
        "Copyright © The Android Open Source Project",
        "https://developer.android.com/jetpack/androidx", listOf("Apache-2.0.txt"),
    ),
    OssComponent(
        "Kotlin, kotlinx, JSpecify, Guava listenablefuture", R.string.oss_kotlin, "Apache-2.0",
        "Copyright © JetBrains s.r.o. and Kotlin contributors; the JSpecify and Guava authors",
        "https://kotlinlang.org", listOf("Apache-2.0.txt"),
    ),
    OssComponent(
        "DM Sans", R.string.oss_dm_sans, "OFL-1.1",
        "Copyright © 2014 The DM Sans Project Authors",
        "https://github.com/googlefonts/dm-fonts", listOf("DM-Sans-OFL.txt"),
    ),
    OssComponent(
        "OurAirports data", R.string.oss_airports, "Public domain (the Unlicense)",
        "OurAirports contributors", "https://ourairports.com/data/", listOf("OurAirports-Unlicense.txt"),
    ),
)

/** Full-screen list of [OSS_COMPONENTS]; tapping one shows its license text. */
@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val current = selected
                    Text(
                        if (current == null) stringResource(R.string.action_licenses) else OSS_COMPONENTS[current].name,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(8.dp),
                    )
                    if (current != null) {
                        TextButton(onClick = { selected = null }) { Text(stringResource(R.string.action_back)) }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                }
                HorizontalDivider()
                val index = selected
                if (index == null) ComponentList { selected = it } else LicenseText(OSS_COMPONENTS[index])
            }
        }
    }
}

@Composable
private fun ComponentList(onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyMedium)
        OSS_COMPONENTS.forEachIndexed { i, c ->
            SectionCard(null, Modifier.clickable { onSelect(i) }) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${stringResource(c.use)} · ${c.license}", style = MaterialTheme.typography.bodyMedium)
                Text(c.copyright, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LicenseText(c: OssComponent) {
    val context = LocalContext.current
    val text = remember(c) {
        c.licenseFiles.joinToString("\n\n\n") { f ->
            runCatching { context.assets.open("licenses/$f").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${c.license} · ${c.copyright}", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { openUrl(context, c.url) }) { Text(stringResource(R.string.action_project_page)) }
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
    }
}
