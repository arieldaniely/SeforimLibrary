package io.github.kdroidfilter.seforimlibrary.searchindex.lucene

import org.apache.lucene.document.IntPoint
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.FieldInfos
import org.apache.lucene.index.IndexNotFoundException
import org.apache.lucene.index.Term
import org.apache.lucene.index.VectorEncoding
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.FSDirectory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IncrementalIndexWriterTest {
    @Test
    fun appendAndRetryPreserveSeedVectorsAndAvoidDuplicateBooksAndAuthors() {
        val root = Files.createTempDirectory("incremental-index")
        try {
            val text = root.resolve("text")
            val lookup = root.resolve("lookup")
            LuceneTextIndexWriter(text, vectorProvider = { FloatArray(256) { if (it == 0) 1f else 0f } }).use { writer ->
                addBook(writer, 1)
                writer.commit()
            }
            LuceneLookupIndexWriter(lookup).use { writer ->
                writer.addBook(1, 1, "seed", listOf("seed"))
                writer.addAuthor(99, "author", listOf("author"), 1)
                writer.commit()
            }
            val seedFiles = Files.list(text).use { paths -> paths.filter { it.fileName.toString().startsWith("_0.") }.toList() }
            repeat(2) {
                LuceneTextIndexWriter(text, append = true, forceMerge = false).use { writer ->
                    writer.deleteBookById(2)
                    addBook(writer, 2)
                    writer.commit()
                }
                LuceneLookupIndexWriter(lookup, append = true).use { writer ->
                    writer.deleteBookById(2)
                    writer.addBook(2, 1, "new", listOf("new"))
                    writer.deleteAuthors()
                    writer.addAuthor(99, "author", listOf("author", "alias"), 2)
                    writer.commit()
                }
            }
            assertTrue(seedFiles.isNotEmpty())
            assertTrue(seedFiles.all(Files::exists), "Unchanged seed segments were rewritten")
            read(text) { reader, searcher ->
                assertEquals(4, reader.numDocs())
                assertEquals(2, searcher.count(IntPoint.newExactQuery("book_id", 1)))
                assertEquals(2, searcher.count(IntPoint.newExactQuery("book_id", 2)))
                assertEquals(VectorEncoding.BYTE, FieldInfos.getMergedFieldInfos(reader).fieldInfo("vec").vectorEncoding)
            }
            read(lookup) { reader, searcher ->
                assertEquals(3, reader.numDocs())
                assertEquals(1, searcher.count(TermQuery(Term("type", "author"))))
                val author = searcher.search(TermQuery(Term("q", "alias")), 1).scoreDocs.single()
                assertEquals(2, searcher.storedFields().document(author.doc).getField("book_count").numericValue().toInt())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun appendRequiresASeedInsteadOfCreatingAnIncompleteIndex() {
        val root = Files.createTempDirectory("missing-index")
        try {
            assertFailsWith<IndexNotFoundException> { LuceneTextIndexWriter(root.resolve("text"), append = true) }
            assertFailsWith<IndexNotFoundException> { LuceneLookupIndexWriter(root.resolve("lookup"), append = true) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun addBook(writer: LuceneTextIndexWriter, id: Long) {
        writer.addBookTitleTerm(id, 1, "book $id", "book $id")
        writer.addLine(id, "book $id", 1, emptyList(), id, 0, "text", null, null, 1, false)
    }

    private fun read(path: Path, check: (DirectoryReader, IndexSearcher) -> Unit) {
        FSDirectory.open(path).use { directory ->
            DirectoryReader.open(directory).use { reader -> check(reader, IndexSearcher(reader)) }
        }
    }
}
