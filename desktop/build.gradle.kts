/*
 * Linux (Ubuntu) desktop GUI for AmneziaWG, built with Compose Multiplatform.
 */
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Version of the desktop GUI. It is independent of the Android app and of
 * AmneziaWG itself, and is used for the .deb package and the Settings screen.
 */
val appVersion = "1.0.2"
version = appVersion

// Makes the version available at runtime as a classpath resource.
val generateVersionResource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/version")
    inputs.property("version", appVersion)
    outputs.dir(outputDir)
    doLast {
        outputDir.get().file("amnezia-awg-version.properties").asFile
            .writeText("version=$appVersion\n")
    }
}

sourceSets {
    main {
        resources.srcDir(generateVersionResource)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.zxing.core)
    // Native KDE/freedesktop tray icon (StatusNotifierItem) over D-Bus.
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.unixsocket)

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "org.amnezia.awg.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "amnezia-awg"
            packageVersion = appVersion
            description = "Linux GUI for AmneziaWG"
            vendor = "AmneziaWG"

            // The packaged runtime is trimmed to these modules (plus the ones
            // the plugin always adds). Re-check with `./gradlew
            // :desktop:suggestRuntimeModules` after adding dependencies:
            //  - jdk.security.auth: dbus-java authenticates to the session bus
            //    (tray icon, single instance) with UnixSystem.
            //  - jdk.unsupported: sun.misc.Unsafe, used by libraries at runtime.
            //  - java.instrument: referenced by a dependency; cheap to include.
            modules("java.instrument", "jdk.security.auth", "jdk.unsupported")

            linux {
                // The GUI talks to awg-quick through a polkit helper, so it
                // does not need to be installed setuid.
                menuGroup = "Network"
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}

tasks.test {
    // Never let tests touch the real ~/.config/amnezia-awg.
    environment("XDG_CONFIG_HOME", layout.buildDirectory.dir("test-config").get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}
