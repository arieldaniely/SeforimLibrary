import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

group = "io.github.kdroidfilter.seforimlibrary"
version = "1.0.0"

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":otzariasqlite"))
            implementation(project(":catalog"))
            implementation(project(":searchindex"))
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.kdroidfilter.seforimlibrary.importer.MainKt"

        nativeDistributions {
            modules("java.sql", "java.desktop", "jdk.unsupported", "jdk.incubator.vector")
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "seforim-importer"
            packageVersion = version.toString()
            description = "Import local Jewish texts and links into SeforimLibrary"
            vendor = "SeforimLibrary"
            jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")
        }
    }
}

val portableOsClassifier = when {
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows-x64"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
    else -> "linux-x64"
}

tasks.register<Zip>("packagePortableZip") {
    group = "distribution"
    description = "Build a portable application folder with its bundled runtime and package it as a ZIP."
    dependsOn("createDistributable")

    from(layout.buildDirectory.dir("compose/binaries/main/app/seforim-importer")) {
        into("seforim-importer")
    }
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("seforim-importer-$portableOsClassifier-${project.version}.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
