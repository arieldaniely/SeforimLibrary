package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import co.touchlab.kermit.Logger
import io.github.kdroidfilter.seforimlibrary.common.OptimizedHttpClient
import io.github.kdroidfilter.seforimlibrary.core.models.Author
import io.github.kdroidfilter.seforimlibrary.core.models.AuthorAlias
import io.github.kdroidfilter.seforimlibrary.core.models.AuthorDetails
import io.github.kdroidfilter.seforimlibrary.core.models.AuthorRelation
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import io.github.kdroidfilter.seforimlibrary.common.ids.IdAllocatorBindings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText

/**
 * Hebrew-only author data from Sefaria Topics.
 *
 * Otzaria's export supplies `authors.json` with slugs and Hebrew title forms.
 * Newer/enriched exports may include the full Topic payload inline. For older
 * exports we fill the missing payload from Sefaria's public Topic endpoint and
 * cache every response on disk, making the operation resumable and polite.
 */
internal class SefariaAuthorCatalog private constructor(
    private val recordsBySlug: Map<String, Record>,
) {
    internal data class Record(
        val slug: String,
        val displayName: String,
        val aliases: List<String>,
        val heBio: String?,
        val birthYear: Int?,
        val birthYearIsApprox: Boolean,
        val deathYear: Int?,
        val deathYearIsApprox: Boolean,
        val era: String?,
        val eraName: String?,
        val birthPlace: String?,
        val deathPlace: String?,
        val heWikiLink: String?,
        val heNliLink: String?,
        val relations: List<Relation>,
    )

    internal data class Relation(
        val targetSlug: String,
        val targetName: String?,
        val relationType: String,
        val relationTypeHe: String?,
        val isInverse: Boolean,
    )

    fun displayName(slug: String?, schemaHe: String): String {
        val record = recordsBySlug[slug?.trim()] ?: return schemaHe
        val schemaMatchesCatalog = record.aliases.any { normalize(it) == normalize(schemaHe) }
        return if (schemaMatchesCatalog) chooseDisplayName(schemaHe, record.aliases) else record.displayName
    }

    fun allNameForms(slug: String?): List<String> = recordsBySlug[slug?.trim()]?.aliases.orEmpty()

    suspend fun persist(repository: SeforimRepository, bindings: IdAllocatorBindings) {
        recordsBySlug.values.sortedBy { it.slug }.forEach { record ->
            val authorId = bindings.upsertAuthor(record.displayName)
            repository.upsertAuthorDetails(
                AuthorDetails(
                    author = Author(
                        id = authorId,
                        name = record.displayName,
                        sefariaSlug = record.slug,
                        heBio = record.heBio,
                        birthYear = record.birthYear,
                        birthYearIsApprox = record.birthYearIsApprox,
                        deathYear = record.deathYear,
                        deathYearIsApprox = record.deathYearIsApprox,
                        era = record.era,
                        eraName = record.eraName,
                        birthPlace = record.birthPlace,
                        deathPlace = record.deathPlace,
                        heWikiLink = record.heWikiLink,
                        heNliLink = record.heNliLink,
                    ),
                    aliases = record.aliases.map { alias ->
                        AuthorAlias(authorId, alias, alias == record.displayName)
                    },
                    relations = record.relations.map { relation ->
                        AuthorRelation(
                            authorId = authorId,
                            targetSlug = relation.targetSlug,
                            targetName = relation.targetName,
                            relationType = relation.relationType,
                            relationTypeHe = relation.relationTypeHe,
                            isInverse = relation.isInverse,
                        )
                    },
                )
            )
        }
    }

    companion object {
        const val FILE_NAME = "authors.json"
        private const val USER_AGENT = "SeforimLibrary-AuthorMetadata/1.0"
        private const val API_BASE = "https://www.sefaria.org/api/topics"
        val EMPTY = SefariaAuthorCatalog(emptyMap())

        fun load(exportRoot: Path, json: Json, logger: Logger): SefariaAuthorCatalog {
            val mode = (System.getProperty("authorMetadataMode")
                ?: System.getenv("SEFARIA_AUTHOR_METADATA_MODE")
                ?: "api").lowercase()
            require(mode in setOf("api", "file", "off")) {
                "authorMetadataMode must be api, file, or off (was '$mode')"
            }
            if (mode == "off") return EMPTY

            val path = exportRoot.resolve(FILE_NAME)
            val sourceRecords = if (Files.isRegularFile(path)) {
                json.parseToJsonElement(path.readText()) as? JsonArray
                    ?: throw IllegalStateException("$path is not a JSON array")
            } else {
                logger.w { "No $FILE_NAME under $exportRoot; deriving author slugs from book schemas" }
                JsonArray(loadAuthorSummariesFromSchemas(exportRoot, json, logger))
            }
            if (sourceRecords.isEmpty()) {
                logger.i { "No author slugs found in the Sefaria export; author metadata import skipped" }
                return EMPTY
            }
            val cacheRoot = Paths.get(
                System.getProperty("authorMetadataCache")
                    ?: System.getenv("SEFARIA_AUTHOR_METADATA_CACHE")
                    ?: Paths.get("build", "sefaria", "author-metadata-cache").toString()
            )
            if (mode == "api") Files.createDirectories(cacheRoot)
            var enriched = 0
            var failed = 0
            var consecutiveFailures = 0
            var apiEnabled = mode == "api"
            val records = linkedMapOf<String, Record>()

            sourceRecords.forEachIndexed { index, element ->
                val summary = element as? JsonObject
                    ?: throw IllegalStateException("$path entry $index is not an object")
                val slug = summary.string("slug")?.trim().orEmpty()
                if (slug.isEmpty()) return@forEachIndexed
                val payload = when {
                    summary.hasRichMetadata() -> summary
                    mode == "api" -> {
                        val topic = fetchTopic(slug, cacheRoot, json, logger, apiEnabled)
                        if (topic != null) {
                            enriched++
                            consecutiveFailures = 0
                            topic
                        } else {
                            failed++
                            consecutiveFailures++
                            if (consecutiveFailures >= 5 && apiEnabled) {
                                apiEnabled = false
                                logger.w {
                                    "Disabling author Topic downloads after 5 consecutive failures; " +
                                        "cached and exported author data will still be imported"
                                }
                            }
                            summary
                        }
                    }
                    else -> summary
                }
                parseRecord(slug, summary, payload)?.let { records[slug] = it }
                if ((index + 1) % 100 == 0) {
                    logger.i { "Author metadata: ${index + 1}/${sourceRecords.size}, enriched=$enriched, failed=$failed" }
                }
            }
            val resolvedRecords = records.mapValues { (_, record) ->
                record.copy(
                    relations = record.relations.map { relation ->
                        if (relation.targetName != null) relation
                        else relation.copy(targetName = records[relation.targetSlug]?.displayName)
                    },
                )
            }
            logger.i {
                "Loaded Hebrew author metadata: ${resolvedRecords.size} authors, " +
                    "${resolvedRecords.values.count { !it.heBio.isNullOrBlank() }} biographies, " +
                    "${resolvedRecords.values.count { it.birthYear != null || it.deathYear != null }} life ranges, " +
                    "${resolvedRecords.values.sumOf { it.relations.size }} relationships"
            }
            return SefariaAuthorCatalog(resolvedRecords)
        }

        private fun loadAuthorSummariesFromSchemas(
            exportRoot: Path,
            json: Json,
            logger: Logger,
        ): List<JsonObject> {
            val schemaDir = exportRoot.resolve("schemas")
            if (!Files.isDirectory(schemaDir)) return emptyList()
            val bySlug = linkedMapOf<String, JsonObject>()
            Files.walk(schemaDir).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .sorted()
                    .forEach { schemaPath ->
                        runCatching {
                            val schema = json.parseToJsonElement(schemaPath.readText()) as? JsonObject
                                ?: return@runCatching
                            (schema["authors"] as? JsonArray).orEmpty()
                                .mapNotNull { it as? JsonObject }
                                .forEach { author ->
                                    val slug = author.string("slug")?.trim().orEmpty()
                                    if (slug.isNotEmpty()) bySlug.putIfAbsent(slug, author)
                                }
                        }.onFailure { logger.w(it) { "Unable to inspect author metadata in $schemaPath" } }
                    }
            }
            logger.i { "Derived ${bySlug.size} author slugs from book schemas" }
            return bySlug.values.toList()
        }

        private fun fetchTopic(
            slug: String,
            cacheRoot: Path,
            json: Json,
            logger: Logger,
            allowNetwork: Boolean,
        ): JsonObject? {
            val safeName = slug.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val cache = cacheRoot.resolve("$safeName.json")
            if (Files.isRegularFile(cache)) {
                return runCatching { json.parseToJsonElement(cache.readText()) as? JsonObject }
                    .getOrElse {
                        logger.w(it) { "Ignoring corrupt author metadata cache $cache" }
                        null
                    }
            }
            if (!allowNetwork) return null
            val encoded = URLEncoder.encode(slug, StandardCharsets.UTF_8).replace("+", "%20")
            val url = "$API_BASE/$encoded?annotate_time_period=1&with_links=1&with_refs=0"
            return runCatching {
                val body = OptimizedHttpClient.fetchJson(url, USER_AGENT, logger)
                val obj = json.parseToJsonElement(body) as? JsonObject
                    ?: error("Sefaria returned a non-object Topic for $slug")
                if (obj.isEmpty()) error("Sefaria returned an empty Topic for $slug")
                val temp = Files.createTempFile(cacheRoot, safeName, ".tmp")
                Files.writeString(temp, body)
                runCatching {
                    Files.move(temp, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }.getOrElse {
                    Files.move(temp, cache, StandardCopyOption.REPLACE_EXISTING)
                }
                obj
            }.onFailure { logger.w(it) { "Unable to enrich Sefaria author '$slug'; keeping exported titles" } }
                .getOrNull()
        }

        private fun parseRecord(slug: String, summary: JsonObject, payload: JsonObject): Record? {
            val aliases = (summary.hebrewTitles() + payload.hebrewTitles()).distinct()
            val primary = payload.primaryHebrewTitle()
                ?: summary.primaryHebrewTitle()
                ?: aliases.firstOrNull()
                ?: return null
            val displayName = chooseDisplayName(primary, aliases)
            val properties = payload["properties"] as? JsonObject
            val era = properties.propertyString("era")
            return Record(
                slug = slug,
                displayName = displayName,
                aliases = (listOf(displayName) + aliases).distinct(),
                heBio = payload.hebrewDescription() ?: properties.propertyString("heBio"),
                birthYear = properties.propertyInt("birthYear"),
                birthYearIsApprox = properties.propertyBoolean("birthYearIsApprox"),
                deathYear = properties.propertyInt("deathYear"),
                deathYearIsApprox = properties.propertyBoolean("deathYearIsApprox"),
                era = era,
                eraName = payload.periodNameHe() ?: eraNameHe(era),
                birthPlace = properties.propertyHebrew("birthPlace") ?: properties.propertyString("heBirthPlace"),
                deathPlace = properties.propertyHebrew("deathPlace") ?: properties.propertyString("heDeathPlace"),
                heWikiLink = properties.propertyString("heWikiLink"),
                heNliLink = properties.propertyString("heNliLink"),
                relations = payload.extractRelations(),
            )
        }

        private fun JsonObject.hasRichMetadata(): Boolean =
            "properties" in this || "description" in this || "links" in this

        private fun JsonObject.hebrewTitles(): List<String> =
            (listOfNotNull(string("he")) + (this["titles"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { it.string("lang") == "he" }
                .mapNotNull { it.string("text")?.trim() }
                .filter { it.isNotEmpty() })

        private fun JsonObject.primaryHebrewTitle(): String? =
            string("primaryHe")
                ?: string("he")
                ?: hebrewNested("primaryTitle")
                ?: (this["titles"] as? JsonArray).orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .firstOrNull { it.string("lang") == "he" && it.boolean("primary") }
                    ?.string("text")

        private fun JsonObject.hebrewDescription(): String? {
            val description = this["description"]
            return when (description) {
                is JsonObject -> description.string("he")
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun JsonObject.periodNameHe(): String? {
            val period = (this["timePeriod"] ?: this["period"]) as? JsonObject ?: return null
            return period.string("he") ?: period.string("heName")
                ?: (period["name"] as? JsonObject)?.string("he")
        }

        private fun JsonObject.extractRelations(): List<Relation> {
            val result = linkedMapOf<String, Relation>()

            // Current Topic API shape: `links` is keyed by relation type and
            // each group carries its localized title plus an array of targets.
            (this["links"] as? JsonObject)?.forEach groupLoop@ { (relationType, rawGroup) ->
                val group = rawGroup as? JsonObject ?: return@groupLoop
                val groupLinks = group["links"] as? JsonArray ?: return@groupLoop
                if (group["shouldDisplay"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == false) {
                    return@groupLoop
                }
                val relationTypeHe = group.hebrewNested("title")?.takeIf(::containsHebrew)
                    ?: return@groupLoop
                groupLinks.mapNotNull { it as? JsonObject }.forEach linkLoop@ { link ->
                    val targetSlug = link.string("topic") ?: return@linkLoop
                    val relation = Relation(
                        targetSlug = targetSlug,
                        targetName = link.hebrewNested("title") ?: link.string("heTitle")?.takeIf(::containsHebrew),
                        relationType = relationType,
                        relationTypeHe = relationTypeHe,
                        isInverse = link.boolean("isInverse") || link.boolean("inverse"),
                    )
                    result["$targetSlug\u0000$relationType\u0000${relation.isInverse}"] = relation
                }
            }

            // Also accept enriched export files that contain flat link rows.
            fun visit(element: JsonElement?) {
                when (element) {
                    is JsonArray -> element.forEach(::visit)
                    is JsonObject -> {
                        val targetSlug = element.string("topic")
                            ?: element.string("toTopic")
                            ?: element.string("targetSlug")
                        val relationType = element.string("linkType")
                            ?: element.string("relationType")
                        if (!targetSlug.isNullOrBlank() && !relationType.isNullOrBlank()) {
                            val targetName = element.hebrewNested("title")
                                ?: element.hebrewNested("displayName")
                                ?: element.string("heTitle")
                            val typeHe = element.hebrewNested("linkTypeTitle")
                                ?: element.string("relationTypeHe")
                            val inverse = element.boolean("isInverse") || element.boolean("inverse")
                            val relation = Relation(targetSlug, targetName, relationType, typeHe, inverse)
                            result["$targetSlug\u0000$relationType\u0000$inverse"] = relation
                        } else {
                            element.values.forEach(::visit)
                        }
                    }
                    else -> Unit
                }
            }
            if (result.isEmpty()) visit(this["links"])
            return result.values.toList()
        }

        private fun JsonObject.hebrewNested(key: String): String? {
            val value = this[key]
            return when (value) {
                is JsonObject -> value.string("he") ?: value.string("hebrew")
                else -> null
            }?.takeIf(::containsHebrew)
        }

        private fun JsonObject?.propertyString(key: String): String? {
            val value = this?.get(key) ?: return null
            return when (value) {
                is JsonPrimitive -> value.contentOrNull
                is JsonObject -> value.string("value") ?: value.string("he")
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun JsonObject?.propertyHebrew(key: String): String? =
            propertyString(key)?.takeIf(::containsHebrew)

        private fun JsonObject?.propertyInt(key: String): Int? {
            val raw = this?.get(key) ?: return null
            val value = if (raw is JsonObject) raw["value"] else raw
            return (value as? JsonPrimitive)?.intOrNull
                ?: (value as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        }

        private fun JsonObject?.propertyBoolean(key: String): Boolean {
            val raw = this?.get(key) ?: return false
            val value = if (raw is JsonObject) raw["value"] else raw
            val primitive = value as? JsonPrimitive ?: return false
            return primitive.booleanOrNull ?: primitive.contentOrNull.equals("true", ignoreCase = true)
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.boolean(key: String): Boolean =
            (this[key] as? JsonPrimitive)?.booleanOrNull == true

        private fun containsHebrew(value: String): Boolean = value.any { it in '\u0590'..'\u05ff' }

        private fun eraNameHe(era: String?): String? = when {
            era == null -> null
            era == "GN" -> "גאונים"
            era == "RI" -> "ראשונים"
            era == "AH" -> "אחרונים"
            era == "CO" -> "מחברי זמננו"
            era.startsWith("Z") -> "זוגות"
            era.startsWith("T") -> "תנאים"
            era.startsWith("A") -> "אמוראים"
            else -> null
        }

        private val nikud = Regex("[\\u0591-\\u05BD\\u05BF-\\u05C7]")
        private val whitespace = Regex("[\\s\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000]+")
        private val honorifics = setOf(
            "רבי", "רבנו", "רבינו", "הרב", "הגאון", "הרה\"ג", "הג\"מ", "ר'",
            "מרן", "כ\"ק", "אדמו\"ר", "חכם", "דון", "מו\"ה", "לורד", "הקדוש",
        ).mapTo(hashSetOf(), ::normalize)

        private fun chooseDisplayName(baseRaw: String, aliases: List<String>): String {
            val base = normalize(baseRaw)
            return aliases.filter { candidate ->
                val normalized = normalize(candidate)
                if (!normalized.endsWith(base) || normalized.length <= base.length) return@filter false
                val prefix = normalized.dropLast(base.length)
                if (prefix == "ה") return@filter base.any { it == '\"' || it == '\'' }
                prefix.endsWith(' ') && prefix.trim().split(' ').all { it in honorifics }
            }.maxWithOrNull(compareBy({ it.length }, { it })) ?: baseRaw
        }

        private fun normalize(raw: String): String = raw
            .replace(nikud, "")
            .replace('״', '\"').replace('“', '\"').replace('”', '\"')
            .replace('׳', '\'').replace('‘', '\'').replace('’', '\'')
            .replace(whitespace, " ")
            .trim()
    }
}
