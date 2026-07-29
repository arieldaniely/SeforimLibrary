package io.github.kdroidfilter.seforimlibrary.search

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.LowerCaseFilter
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper
import org.apache.lucene.analysis.ngram.NGramTokenFilter
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.FSDirectory
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** Builds the small, replaceable index that belongs to a personal-library generation. */
object PersonalLuceneIndexBuilder {
    fun build(database: Path, target: Path) {
        Files.createDirectories(target)
        val ngramAnalyzer = object : Analyzer() {
            override fun createComponents(fieldName: String): TokenStreamComponents {
                val tokenizer = StandardTokenizer()
                var stream: TokenStream = LowerCaseFilter(tokenizer)
                stream = NGramTokenFilter(stream, 4, 4, false)
                return TokenStreamComponents(tokenizer, stream)
            }
        }
        val analyzer = PerFieldAnalyzerWrapper(StandardAnalyzer(), mapOf("text_ng4" to ngramAnalyzer))
        FSDirectory.open(target).use { directory ->
            IndexWriter(directory, IndexWriterConfig(analyzer).apply { openMode = IndexWriterConfig.OpenMode.CREATE }).use { writer ->
                DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                    val ancestors = HashMap<Long, List<Long>>()
                    connection.prepareStatement(
                        "SELECT ancestorId FROM category_closure WHERE descendantId=? ORDER BY ancestorId",
                    ).use { ancestorQuery ->
                        connection.prepareStatement(
                            "SELECT id, categoryId, title, orderIndex, isBaseBook FROM book ORDER BY id",
                        ).use { books ->
                            books.executeQuery().use { rows ->
                                while (rows.next()) {
                                    val bookId = rows.getLong(1)
                                    val categoryId = rows.getLong(2)
                                    val title = rows.getString(3)
                                    val order = rows.getInt(4)
                                    val baseBook = rows.getInt(5) != 0
                                    addTitle(writer, bookId, categoryId, title, title)
                                    connection.prepareStatement("SELECT term FROM book_acronym WHERE bookId=?").use { aliases ->
                                        aliases.setLong(1, bookId)
                                        aliases.executeQuery().use { aliasRows ->
                                            while (aliasRows.next()) addTitle(writer, bookId, categoryId, title, aliasRows.getString(1))
                                        }
                                    }
                                    val categoryAncestors = ancestors.getOrPut(categoryId) {
                                        ancestorQuery.setLong(1, categoryId)
                                        ancestorQuery.executeQuery().use { result ->
                                            buildList { while (result.next()) add(result.getLong(1)) }
                                        }
                                    }
                                    connection.prepareStatement(
                                        "SELECT id, lineIndex, content FROM line WHERE bookId=? ORDER BY lineIndex",
                                    ).use { lines ->
                                        lines.setLong(1, bookId)
                                        lines.executeQuery().use { lineRows ->
                                            while (lineRows.next()) {
                                                addLine(
                                                    writer = writer,
                                                    bookId = bookId,
                                                    categoryId = categoryId,
                                                    ancestors = categoryAncestors,
                                                    title = title,
                                                    order = order,
                                                    baseBook = baseBook,
                                                    lineId = lineRows.getLong(1),
                                                    lineIndex = lineRows.getInt(2),
                                                    content = lineRows.getString(3),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                writer.commit()
            }
        }
    }

    private fun addTitle(writer: IndexWriter, bookId: Long, categoryId: Long, title: String, term: String) {
        writer.addDocument(Document().apply {
            add(StringField("type", "book_title", Field.Store.NO))
            add(StoredField("book_id", bookId)); add(IntPoint("book_id", bookId.toInt()))
            add(StoredField("category_id", categoryId)); add(IntPoint("category_id", categoryId.toInt()))
            add(StoredField("book_title", title)); add(TextField("title", term, Field.Store.NO))
        })
    }

    private fun addLine(
        writer: IndexWriter,
        bookId: Long,
        categoryId: Long,
        ancestors: List<Long>,
        title: String,
        order: Int,
        baseBook: Boolean,
        lineId: Long,
        lineIndex: Int,
        content: String,
    ) {
        val text = normalize(content)
        writer.addDocument(Document().apply {
            add(StringField("type", "line", Field.Store.NO))
            add(StoredField("book_id", bookId)); add(IntPoint("book_id", bookId.toInt()))
            add(StoredField("category_id", categoryId)); add(IntPoint("category_id", categoryId.toInt()))
            add(StoredField("book_title", title))
            ancestors.forEach { add(IntPoint("ancestor_category_ids", it.toInt())) }
            add(StoredField("ancestor_category_ids", ancestors.joinToString(",")))
            add(StoredField("line_id", lineId)); add(IntPoint("line_id", lineId.toInt()))
            add(StoredField("line_index", lineIndex)); add(IntPoint("line_index", lineIndex))
            add(StoredField("order_index", order)); add(IntPoint("order_index", order))
            add(StoredField("is_base_book", if (baseBook) 1 else 0))
            add(IntPoint("is_base_book", if (baseBook) 1 else 0))
            add(TextField("text", text, Field.Store.NO)); add(TextField("text_ng4", text, Field.Store.NO))
        })
    }

    private fun normalize(html: String): String {
        val plain = Jsoup.clean(html, Safelist.none())
        val output = StringBuilder(plain.length)
        var spaced = true
        plain.forEach { char ->
            val code = char.code
            if (code in 0x0591..0x05BD || code == 0x05C1 || code == 0x05C2 || code == 0x05C7 ||
                code == 0x05F3 || code == 0x05F4) return@forEach
            if (code == 0x05BE || char.isWhitespace()) {
                if (!spaced) output.append(' ')
                spaced = true
            } else {
                output.append(when (char) { 'ך' -> 'כ'; 'ם' -> 'מ'; 'ן' -> 'נ'; 'ף' -> 'פ'; 'ץ' -> 'צ'; else -> char })
                spaced = false
            }
        }
        return output.toString().trim()
    }
}
