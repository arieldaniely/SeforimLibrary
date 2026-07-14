package io.github.kdroidfilter.seforimlibrary.searchindex.lucene

import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.store.FSDirectory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LuceneIncrementalWriterTest {
    @Test
    fun textWriterAppendKeepsSeedDocumentsAndIsIdempotentPerBook() {
        val indexDir = Files.createTempDirectory("text-index-append")

        LuceneTextIndexWriter(indexDir).use { writer ->
            writer.addLine(
                bookId = 1,
                bookTitle = "seed",
                categoryId = 1,
                lineId = 10,
                lineIndex = 0,
                normalizedText = "seed text",
            )
            writer.commit()
        }

        repeat(2) {
            LuceneTextIndexWriter(indexDir, append = true).use { writer ->
                writer.deleteBookById(2)
                writer.addLine(
                    bookId = 2,
                    bookTitle = "new",
                    categoryId = 1,
                    lineId = 20,
                    lineIndex = 0,
                    normalizedText = "new text",
                )
                writer.commit()
            }
        }

        FSDirectory.open(indexDir).use { directory ->
            DirectoryReader.open(directory).use { reader ->
                assertEquals(2, reader.numDocs())
            }
        }
    }

    @Test
    fun lookupWriterAppendKeepsSeedDocumentsAndIsIdempotentPerBook() {
        val indexDir = Files.createTempDirectory("lookup-index-append")

        LuceneLookupIndexWriter(indexDir).use { writer ->
            writer.addBook(1, 1, "seed", listOf("seed"))
            writer.commit()
        }

        repeat(2) {
            LuceneLookupIndexWriter(indexDir, append = true).use { writer ->
                writer.deleteBookById(2)
                writer.addBook(2, 1, "new", listOf("new"))
                writer.commit()
            }
        }

        FSDirectory.open(indexDir).use { directory ->
            DirectoryReader.open(directory).use { reader ->
                assertEquals(2, reader.numDocs())
            }
        }
    }
}
