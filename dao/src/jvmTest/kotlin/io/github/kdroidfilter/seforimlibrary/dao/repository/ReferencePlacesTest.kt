package io.github.kdroidfilter.seforimlibrary.dao.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.kdroidfilter.seforimlibrary.core.models.Book
import io.github.kdroidfilter.seforimlibrary.core.models.Category
import io.github.kdroidfilter.seforimlibrary.core.models.ConnectionType
import io.github.kdroidfilter.seforimlibrary.core.models.Line
import io.github.kdroidfilter.seforimlibrary.core.models.Link
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferencePlacesTest {
    @Test
    fun referencesIncludeBothDirectionsAndSpecialKindsWithoutSelfLinks() =
        runBlocking {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
                val repository = SeforimRepository(":memory:", driver)
                val source = repository.insertSource("fixture")
                val category = repository.insertCategory(Category(title = "קטגוריה", level = 0, order = 1))
                val book = repository.insertBook(Book(categoryId = category, sourceId = source, title = "ספר", order = 1f))
                val lines = (0..4).map { repository.insertLine(Line(bookId = book, lineIndex = it, content = "שורה $it")) }

                fun link(
                    from: Int,
                    to: Int,
                    type: ConnectionType,
                ) = Link(
                    sourceBookId = book,
                    targetBookId = book,
                    sourceLineId = lines[from],
                    targetLineId = lines[to],
                    targetLineIndex = to,
                    connectionType = type,
                )
                repository.insertLink(link(0, 1, ConnectionType.REFERENCE))
                repository.insertLink(link(2, 0, ConnectionType.REFERENCE))
                repository.insertLink(link(0, 3, ConnectionType.EIN_MISHPAT))
                repository.insertLink(link(4, 0, ConnectionType.MESORAT_HASHAS))
                repository.insertLink(link(0, 0, ConnectionType.OTHER))
                val references = repository.getReferencesForLines(listOf(lines[0]))
                assertEquals(lines.drop(1).toSet(), references.map { it.line.id }.toSet())
                assertTrue(references.all { it.fromLineId == lines[0] && it.bookTitle == "ספר" })
                assertTrue(repository.getReferencesForLines(emptyList()).isEmpty())
            }
        }
}
