import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
}

tasks.register<JavaExec>("buildSemanticIndex") {
    group = "application"
    description = "Build one optional Round 2 semantic Lucene shard"
    dependsOn("jvmJar")
    mainClass.set("io.github.kdroidfilter.seforimlibrary.search.BuildSemanticIndexKt")
    classpath = files(tasks.named("jvmJar")) + configurations.getByName("jvmRuntimeClasspath")
    val db = providers.gradleProperty("seforimDb").orElse(providers.environmentVariable("SEFORIM_DB"))
    val model = providers.gradleProperty("semanticModelDir")
    val output = providers.gradleProperty("semanticIndexDir")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(db.get(), model.get(), output.get(),
            providers.gradleProperty("shardIndex").orElse("0").get(),
            providers.gradleProperty("shardCount").orElse("1").get(),
            providers.gradleProperty("semanticWorkers").orElse("1").get())
    })
    providers.gradleProperty("semanticThreads").orNull?.let { systemProperty("seforimEmbedThreads", it) }
    maxHeapSize = "4g"
}

tasks.register<JavaExec>("buildSemanticIndexFromVectors") {
    group = "application"
    description = "Build a unified semantic Lucene index from GPU-generated vector files"
    dependsOn("jvmJar")
    mainClass.set("io.github.kdroidfilter.seforimlibrary.search.BuildSemanticIndexFromVectorsKt")
    classpath = files(tasks.named("jvmJar")) + configurations.getByName("jvmRuntimeClasspath")
    workingDir = layout.projectDirectory.asFile
    val db = providers.gradleProperty("seforimDb")
    val model = providers.gradleProperty("semanticModelDir")
    val vectors = providers.gradleProperty("semanticVectors")
    val output = providers.gradleProperty("semanticIndexDir")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(db.get(), model.get(), vectors.get(), output.get(),
            providers.gradleProperty("shardIndex").orElse("0").get(),
            providers.gradleProperty("shardCount").orElse("1").get())
    })
    systemProperty("semanticForceMerge", providers.gradleProperty("semanticForceMerge").orElse("true").get())
    maxHeapSize = providers.gradleProperty("semanticHeap").orElse("4g").get()
}

group = "io.github.kdroidfilter.seforimlibrary"

tasks.register<JavaExec>("optimizeSemanticIndex") {
    dependsOn("jvmJar")
    mainClass.set("io.github.kdroidfilter.seforimlibrary.search.OptimizeSemanticIndexKt")
    classpath = files(tasks.named("jvmJar")) + configurations.getByName("jvmRuntimeClasspath")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(providers.gradleProperty("seforimDb").get(), providers.gradleProperty("semanticModelDir").get(),
            providers.gradleProperty("semanticIndexDir").get())
    })
    maxHeapSize = providers.gradleProperty("semanticHeap").orElse("6g").get()
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())

    jvm()

    sourceSets {
        jvmMain.dependencies {
            api(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.lucene.core)
            implementation(libs.lucene.analysis.common)
            implementation(libs.sqlDelight.driver.sqlite)
            implementation(libs.kermit)
            implementation(libs.jsoup)
            implementation(libs.onnxruntime)
            implementation(libs.djl.huggingface.tokenizers)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
