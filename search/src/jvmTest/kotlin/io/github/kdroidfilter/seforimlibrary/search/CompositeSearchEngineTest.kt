package io.github.kdroidfilter.seforimlibrary.search

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompositeSearchEngineTest {
    @Test
    fun mergesPagesByScoreAndCombinesFacets() = runTest {
        val base = FakeEngine(
            hits = listOf(hit(1, 10f), hit(2, 4f)),
            facets = SearchFacets(2, mapOf(7L to 2), mapOf(1L to 1)),
        )
        val personal = FakeEngine(
            hits = listOf(hit(-1, 8f), hit(-2, 2f)),
            facets = SearchFacets(2, mapOf(7L to 1, -7L to 2), mapOf(-1L to 1)),
        )
        val engine = CompositeSearchEngine(base, personal)

        val session = engine.openSession("שלום")!!
        val first = session.nextPage(3)!!
        val second = session.nextPage(3)!!
        assertEquals(listOf(1L, -1L, 2L), first.hits.map(LineHit::bookId))
        assertEquals(listOf(-2L), second.hits.map(LineHit::bookId))
        assertTrue(second.isLastPage)

        val facets = engine.computeFacets("שלום")!!
        assertEquals(4, facets.totalHits)
        assertEquals(3, facets.categoryCounts[7L])
        assertEquals(2, facets.categoryCounts[-7L])
    }

    private fun hit(id: Long, score: Float) = LineHit(id, "book$id", id, 0, "", score, "")

    private class FakeEngine(
        private val hits: List<LineHit>,
        private val facets: SearchFacets,
    ) : SearchEngine {
        override fun openSession(
            query: String, near: Int, bookFilter: Long?, categoryFilter: Long?, bookIds: Collection<Long>?,
            lineIds: Collection<Long>?, baseBookOnly: Boolean,
        ): SearchSession = object : SearchSession {
            private var offset = 0
            override suspend fun nextPage(limit: Int): SearchPage? {
                if (offset >= hits.size) return null
                val page = hits.drop(offset).take(limit)
                offset += page.size
                return SearchPage(page, hits.size.toLong(), offset >= hits.size)
            }
            override fun close() = Unit
        }

        override fun searchBooksByTitlePrefix(query: String, limit: Int): List<Long> = hits.map(LineHit::bookId).take(limit)
        override fun buildSnippet(rawText: String, query: String, near: Int): String = rawText
        override fun buildHighlightTerms(query: String): List<String> = emptyList()
        override fun computeFacets(
            query: String, near: Int, bookFilter: Long?, categoryFilter: Long?, bookIds: Collection<Long>?,
            lineIds: Collection<Long>?, baseBookOnly: Boolean,
        ): SearchFacets = facets
        override fun close() = Unit
    }
}
