package io.github.kdroidfilter.seforimlibrary.search

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    ): SearchSession? {
        val baseSession = base.openSession(query, near, bookFilter, categoryFilter, bookIds, lineIds, baseBookOnly)
        val personalSession = personalDelegate?.openSession(
            query, near, bookFilter, categoryFilter, bookIds, lineIds, baseBookOnly,
        )
        return when {
            baseSession == null -> personalSession
            personalSession == null -> baseSession
            else -> MergedSearchSession(baseSession, personalSession)
        }
    }

    override fun searchBooksByTitlePrefix(query: String, limit: Int): List<Long> =
        buildList {
            addAll(base.searchBooksByTitlePrefix(query, limit))
            addAll(personalDelegate?.searchBooksByTitlePrefix(query, limit).orEmpty())
        }.distinct().take(limit)

    override fun buildSnippet(rawText: String, query: String, near: Int): String =
        base.buildSnippet(rawText, query, near)

    override fun buildHighlightTerms(query: String): List<String> = base.buildHighlightTerms(query)

    override suspend fun semanticSpan(query: String, text: String): String? =
        base.semanticSpan(query, text)

    override suspend fun semanticFind(query: String, bookId: Long, limit: Int): List<Long> =
        base.semanticFind(query, bookId, limit)

    override suspend fun denseReady(): Boolean = base.denseReady()

    override fun computeFacets(
        query: String,
        near: Int,
        bookFilter: Long?,
        categoryFilter: Long?,
        bookIds: Collection<Long>?,
        lineIds: Collection<Long>?,
        baseBookOnly: Boolean,
    ): SearchFacets? {
        val first = base.computeFacets(query, near, bookFilter, categoryFilter, bookIds, lineIds, baseBookOnly)
        val second = personalDelegate?.computeFacets(
            query, near, bookFilter, categoryFilter, bookIds, lineIds, baseBookOnly,
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
) : SearchSession {
    private val firstBuffer = ArrayDeque<LineHit>()
    private val secondBuffer = ArrayDeque<LineHit>()
    private var firstFinished = false
    private var secondFinished = false
    private var firstTotal = 0L
    private var secondTotal = 0L

    override suspend fun nextPage(limit: Int): SearchPage? = coroutineScope {
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
        SearchPage(hits, firstTotal + secondTotal, last)
    }

    private suspend fun fillBoth(limit: Int) = coroutineScope {
        val firstFill = async { fill(first, firstBuffer, limit, isFirst = true) }
        val secondFill = async { fill(second, secondBuffer, limit, isFirst = false) }
        firstFill.await()
        secondFill.await()
    }

    private suspend fun fill(
        session: SearchSession,
        buffer: ArrayDeque<LineHit>,
        limit: Int,
        isFirst: Boolean,
    ) {
        val finished = if (isFirst) firstFinished else secondFinished
        if (finished || buffer.isNotEmpty()) return
        val page = session.nextPage(limit)
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
