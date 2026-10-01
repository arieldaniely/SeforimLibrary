package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import kotlin.test.Test
import kotlin.test.assertEquals

class SefariaBlacklistsTest {
    @Test
    fun reportKeepsEveryBlacklistedTitle() {
        val payloads = (1..7).map { index -> payload("ספר $index") }
        val blacklists = SefariaBlacklists(
            authorKeys = emptySet(),
            bookTitleKeys = payloads.mapNotNull { normalizeTitleKey(it.heTitle) }.toSet(),
            bookPathKeys = emptySet(),
        )

        val result = filterBlacklistedPayloads(payloads, blacklists)

        assertEquals(7, result.skippedByBook)
        assertEquals(payloads.map { it.heTitle }, result.skippedBookExamples)
    }

    private fun payload(title: String) = BookPayload(
        heTitle = title,
        enTitle = title,
        categoriesHe = listOf("בדיקה"),
        lines = emptyList(),
        refEntries = emptyList(),
        headings = emptyList(),
        authors = emptyList(),
        description = null,
        pubDates = emptyList(),
        altStructures = emptyList(),
    )
}
