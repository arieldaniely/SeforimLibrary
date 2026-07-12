package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.github.kdroidfilter.seforimlibrary.common.countVisibleChars
import io.github.kdroidfilter.seforimlibrary.common.ids.IdAllocatorBindings
import io.github.kdroidfilter.seforimlibrary.common.ids.InMemoryIdAllocator
import io.github.kdroidfilter.seforimlibrary.core.models.ConnectionType
import io.github.kdroidfilter.seforimlibrary.core.models.Link
import io.github.kdroidfilter.seforimlibrary.core.models.LinkAnchor
import io.github.kdroidfilter.seforimlibrary.core.models.LinkRange
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Phase-2 LINKER importer (stage 5): turns LinkerToOtzaria's ref-based artifacts into
 * clickable, delta-stable in-app links.
 *
 * For each artifact record `(book_key, line_index, start, end, target_ref)`:
 *  • target ← `resolveRefs(target_ref)` (the SAME resolver Sefaria links use) → a RefEntry;
 *    its (path, lineIndex) maps to a lineId via the sidecar → `targetLineId`.
 *  • source ← `BookKey(source_name, canonical_he_title)` → `bookId` (built from the DB:
 *    `source.name` + `COALESCE(book.heRef, book.title)`), then `(bookId, line_index)` → lineId.
 *  • write `link(source→target, LINKER)` (stable id via the allocator) + a `link_anchor`
 *    (side=0) whose visible-char range is the whole citation phrase (raw start/end mapped
 *    through `countVisibleChars`). No `<a href>` touches the text — the app renders the
 *    anchor as an internal link.
 *
 * Delta-robustness comes for free: target refs are resolved fresh each build against
 * heRef-keyed line ids, and link ids are allocated stably, so a Sefaria content update
 * costs zero churn. The SOURCE side (whose byte offsets can't be re-resolved) is guarded by
 * `source_hash`: if a source line changed since the snapshot the offsets were computed on,
 * the record is safe-dropped and recovered on the next re-link. LINKER is excluded from the
 * SOURCE virtual view by the repository's allow-list, so no reverse-view wiring is needed.
 *
 * Usage:
 *   ./gradlew :sefariasqlite:generateLinkerLinks \
 *     -PseforimDb=/path/seforim.db -PlinkerArtifacts=/unpacked/artifacts -PlinkerSidecar=/sidecar.tsv
 */
fun main(args: Array<String>) = runBlocking {
    Logger.setMinSeverity(Severity.Info)
    val logger = Logger.withTag("GenerateLinkerLinks")

    val dbPath = prop("seforimDb", args.getOrNull(0)) ?: Paths.get("build", "seforim.db").toString()
    val artifactsDir = prop("linkerArtifacts", null)
        ?: error("linkerArtifacts (unpacked artifacts/ dir) is required")
    val sidecarPath = prop("linkerSidecar", null)
        ?: error("linkerSidecar (TSV from the Sefaria import) is required")

    val driver = JdbcSqliteDriver(url = "jdbc:sqlite:$dbPath")
    val repository = SeforimRepository(dbPath, driver)
    val buildStatePath = Paths.get(prop("buildStatePath", null) ?: "$dbPath.buildstate")
    val prev = buildStatePath.takeIf { Files.exists(it) }
    val allocator = InMemoryIdAllocator.load(prev, Logger.withTag("IdAllocator"))
    val bindings = IdAllocatorBindings(allocator, repository)
    ConnectionType.values().forEach { bindings.upsertConnectionType(it.name) }
    val ctLinker = bindings.upsertConnectionType(ConnectionType.LINKER.name)

    // Havrouta links sit at implicit rowids ABOVE the persisted link counter (they are
    // deleted+recreated each build outside the allocator), so allocating fresh LINKER
    // ids straight from the counter would collide with them: the link INSERT OR IGNOREs
    // away and its anchors attach to the Havrouta row. Raise the counter past the DB.
    run {
        var maxLinkId = 0L
        driver.executeQuery(null, "SELECT COALESCE(MAX(id), 0) FROM link",
            { c -> if (c.next().value) maxLinkId = c.getLong(0) ?: 0L; QueryResult.Value(Unit) }, 0)
        allocator.ensureCounterAtLeast(io.github.kdroidfilter.seforimlibrary.common.buildstate.IdTable.LINK, maxLinkId + 1)
    }

    try {
        repository.executeRawQuery("PRAGMA foreign_keys = OFF")
        repository.executeRawQuery("PRAGMA synchronous = OFF")
        repository.executeRawQuery("PRAGMA journal_mode = OFF")

        // ── sidecar → refsByCanonical / refsByBase + (path,lineIndex)→lineId ──
        logger.i { "Loading sidecar…" }
        val allRefs = ArrayList<RefEntry>()
        val lineIdByRefKey = HashMap<Pair<String, Int>, Long>()
        File(sidecarPath).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val f = line.split('\t')
                if (f.size < 5) continue
                val ref = f[0]; val heRef = f[1]; val path = f[2]
                val lineIndex = f[3].toInt(); val lineId = f[4].toLong()
                allRefs.add(RefEntry(ref, heRef, path, lineIndex))
                lineIdByRefKey[path to lineIndex] = lineId
            }
        }
        val refsByCanonical = allRefs.groupBy { canonicalCitation(it.ref) }
        val refsByBase = HashMap<String, RefEntry>()
        // lastByBase — the base's LAST segment: closes the scope of section-level
        // citations (a whole amud/chapter) so the app can show/load the full range.
        val lastByBase = HashMap<String, RefEntry>()
        for (e in allRefs) {
            val base = canonicalBase(e.ref)
            val existing = refsByBase[base]
            if (existing == null || e.lineIndex < existing.lineIndex) refsByBase[base] = e
            val existingLast = lastByBase[base]
            if (existingLast == null || e.lineIndex > existingLast.lineIndex) lastByBase[base] = e
        }
        logger.i { "Sidecar: ${allRefs.size} refs, ${refsByCanonical.size} canonical keys" }

        // ── DB identity maps: BookKey→bookId and (bookId,lineIndex)→lineId ──
        val bookIdByKey = HashMap<Pair<String, String>, Long>()
        driver.executeQuery(null,
            "SELECT b.id, s.name, COALESCE(b.heRef, b.title) FROM book b JOIN source s ON b.sourceId = s.id",
            { c ->
                while (c.next().value) {
                    bookIdByKey[(c.getString(1) ?: "") to (c.getString(2) ?: "")] = c.getLong(0)!!
                }
                QueryResult.Value(Unit)
            }, 0)
        // (bookId,lineIndex)→lineId for the source side, plus the reverse lineId→(bookId,lineIndex).
        // METADATA ONLY — never `content`: on the real DB (≈5.9M lines / 2.4GiB text) loading all
        // content OOMs. `content` is fetched lazily below, only for the few lines that get anchors.
        val lineIdByBookLine = HashMap<Pair<Long, Int>, Long>()
        val lineMeta = HashMap<Long, Pair<Long, Int>>()      // lineId → (bookId, 0-based lineIndex)
        driver.executeQuery(null, "SELECT id, bookId, lineIndex FROM line",
            { c ->
                while (c.next().value) {
                    val id = c.getLong(0)!!; val bookId = c.getLong(1)!!; val idx = c.getLong(2)!!.toInt()
                    lineIdByBookLine[bookId to idx] = id
                    lineMeta[id] = bookId to idx
                }
                QueryResult.Value(Unit)
            }, 0)
        logger.i { "DB maps: ${bookIdByKey.size} books, ${lineIdByBookLine.size} lines" }

        // Content is fetched per source line only when a link/anchor needs it. Cache exactly ONE
        // line: artifact records are written per book in line order, so consecutive records hit
        // the same line; an unbounded cache holds every linked line's text and OOMs (~GBs).
        var cachedLineId = Long.MIN_VALUE
        var cachedContent = ""
        fun contentFor(id: Long): String {
            if (id != cachedLineId) {
                var s = ""
                driver.executeQuery(1001, "SELECT content FROM line WHERE id = ?",
                    { c -> if (c.next().value) s = c.getString(0) ?: ""; QueryResult.Value(Unit) },
                    1) { bindLong(0, id) }
                cachedLineId = id; cachedContent = s
            }
            return cachedContent
        }
        // linkerContentHash MUST match linker_artifact.content_hash so the source-drift guard agrees.
        fun hashFor(id: Long): String = linkerContentHash(contentFor(id))

        // ── walk artifacts → links + anchors ──
        val json = Json { ignoreUnknownKeys = true }
        val linkBatch = ArrayList<Link>()
        val anchorBatch = ArrayList<LinkAnchor>()
        val linkIdByPair = HashMap<Pair<Long, Long>, Long>()   // (src,tgt) → linkId (dedup links)
        val seenAnchor = HashSet<Triple<Long, Int, Int>>()      // (linkId, cs, ce) → dedup anchors
        // linkId → (endLineId, endLineIndex): target-side scope end for multi-line
        // citations (a whole amud/section or a dashed range). Written as link_range
        // side=1 so the app shows the range in the title and loads the full content
        // in the preview. Widest end wins when the same pair is cited at two scopes.
        // No link_coverage rows — target-page surfacing stays first-line-only.
        val rangeEndByLink = HashMap<Long, Pair<Long, Int>>()
        var links = 0; var anchors = 0; var unresolvedTarget = 0; var unmappedSource = 0; var staleSource = 0

        suspend fun flush() {
            if (linkBatch.isNotEmpty()) { repository.insertLinksBatch(linkBatch); linkBatch.clear() }
            if (anchorBatch.isNotEmpty()) { repository.insertLinkAnchorsBatch(anchorBatch); anchorBatch.clear() }
        }

        val files = File(artifactsDir).walkTopDown().filter { it.isFile && it.extension == "jsonl" }.toList()
        logger.i { "Processing ${files.size} artifact files…" }
        for (file in files) {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val rec = json.decodeFromString<ArtifactRecord>(line)
                    // target: resolveRefs → RefEntry → lineId (sidecar) → (bookId, 0-based idx) via lineMeta
                    val tgtEntry = resolveRefs(rec.target_ref, refsByCanonical, refsByBase).firstOrNull()
                    val tgtLineId = tgtEntry?.let { lineIdByRefKey[it.path to it.lineIndex] }
                    val tgtMeta = tgtLineId?.let { lineMeta[it] }
                    if (tgtLineId == null || tgtMeta == null) { unresolvedTarget++; continue }
                    // source: BookKey → bookId → (bookId, line_index) → lineId
                    val srcBookId = bookIdByKey[rec.book_key.source_name to rec.book_key.canonical_he_title]
                    val srcLineId = srcBookId?.let { lineIdByBookLine[it to rec.line_index] }
                    if (srcBookId == null || srcLineId == null) { unmappedSource++; continue }
                    if (srcLineId == tgtLineId) continue

                    // Source-drift guard: the offsets index the snapshot the linker ran on. If this
                    // source line changed since (cross-cycle drift), the offsets are untrustworthy —
                    // safe-drop the whole record (link + anchor); it recovers on the next re-link.
                    val content = contentFor(srcLineId)
                    if (rec.source_hash != null && hashFor(srcLineId) != rec.source_hash) { staleSource++; continue }

                    // One link per (src,tgt); reuse its id for repeated citations of the same ref.
                    val pair = srcLineId to tgtLineId
                    val linkId = linkIdByPair.getOrPut(pair) {
                        val id = allocator.linkId(srcLineId, tgtLineId, ctLinker)
                        linkBatch.add(Link(
                            id = id, sourceBookId = srcBookId, targetBookId = tgtMeta.first,
                            sourceLineId = srcLineId, targetLineId = tgtLineId,
                            targetLineIndex = tgtMeta.second, connectionType = ConnectionType.LINKER,
                        ))
                        links++
                        id
                    }
                    // A separate clickable anchor for EACH citation occurrence (same line may cite
                    // the same ref twice at different offsets → one link, two anchors).
                    val cs = countVisibleChars(content, rec.start)
                    val ce = countVisibleChars(content, rec.end)
                    if (ce > cs && seenAnchor.add(Triple(linkId, cs, ce))) {
                        anchorBatch.add(LinkAnchor(linkId = linkId, side = 0, charStart = cs, charEnd = ce))
                        anchors++
                    }

                    // Multi-line citation scope (whole amud/section or dashed range) →
                    // remember its end for a link_range(side=1) row.
                    val endEntry = resolveRefEnd(rec.target_ref, refsByCanonical, refsByBase, lastByBase)
                    if (endEntry != null &&
                        endEntry.path == tgtEntry.path &&
                        endEntry.lineIndex > tgtEntry.lineIndex
                    ) {
                        val endLineId = lineIdByRefKey[endEntry.path to endEntry.lineIndex]
                        val endMeta = endLineId?.let { lineMeta[it] }
                        if (endLineId != null && endMeta != null) {
                            val prev = rangeEndByLink[linkId]
                            if (prev == null || endMeta.second > prev.second) {
                                rangeEndByLink[linkId] = endLineId to endMeta.second
                            }
                        }
                    }
                    if (linkBatch.size >= 5000) flush()
                }
            }
        }
        flush()
        if (rangeEndByLink.isNotEmpty()) {
            val ranges = rangeEndByLink.map { (linkId, end) ->
                LinkRange(linkId = linkId, side = 1, endLineId = end.first, endLineIndex = end.second)
            }
            ranges.chunked(5000).forEach { repository.insertLinkRangesBatch(it) }
        }
        logger.i { "LINKER: $links links, $anchors anchors, ${rangeEndByLink.size} target ranges (unresolved target: $unresolvedTarget, unmapped source: $unmappedSource, stale source: $staleSource)" }

        // Serial-pipeline invariant (-PlinkerStrict): the linker just ran on THIS build's
        // snapshot, so every record's source line must exist and match its stamped hash.
        // A violation means the artifacts came from some other snapshot — fail the build.
        if (prop("linkerStrict", null)?.toBoolean() == true) {
            check(unmappedSource == 0 && staleSource == 0) {
                "linkerStrict: unmapped source=$unmappedSource, stale source=$staleSource — " +
                    "artifacts do not match this build's snapshot"
            }
        }

        // LINKER links were inserted AFTER the Sefaria import's book_has_links pass, so refresh the
        // source/target flags — additively (never reset to 0) — or a book that gained ONLY linker
        // links would still read hasSourceLinks=0 and the app would treat it as link-less.
        repository.executeRawQuery(
            "INSERT OR IGNORE INTO book_has_links(bookId, hasSourceLinks, hasTargetLinks) SELECT id, 0, 0 FROM book")
        repository.executeRawQuery(
            "UPDATE book_has_links SET hasSourceLinks=1 WHERE bookId IN (SELECT DISTINCT sourceBookId FROM link)")
        repository.executeRawQuery(
            "UPDATE book_has_links SET hasTargetLinks=1 WHERE bookId IN (SELECT DISTINCT targetBookId FROM link)")

        repository.executeRawQuery("PRAGMA foreign_keys = ON")
        repository.executeRawQuery("PRAGMA synchronous = NORMAL")
        repository.executeRawQuery("PRAGMA journal_mode = WAL")
        runCatching {
            allocator.snapshotTo(buildStatePath, extraMeta = mapOf("generator" to "linkerlinks"))
        }.onFailure { logger.w(it) { "Failed to write build_state" } }
        Unit
    } catch (e: Exception) {
        logger.e(e) { "Error generating LINKER links" }
        throw e
    } finally {
        repository.close()
    }
}

/** SHA-1(UTF-8(content)) truncated to 16 hex chars. MUST equal linker_artifact.content_hash. */
internal fun linkerContentHash(content: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-1").digest(content.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) { val v = b.toInt() and 0xff; sb.append("0123456789abcdef"[v ushr 4]); sb.append("0123456789abcdef"[v and 0xf]) }
    return sb.substring(0, 16)
}

@Serializable
private data class ArtifactBookKey(val source_name: String, val canonical_he_title: String)

@Serializable
private data class ArtifactRecord(
    val book_key: ArtifactBookKey,
    val line_index: Int,
    val start: Int,
    val end: Int,
    val target_ref: String,
    val line_index_base: Int = 0,
    val source_path: String? = null,
    val source_hash: String? = null,
)

private fun prop(name: String, fallback: String?): String? =
    System.getProperty(name) ?: System.getenv(name.uppercase()) ?: fallback
