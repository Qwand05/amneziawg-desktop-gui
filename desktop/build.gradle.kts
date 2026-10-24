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
            packageVersion = "3.1.4"
            description = "Linux GUI for AmneziaWG"
            vendor = "AmneziaWG"

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
