plugins {
    kotlin("jvm")
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)

    implementation(project(":otzariasqlite"))
    implementation(project(":catalog"))
    implementation(project(":searchindex"))
}

compose.desktop {
    application {
        mainClass = "io.github.kdroidfilter.seforimlibrary.bookimporter.MainKt"

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            packageName = "SeforimBookImporter"
            packageVersion = "1.0.0"
            description = "GUI tool for appending seforim into an existing SeforimLibrary SQLite DB"
            vendor = "SeforimLibrary"
        }
    }
}

tasks.register("packagePortable") {
    group = "distribution"
    description = "Build a portable app distribution for the desktop importer (no installer)."
    dependsOn("createReleaseDistributable")
}
