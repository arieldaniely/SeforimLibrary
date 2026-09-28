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
            providers.gradleProperty("shardCount").orElse("1").get())
    })
    maxHeapSize = "4g"
}

group = "io.github.kdroidfilter.seforimlibrary"

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
            // Dense semantic search: ONNX Runtime (query embedding) + HuggingFace tokenizer.
            // Stock Maven `onnxruntime` is CPU-only on desktop JVM (no DirectML/CoreML/XNNPACK
            // native — those need a custom build; CUDA needs the separate onnxruntime_gpu).
            implementation(libs.onnxruntime)
            implementation(libs.djl.huggingface.tokenizers)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
