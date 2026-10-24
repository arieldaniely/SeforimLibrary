package io.github.kdroidfilter.seforimlibrary.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RankedSearchResultsTest {
    private fun hit(id: Long) = LineHit(10, "book", id, id.toInt(), "snippet", 1f, "line $id")

    @Test
    fun firstPageResolvesOnlyVisibleDenseHitsAndKeepsGlobalRanking() = runTest {
        val resolved = mutableListOf<Long>()
        val ranking = (1L..150L).map { id -> RankedLine(id, 10, 1f / id, if (id == 2L) hit(id) else null) }
        val results = RankedSearchResults(ranking) { id, _ -> resolved += id; hit(id) }
        assertTrue(resolved.isEmpty())
        val first = results.nextPage(25) { it }!!
        assertEquals((1L..25L).toList(), first.hits.map { it.lineId })
        assertEquals((1L..25L).filter { it != 2L }, resolved)
        assertEquals(150L, first.totalHits)
        assertFalse(first.isLastPage)
        val second = results.nextPage(25) { it }!!
        assertEquals((26L..50L).toList(), second.hits.map { it.lineId })
        assertEquals(1f / 26L, second.hits.first().score)
        repeat(4) { results.nextPage(25) { it } }
        assertNull(results.nextPage(25) { it })
        assertEquals(149, resolved.size)
    }

    @Test
    fun missingLinesDoNotCreateShortPagesOrLoseSubsequentResults() = runTest {
        val results = RankedSearchResults((1L..5L).map { RankedLine(it, 10, 1f) }) { id, _ ->
            if (id == 2L) null else hit(id)
        }
        val first = results.nextPage(3) { it }!!
        assertEquals(listOf(1L, 3L, 4L), first.hits.map { it.lineId })
        assertEquals(4L, first.totalHits)
        val last = results.nextPage(3) { it }!!
        assertEquals(listOf(5L), last.hits.map { it.lineId })
        assertTrue(last.isLastPage)
    }

    @Test
    fun cancelledDecorationDoesNotConsumeThePage() = runTest {
        val results = RankedSearchResults((1L..3L).map { RankedLine(it, 10, 1f) }) { id, _ -> hit(id) }
        assertFailsWith<CancellationException> {
            results.nextPage(2) { throw CancellationException("query changed") }
        }
        assertEquals(listOf(1L, 2L), results.nextPage(2) { it }!!.hits.map { it.lineId })
    }
}
