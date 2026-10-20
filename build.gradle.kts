buildscript {
    // Lock the build's own plugins (AGP, Kotlin) as well as the app's dependencies; see README.
    configurations.classpath { resolutionStrategy.activateDependencyLocking() }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
