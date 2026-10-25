package io.github.kdroidfilter.seforimlibrary.search

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import java.util.ArrayDeque

/**
 * A reloadable two-index search facade. The immutable distribution index remains open while the
 * much smaller personal index can be replaced after an import.
 */
class CompositeSearchEngine(
    private val base: SearchEngine,
    personal: SearchEngine? = null,
) : SearchEngine {
    @Volatile
    private var personalDelegate: SearchEngine? = personal

    suspend fun sharedEmbedder(): SeforimEmbedder? = (base as? HybridSearchEngine)?.sharedEmbedder()

    @Synchronized
    fun replacePersonal(engine: SearchEngine?) {
        val previous = personalDelegate
        personalDelegate = engine
        if (previous !== engine) runCatching { previous?.close() }
    }

    override fun openSession(
        query: String,
        near: Int,
        bookFilter: Long?,
        categoryFilter: Long?,
        bookIds: Collection<Long>?,
        lineIds: Collection<Long>?,
        baseBookOnly: Boolean,
        mode: SearchMode,
    ): SearchSession? {
        val baseSession = base.openSession(query, near, bookFilter, categoryFilter, bookIds, lineIds, baseBookOnly, mode)
        val personalSession = personalDelegate?.openSession(
            query, near, bookFilter, categoryFilter, bookIds, lineIds, baseBookOnly, mode,
        )
        return when {
            baseSession == null -> personalSession
            personalSession == null -> baseSession
            else -> MergedSearchSession(baseSession, personalSession) { hits -> attachSnippets(hits, query, near) }
        }
    }

    override fun searchBooksByTitlePrefix(query: String, limit: Int): List<Long> =
        buildList {
            addAll(base.searchBooksByTitlePrefix(query, limit))
            addAll(personalDelegate?.searchBooksByTitlePrefix(query, limit).orEmpty())
        }.distinct().take(limit)

    override fun buildSnippet(rawText: String, query: String, near: Int): String =
        base.buildSnippet(rawText, query, near)

    override fun highlightRanges(text: String, query: String): List<IntRange> = base.highlightRanges(text, query)

    override fun rangeSnippet(text: String, ranges: List<IntRange>): String = base.rangeSnippet(text, ranges)

    override fun findInBookCandidates(query: String, bookId: Long): LongArray? =
        (if (bookId < 0) personalDelegate else base)?.findInBookCandidates(query, bookId)

    override suspend fun passagesByMeaning(query: String, texts: List<String>): List<String?>? =
        base.passagesByMeaning(query, texts)

    override suspend fun passagesInBookByMeaning(query: String, texts: List<String>): List<String?>? =
        base.passagesInBookByMeaning(query, texts)

    override suspend fun attachSnippets(hits: List<LineHit>, query: String, near: Int): List<LineHit> {
        val personalIds = hits.filter { it.bookId < 0 }.map { it.lineId }.toSet()
        val enriched = base.attachSnippets(hits.filter { it.lineId !in personalIds }, query, near) +
            (personalDelegate?.attachSnippets(hits.filter { it.lineId in personalIds }, query, near).orEmpty())
        val byId = enriched.associateBy { it.lineId }
        return hits.map { byId[it.lineId] ?: it }
    }

    override suspend fun warmUp() {
        base.warmUp()
        personalDelegate?.warmUp()
    }

    override fun buildHighlightTerms(query: String): List<String> = base.buildHighlightTerms(query)

    override suspend fun semanticSpan(query: String, text: String): String? =
        base.semanticSpan(query, text)

    override suspend fun semanticFind(query: String, bookId: Long, limit: Int): List<Long> =
        (if (bookId < 0) personalDelegate else base)?.semanticFind(query, bookId, limit).orEmpty()

    override suspend fun denseReady(): Boolean = base.denseReady()

    override fun computeFacets(
        query: String,
        near: Int,
        bookFilter: Long?,
        categoryFilter: Long?,
        bookIds: Collection<Long>?,
        lineIds: Collection<Long>?,
        baseBookOnly: Boolean,
        mode: SearchMode,
    ): SearchFacets? {
        val first = base.computeFacets(query, near, bookFilter, categoryFilter, bookIds, lineIds, baseBookOnly, mode)
        val second = personalDelegate?.computeFacets(
            query, near, bookFilter, categoryFilter, bookIds, lineIds, baseBookOnly, mode,
        )
        if (first == null) return second
        if (second == null) return first
        return SearchFacets(
            totalHits = first.totalHits + second.totalHits,
            categoryCounts = mergeCounts(first.categoryCounts, second.categoryCounts),
            bookCounts = mergeCounts(first.bookCounts, second.bookCounts),
        )
    }

    override fun close() {
        runCatching { personalDelegate?.close() }
        base.close()
    }

    private fun mergeCounts(a: Map<Long, Int>, b: Map<Long, Int>): Map<Long, Int> =
        HashMap<Long, Int>(a.size + b.size).apply {
            putAll(a)
            b.forEach { (key, count) -> merge(key, count, Int::plus) }
        }
}

