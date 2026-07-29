package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SefariaOtzariaExporterTest {
    @Test
    fun writesOtzariaTextsMetadataManifestAndBidirectionalLinks() {
        val temp = Files.createTempDirectory("sefaria-otzaria-export")
        val linksDir = Files.createDirectories(temp.resolve("source-links"))
        Files.writeString(
            linksDir.resolve("links0.csv"),
            "Citation 1,Citation 2,Conection Type\nAlpha 1:1,Beta 1:1,commentary\n"
        )

        val payloads = listOf(
            payload("א", "Alpha", "Alpha 1:1", "א א", "alpha text"),
            payload("ב", "Beta", "Beta 1:1", "ב א", "beta text"),
        )
        val outputRoot = temp.resolve("output")
        val outputZip = temp.resolve("incremental.zip")

        val result = SefariaOtzariaExporter(
            json = Json { prettyPrint = true; explicitNulls = false },
            logger = Logger.withTag("SefariaOtzariaExporterTest"),
        ).export(payloads, linksDir, outputRoot, outputZip)

        assertEquals(2, result.bookCount)
        assertEquals(2, result.linkCount)
        assertTrue(outputRoot.resolve("אוצריא/הלכה/א.txt").readText().contains("alpha text"))
        assertTrue(outputRoot.resolve("metadata.json").readText().contains("\"א\""))
        assertTrue(outputRoot.resolve("files_manifest.json").readText().contains("sefaria/אוצריא/הלכה/א.txt"))
        assertTrue(outputRoot.resolve("links/א_links.json").readText().contains("אוצריא/הלכה/ב.txt"))
        assertTrue(outputRoot.resolve("links/ב_links.json").readText().contains("אוצריא/הלכה/א.txt"))

        ZipFile(outputZip.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("אוצריא/הלכה/א.txt" in names)
            assertTrue("אוצריא/הלכה/ב.txt" in names)
            assertTrue("links/א_links.json" in names)
            assertTrue("metadata.json" in names)
            assertTrue("files_manifest.json" in names)
        }
    }

    private fun payload(
        heTitle: String,
        enTitle: String,
        ref: String,
        heRef: String,
        content: String,
    ) = BookPayload(
        heTitle = heTitle,
        enTitle = enTitle,
        categoriesHe = listOf("הלכה"),
        lines = listOf("<h1>$heTitle</h1>", content),
        refEntries = listOf(RefEntry(ref = ref, heRef = heRef, path = "", lineIndex = 2)),
        headings = listOf(Heading(title = heTitle, level = 0, lineIndex = 0)),
        authors = listOf("מחבר"),
        description = "תיאור",
        pubDates = emptyList(),
        altStructures = emptyList(),
    )
}
