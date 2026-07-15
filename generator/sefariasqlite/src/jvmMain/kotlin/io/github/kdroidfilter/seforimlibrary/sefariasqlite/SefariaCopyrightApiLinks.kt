package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import co.touchlab.kermit.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
private data class CopyrightApiLink(
    val anchorRef: String,
    val anchorRefExpanded: List<String> = emptyList(),
    val sourceRef: String? = null,
    val sourceHeRef: String,
    val connectionType: String = "API",
)

internal data class CopyrightApiLinkResult(
    val written: Int,
    val unresolved: Int,
)

private data class SeedRefTarget(
    val heRef: String,
    val path: String,
    val lineIndex: Int,
)

internal fun appendCopyrightApiLinks(
    json: Json,
    apiLinksPath: Path,
    seedDb: Path,
    copyrightRefs: List<RefEntry>,
    outputRoot: Path,
    logger: Logger,
): CopyrightApiLinkResult {
    if (!Files.isRegularFile(apiLinksPath)) return CopyrightApiLinkResult(0, 0)
    val apiLinks = json.decodeFromString<List<CopyrightApiLink>>(apiLinksPath.readText())
    if (apiLinks.isEmpty() || copyrightRefs.isEmpty()) return CopyrightApiLinkResult(0, 0)

    val refsByCitation = copyrightRefs.groupBy { canonicalCitation(it.ref) }
    val refsByBase = buildMap<String, RefEntry> {
        copyrightRefs.forEach { ref ->
            val key = canonicalBase(ref.ref)
            val existing = this[key]
            if (existing == null || ref.lineIndex < existing.lineIndex) put(key, ref)
        }
    }
    val copyrightByHebrew = copyrightRefs
        .groupBy { canonicalHebrewRef(it.heRef) }
        .mapValues { (_, refs) -> refs.minBy { it.lineIndex } }

    val targetKeys = apiLinks.map { canonicalHebrewRef(it.sourceHeRef) }.filter(String::isNotBlank).toSet()
    val seedTargets = loadSeedTargets(seedDb, targetKeys)
    val additions = linkedMapOf<String, LinkedHashSet<JsonObject>>()
    var unresolved = 0

    apiLinks.forEach { link ->
        val sourceCandidates = buildList {
            add(link.anchorRef)
            addAll(link.anchorRefExpanded)
        }.flatMap { resolveRefs(it, refsByCitation, refsByBase) }.distinct()
        val targetKey = canonicalHebrewRef(link.sourceHeRef)
        val target = copyrightByHebrew[targetKey]?.let {
            SeedRefTarget(it.heRef, it.path, it.lineIndex)
        } ?: seedTargets[targetKey]
        if (sourceCandidates.isEmpty() || target == null) {
            unresolved++
            return@forEach
        }

        sourceCandidates.forEach { source ->
            val sourceTitle = Paths.get(source.path).nameWithoutExtension
            val entry = JsonObject(
                linkedMapOf(
                    "line_index_1" to JsonPrimitive(source.lineIndex),
                    "line_index_2" to JsonPrimitive(target.lineIndex),
                    "heRef_2" to JsonPrimitive(target.heRef),
                    "path_2" to JsonPrimitive(target.path),
                    "Conection Type" to JsonPrimitive(link.connectionType),
                )
            )
            additions.getOrPut(sourceTitle) { linkedSetOf() } += entry
        }
    }

    if (additions.isEmpty()) {
        logger.w { "No outbound copyright API links could be resolved; unresolved=$unresolved" }
        return CopyrightApiLinkResult(0, unresolved)
    }
    val linksRoot = Files.createDirectories(outputRoot.resolve("links"))
    var written = 0
    additions.forEach { (sourceTitle, newEntries) ->
        val path = linksRoot.resolve("${sanitizeOtzariaFileName(sourceTitle)}_links.json")
        val existing = if (Files.isRegularFile(path)) {
            runCatching { json.parseToJsonElement(path.readText()) as? JsonArray }
                .getOrNull()?.toList().orEmpty()
        } else {
            emptyList()
        }
        val combined = LinkedHashSet<JsonElement>(existing.size + newEntries.size)
        combined += existing
        combined += newEntries
        written += combined.size - existing.size
        path.writeText(json.encodeToString(JsonArray(combined.toList())))
    }
    logger.i { "Resolved $written outbound copyright API links; unresolved=$unresolved" }
    return CopyrightApiLinkResult(written, unresolved)
}

private fun loadSeedTargets(seedDb: Path, wanted: Set<String>): Map<String, SeedRefTarget> {
    if (wanted.isEmpty()) return emptyMap()
    Class.forName("org.sqlite.JDBC")
    val targets = HashMap<String, SeedRefTarget>()
    DriverManager.getConnection("jdbc:sqlite:${seedDb.toUri()}?mode=ro").use { connection ->
        val categories = HashMap<Long, Pair<Long?, String>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id, parentId, title FROM category").use { rows ->
                while (rows.next()) {
                    val parent = rows.getLong(2).let { if (rows.wasNull()) null else it }
                    categories[rows.getLong(1)] = parent to rows.getString(3)
                }
            }
        }
        val categoryPathCache = HashMap<Long, List<String>>()
        fun categoryPath(id: Long): List<String> = categoryPathCache.getOrPut(id) {
            val reversed = ArrayList<String>()
            var current: Long? = id
            val seen = HashSet<Long>()
            while (current != null && seen.add(current)) {
                val category = categories[current] ?: break
                reversed += category.second
                current = category.first
            }
            reversed.asReversed()
        }

        connection.createStatement().use { statement ->
            statement.fetchSize = 10_000
            statement.executeQuery(
                "SELECT l.heRef, l.lineIndex, b.title, b.categoryId " +
                    "FROM line l JOIN book b ON b.id = l.bookId WHERE l.heRef IS NOT NULL"
            ).use { rows ->
                while (rows.next() && targets.size < wanted.size) {
                    val heRef = rows.getString(1)
                    val key = canonicalHebrewRef(heRef)
                    if (key !in wanted || key in targets) continue
                    var path = Paths.get("אוצריא")
                    categoryPath(rows.getLong(4)).map(::sanitizeFolder).filter(String::isNotBlank)
                        .forEach { path = path.resolve(it) }
                    path = path.resolve("${sanitizeOtzariaFileName(rows.getString(3))}.txt")
                    targets[key] = SeedRefTarget(
                        heRef = heRef,
                        path = path.invariantSeparatorsPathString,
                        lineIndex = rows.getInt(2) + 1,
                    )
                }
            }
        }
    }
    return targets
}

private fun canonicalHebrewRef(value: String): String = value
    .lowercase()
    .replace(Regex("[\\u0591-\\u05bd\\u05bf-\\u05c7]"), "")
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")
