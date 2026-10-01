package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.github.kdroidfilter.seforimlibrary.core.models.BookMetadata
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Exports the currently allowed Sefaria books that are absent from a seed DB
 * as an Otzaria-compatible ZIP. The seed is opened read-only and is never
 * copied or modified.
 */
fun main() = runBlocking {
    Logger.setMinSeverity(Severity.Info)
    val logger = Logger.withTag("SefariaOtzariaExport")

    val seedDb = Paths.get(
        System.getProperty("seedDb")
            ?: System.getenv("SEED_DB")
            ?: Paths.get("build", "seforim.db").toString()
    ).toAbsolutePath().normalize()
    require(Files.isRegularFile(seedDb)) { "Seed database not found at $seedDb" }

    val outputDir = Paths.get(
        System.getProperty("outputDir")
            ?: System.getenv("OTZARIA_OUTPUT_DIR")
            ?: Paths.get("build", "incremental-sefaria-otzaria").toString()
    ).toAbsolutePath().normalize()
    val outputZip = Paths.get(
        System.getProperty("outputZip")
            ?: System.getenv("OTZARIA_OUTPUT_ZIP")
            ?: Paths.get("build", "incremental-sefaria-otzaria.zip").toString()
    ).toAbsolutePath().normalize()
    val reportPath = Paths.get(
        System.getProperty("reportPath")
            ?: System.getenv("SEFARIA_INCREMENTAL_REPORT")
            ?: Paths.get("build", "incremental-sefaria-report.json").toString()
    ).toAbsolutePath().normalize()
    val mergedFilesList = System.getProperty("mergedFilesList")?.let(Paths::get)?.toAbsolutePath()?.normalize()
    val ignoreBlacklists = System.getProperty("ignoreBlacklists").toBoolean()
    val apiLinksPath = System.getProperty("apiLinksPath")?.let(Paths::get)?.toAbsolutePath()?.normalize()
    val reportSource = System.getProperty("reportSource") ?: "Sefaria bulk export"
    require(!outputZip.startsWith(outputDir)) {
        "outputZip must be outside outputDir so it is not included in itself"
    }

    val exportDir = System.getProperty("exportDir")
        ?: System.getenv("SEFARIA_EXPORT_DIR")
    val exportRoot = exportDir?.let(Paths::get) ?: SefariaExportFetcher.ensureLocalExport(logger)
    val dbRoot = findDatabaseExportRoot(exportRoot)
    val jsonDir = dbRoot.resolve("json")
    val schemaDir = dbRoot.resolve("schemas")

    val existingTitleKeys = loadSeedTitleKeys(seedDb, logger)
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        prettyPrint = true
        explicitNulls = false
    }
    val reader = SefariaBookPayloadReader(json, logger)
    val schemaLookup = reader.buildSchemaLookup(schemaDir)
    val selection = reader.selectMergedFiles(
        jsonDir = jsonDir,
        schemaDir = schemaDir,
        schemaLookup = schemaLookup,
        excludedTitleKeys = existingTitleKeys,
    )
    val mergedFiles = if (mergedFilesList != null) {
        require(Files.isRegularFile(mergedFilesList)) { "Merged-files manifest not found at $mergedFilesList" }
        Files.readAllLines(mergedFilesList, StandardCharsets.UTF_8)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { Paths.get(it) }
            .map { it.toAbsolutePath().normalize() }
            .onEach { require(Files.isRegularFile(it)) { "Merged JSON not found at $it" } }
            .toList()
    } else {
        selection.files
    }

    SefariaImageEmbedder.prefetch(mergedFiles, logger = logger)
    val candidates = reader.readBooksInParallel(mergedFiles, schemaDir, schemaLookup)
    // An explicit merged-files manifest (used by the Copyright API export)
    // bypasses selectMergedFiles' schema-level seed filter. Filter the parsed
    // payloads as well, using their authoritative Hebrew/English titles, so the
    // ZIP and the missing-books importer agree on what is actually new.
    val seedFilteredCandidates = filterPayloadsAlreadyInSeed(candidates, existingTitleKeys)
    val skippedParsedAlreadyInSeed = candidates.size - seedFilteredCandidates.size
    if (skippedParsedAlreadyInSeed > 0) {
        logger.i { "Skipped $skippedParsedAlreadyInSeed parsed books already present in the seed DB" }
    }
    val filtered = filterBlacklistedPayloads(
        payloads = seedFilteredCandidates,
        blacklists = if (ignoreBlacklists) SefariaBlacklists.Empty else
            loadSefariaBlacklists(SefariaOtzariaExporter::class.java.classLoader, logger),
    )
    if (filtered.skippedTotal > 0) {
        logger.i {
            "Skipped ${filtered.skippedTotal} candidate books by the current blacklists " +
                "(books=${filtered.skippedByBook}, authors=${filtered.skippedByAuthor})"
        }
    }
    val priorityEntries = loadPriorityList(SefariaOtzariaExporter::class.java.classLoader, logger)
    val payloads = applyPriorityOrdering(filtered.payloads, priorityEntries).first

    val result = SefariaOtzariaExporter(json, logger).export(
        payloads = payloads,
        linksDir = dbRoot.resolve("links"),
        outputRoot = outputDir,
        outputZip = outputZip,
        apiLinksPath = apiLinksPath,
        seedDb = seedDb,
    )
    val report = IncrementalSefariaExportReport(
        source = reportSource,
        schemasInSource = selection.schemaCount,
        schemasWithoutMerged = selection.schemasWithoutMergedCount,
        schemasWithoutMergedExamples = selection.schemasWithoutMergedExamples,
        schemasWithoutMergedTitles = selection.schemasWithoutMergedTitles,
        mergedBooksInSource = selection.discoveredCount,
        skippedAlreadyInSeed = if (mergedFilesList == null) {
            selection.excludedExistingTitleCount + skippedParsedAlreadyInSeed
        } else {
            skippedParsedAlreadyInSeed
        },
        selectedForParsing = mergedFiles.size,
        parsedSuccessfully = candidates.size,
        skippedParseFailure = mergedFiles.size - candidates.size,
        skippedByBlacklist = filtered.skippedTotal,
        skippedByBookBlacklist = filtered.skippedByBook,
        skippedByAuthorBlacklist = filtered.skippedByAuthor,
        exportedBooks = result.bookCount,
        exportedLinks = result.linkCount,
        unresolvedExternalLinks = result.unresolvedExternalLinks,
        bookBlacklistExamples = filtered.skippedBookExamples,
        authorBlacklistExamples = filtered.skippedAuthorExamples,
    )
    Files.createDirectories(reportPath.parent)
    reportPath.writeText(json.encodeToString(report))
    logger.i {
        "Incremental Otzaria ZIP ready at ${result.zipPath}: " +
            "books=${result.bookCount}, links=${result.linkCount}; report=$reportPath"
    }
}

