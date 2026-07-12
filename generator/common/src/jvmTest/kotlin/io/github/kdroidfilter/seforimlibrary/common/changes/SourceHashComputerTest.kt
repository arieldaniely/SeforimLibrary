package io.github.kdroidfilter.seforimlibrary.common.changes

import io.github.kdroidfilter.seforimlibrary.common.buildstate.BookKey
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceHashComputerTest {
    @JvmField @Rule
    val tmp = TemporaryFolder()

    // ─── Otzaria: parses files_manifest.json and emits hashes for each .txt ───

    @Test
    fun `otzaria parses manifest and emits one entry per txt book`() {
        val root = tmp.newFolder().toPath()
        Files.writeString(
            root.resolve("files_manifest.json"),
            """
            {
              "אוצריא/Tanakh/Genesis.txt": {"hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
              "אוצריא/Tanakh/Exodus.txt":  {"hash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
              "metadata.json":             {"hash": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}
            }
            """.trimIndent(),
        )
        val computer = OtzariaSourceHashComputer()
        val out = computer.compute(root, version = 7)

        // Two books — metadata.json is filtered out (not .txt).
        assertEquals(2, out.size)
        val genesis = BookKey("Otzaria", "Genesis")
        val exodus = BookKey("Otzaria", "Exodus")
        assertNotNull(out[genesis])
        assertNotNull(out[exodus])
        assertEquals(7, out.getValue(genesis).lastSeenVersion)
        // First byte of "aaaaaaaa..." sha256 hex is 0xaa.
        assertEquals(0xaa.toByte(), out.getValue(genesis).hash[0])
        assertEquals(0xbb.toByte(), out.getValue(exodus).hash[0])
        // Hashes must be 32 bytes.
        assertEquals(32, out.getValue(genesis).hash.size)
    }

    @Test
    fun `otzaria detects content change by hash diff`() {
        val rootA = tmp.newFolder().toPath()
        Files.writeString(
            rootA.resolve("files_manifest.json"),
            """{"foo/A.txt": {"hash": "1111111111111111111111111111111111111111111111111111111111111111"}}""",
        )
        val rootB = tmp.newFolder().toPath()
        Files.writeString(
            rootB.resolve("files_manifest.json"),
            """{"foo/A.txt": {"hash": "2222222222222222222222222222222222222222222222222222222222222222"}}""",
        )
        val a = OtzariaSourceHashComputer().compute(rootA, 1)
        val b = OtzariaSourceHashComputer().compute(rootB, 2)
        val key = BookKey("Otzaria", "A")
        assertNotEquals(a.getValue(key).hash.toList(), b.getValue(key).hash.toList())
    }

    @Test
    fun `otzaria parses nested entry even with sibling fields and hash not first`() {
        // Regression guard: the manifest is nested `{path: {"hash": ...}}`. The parser
        // must find `hash` inside the object even when it is not the first key and other
        // fields sit beside it — and must NOT read across into a sibling entry's object.
        val dd = "d".repeat(64)
        val ee = "e".repeat(64)
        val root = tmp.newFolder().toPath()
        Files.writeString(
            root.resolve("files_manifest.json"),
            """
            {
              "src/אוצריא/foo/A.txt": {"size": 123, "hash": "$dd"},
              "src/אוצריא/foo/B.txt": {"hash": "$ee", "size": 9}
            }
            """.trimIndent(),
        )
        val out = OtzariaSourceHashComputer().compute(root, 1)
        assertEquals(2, out.size)
        assertEquals(0xdd.toByte(), out.getValue(BookKey("Otzaria", "A")).hash[0])
        assertEquals(0xee.toByte(), out.getValue(BookKey("Otzaria", "B")).hash[0])
    }

    @Test
    fun `otzaria honors source resolver`() {
        val root = tmp.newFolder().toPath()
        Files.writeString(
            root.resolve("files_manifest.json"),
            """{"foo/A.txt": {"hash": "1111111111111111111111111111111111111111111111111111111111111111"}}""",
        )
        val out = OtzariaSourceHashComputer(sourceNameResolver = { "wikisourceToOtzaria" })
            .compute(root, 1)
        assertEquals(setOf(BookKey("wikisourceToOtzaria", "A")), out.keys)
    }

    @Test
    fun `otzaria applies title normalizer so key matches importer`() {
        // The importer stores `normalizeBookTitle(filename)`; the computer must too, or
        // peekBookId misses these books and their delta detection silently breaks.
        val root = tmp.newFolder().toPath()
        val h = "1".repeat(64)
        Files.writeString(
            root.resolve("files_manifest.json"),
            """
            {
              "src/אוצריא/foo/פעמוני זהב .txt": {"hash": "$h"},
              "src/אוצריא/foo/מהרי''ק.txt": {"hash": "$h"}
            }
            """.trimIndent(),
        )
        // Stand-in for the importer's normalizeBookTitle: trim + `''`→`״`.
        val normalizer: (String) -> String = { it.trim().replace("''", "״") }
        val out = OtzariaSourceHashComputer(titleNormalizer = normalizer).compute(root, 1)
        assertEquals(
            setOf(BookKey("Otzaria", "פעמוני זהב"), BookKey("Otzaria", "מהרי״ק")),
            out.keys,
        )
    }

    @Test
    fun `otzaria resolves source from the manifest key prefix, not Unknown`() {
        // Regression guard for the real-manifest shape: keys carry a source prefix before
        // "אוצריא". The resolver must read that prefix (like the importer's manifestSourcesByRel),
        // NOT relativize a Path — otherwise every book collapses to "Unknown" and the
        // BookKey never matches the allocator (delta becomes a silent no-op).
        val root = tmp.newFolder().toPath()
        val h = "1".repeat(64)
        Files.writeString(
            root.resolve("files_manifest.json"),
            """
            {
              "metadata.json": {"hash": "$h"},
              "DictaToOtzaria/ערוך/ספרים/אוצריא/משנה/אחרונים/אור הישר.txt": {"hash": "$h"},
              "MoreBooks/ x /אוצריא/פוסקים/חזון איש.txt": {"hash": "$h"}
            }
            """.trimIndent(),
        )
        // Mirrors Generator.sourceNameForManifestKey: source = first segment before "אוצריא".
        val resolver: (String) -> String = { key ->
            val parts = key.split('/')
            val idx = parts.indexOf("אוצריא")
            if (idx > 0) parts.first() else "Unknown"
        }
        val out = OtzariaSourceHashComputer(sourceNameResolver = resolver).compute(root, 1)
        assertEquals(
            setOf(
                BookKey("DictaToOtzaria", "אור הישר"),
                BookKey("MoreBooks", "חזון איש"),
            ),
            out.keys,
        )
    }

    // ─── Sefaria: walks json/<...>/merged.json and incorporates the schema file ───

    @Test
    fun `sefaria hashes a single merged_json and emits canonical key`() {
        val root = tmp.newFolder().toPath()
        val bookDir = root.resolve("json/Tanakh/Torah/Genesis")
        Files.createDirectories(bookDir)
        Files.writeString(
            bookDir.resolve("merged.json"),
            """
            {
              "title": "Genesis",
              "heTitle": "בראשית",
              "text": [["a", "b"]]
            }
            """.trimIndent(),
        )
        Files.createDirectories(root.resolve("schemas"))
        val out = SefariaSourceHashComputer().compute(root, version = 3)

        assertEquals(1, out.size)
        val key = BookKey("Sefaria", "בראשית")
        assertNotNull(out[key])
        assertEquals(32, out.getValue(key).hash.size)
        assertEquals(3, out.getValue(key).lastSeenVersion)
    }

    @Test
    fun `sefaria hash changes when schema changes (not just merged_json)`() {
        val root = tmp.newFolder().toPath()
        val bookDir = root.resolve("json/Tanakh/Torah/Genesis")
        Files.createDirectories(bookDir)
        Files.writeString(
            bookDir.resolve("merged.json"),
            """{"title":"Genesis","heTitle":"בראשית","text":[["a"]]}""",
        )
        val schemaDir = root.resolve("schemas")
        Files.createDirectories(schemaDir)
        Files.writeString(schemaDir.resolve("בראשית.json"), """{"schema":"v1"}""")

        val a = SefariaSourceHashComputer().compute(root, 1)

        Files.writeString(schemaDir.resolve("בראשית.json"), """{"schema":"v2"}""")
        val b = SefariaSourceHashComputer().compute(root, 2)

        val key = BookKey("Sefaria", "בראשית")
        assertNotEquals(
            a.getValue(key).hash.toList(),
            b.getValue(key).hash.toList(),
            "schema change must alter the hash",
        )
    }

    @Test
    fun `sefaria skips books missing heTitle`() {
        val root = tmp.newFolder().toPath()
        val bookDir = root.resolve("json/foo/Bar")
        Files.createDirectories(bookDir)
        Files.writeString(bookDir.resolve("merged.json"), """{"title":"NoHeTitle","text":[]}""")
        Files.createDirectories(root.resolve("schemas"))
        val out = SefariaSourceHashComputer().compute(root, 1)
        assertTrue(out.isEmpty())
    }
}
