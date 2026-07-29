plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
}

// Generator forked-JVM heap. Honors -PgeneratorHeap=… (CI lowers it on 16 GB runners).
// Default 10g matches local workstation use; CI sets 5g via the workflow.
val generatorHeap: String = (project.findProperty("generatorHeap") as String?)
    ?: System.getenv("SEFORIM_GENERATOR_HEAP")
    ?: "10g"


kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":dao"))

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmMain.dependencies {
            implementation(project(":generator-common"))
            implementation(libs.sqlDelight.driver.sqlite)
            implementation(libs.commons.compress)
            implementation(libs.zstd)
        }
    }
}

tasks.register<JavaExec>("generateSefariaSqlite") {
    group = "application"
    description = "Convert Sefaria export directly into a SQLite DB (one-step pipeline)."

    dependsOn("jvmJar")
    mainClass.set("io.github.kdroidfilter.seforimlibrary.sefariasqlite.GenerateSefariaSqliteKt")
    classpath = files(tasks.named("jvmJar")) + configurations.getByName("jvmRuntimeClasspath")

    val defaultDbPath = rootProject.layout.buildDirectory.file("seforim.db").get().asFile.absolutePath
    val dbPath = if (project.hasProperty("seforimDb")) {
        project.property("seforimDb") as String
    } else {
        defaultDbPath
    }
    val exportDir = if (project.hasProperty("exportDir")) {
        project.property("exportDir") as String
    } else {
        null
    }
    args = listOfNotNull(dbPath, exportDir)

    // Optional overrides (the Kotlin entrypoint also supports -D / env)
    if (project.hasProperty("persistDb")) {
        systemProperty("persistDb", project.property("persistDb") as String)
    }
    if (project.hasProperty("inMemoryDb")) {
        systemProperty("inMemoryDb", project.property("inMemoryDb") as String)
    }

    // Optional JVM tuning (similar to generator)
    jvmArgs = listOf(
        "-Xmx$generatorHeap",
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=200"
    )
}

// Incrementally add Sefaria books that are allowed by the current blacklists
// but are missing from an existing release database.
// Usage:
//   ./gradlew :sefariasqlite:appendMissingSefaria -PseforimDb=/path/to/seforim.db
tasks.register<JavaExec>("appendMissingSefaria") {
    group = "application"
    description = "Append currently-allowed Sefaria books missing from an existing database."

    dependsOn("jvmJar")
    mainClass.set("io.github.kdroidfilter.seforimlibrary.sefariasqlite.GenerateSefariaSqliteKt")
    classpath = files(tasks.named("jvmJar")) + configurations.getByName("jvmRuntimeClasspath")

    val dbPath = if (project.hasProperty("seforimDb")) {
        project.property("seforimDb") as String
    } else if (System.getenv("SEFORIM_DB") != null) {
        System.getenv("SEFORIM_DB")
    } else {
        rootProject.layout.buildDirectory.file("seforim.db").get().asFile.absolutePath
    }
    systemProperty("seforimDb", dbPath)
    systemProperty("appendExistingDb", "true")
    systemProperty("onlyMissingBooks", "true")
    systemProperty("inMemoryDb", "false")

    if (project.hasProperty("exportDir")) {
        systemProperty("exportDir", project.property("exportDir") as String)
    }
    if (project.hasProperty("buildStatePath")) {
        systemProperty("buildStatePath", project.property("buildStatePath") as String)
    }
    if (project.hasProperty("buildVersion")) {
        systemProperty("buildVersion", project.property("buildVersion") as String)
    }

    jvmArgs = listOf(
        "-Xmx$generatorHeap",
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=200"
    )
}

// Export the same currently-allowed, seed-missing Sefaria books without
// constructing or modifying a database. The result is an Otzaria-compatible ZIP.
// Usage:
//   ./gradlew :sefariasqlite:exportIncrementalSefariaOtzaria \
//     -PseedDb=/path/to/seforim.db -PotzariaOutputZip=/path/to/books.zip
tasks.register<JavaExec>("exportIncrementalSefariaOtzaria") {
    group = "application"
    description = "Export currently-allowed Sefaria books missing from a seed DB as an Otzaria ZIP."

    dependsOn("jvmJar")
    mainClass.set("io.github.kdroidfilter.seforimlibrary.sefariasqlite.ExportIncrementalSefariaOtzariaKt")
    classpath = files(tasks.named("jvmJar")) + configurations.getByName("jvmRuntimeClasspath")

    val seedDb = (project.findProperty("seedDb") as String?)
        ?: System.getenv("SEED_DB")
        ?: rootProject.layout.buildDirectory.file("seforim.db").get().asFile.absolutePath
    val outputDir = (project.findProperty("otzariaOutputDir") as String?)
        ?: rootProject.layout.buildDirectory.dir("incremental-sefaria-otzaria").get().asFile.absolutePath
    val outputZip = (project.findProperty("otzariaOutputZip") as String?)
        ?: rootProject.layout.buildDirectory.file("incremental-sefaria-otzaria.zip").get().asFile.absolutePath

    systemProperty("seedDb", seedDb)
    systemProperty("outputDir", outputDir)
    systemProperty("outputZip", outputZip)
    val reportPath = (project.findProperty("incrementalReport") as String?)
        ?: rootProject.layout.buildDirectory.file("incremental-sefaria-report.json").get().asFile.absolutePath
    systemProperty("reportPath", reportPath)
    if (project.hasProperty("exportDir")) {
        systemProperty("exportDir", project.property("exportDir") as String)
    }

    jvmArgs = listOf(
        "-Xmx$generatorHeap",
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=200"
    )
}

// Post-processing step to rename categories after all generation is complete
// Usage:
//   ./gradlew :sefariasqlite:renameCategories
//   ./gradlew :sefariasqlite:renameCategories -PseforimDb=/path/to/seforim.db
tasks.register<JavaExec>("renameCategories") {
    group = "application"
    description = "Rename 'פירושים מודרניים' categories to 'מחברי זמננו' after generation."

    dependsOn("jvmJar")
    mainClass.set("io.github.kdroidfilter.seforimlibrary.sefariasqlite.RenameCategoriesPostProcessKt")
    classpath = files(tasks.named("jvmJar")) + configurations.getByName("jvmRuntimeClasspath")

    // Pass DB path if provided
    if (project.hasProperty("seforimDb")) {
        systemProperty("seforimDb", project.property("seforimDb") as String)
    } else if (System.getenv("SEFORIM_DB") != null) {
        systemProperty("seforimDb", System.getenv("SEFORIM_DB"))
    } else {
        val defaultDbPath = rootProject.layout.buildDirectory.file("seforim.db").get().asFile.absolutePath
        systemProperty("seforimDb", defaultDbPath)
    }

    jvmArgs = listOf("-Xmx256m")
}