private class MergedSearchSession(
    private val first: SearchSession,
    private val second: SearchSession,
    private val attachSnippets: suspend (List<LineHit>) -> List<LineHit>,
) : SearchSession {
    override val effectiveMode: SearchMode? get() = first.effectiveMode
    private val firstBuffer = ArrayDeque<LineHit>()
    private val secondBuffer = ArrayDeque<LineHit>()
    private var firstFinished = false
    private var secondFinished = false
    private var firstTotal = 0L
    private var secondTotal = 0L

    override suspend fun nextPage(limit: Int, snippets: Boolean): SearchPage? = coroutineScope {
        require(limit > 0) { "limit must be positive" }
        fillBoth(limit)
        if (firstBuffer.isEmpty() && secondBuffer.isEmpty()) return@coroutineScope null

        val hits = ArrayList<LineHit>(limit)
        while (hits.size < limit) {
            fillBoth(limit)
            val left = firstBuffer.firstOrNull()
            val right = secondBuffer.firstOrNull()
            val next = when {
                left == null && right == null -> break
                right == null || (left != null && left.score >= right.score) -> firstBuffer.removeFirst()
                else -> secondBuffer.removeFirst()
            }
            hits += next
        }
        val last = firstFinished && secondFinished && firstBuffer.isEmpty() && secondBuffer.isEmpty()
        SearchPage(if (snippets) attachSnippets(hits) else hits, firstTotal + secondTotal, last)
    }

    private suspend fun fillBoth(limit: Int) = coroutineScope {
        val firstFill = async { fill(first, firstBuffer, limit, isFirst = true) }
        val secondFill = async { fill(second, secondBuffer, limit, isFirst = false) }
        firstFill.await()
        secondFill.await()
    }

    override suspend fun refinedSnippets(hits: List<LineHit>): Map<Long, String> = coroutineScope {
        val baseSnippets = async { first.refinedSnippets(hits) }
        val personalSnippets = async { second.refinedSnippets(hits) }
        baseSnippets.await() + personalSnippets.await()
    }

    override fun highlightUpdates(hits: List<LineHit>): Flow<LineHit> =
        merge(first.highlightUpdates(hits), second.highlightUpdates(hits))

    private suspend fun fill(
        session: SearchSession,
        buffer: ArrayDeque<LineHit>,
        limit: Int,
        isFirst: Boolean,
    ) {
        val finished = if (isFirst) firstFinished else secondFinished
        if (finished || buffer.isNotEmpty()) return
        val page = session.nextPage(limit, snippets = false)
        if (page == null) {
            if (isFirst) firstFinished = true else secondFinished = true
            return
        }
        buffer.addAll(page.hits)
        if (isFirst) {
            firstTotal = page.totalHits
            firstFinished = page.isLastPage
        } else {
            secondTotal = page.totalHits
            secondFinished = page.isLastPage
        }
    }

    override fun close() {
        runCatching { first.close() }
        runCatching { second.close() }
    }
}
