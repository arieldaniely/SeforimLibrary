package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import co.touchlab.kermit.Logger
import io.github.kdroidfilter.seforimlibrary.common.ids.IdAllocatorBindings
import io.github.kdroidfilter.seforimlibrary.common.ids.InMemoryIdAllocator
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SefariaAuthorMetadataTest {
    @Test
    fun derivesAuthorCatalogFromSchemasWhenAuthorsExportIsMissing() = runBlocking {
        val exportRoot = Files.createTempDirectory("sefaria-schema-authors")
        val schemas = Files.createDirectories(exportRoot.resolve("schemas"))
        Files.writeString(
            schemas.resolve("book.json"),
            """{"authors":[{"slug":"rashi","he":"רש״י","en":"Rashi"}]}""",
        )
        System.setProperty("authorMetadataMode", "file")
        try {
            val catalog = SefariaAuthorCatalog.load(
                exportRoot,
                Json { ignoreUnknownKeys = true },
                Logger.withTag("SefariaAuthorMetadataTest"),
            )
            assertEquals("רש״י", catalog.displayName("rashi", "שלמה יצחקי"))

            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            val repository = SeforimRepository(":memory:", driver)
            try {
                catalog.persist(repository, IdAllocatorBindings(InMemoryIdAllocator.load(path = null), repository))
                assertEquals("רש״י", assertNotNull(repository.getAuthorBySlug("rashi")).name)
            } finally {
                repository.close()
            }
        } finally {
            System.clearProperty("authorMetadataMode")
        }
    }

    @Test
    fun importsHebrewBiographyLifePeriodAliasesAndRelations() = runBlocking {
        val exportRoot = Files.createTempDirectory("sefaria-authors")
        Files.writeString(exportRoot.resolve("authors.json"), richAuthorsJson)
        System.setProperty("authorMetadataMode", "file")
        try {
            val catalog = SefariaAuthorCatalog.load(
                exportRoot,
                Json { ignoreUnknownKeys = true },
                Logger.withTag("SefariaAuthorMetadataTest"),
            )
            assertEquals("רבי שלמה יצחקי", catalog.displayName("rashi", "שלמה יצחקי"))

            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            val repository = SeforimRepository(":memory:", driver)
            try {
                val bindings = IdAllocatorBindings(InMemoryIdAllocator.load(path = null), repository)
                catalog.persist(repository, bindings)

                val author = assertNotNull(repository.getAuthorBySlug("rashi"))
                assertEquals("פירושו נעשה יסוד ללימוד המקרא והתלמוד.", author.heBio)
                assertEquals(1040, author.birthYear)
                assertFalse(author.birthYearIsApprox)
                assertEquals(1105, author.deathYear)
                assertTrue(author.deathYearIsApprox)
                assertEquals("RI", author.era)
                assertEquals("ראשונים", author.eraName)

                val details = assertNotNull(repository.getAuthorDetails(author.id))
                assertTrue(details.aliases.any { it.name == "רש״י" })
                assertEquals("rashbam", details.relations.single().targetSlug)
                assertEquals("משפחה", details.relations.single().relationTypeHe)
            } finally {
                repository.close()
            }
        } finally {
            System.clearProperty("authorMetadataMode")
        }
    }

    private val richAuthorsJson = """
        [
          {
            "slug": "rashi",
            "primaryTitle": {"he": "שלמה יצחקי", "en": "Rashi"},
            "titles": [
              {"lang": "he", "text": "שלמה יצחקי", "primary": true},
              {"lang": "he", "text": "רבי שלמה יצחקי"},
              {"lang": "he", "text": "רש״י"},
              {"lang": "en", "text": "Rashi"}
            ],
            "description": {"he": "פירושו נעשה יסוד ללימוד המקרא והתלמוד.", "en": "Ignored"},
            "properties": {
              "birthYear": {"value": 1040},
              "birthYearIsApprox": {"value": false},
              "deathYear": {"value": "1105"},
              "deathYearIsApprox": {"value": true},
              "era": {"value": "RI"},
              "heWikiLink": {"value": "https://he.wikipedia.org/wiki/רש״י"}
            },
            "timePeriod": {"name": {"he": "ראשונים", "en": "Rishonim"}},
            "links": {
              "family-member-of": {
                "title": {"he": "משפחה", "en": "Family"},
                "shouldDisplay": true,
                "links": [
                  {
                    "topic": "rashbam",
                    "title": {"he": "רשב״ם", "en": "Rashbam"},
                    "isInverse": false
                  }
                ]
              }
            }
          }
        ]
    """.trimIndent()
}
