package io.github.kdroidfilter.seforimlibrary.importer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

data class ImportConfig(
    val database: Path,
    val booksDirectory: Path,
    val linksDirectory: Path,
    val maxHeapGb: Int = 8,
)

data class PipelineStage(
    val title: String,
    val mainClass: String,
    val arguments: List<String> = emptyList(),
    val properties: Map<String, String> = emptyMap(),
)

class ImportPipeline(
    private val javaExecutable: Path = defaultJavaExecutable(),
    private val classpath: String = System.getProperty("java.class.path"),
    private val applicationExecutable: Path? = detectPackagedApplication(),
) {
    @Volatile private var runningProcess: Process? = null

    fun validate(config: ImportConfig): Path {
        require(config.maxHeapGb in 2..64) { "Maximum memory must be between 2 and 64 GB" }
        require(config.booksDirectory.isDirectory()) { "Books directory does not exist: ${config.booksDirectory}" }
        require(config.linksDirectory.isDirectory()) { "Links directory does not exist: ${config.linksDirectory}" }
        val sourceRoot = sourceRootFor(config.booksDirectory)
        val libraryDirectory = libraryDirectoryFor(config.booksDirectory)
        require(hasFileWithExtension(libraryDirectory, "txt", true)) { "No .txt books were found under $libraryDirectory" }
        require(hasFileWithExtension(config.linksDirectory, "json", false)) {
            "No .json link files were found directly under ${config.linksDirectory}"
        }
        val db = config.database.toAbsolutePath().normalize()
        require(!Files.exists(db) || db.isRegularFile()) { "Database path is not a file: $db" }
        db.parent?.let(Files::createDirectories)
        if (applicationExecutable == null) {
        require(classpath.isNotBlank()) { "Application classpath is empty" }
        require(javaExecutable.isRegularFile()) { "Java runtime was not found at $javaExecutable" }
        }
        return sourceRoot
    }

    fun stages(config: ImportConfig, sourceRoot: Path = sourceRootFor(config.booksDirectory)): List<PipelineStage> {
        val db = config.database.toAbsolutePath().normalize().toString()
        val common = mapOf("seforimDb" to db, "persistDb" to db, "inMemoryDb" to "false")
        return listOf(
            PipelineStage(
                title = "\u05d9\u05d9\u05d1\u05d5\u05d0 \u05e1\u05e4\u05e8\u05d9\u05dd",
                mainClass = "io.github.kdroidfilter.seforimlibrary.otzariasqlite.GenerateLinesKt",
                arguments = listOf(db, sourceRoot.toString()),
                properties = common + mapOf(
                    "appendExistingDb" to "true",
                    "onlyMissingBooks" to "true",
                    "skipAcronyms" to "true",
                    "booksDir" to libraryDirectoryFor(config.booksDirectory).toString(),
                ),
            ),
            PipelineStage(
                title = "\u05d9\u05d9\u05d1\u05d5\u05d0 \u05e7\u05d9\u05e9\u05d5\u05e8\u05d9\u05dd",
                mainClass = "io.github.kdroidfilter.seforimlibrary.otzariasqlite.GenerateLinksKt",
                arguments = listOf(db, sourceRoot.toString()),
                properties = common + ("linksDir" to config.linksDirectory.toAbsolutePath().normalize().toString()),
            ),
            PipelineStage(
                title = "\u05d1\u05e0\u05d9\u05d9\u05ea \u05e7\u05d8\u05dc\u05d5\u05d2",
                mainClass = "io.github.kdroidfilter.seforimlibrary.catalog.BuildCatalogKt",
                arguments = listOf(db),
                properties = mapOf("seforimDb" to db),
            ),
            PipelineStage(
                title = "\u05d1\u05e0\u05d9\u05d9\u05ea \u05d0\u05d9\u05e0\u05d3\u05e7\u05e1 \u05d7\u05d9\u05e4\u05d5\u05e9",
                mainClass = "io.github.kdroidfilter.seforimlibrary.searchindex.BuildLuceneIndexKt",
                properties = mapOf("seforimDb" to db, "inMemoryDb" to "false"),
            ),
        )
    }

    fun run(config: ImportConfig, onStage: (Int, Int, PipelineStage) -> Unit, onLog: (String) -> Unit) {
        val stages = stages(config, validate(config))
        stages.forEachIndexed { index, stage ->
            onStage(index, stages.size, stage)
            onLog("\n=== ${stage.title} (${index + 1}/${stages.size}) ===")
            runStage(stage, config.maxHeapGb, onLog)
        }
    }

    fun cancel() {
        runningProcess?.destroy()
        if (runningProcess?.isAlive == true) runningProcess?.destroyForcibly()
    }

    private fun runStage(stage: PipelineStage, maxHeapGb: Int, onLog: (String) -> Unit) {
        val command = if (applicationExecutable != null) {
            buildList {
                add(applicationExecutable.toString())
                add("--worker")
                add(stage.mainClass)
                add(stage.properties.size.toString())
                stage.properties.forEach { (key, value) -> add("$key=$value") }
                addAll(stage.arguments)
            }
        } else {
            buildList {
                add(javaExecutable.toString())
                add("-Xmx${maxHeapGb}g")
                add("--enable-native-access=ALL-UNNAMED")
                add("--add-modules=jdk.incubator.vector")
                stage.properties.forEach { (key, value) -> add("-D$key=$value") }
                add("-cp")
                add(classpath)
                add(stage.mainClass)
                addAll(stage.arguments)
            }
        }
        onLog("Starting ${stage.title}...")
        val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
        if (applicationExecutable != null) {
            processBuilder.environment()["JAVA_TOOL_OPTIONS"] = "-Xmx${maxHeapGb}g"
        }
        val process = processBuilder.start()
        runningProcess = process
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLog) }
            val exitCode = process.waitFor()
            check(exitCode == 0) { "${stage.title} failed (exit code $exitCode)" }
        } finally {
            runningProcess = null
        }
    }

    companion object {
        private const val OTZARIA_DIRECTORY = "\u05d0\u05d5\u05e6\u05e8\u05d9\u05d0"

        fun sourceRootFor(selectedBooksDirectory: Path): Path {
            val selected = selectedBooksDirectory.toAbsolutePath().normalize()
            return when {
                selected.fileName?.toString() == OTZARIA_DIRECTORY ->
                    requireNotNull(selected.parent) { "The books directory must have a parent directory" }
                Files.isDirectory(selected.resolve(OTZARIA_DIRECTORY)) -> selected
                else -> requireNotNull(selected.parent) { "The books directory must have a parent directory" }
            }
        }

        fun libraryDirectoryFor(selectedBooksDirectory: Path): Path {
            val selected = selectedBooksDirectory.toAbsolutePath().normalize()
            return if (Files.isDirectory(selected.resolve(OTZARIA_DIRECTORY))) {
                selected.resolve(OTZARIA_DIRECTORY)
            } else {
                selected
            }
        }


        private fun defaultJavaExecutable(): Path {
            val executable = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
            return Paths.get(System.getProperty("java.home"), "bin", executable)
        }

        private fun detectPackagedApplication(): Path? {
            val command = ProcessHandle.current().info().command().orElse(null) ?: return null
            val path = runCatching { Paths.get(command).toAbsolutePath().normalize() }.getOrNull() ?: return null
            val name = path.fileName?.toString()?.lowercase() ?: return null
            if (name == "java" || name == "java.exe" || name == "javaw.exe") return null
            return path.takeIf {
                Files.isRegularFile(it) &&
                    (name.endsWith(".exe") || !System.getProperty("os.name").startsWith("Windows", true))
            }
        }

        private fun hasFileWithExtension(directory: Path, extension: String, recursive: Boolean): Boolean {
            if (!Files.isDirectory(directory)) return false
            val stream = if (recursive) Files.walk(directory) else Files.list(directory)
            return stream.use { paths ->
                paths.anyMatch { Files.isRegularFile(it) && it.extension.equals(extension, ignoreCase = true) }
            }
        }
    }
}
