// Google's groups come only from Google's repository, never from Maven Central or the Gradle Plugin
// Portal, so a look-alike published there can't be picked up instead (security review 2026-10-11,
// SC3). Everything else resolves as usual. The checksums in gradle/verification-metadata.xml still
// apply to whatever is resolved.
fun RepositoryHandler.googleOnly() = exclusiveContent {
    forRepository { google() }
    filter {
        includeGroupByRegex("androidx(\\..*)?")
        includeGroupByRegex("com\\.android(\\..*)?")
        includeGroupByRegex("com\\.google\\.android(\\..*)?")
        includeGroup("com.google.firebase")
    }
}

pluginManagement {
    repositories {
        // The same filter as googleOnly(): pluginManagement is evaluated on its own and can't call it.
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android(\\..*)?")
                includeGroup("com.google.firebase")
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        googleOnly()
        google()
        mavenCentral()
    }
}

rootProject.name = "PhoneReceiver"
include(":app")
