package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SefariaCategoryMetadataTest {
    @Test
    fun readsDescriptionsUsingTheFullHebrewCategoryPath() {
        val root = Files.createTempDirectory("sefaria-categories")
        Files.writeString(root.resolve("table_of_contents.json"), tocJson)

        val metadata = parseTableOfContentsMetadata(root, Json, Logger.withTag("CategoryMetadataTest"))

        assertEquals("סיכום", metadata.categoryDescriptions["תלמוד/בבלי"]?.heShortDesc)
        assertEquals("תיאור מלא", metadata.categoryDescriptions["תלמוד/בבלי"]?.heDesc)
    }

    private val tocJson = """
        [
          {
            "category": "Talmud",
            "heCategory": "תלמוד",
            "order": 1,
            "contents": [
              {
                "category": "Bavli",
                "heCategory": "בבלי",
                "order": 2,
                "heShortDesc": "סיכום",
                "heDesc": "תיאור מלא",
                "contents": []
              }
            ]
          }
        ]
    """.trimIndent()
}
