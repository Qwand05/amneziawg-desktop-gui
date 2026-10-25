/*
 * Linux (Ubuntu) desktop GUI for AmneziaWG, built with Compose Multiplatform.
 */
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Version of the desktop GUI. It is independent of the Android app and of
 * AmneziaWG itself, and is used for the .deb package and the Settings screen.
 */
val appVersion = "1.2.2"
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
        // Lets Main.kt set the X11 window class to "AmneziaWG" (see setWindowClass),
        // which the launcher's StartupWMClass refers to.
        jvmArgs += listOf("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")

        nativeDistributions {
            // Plus a portable tar.gz and a Windows folder; see the tasks at the end.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            // Application name: shown in the launcher, names /opt/amnezia-awg/bin/AmneziaWG.
            packageName = "AmneziaWG"
            packageVersion = appVersion
            description = "Manage AmneziaWG VPN tunnels"
            vendor = "AmneziaWG"

            // The packaged runtime is trimmed to these modules (plus the ones
            // the plugin always adds). Re-check with `./gradlew
            // :desktop:suggestRuntimeModules` after adding dependencies:
            //  - jdk.security.auth: dbus-java authenticates to the session bus
            //    (tray icon, single instance) with UnixSystem.
            //  - jdk.unsupported: sun.misc.Unsafe, used by libraries at runtime.
            //  - java.instrument: referenced by a dependency; cheap to include.
            //  - jdk.localedata: number formats of the translated languages
            //    (e.g. "1,50" in Russian); not found by suggestRuntimeModules.
            modules("java.instrument", "jdk.localedata", "jdk.security.auth", "jdk.unsupported")

            linux {
                // The GUI talks to awg-quick through a polkit helper, so it
                // does not need to be installed setuid.
                menuGroup = "Network"
                iconFile.set(project.file("src/main/resources/icon.png"))
                // Debian/RPM package and /opt directory name. Not "amneziawg": that is
                // the official kernel module package.
                packageName = "amnezia-awg"
                rpmLicenseType = "Apache-2.0"
            }
        }
    }
}

// The launcher entry (name, translated generic name and keywords, window
// class) replaces jpackage's generated one. jpackage would take it from a
// --resource-dir, but the Compose plugin always passes its own (empty) one
// last, so the finished .deb is patched instead: unpack, swap the .desktop
// file (keeping jpackage's Exec and Icon paths), fix md5sums, repack.
val launcherEntry = layout.projectDirectory.file("packaging/linux/AmneziaWG.desktop")

fun replaceLauncherEntry(deb: File, template: File, workDir: File) {
    fun run(vararg command: String) {
        val process = ProcessBuilder(*command).inheritIO().start()
        check(process.waitFor() == 0) { "${command.joinToString(" ")} failed" }
    }
    val root = File(workDir, "deb-root").apply { deleteRecursively() }
    run("dpkg-deb", "-R", deb.path, root.path)
    val generated = root.walk().single { it.isFile && it.extension == "desktop" && "/opt/" in it.path }
    fun field(name: String) = generated.readLines().first { it.startsWith("$name=") }.substringAfter('=')
    generated.writeText(
        template.readText()
            .replace("APPLICATION_LAUNCHER", field("Exec"))
            .replace("APPLICATION_ICON", field("Icon"))
    )
    val md5sums = File(root, "DEBIAN/md5sums")
    if (md5sums.isFile) {
        val path = generated.relativeTo(root).path
        val digest = MessageDigest.getInstance("MD5").digest(generated.readBytes())
            .joinToString("") { "%02x".format(it) }
        md5sums.writeText(md5sums.readLines().joinToString("\n", postfix = "\n") { line ->
            if (line.endsWith("  $path")) "$digest  $path" else line
        })
    }
    run("dpkg-deb", "--root-owner-group", "--build", root.path, deb.path)
    root.deleteRecursively()
}

tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>()
    .matching { it.name.endsWith("Deb") }
    .configureEach {
        inputs.file(launcherEntry)
        doLast {
            destinationDir.get().asFile.walk().filter { it.extension == "deb" }.forEach { deb ->
                replaceLauncherEntry(deb, launcherEntry.asFile, temporaryDir)
            }
        }
    }

