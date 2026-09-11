package io.github.kdroidfilter.seforimlibrary.common.ids

import co.touchlab.kermit.Logger
import io.github.kdroidfilter.seforimlibrary.common.buildstate.BookKey
import io.github.kdroidfilter.seforimlibrary.common.buildstate.BookSourceHash
import io.github.kdroidfilter.seforimlibrary.common.buildstate.BuildStateSchema
import io.github.kdroidfilter.seforimlibrary.common.buildstate.IdTable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.atomic.AtomicLong

/**
 * Disk-backed stable-ID allocator for memory-constrained generation.
 *
 * Natural keys live in the existing build-state SQLite schema and are looked
 * up through its indexes. Only the small counter/stat maps remain in memory.
 * Writes are committed in bounded batches, to avoid both a multi-gigabyte JVM
 * map and an unbounded SQLite transaction.
 */
class SqliteIdAllocator private constructor(
    private val statePath: Path,
    private val connection: Connection,
    private val logger: Logger,
) : IdAllocator, AutoCloseable {
    private val counters = IdTable.values().associateWith { AtomicLong(nextId(it)) }
    private val reused = IdTable.values().associateWith { AtomicLong(0) }
    private val fresh = IdTable.values().associateWith { AtomicLong(0) }
    private var writesSinceCommit = 0
    private var closed = false

    private val selectLookup = connection.prepareStatement(
        "SELECT id FROM id_lookup WHERE kind = ? AND natural_key = ?",
    )
    private val insertLookup = connection.prepareStatement(
        "INSERT INTO id_lookup(kind, natural_key, id) VALUES (?, ?, ?)",
    )
    private val selectBook = connection.prepareStatement(
        "SELECT id FROM id_book WHERE source_name = ? AND canonical_he_title = ?",
    )
    private val selectBookAlias = connection.prepareStatement(
        """
        SELECT b.id
        FROM book_aliases a
        JOIN id_book b
          ON b.source_name = a.new_source_name
         AND b.canonical_he_title = a.new_canonical_he_title
        WHERE a.old_source_name = ? AND a.old_canonical_he_title = ?
        """.trimIndent(),
    )
    private val insertBook = connection.prepareStatement(
        "INSERT INTO id_book(source_name, canonical_he_title, id) VALUES (?, ?, ?)",
    )
    private val selectLine = connection.prepareStatement(
        "SELECT id FROM id_line WHERE book_id = ? AND content_hash = ? AND occurrence_idx = ?",
    )
    private val insertLine = connection.prepareStatement(
        "INSERT INTO id_line(book_id, content_hash, occurrence_idx, id) VALUES (?, ?, ?, ?)",
    )
    private val selectToc = connection.prepareStatement(
        "SELECT id FROM id_toc_entry WHERE book_id = ? AND ancestor_path = ?",
    )
    private val insertToc = connection.prepareStatement(
        "INSERT INTO id_toc_entry(book_id, ancestor_path, id) VALUES (?, ?, ?)",
    )
    private val selectAltStructure = connection.prepareStatement(
        "SELECT id FROM id_alt_toc_structure WHERE book_id = ? AND key = ?",
    )
    private val insertAltStructure = connection.prepareStatement(
        "INSERT INTO id_alt_toc_structure(book_id, key, id) VALUES (?, ?, ?)",
    )
    private val selectAltEntry = connection.prepareStatement(
        "SELECT id FROM id_alt_toc_entry WHERE structure_id = ? AND ancestor_path = ?",
    )
    private val insertAltEntry = connection.prepareStatement(
        "INSERT INTO id_alt_toc_entry(structure_id, ancestor_path, id) VALUES (?, ?, ?)",
    )
    private val selectLink = connection.prepareStatement(
        """
        SELECT id FROM id_link
        WHERE src_line_id = ? AND tgt_line_id = ? AND connection_type_id = ?
        """.trimIndent(),
    )
    private val insertLink = connection.prepareStatement(
        """
        INSERT INTO id_link(src_line_id, tgt_line_id, connection_type_id, id)
        VALUES (?, ?, ?, ?)
        """.trimIndent(),
    )

    @Synchronized
    override fun sourceId(name: String): Long = lookupId(IdTable.SOURCE, name)

    @Synchronized
    override fun authorId(name: String): Long = lookupId(IdTable.AUTHOR, name)

    @Synchronized
    override fun topicId(name: String): Long = lookupId(IdTable.TOPIC, name)

    @Synchronized
    override fun pubPlaceId(name: String): Long = lookupId(IdTable.PUB_PLACE, name)

    @Synchronized
    override fun pubDateId(date: String): Long = lookupId(IdTable.PUB_DATE, date)

    @Synchronized
    override fun connectionTypeId(name: String): Long = lookupId(IdTable.CONNECTION_TYPE, name)

    @Synchronized
    override fun categoryId(canonicalPath: String): Long = lookupId(IdTable.CATEGORY, canonicalPath)

    @Synchronized
    override fun tocTextId(text: String): Long = lookupId(IdTable.TOC_TEXT, text)

    @Synchronized
    override fun bookId(sourceName: String, canonicalHeTitle: String): Long {
        findBook(selectBook, sourceName, canonicalHeTitle)?.let {
            markReused(IdTable.BOOK)
            return it
        }
        findBook(selectBookAlias, sourceName, canonicalHeTitle)?.let {
            markReused(IdTable.BOOK)
            return it
        }
        val id = freshId(IdTable.BOOK)
        insertBook.bind(sourceName, canonicalHeTitle, id).executeUpdate()
        afterWrite()
        return id
    }

    @Synchronized
    override fun lineId(bookId: Long, contentHash: ByteArray, occurrenceIdx: Int): Long {
        require(contentHash.size == 20) { "contentHash must be 20-byte sha1, got ${contentHash.size}" }
        selectLine.clearParameters()
        selectLine.setLong(1, bookId)
        selectLine.setBytes(2, contentHash)
        selectLine.setInt(3, occurrenceIdx)
        find(selectLine)?.let {
            markReused(IdTable.LINE)
            return it
        }
        val id = freshId(IdTable.LINE)
        insertLine.clearParameters()
        insertLine.setLong(1, bookId)
        insertLine.setBytes(2, contentHash)
        insertLine.setInt(3, occurrenceIdx)
        insertLine.setLong(4, id)
        insertLine.executeUpdate()
        afterWrite()
        return id
    }

    @Synchronized
    override fun tocEntryId(bookId: Long, ancestorPath: String): Long =
        twoPartId(IdTable.TOC_ENTRY, selectToc, insertToc, bookId, ancestorPath)

    @Synchronized
    override fun altTocStructureId(bookId: Long, key: String): Long =
        twoPartId(IdTable.ALT_TOC_STRUCTURE, selectAltStructure, insertAltStructure, bookId, key)

    @Synchronized
    override fun altTocEntryId(structureId: Long, ancestorPath: String): Long =
        twoPartId(IdTable.ALT_TOC_ENTRY, selectAltEntry, insertAltEntry, structureId, ancestorPath)

    @Synchronized
    override fun linkId(srcLineId: Long, tgtLineId: Long, connectionTypeId: Long): Long {
        selectLink.clearParameters()
        selectLink.setLong(1, srcLineId)
        selectLink.setLong(2, tgtLineId)
        selectLink.setLong(3, connectionTypeId)
        find(selectLink)?.let {
            markReused(IdTable.LINK)
            return it
        }
        val id = freshId(IdTable.LINK)
        insertLink.clearParameters()
        insertLink.setLong(1, srcLineId)
        insertLink.setLong(2, tgtLineId)
        insertLink.setLong(3, connectionTypeId)
        insertLink.setLong(4, id)
        insertLink.executeUpdate()
        afterWrite()
        return id
    }

    @Synchronized
    override fun peekBookId(sourceName: String, canonicalHeTitle: String): Long? =
        findBook(selectBook, sourceName, canonicalHeTitle)
            ?: findBook(selectBookAlias, sourceName, canonicalHeTitle)

    @Synchronized
    override fun registerBookAlias(oldKey: BookKey, newKey: BookKey, atVersion: Int) {
        val id = findBook(selectBook, oldKey.sourceName, oldKey.canonicalHeTitle) ?: return
        connection.prepareStatement(
            """
            UPDATE id_book SET source_name = ?, canonical_he_title = ?
            WHERE source_name = ? AND canonical_he_title = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, newKey.sourceName)
            ps.setString(2, newKey.canonicalHeTitle)
            ps.setString(3, oldKey.sourceName)
            ps.setString(4, oldKey.canonicalHeTitle)
            ps.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO book_aliases(
                old_source_name, old_canonical_he_title,
                new_source_name, new_canonical_he_title, detected_at_version
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(old_source_name, old_canonical_he_title) DO UPDATE SET
                new_source_name = excluded.new_source_name,
                new_canonical_he_title = excluded.new_canonical_he_title,
                detected_at_version = excluded.detected_at_version
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, oldKey.sourceName)
            ps.setString(2, oldKey.canonicalHeTitle)
            ps.setString(3, newKey.sourceName)
            ps.setString(4, newKey.canonicalHeTitle)
            ps.setInt(5, atVersion)
            ps.executeUpdate()
        }
        afterWrite(2)
        logger.i { "Registered book alias: $oldKey -> $newKey (id=$id, version=$atVersion)" }
    }

    @Synchronized
    override fun recordSourceHash(key: BookKey, sourceHash: BookSourceHash) {
        connection.prepareStatement(
            """
            INSERT INTO book_source_hashes(
                source_name, canonical_he_title, source_hash, last_seen_version
            ) VALUES (?, ?, ?, ?)
            ON CONFLICT(source_name, canonical_he_title) DO UPDATE SET
                source_hash = excluded.source_hash,
                last_seen_version = excluded.last_seen_version
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, key.sourceName)
            ps.setString(2, key.canonicalHeTitle)
            ps.setBytes(3, sourceHash.hash)
            ps.setInt(4, sourceHash.lastSeenVersion)
            ps.executeUpdate()
        }
        afterWrite()
    }

    @Synchronized
    override fun previousSourceHash(key: BookKey): BookSourceHash? {
        connection.prepareStatement(
            """
            SELECT source_hash, last_seen_version FROM book_source_hashes
            WHERE source_name = ? AND canonical_he_title = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, key.sourceName)
            ps.setString(2, key.canonicalHeTitle)
            ps.executeQuery().use { rs ->
                return if (rs.next()) BookSourceHash(rs.getBytes(1), rs.getInt(2)) else null
            }
        }
    }

    @Synchronized
    override fun stats(): AllocatorStats = AllocatorStats(
        IdTable.values().associateWith { table ->
            val reusedCount = reused.getValue(table).get()
            val freshCount = fresh.getValue(table).get()
            AllocatorStats.TableStats(reusedCount + freshCount, reusedCount, freshCount)
        },
    )

    @Synchronized
    override fun snapshotTo(target: Path, extraMeta: Map<String, String>) {
        writeCounters()
        writeMeta("schema_version", BuildStateSchema.CURRENT_VERSION.toString())
        extraMeta.forEach(::writeMeta)
        connection.commit()
        connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }

        if (target.toAbsolutePath().normalize() != statePath.toAbsolutePath().normalize()) {
            Files.createDirectories(target.toAbsolutePath().parent)
            val temp = target.resolveSibling("${target.fileName}.tmp")
            Files.deleteIfExists(temp)
            Files.copy(statePath, temp, StandardCopyOption.REPLACE_EXISTING)
            runCatching {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        logger.i { "Disk-backed build_state checkpoint completed at $target" }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        runCatching {
            writeCounters()
            connection.commit()
            connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        }
        connection.close()
        closed = true
    }

    private fun lookupId(table: IdTable, key: String): Long {
        val kind = requireNotNull(table.lookupKind)
        selectLookup.clearParameters()
        selectLookup.setString(1, kind)
        selectLookup.setString(2, key)
        find(selectLookup)?.let {
            markReused(table)
            return it
        }
        val id = freshId(table)
        insertLookup.clearParameters()
        insertLookup.setString(1, kind)
        insertLookup.setString(2, key)
        insertLookup.setLong(3, id)
        insertLookup.executeUpdate()
        afterWrite()
        return id
    }

    private fun twoPartId(
        table: IdTable,
        select: PreparedStatement,
        insert: PreparedStatement,
        first: Long,
        second: String,
    ): Long {
        select.clearParameters()
        select.setLong(1, first)
        select.setString(2, second)
        find(select)?.let {
            markReused(table)
            return it
        }
        val id = freshId(table)
        insert.clearParameters()
        insert.setLong(1, first)
        insert.setString(2, second)
        insert.setLong(3, id)
        insert.executeUpdate()
        afterWrite()
        return id
    }

    private fun findBook(statement: PreparedStatement, sourceName: String, title: String): Long? {
        statement.clearParameters()
        statement.setString(1, sourceName)
        statement.setString(2, title)
        return find(statement)
    }

    private fun find(statement: PreparedStatement): Long? =
        statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }

    private fun PreparedStatement.bind(first: String, second: String, id: Long): PreparedStatement = apply {
        clearParameters()
        setString(1, first)
        setString(2, second)
        setLong(3, id)
    }

    private fun markReused(table: IdTable) {
        reused.getValue(table).incrementAndGet()
    }

    private fun freshId(table: IdTable): Long {
        fresh.getValue(table).incrementAndGet()
        return counters.getValue(table).getAndIncrement()
    }

    private fun afterWrite(count: Int = 1) {
        writesSinceCommit += count
        if (writesSinceCommit >= COMMIT_INTERVAL) {
            writeCounters()
            connection.commit()
            writesSinceCommit = 0
        }
    }

    private fun writeCounters() {
        connection.prepareStatement(
            """
            INSERT INTO id_counters(table_name, next_id) VALUES (?, ?)
            ON CONFLICT(table_name) DO UPDATE SET next_id = excluded.next_id
            """.trimIndent(),
        ).use { ps ->
            counters.forEach { (table, next) ->
                ps.setString(1, table.tableName)
                ps.setLong(2, next.get())
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun writeMeta(key: String, value: String) {
        connection.prepareStatement(
            """
            INSERT INTO meta(key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, key)
            ps.setString(2, value)
            ps.executeUpdate()
        }
    }

    private fun nextId(table: IdTable): Long {
        val persisted = connection.prepareStatement(
            "SELECT next_id FROM id_counters WHERE table_name = ?",
        ).use { ps ->
            ps.setString(1, table.tableName)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 1L }
        }
        val maximum = when (table) {
            IdTable.SOURCE,
            IdTable.AUTHOR,
            IdTable.TOPIC,
            IdTable.PUB_PLACE,
            IdTable.PUB_DATE,
            IdTable.CONNECTION_TYPE,
            IdTable.CATEGORY,
            IdTable.TOC_TEXT,
            -> connection.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM id_lookup WHERE kind = ?").use { ps ->
                ps.setString(1, table.lookupKind)
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
            IdTable.BOOK -> maxId("id_book")
            IdTable.LINE -> maxId("id_line")
            IdTable.TOC_ENTRY -> maxId("id_toc_entry")
            IdTable.ALT_TOC_STRUCTURE -> maxId("id_alt_toc_structure")
            IdTable.ALT_TOC_ENTRY -> maxId("id_alt_toc_entry")
            IdTable.LINK -> maxId("id_link")
        }
        return maxOf(1L, persisted, maximum + 1)
    }

    private fun maxId(tableName: String): Long = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM $tableName").use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }

    companion object {
        private const val COMMIT_INTERVAL = 10_000

        fun open(
            previous: Path?,
            working: Path,
            logger: Logger = Logger.withTag("SqliteIdAllocator"),
        ): SqliteIdAllocator {
            val target = working.toAbsolutePath().normalize()
            Files.createDirectories(target.parent)
            Files.deleteIfExists(target)
            Files.deleteIfExists(target.resolveSibling("${target.fileName}-wal"))
            Files.deleteIfExists(target.resolveSibling("${target.fileName}-shm"))
            if (previous != null && Files.exists(previous)) {
                Files.copy(previous, target, StandardCopyOption.REPLACE_EXISTING)
                logger.i { "Copied previous build_state to disk-backed working file $target" }
            }

            Class.forName("org.sqlite.JDBC")
            val connection = DriverManager.getConnection("jdbc:sqlite:$target")
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA temp_store=FILE")
                statement.execute("PRAGMA cache_size=-8192")
                BuildStateSchema.statements.forEach(statement::executeUpdate)
            }
            connection.autoCommit = false
            val schemaVersion = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT value FROM meta WHERE key='schema_version'").use { rs ->
                    if (rs.next()) rs.getString(1).toIntOrNull() else null
                }
            }
            check(schemaVersion == null || schemaVersion <= BuildStateSchema.CURRENT_VERSION) {
                "build_state.db schema_version=$schemaVersion is newer than supported ${BuildStateSchema.CURRENT_VERSION}"
            }
            return SqliteIdAllocator(target, connection, logger)
        }
    }
}
