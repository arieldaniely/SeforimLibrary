package io.github.kdroidfilter.seforimlibrary.otzariasqlite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IncrementalImportNormalizationTest {
    @Test
    fun `category identity includes the complete parent path`() {
        val tanakhProphets = categoryCanonicalPath(
            canonicalParentPath = listOf("תנ״ך"),
            normalizedSegments = listOf("נביאים"),
            segmentIndex = 0,
        )
        val kliYakarProphets = categoryCanonicalPath(
            canonicalParentPath = listOf("תנ״ך", "כלי יקר"),
            normalizedSegments = listOf("נביאים"),
            segmentIndex = 0,
        )

        assertEquals(listOf("תנ״ך", "נביאים"), tanakhProphets)
        assertNotEquals(tanakhProphets, kliYakarProphets)
    }

    @Test
    fun `supplemental dependant link is stored base to dependant`() {
        val oriented = orientOtzariaLink(
            sourceBookId = 20,
            targetBookId = 10,
            sourceLineId = 200,
            targetLineId = 100,
            sourceLineIndex = 7,
            targetLineIndex = 3,
            sourceIsDependent = true,
        )

        assertEquals(10L, oriented.sourceBookId)
        assertEquals(20L, oriented.targetBookId)
        assertEquals(100L, oriented.sourceLineId)
        assertEquals(200L, oriented.targetLineId)
        assertEquals(7, oriented.targetLineIndex)
    }

    @Test
    fun `ordinary Otzaria link keeps its file direction`() {
        val oriented = orientOtzariaLink(
            sourceBookId = 10,
            targetBookId = 20,
            sourceLineId = 100,
            targetLineId = 200,
            sourceLineIndex = 3,
            targetLineIndex = 7,
            sourceIsDependent = false,
        )

        assertEquals(10L, oriented.sourceBookId)
        assertEquals(20L, oriented.targetBookId)
        assertEquals(100L, oriented.sourceLineId)
        assertEquals(200L, oriented.targetLineId)
        assertEquals(7, oriented.targetLineIndex)
    }
}