@Serializable
internal data class IncrementalSefariaExportReport(
    val source: String,
    val schemasInSource: Int,
    val schemasWithoutMerged: Int,
    val schemasWithoutMergedExamples: List<String>,
    val schemasWithoutMergedTitles: List<String>,
    val mergedBooksInSource: Int,
    val skippedAlreadyInSeed: Int,
    val selectedForParsing: Int,
    val parsedSuccessfully: Int,
    val skippedParseFailure: Int,
    val skippedByBlacklist: Int,
    val skippedByBookBlacklist: Int,
    val skippedByAuthorBlacklist: Int,
    val exportedBooks: Int,
    val exportedLinks: Int,
    val unresolvedExternalLinks: Int,
    val bookBlacklistExamples: List<String>,
    val authorBlacklistExamples: List<String>,
)

internal class SefariaOtzariaExporter(
    private val json: Json = Json { prettyPrint = true; explicitNulls = false },
    private val logger: Logger = Logger.withTag("SefariaOtzariaExporter"),
) {
    fun export(
        payloads: List<BookPayload>,
        linksDir: Path,
        outputRoot: Path,
        outputZip: Path,
        apiLinksPath: Path? = null,
        seedDb: Path? = null,
    ): OtzariaExportResult {
        recreateDirectory(outputRoot)
        Files.createDirectories(outputZip.parent)
        Files.deleteIfExists(outputZip)

        val refs = ArrayList<RefEntry>()
        val manifest = linkedMapOf<String, ManifestEntry>()
        val metadata = linkedMapOf<String, BookMetadata>()

        payloads.forEach { payload ->
            val relativePath = bookRelativePath(payload)
            val outputFile = outputRoot.resolve(relativePath)
            Files.createDirectories(outputFile.parent)
            Files.write(outputFile, payload.lines, StandardCharsets.UTF_8)

            val portablePath = relativePath.invariantSeparatorsPathString
            payload.refEntries.forEach { ref -> refs += ref.copy(path = portablePath) }
            manifest["Sefaria/$portablePath"] = ManifestEntry(sha256(outputFile))
            metadata[payload.heTitle] = BookMetadata(
                title = payload.heTitle,
                author = payload.authors.firstOrNull(),
                heShortDesc = payload.description,
                pubDate = payload.pubDates.firstOrNull()?.date,
            )
        }

        outputRoot.resolve("files_manifest.json").writeText(json.encodeToString(manifest))
        outputRoot.resolve("metadata.json").writeText(json.encodeToString(metadata))
        val bulkLinkCount = writeLinks(linksDir, refs, outputRoot)
        val apiLinkResult = if (apiLinksPath != null && seedDb != null) {
            appendCopyrightApiLinks(json, apiLinksPath, seedDb, refs, outputRoot, logger)
        } else {
            CopyrightApiLinkResult(0, 0)
        }
        zipDirectory(outputRoot, outputZip)

        return OtzariaExportResult(
            zipPath = outputZip,
            bookCount = payloads.size,
            linkCount = bulkLinkCount + apiLinkResult.written,
            unresolvedExternalLinks = apiLinkResult.unresolved,
        )
    }

    private fun writeLinks(linksDir: Path, refs: List<RefEntry>, outputRoot: Path): Int {
        if (refs.isEmpty() || !linksDir.isDirectory()) {
            if (!linksDir.isDirectory()) logger.w { "Links directory not found at $linksDir" }
            return 0
        }

        val refsByCitation = refs.groupBy { canonicalCitation(it.ref) }
        val refsByBase = buildMap<String, RefEntry> {
            refs.forEach { ref ->
                val key = canonicalBase(ref.ref)
                val existing = this[key]
                if (existing == null || ref.lineIndex < existing.lineIndex) put(key, ref)
            }
        }
        val linksByBook = linkedMapOf<String, LinkedHashSet<OtzariaLink>>()

        Files.list(linksDir).use { files ->
            files.filter { it.extension.equals("csv", ignoreCase = true) }
                .sorted()
                .forEach { file ->
                    Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                        val rows = reader.lineSequence().iterator()
                        if (!rows.hasNext()) return@use
                        val headers = parseCsvLine(rows.next()).map(::normalizeCitation)
                        val citation1Index = headers.indexOf("Citation 1")
                        val citation2Index = headers.indexOf("Citation 2")
                        val connectionIndex = headers.indexOf("Conection Type")
                        if (citation1Index < 0 || citation2Index < 0 || connectionIndex < 0) return@use

                        while (rows.hasNext()) {
                            val row = parseCsvLine(rows.next())
                            val citation1 = normalizeCitation(row.getOrNull(citation1Index).orEmpty())
                            val citation2 = normalizeCitation(row.getOrNull(citation2Index).orEmpty())
                            if (citation1.isBlank() || citation2.isBlank()) continue

                            val fromRefs = resolveRefs(citation1, refsByCitation, refsByBase)
                            val toRefs = resolveRefs(citation2, refsByCitation, refsByBase)
                            if (fromRefs.isEmpty() || toRefs.isEmpty()) continue
                            val connectionType = row.getOrNull(connectionIndex)?.trim().orEmpty()

                            fromRefs.forEach { from ->
                                toRefs.forEach { to ->
                                    addLink(linksByBook, from, to, connectionType)
                                    addLink(linksByBook, to, from, connectionType)
                                }
                            }
                        }
                    }
                }
        }

        if (linksByBook.isEmpty()) return 0
        val linksRoot = Files.createDirectories(outputRoot.resolve("links"))
        linksByBook.forEach { (bookTitle, links) ->
            linksRoot.resolve("${sanitizeOtzariaFileName(bookTitle)}_links.json")
                .writeText(json.encodeToString(links.toList()))
        }
        return linksByBook.values.sumOf { it.size }
    }

    private fun addLink(
        linksByBook: MutableMap<String, LinkedHashSet<OtzariaLink>>,
        from: RefEntry,
        to: RefEntry,
        connectionType: String,
    ) {
        val sourceTitle = Paths.get(from.path).nameWithoutExtension
        linksByBook.getOrPut(sourceTitle) { linkedSetOf() } += OtzariaLink(
            lineIndex1 = from.lineIndex,
            heRef2 = to.heRef,
            path2 = to.path,
            lineIndex2 = to.lineIndex,
            connectionType = connectionType,
        )
    }

    private fun bookRelativePath(payload: BookPayload): Path {
        var path = Paths.get("אוצריא")
        payload.categoriesHe.map(::sanitizeFolder).filter(String::isNotBlank).forEach { path = path.resolve(it) }
        return path.resolve("${sanitizeOtzariaFileName(payload.heTitle)}.txt")
    }

    private fun zipDirectory(sourceRoot: Path, outputZip: Path) {
        ZipOutputStream(Files.newOutputStream(outputZip), StandardCharsets.UTF_8).use { zip ->
            Files.walk(sourceRoot).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { file ->
                    val entryName = sourceRoot.relativize(file).invariantSeparatorsPathString
                    zip.putNextEntry(ZipEntry(entryName))
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun recreateDirectory(path: Path) {
        if (Files.exists(path)) {
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
        Files.createDirectories(path)
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal data class OtzariaExportResult(
    val zipPath: Path,
    val bookCount: Int,
    val linkCount: Int,
    val unresolvedExternalLinks: Int,
)

@Serializable
private data class ManifestEntry(val hash: String)

@Serializable
private data class OtzariaLink(
    @SerialName("line_index_1") val lineIndex1: Int,
    @SerialName("heRef_2") val heRef2: String,
    @SerialName("path_2") val path2: String,
    @SerialName("line_index_2") val lineIndex2: Int,
    @SerialName("Conection Type") val connectionType: String,
)

internal fun filterPayloadsAlreadyInSeed(
    payloads: List<BookPayload>,
    existingTitleKeys: Set<String>,
): List<BookPayload> {
    if (existingTitleKeys.isEmpty()) return payloads
    return payloads.filterNot { payload ->
        sequenceOf(payload.heTitle, payload.enTitle)
            .mapNotNull(::normalizeTitleKey)
            .any(existingTitleKeys::contains)
    }
}

internal fun sanitizeOtzariaFileName(name: String): String = name
    .replace("\"", "")
    .replace("'", "")
    .replace("״", "")
    .replace(Regex("[\\\\/:*?\"<>|]"), " ")
    .trim()
    .ifEmpty { "book" }

private fun loadSeedTitleKeys(seedDb: Path, logger: Logger): Set<String> {
    Class.forName("org.sqlite.JDBC")
    val url = "jdbc:sqlite:${seedDb.toUri()}?mode=ro"
    return DriverManager.getConnection(url).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT title FROM book").use { rows ->
                buildSet {
                    while (rows.next()) normalizeTitleKey(rows.getString(1))?.let(::add)
                }
            }
        }
    }.also { logger.i { "Loaded ${it.size} existing book titles from the read-only seed DB" } }
}
