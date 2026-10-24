package io.github.kdroidfilter.seforimlibrary.search

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Ranking is cheap; database text and snippets are resolved only for the requested page. */
internal data class RankedLine(
    val lineId: Long,
    val bookId: Long,
    val score: Float,
    val lexicalHit: LineHit? = null,
)

internal class RankedSearchResults(
    private val ranking: List<RankedLine>,
    private val resolve: suspend (Long, Long) -> LineHit?,
) {
    private var offset = 0
    private var missing = 0

    suspend fun nextPage(limit: Int, decorate: suspend (List<LineHit>) -> List<LineHit>): SearchPage? {
        require(limit > 0)
        if (offset >= ranking.size) return null
        var end = offset
        var skipped = 0
        val hits = ArrayList<LineHit>(minOf(limit, ranking.size - offset))
        while (end < ranking.size && hits.size < limit) {
            currentCoroutineContext().ensureActive()
            val candidate = ranking[end++]
            val hit = candidate.lexicalHit ?: resolve(candidate.lineId, candidate.bookId)
            if (hit == null) skipped++ else hits += hit.copy(score = candidate.score)
        }
        val decorated = decorate(hits)
        currentCoroutineContext().ensureActive()
        // Publish the cursor only after the entire page succeeds, including snippets.
        offset = end
        missing += skipped
        return SearchPage(decorated, (ranking.size - missing).toLong(), offset >= ranking.size)
    }
}
