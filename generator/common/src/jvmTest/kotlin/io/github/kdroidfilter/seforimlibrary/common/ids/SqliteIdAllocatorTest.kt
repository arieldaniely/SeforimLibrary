package io.github.kdroidfilter.seforimlibrary.common.ids

import io.github.kdroidfilter.seforimlibrary.common.buildstate.BookKey
import io.github.kdroidfilter.seforimlibrary.common.buildstate.BookSourceHash
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class SqliteIdAllocatorTest {
    @Test
    fun persistsAndReusesIdsWithoutLoadingTheSnapshotIntoMemory() {
        val root = Files.createTempDirectory("sqlite-id-allocator")
        val firstState = root.resolve("first.buildstate")
        val bookKey = BookKey("Sefaria", "בראשית")
        val hash = BookSourceHash(ByteArray(32) { it.toByte() }, 7)

        val first = SqliteIdAllocator.open(previous = null, working = firstState)
        val sourceId = first.sourceId("Sefaria")
        val bookId = first.bookId(bookKey.sourceName, bookKey.canonicalHeTitle)
        val lineId = first.lineId(bookId, ByteArray(20) { 3 }, 0)
        first.recordSourceHash(bookKey, hash)
        first.snapshotTo(firstState, mapOf("build_version" to "7"))
        first.close()

        val secondState = root.resolve("second.buildstate")
        val second = SqliteIdAllocator.open(previous = firstState, working = secondState)
        try {
            assertEquals(sourceId, second.sourceId("Sefaria"))
            assertEquals(bookId, second.bookId(bookKey.sourceName, bookKey.canonicalHeTitle))
            assertEquals(lineId, second.lineId(bookId, ByteArray(20) { 3 }, 0))
            assertNotEquals(lineId, second.lineId(bookId, ByteArray(20) { 3 }, 1))
            val previousHash = assertNotNull(second.previousSourceHash(bookKey))
            assertContentEquals(hash.hash, previousHash.hash)
            assertEquals(7, previousHash.lastSeenVersion)
        } finally {
            second.close()
        }
    }
}
