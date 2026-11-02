plugins {
    alias(libs.plugins.android.application)
    // Kotlin itself is built into AGP 9+; do not apply org.jetbrains.kotlin.android.
    alias(libs.plugins.kotlin.compose)
}

// The commit this build came from, shown under Settings > About so an installed build can be matched
// to its source; "-dirty" if tracked files had uncommitted changes. "unknown" when built without git.
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
    }.standardOutput.asText.get().trim()
}.getOrNull()
val gitCommit = git("rev-parse", "--short=7", "HEAD")?.ifEmpty { null }?.let { sha ->
    if (git("status", "--porcelain", "--untracked-files=no").isNullOrEmpty()) sha else "$sha-dirty"
} ?: "unknown"

android {
    // Package name is permanent once published on Play.
    namespace = "com.adsbexchange.feeder"
    compileSdk = 37 // androidx.core 1.19 requires 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.adsbexchange.phonefeeder"
        minSdk = 24 // Android 7.0 — old phones are the whole point
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.0"

        // Where users get this app's source, as the GPL requires (shown under Settings > About).
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/ADSBexchange/adsbexchange-phone-feeder\"")
        // Support is ADS-B Exchange's Discord server. This address is ADS-B Exchange's own, so the
        // invite behind it (today discord.gg/ad8SSMpWvH, which never expires) can change without
        // an app update. For now it opens their community page, one tap from Discord.
        buildConfigField("String", "SUPPORT_URL", "\"https://www.adsbexchange.com/discord\"")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")

        ndk {
            // armeabi-v7a matters here: many retired phones are 32-bit only.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    // Release signing with the Play upload key. The key and its passwords never live in this repo:
    // they come from environment variables (or the user-level ~/.gradle/gradle.properties).
    // Without them, release builds are signed with the debug key: they install, but Google Play
    // rejects them.
    fun secret(name: String): String? =
        providers.environmentVariable(name).orElse(providers.gradleProperty(name)).orNull?.takeIf { it.isNotBlank() }
    val uploadKeystore = secret("ADSBX_UPLOAD_KEYSTORE")
    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = secret("ADSBX_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = secret("ADSBX_UPLOAD_KEY_ALIAS")
                keyPassword = secret("ADSBX_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            // Native symbols go into the App Bundle so Play's crash reports show function names.
            ndk { debugSymbolLevel = "FULL" }
        }
    }

    buildFeatures {
        buildConfig = true // BuildConfig.DEBUG gates the smoke-test entry point
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

// Without the upload key, a release build stops, rather than quietly signing with the debug key
// (security report MF-SEC-02). A sideloaded test APK signed with the debug key has to be asked for
// with -PdebugSignedRelease, and still gets a warning. A release App Bundle is only for Google Play,
// so it always needs the key.
gradle.taskGraph.whenReady {
    val ours = allTasks.filter { it.project == project }
    val upload = android.signingConfigs.findByName("upload")
    val problem = when {
        upload == null -> "ADSBX_UPLOAD_KEYSTORE is not set"
        upload.storeFile?.isFile != true -> "the keystore ADSBX_UPLOAD_KEYSTORE names doesn't exist"
        upload.storePassword == null || upload.keyAlias == null || upload.keyPassword == null ->
            "ADSBX_UPLOAD_KEYSTORE_PASSWORD, ADSBX_UPLOAD_KEY_ALIAS or ADSBX_UPLOAD_KEY_PASSWORD is not set"
        else -> null
    } ?: return@whenReady
    if (ours.any { it.name == "bundleRelease" }) {
        throw GradleException("Release App Bundles are for Google Play and need the upload key, but $problem. " +
            "See README, \"For Google Play\".")
    }
    if (ours.any { it.name == "assembleRelease" }) {
        if (!project.hasProperty("debugSignedRelease")) {
            throw GradleException("Release signing: $problem. For a test APK signed with the debug key " +
                "(sideloading only), add -PdebugSignedRelease. See README, \"For Google Play\".")
        }
        logger.warn("Release signing: $problem, so this release APK is signed with the debug key. It installs " +
            "(a test build for sideloading), but Google Play will reject it.")
    }
}

// Every resolved version, transitive ones included, is recorded in gradle.lockfile. To change a
// dependency: edit gradle/libs.versions.toml, then run the build with --write-locks (see README).
dependencyLocking { lockAllConfigurations() }