tasks.test {
    // Never let tests touch the real ~/.config/amnezia-awg or the real log.
    environment("XDG_CONFIG_HOME", layout.buildDirectory.dir("test-config").get().asFile.absolutePath)
    environment("XDG_STATE_HOME", layout.buildDirectory.dir("test-state").get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}

// ---------------------------------------------------------------------------
// Portable builds, next to the .deb and .rpm under build/compose/binaries/main/.
// ---------------------------------------------------------------------------

val binariesDir = layout.buildDirectory.dir("compose/binaries/main")

/**
 * Linux: the jpackage app image (bin/AmneziaWG, lib/, runtime/) as a
 * tar.gz that runs from wherever it is extracted. It still needs
 * amneziawg-tools and polkit on the system, like the packages.
 */
val packageTarGz by tasks.registering(Tar::class) {
    description = "Builds the portable Linux tar.gz."
    group = "compose desktop"
    val distributable = tasks.named<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>("createDistributable")
    dependsOn(distributable)
    from(distributable.map { it.destinationDir.get().asFile.resolve("AmneziaWG") })
    into("amnezia-awg-$appVersion")
    archiveFileName.set("amnezia-awg-$appVersion-linux-x64.tar.gz")
    destinationDirectory.set(binariesDir.map { it.dir("tar") })
    compression = Compression.GZIP
}

/*
 * Windows: jpackage cannot cross-build, so the portable folder is assembled
 * by hand: the application jars with Compose's Windows natives and a
 * launcher script that uses the Java 21 installed on the machine, or a
 * runtime/ folder bundled from a JRE zip given as -PwindowsJre=<zip>
 * (e.g. a Temurin 21 Windows x64 JRE). The GUI runs on Windows, but the
 * backend is Linux-only (awg-quick, polkit), so tunnels cannot be switched
 * there yet.
 */
val windowsJreZip: File? = (findProperty("windowsJre") as String?)?.let { file(it) }

// The runtime classpath with the Windows natives instead of the Linux ones.
val windowsRuntimeClasspath: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.implementation.get())
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-linux-x64")
    val reference = configurations.runtimeClasspath.get().attributes
    attributes {
        @Suppress("UNCHECKED_CAST")
        reference.keySet().forEach { key -> attribute(key as Attribute<Any>, reference.getAttribute(key)!!) }
    }
}

dependencies {
    windowsRuntimeClasspath(compose.desktop.windows_x64)
}

val windowsPortableDir = binariesDir.map { it.dir("windows/amnezia-awg-$appVersion-windows-x64") }

val assembleWindowsPortable by tasks.registering(Sync::class) {
    description = "Assembles the portable Windows folder (GUI only); -PwindowsJre=<zip> bundles a runtime."
    group = "compose desktop"
    dependsOn(tasks.jar)
    into(windowsPortableDir)
    into("lib") {
        from(tasks.jar)
        from(windowsRuntimeClasspath)
    }
    if (windowsJreZip != null) {
        require(windowsJreZip.isFile) { "windowsJre: $windowsJreZip is not a file" }
        into("runtime") {
            // A JRE zip holds a single jdk-21.x.y+z-jre/ directory.
            from(zipTree(windowsJreZip)) {
                eachFile { path = path.substringAfter('/') }
                includeEmptyDirs = false
            }
        }
    }
    from(layout.projectDirectory.dir("packaging/windows"))
    from(layout.projectDirectory.file("src/main/resources/icon.png"))
}

val packageWindowsPortable by tasks.registering(Zip::class) {
    description = "Zips the portable Windows folder."
    group = "compose desktop"
    dependsOn(assembleWindowsPortable)
    from(windowsPortableDir)
    into("amnezia-awg-$appVersion-windows-x64")
    archiveFileName.set("amnezia-awg-$appVersion-windows-x64.zip")
    destinationDirectory.set(binariesDir.map { it.dir("windows") })
}

tasks.register("packageAll") {
    description = "Builds the .deb, the .rpm, the portable Linux tar.gz and the portable Windows zip."
    group = "compose desktop"
    dependsOn("packageDeb", "packageRpm", packageTarGz, packageWindowsPortable)
}
