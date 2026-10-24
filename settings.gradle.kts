import com.android.build.api.dsl.SettingsExtension

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.settings") version "8.13.0"
}

rootProject.name = "amneziawg-android"

include(":core")
include(":tunnel")
include(":ui")
include(":desktop")

configure<SettingsExtension> {
    buildToolsVersion = "36.0.0"
    compileSdk = 36
    minSdk = 24
    ndkVersion = "26.1.10909125"
}
