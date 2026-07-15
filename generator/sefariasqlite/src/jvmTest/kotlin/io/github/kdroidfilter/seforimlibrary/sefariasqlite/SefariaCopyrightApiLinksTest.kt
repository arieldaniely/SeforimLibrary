package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SefariaCopyrightApiLinksTest {
    @Test
    fun `writes outbound Otzaria link to a book already present in seed`() {
        val root = Files.createTempDirectory("copyright-api-links-test")
        val seedDb = root.resolve("seed.db")
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$seedDb").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE category (id INTEGER PRIMARY KEY, parentId INTEGER, title TEXT NOT NULL)")
                statement.execute("CREATE TABLE book (id INTEGER PRIMARY KEY, categoryId INTEGER NOT NULL, title TEXT NOT NULL)")
                statement.execute("CREATE TABLE line (id INTEGER PRIMARY KEY, bookId INTEGER NOT NULL, lineIndex INTEGER NOT NULL, heRef TEXT)")
                statement.execute("INSERT INTO category VALUES (1, NULL, 'תנך'), (2, 1, 'תורה')")
                statement.execute("INSERT INTO book VALUES (10, 2, 'בראשית')")
                statement.execute("INSERT INTO line VALUES (100, 10, 0, 'בראשית א, א')")
            }
        }

        val apiLinks = root.resolve("api-links.json")
        apiLinks.writeText(
            """[{"anchorRef":"Steinsaltz on Genesis 1:1","sourceHeRef":"בראשית א׳:א׳","connectionType":"commentary"}]"""
        )
        val output = root.resolve("output")
        Files.createDirectories(output)
        val result = appendCopyrightApiLinks(
            json = Json { ignoreUnknownKeys = true; prettyPrint = true },
            apiLinksPath = apiLinks,
            seedDb = seedDb,
            copyrightRefs = listOf(
                RefEntry(
                    ref = "Steinsaltz on Genesis 1:1",
                    heRef = "ביאור שטיינזלץ על בראשית א, א",
                    path = "אוצריא/תנך/ביאור שטיינזלץ על בראשית.txt",
                    lineIndex = 2,
                )
            ),
            outputRoot = output,
            logger = Logger.withTag("test"),
        )

        assertEquals(1, result.written)
        assertEquals(0, result.unresolved)
        val linksFile = output.resolve("links/ביאור שטיינזלץ על בראשית_links.json")
        assertTrue(Files.isRegularFile(linksFile))
        val link = Json.parseToJsonElement(linksFile.readText()).jsonArray.single().jsonObject
        assertEquals(2, link.getValue("line_index_1").jsonPrimitive.content.toInt())
        assertEquals(1, link.getValue("line_index_2").jsonPrimitive.content.toInt())
        assertEquals("בראשית א, א", link.getValue("heRef_2").jsonPrimitive.content)
        assertEquals("אוצריא/תנך/תורה/בראשית.txt", link.getValue("path_2").jsonPrimitive.content)
        assertEquals("commentary", link.getValue("Conection Type").jsonPrimitive.content)
    }
}
