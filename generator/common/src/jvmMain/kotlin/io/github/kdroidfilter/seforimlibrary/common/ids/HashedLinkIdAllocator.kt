package io.github.kdroidfilter.seforimlibrary.common.ids

import io.github.kdroidfilter.seforimlibrary.common.buildstate.BookKey
import io.github.kdroidfilter.seforimlibrary.common.buildstate.BookSourceHash
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Minimal allocator for generated, source-local links.
 *
 * Positive IDs belong to the regular build-state allocator. A SHA-256-derived
 * negative ID is deterministic, bounded-memory, and disjoint from that space.
 * Connection types are resolved from the already-built database by the caller.
 */
class HashedLinkIdAllocator(
    private val connectionTypeIds: Map<String, Long>,
) : IdAllocator {
    override fun connectionTypeId(name: String): Long =
        connectionTypeIds[name] ?: error("Unknown connection type '$name'")

    override fun linkId(srcLineId: Long, tgtLineId: Long, connectionTypeId: Long): Long {
        val bytes = ByteBuffer.allocate(Long.SIZE_BYTES * 3)
            .putLong(srcLineId)
            .putLong(tgtLineId)
            .putLong(connectionTypeId)
            .array()
        val positive = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes))
            .long and Long.MAX_VALUE
        return if (positive == 0L) Long.MIN_VALUE + 1 else -positive
    }

    override fun sourceId(name: String): Long = unsupported()
    override fun authorId(name: String): Long = unsupported()
    override fun topicId(name: String): Long = unsupported()
    override fun pubPlaceId(name: String): Long = unsupported()
    override fun pubDateId(date: String): Long = unsupported()
    override fun categoryId(canonicalPath: String): Long = unsupported()
    override fun tocTextId(text: String): Long = unsupported()
    override fun bookId(sourceName: String, canonicalHeTitle: String): Long = unsupported()
    override fun lineId(bookId: Long, contentHash: ByteArray, occurrenceIdx: Int): Long = unsupported()
    override fun tocEntryId(bookId: Long, ancestorPath: String): Long = unsupported()
    override fun altTocStructureId(bookId: Long, key: String): Long = unsupported()
    override fun altTocEntryId(structureId: Long, ancestorPath: String): Long = unsupported()
    override fun peekBookId(sourceName: String, canonicalHeTitle: String): Long? = unsupported()
    override fun registerBookAlias(oldKey: BookKey, newKey: BookKey, atVersion: Int) = unsupported<Unit>()
    override fun recordSourceHash(key: BookKey, sourceHash: BookSourceHash) = unsupported<Unit>()
    override fun previousSourceHash(key: BookKey): BookSourceHash? = unsupported()
    override fun stats(): AllocatorStats = AllocatorStats(emptyMap())
    override fun snapshotTo(target: Path, extraMeta: Map<String, String>) = Unit

    private fun <T> unsupported(): T = error("HashedLinkIdAllocator only allocates connection types and links")
}
