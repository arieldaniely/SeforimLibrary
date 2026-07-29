package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import co.touchlab.kermit.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SefariaBookPayloadReaderTest {
    @Test
    fun selectsOnlyHebrewMergedFileFromLanguageLayout() {
        val tempDir = Files.createTempDirectory("seforim-language-filter-test")
        val schemaDir = Files.createDirectories(tempDir.resolve("schemas"))
        val jsonDir = Files.createDirectories(tempDir.resolve("json"))
        val title = "Introductions to the Babylonian Talmud"
        val bookDir = Files.createDirectories(jsonDir.resolve(title))
        val hebrewDir = Files.createDirectories(bookDir.resolve("Hebrew"))
        val englishDir = Files.createDirectories(bookDir.resolve("English"))

        val schemaPath = schemaDir.resolve("$title.json")
        Files.writeString(schemaPath, titlesArrayOnlySchemaJson)
        Files.writeString(
            hebrewDir.resolve("merged.json"),
            """{"title":"$title","heTitle":"הקדמות לתלמוד הבבלי","text":{"Berakhot":["תוכן"]}}""",
        )
        Files.writeString(englishDir.resolve("merged.json"), "not-json")

        val reader = SefariaBookPayloadReader(
            Json { ignoreUnknownKeys = true; coerceInputValues = true },
            Logger.withTag("SefariaBookPayloadReaderTest")
        )
        val schemaLookup = reader.buildSchemaLookup(schemaDir)
        val selection = reader.selectMergedFiles(jsonDir, schemaDir, schemaLookup)

        assertEquals(schemaPath, schemaLookup[normalizeTitleKey("הקדמות לתלמוד הבבלי")])
        assertEquals(listOf(hebrewDir.resolve("merged.json")), selection.files)
        assertEquals(0, selection.schemasWithoutMergedCount)
    }

    @Test
    fun incrementalFilterSkipsMergedJsonWithoutParsingItsText() {
        val tempDir = Files.createTempDirectory("seforim-filter-test")
        val schemaDir = Files.createDirectories(tempDir.resolve("schemas"))
        val jsonDir = Files.createDirectories(tempDir.resolve("json"))
        val bookDir = Files.createDirectories(jsonDir.resolve("Tur"))

        Files.writeString(schemaDir.resolve("Tur.json"), schemaJson)
        // Invalid JSON proves the filter decided from the schema and did not
        // parse the large merged payload for an existing book.
        Files.writeString(bookDir.resolve("merged.json"), "not-json")

        val reader = SefariaBookPayloadReader(
            Json { ignoreUnknownKeys = true; coerceInputValues = true },
            Logger.withTag("SefariaBookPayloadReaderTest")
        )
        val schemaLookup = reader.buildSchemaLookup(schemaDir)
        val existingTitle = requireNotNull(normalizeTitleKey("Tur"))

        val mergedFiles = reader.findMergedFiles(
            jsonDir = jsonDir,
            schemaDir = schemaDir,
            schemaLookup = schemaLookup,
            excludedTitleKeys = setOf(existingTitle),
        )

        assertTrue(mergedFiles.isEmpty())
    }

    @Test
    fun defaultNodeWithoutTitleKeepsSimanimAtSameLevelAsIntroduction() = runBlocking {
        val tempDir = Files.createTempDirectory("seforim-test")
        val schemaDir = Files.createDirectories(tempDir.resolve("schemas"))
        val jsonDir = Files.createDirectories(tempDir.resolve("json"))
        val bookDir = Files.createDirectories(jsonDir.resolve("Tur"))

        Files.writeString(schemaDir.resolve("Tur.json"), schemaJson)
        Files.writeString(bookDir.resolve("merged.json"), mergedJson)

        val reader = SefariaBookPayloadReader(
            Json { ignoreUnknownKeys = true; coerceInputValues = true },
            Logger.withTag("SefariaBookPayloadReaderTest")
        )
        val schemaLookup = reader.buildSchemaLookup(schemaDir)
        val payload = reader.readBooksInParallel(jsonDir, schemaDir, schemaLookup).single()

        val intro = payload.headings.firstOrNull { it.title == "הקדמה" }
        val siman = payload.headings.firstOrNull { it.title == "סימן א" }

        assertNotNull(intro)
        assertNotNull(siman)
        assertEquals(2, intro.level)
        assertEquals(2, siman.level)
        assertTrue(payload.lines.any { it == "<h3>סימן א</h3>" })
        assertTrue(payload.lines.none { it == "<h4>סימן א</h4>" })
    }

    @Test
    fun importsDictionaryNodeEntriesWithHeadwordRefs() = runBlocking {
        val tempDir = Files.createTempDirectory("seforim-dictionary-test")
        val schemaDir = Files.createDirectories(tempDir.resolve("schemas"))
        val jsonDir = Files.createDirectories(tempDir.resolve("json"))
        val bookDir = Files.createDirectories(jsonDir.resolve("Test Dictionary"))
        val schemaPath = schemaDir.resolve("Test Dictionary.json")

        Files.writeString(
            schemaPath,
            """
            {
              "schema": {
                "title": "Test Dictionary",
                "heTitle": "מילון בדיקה",
                "nodes": [{
                  "key": "Test Dictionary",
                  "default": true,
                  "nodeType": "DictionaryNode",
                  "lexiconName": "Test Lexicon",
                  "firstWord": "אב",
                  "lastWord": "אג",
                  "headwordMap": [["א", "Test Dictionary, אב"]]
                }]
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            bookDir.resolve("merged.json"),
            """
            {
              "title": "Test Dictionary",
              "heTitle": "מילון בדיקה",
              "text": {"": [
                {"headword": "אב", "text": "<b>אב</b> הגדרה ראשונה"},
                {"headword": "אג", "text": "<b>אג</b> הגדרה שנייה"}
              ]}
            }
            """.trimIndent(),
        )

        val reader = SefariaBookPayloadReader(
            Json { ignoreUnknownKeys = true; coerceInputValues = true },
            Logger.withTag("SefariaBookPayloadReaderTest")
        )
        val schemaLookup = reader.buildSchemaLookup(schemaDir)
        val payload = reader.readBooksInParallel(jsonDir, schemaDir, schemaLookup).single()

        assertTrue(payload.lines.contains("<b>אב</b> הגדרה ראשונה"))
        assertTrue(payload.lines.contains("<b>אג</b> הגדרה שנייה"))
        assertTrue(payload.refEntries.any { it.ref == "Test Dictionary, אב" && it.heRef == "מילון בדיקה, אב" })
        assertTrue(payload.refEntries.any { it.ref == "Test Dictionary, אג" && it.heRef == "מילון בדיקה, אג" })
    }

    @Test
    fun readsArrayAndScalarIndexOffsetsWithoutCrashing() {
        val json = Json { ignoreUnknownKeys = true }
        val reader = SefariaBookPayloadReader(json, Logger.withTag("SefariaBookPayloadReaderTest"))

        val arrayNode = json.parseToJsonElement(
            """{"index_offsets_by_depth":{"2":[3,8]}}""",
        ).jsonObject
        val scalarNode = json.parseToJsonElement(
            """{"index_offsets_by_depth":{"2":7}}""",
        ).jsonObject
        val invalidOffsetsNode = json.parseToJsonElement(
            """{"index_offsets_by_depth":{"2":{"unexpected":1}}}""",
        ).jsonObject
        val invalidMapNode = json.parseToJsonElement(
            """{"index_offsets_by_depth":5}""",
        ).jsonObject

        assertEquals(listOf(3, 8), reader.readIndexOffsets(arrayNode, 2))
        assertEquals(listOf(7), reader.readIndexOffsets(scalarNode, 2))
        assertEquals(null, reader.readIndexOffsets(invalidOffsetsNode, 2))
        assertEquals(null, reader.readIndexOffsets(invalidMapNode, 2))
    }

    companion object {
        private val titlesArrayOnlySchemaJson = """
            {
              "schema": {
                "titles": [
                  {"lang": "en", "primary": true, "text": "Introductions to the Babylonian Talmud"},
                  {"lang": "he", "primary": true, "text": "הקדמות לתלמוד הבבלי"}
                ],
                "nodes": []
              }
            }
        """.trimIndent()

        private val schemaJson = """
            {
              "title": "Tur",
              "heTitle": "טור",
              "schema": {
                "title": "Tur",
                "heTitle": "טור",
                "nodes": [
                  {
                    "nodeType": "SchemaNode",
                    "title": "Orach Chayim",
                    "heTitle": "אורח חיים",
                    "key": "Orach Chaim",
                    "nodes": [
                      {
                        "nodeType": "JaggedArrayNode",
                        "depth": 1,
                        "addressTypes": [
                          "Integer"
                        ],
                        "sectionNames": [
                          "Paragraph"
                        ],
                        "title": "Introduction",
                        "heTitle": "הקדמה",
                        "heSectionNames": [
                          "פסקה"
                        ],
                        "key": "Introduction"
                      },
                      {
                        "nodeType": "JaggedArrayNode",
                        "depth": 2,
                        "addressTypes": [
                          "Siman",
                          "Seif"
                        ],
                        "sectionNames": [
                          "Siman",
                          "Seif"
                        ],
                        "title": "",
                        "heTitle": "",
                        "heSectionNames": [
                          "סימן",
                          "סעיף"
                        ],
                        "key": "default",
                        "default": true
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        private val mergedJson = """
            {
              "title": "Tur",
              "heTitle": "טור",
              "text": {
                "Orach Chayim": {
                  "Introduction": [
                    "intro paragraph"
                  ],
                  "": [
                    [
                      "siman text"
                    ]
                  ]
                }
              }
            }
        """.trimIndent()
    }
}
