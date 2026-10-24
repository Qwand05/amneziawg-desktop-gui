/*
 * Platform independent AmneziaWG core: configuration parsing and cryptography.
 *
 * This module is shared between the Android application (`:ui`, `:tunnel`) and
 * the Linux desktop GUI (`:desktop`). It must stay free of Android APIs.
 */
plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    api(libs.androidx.annotation)
    compileOnly(libs.jsr305)
    testImplementation(libs.junit)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:unchecked")
    options.isDeprecation = true
}
