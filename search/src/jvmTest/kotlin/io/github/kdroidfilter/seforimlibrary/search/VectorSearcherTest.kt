package io.github.kdroidfilter.seforimlibrary.search

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.KnnByteVectorField
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.store.FSDirectory
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VectorSearcherTest {
    @Test
    fun searchesInt8AndLegacyFloatIndexesWithFilters() {
        for (int8 in listOf(true, false)) withIndex(int8) { root ->
            VectorSearcher(root, expectedDim = 3).use { searcher ->
                val query = floatArrayOf(1f, 0f, 0f)
                assertEquals(listOf(1L, 2L), searcher.search(query, 2).map { it.lineId })
                assertEquals(listOf(2L), searcher.search(query, 2, baseBookOnly = true).map { it.lineId })
                assertEquals(listOf(1L), searcher.search(query, 2, bookIds = listOf(11L)).map { it.lineId })
            }
        }
    }

    @Test
    fun rejectsManifestThatMislabelsFloatStorageAsInt8() = withIndex(false, Int8Vectors.ENCODING) { root ->
        assertFailsWith<IllegalArgumentException> { VectorSearcher(root, expectedDim = 3).close() }
    }

    @Test
    fun stillSearchesLegacyShardedBundles() = withIndex(true, shardCount = 2) { root ->
        VectorSearcher(root, expectedDim = 3).use { searcher ->
            val query = floatArrayOf(1f, 0f, 0f)
            repeat(3) {
                assertEquals(
                    setOf(1L, 2L, 3L, 4L),
                    searcher.search(query, 4)
                        .map { it.lineId }
                        .toSet(),
                )
                assertEquals(
                    setOf(2L, 4L),
                    searcher.search(query, 4, baseBookOnly = true)
                        .map { it.lineId }
                        .toSet(),
                )
                assertEquals(
                    listOf(3L),
                    searcher.search(query, 4, bookIds = listOf(13L))
                        .map { it.lineId },
                )
            }
        }
    }

    private fun withIndex(
        int8: Boolean,
        encoding: String? = if (int8) Int8Vectors.ENCODING else null,
        shardCount: Int = 1,
        block: (java.nio.file.Path) -> Unit,
    ) {
        val root = Files.createTempDirectory("semantic-int8-test")
        try {
            for (shardIndex in 0 until shardCount) {
                val shard = Files.createDirectories(root.resolve("shard-%02d".format(shardIndex)))
                FSDirectory.open(shard).use { directory ->
                    IndexWriter(directory, IndexWriterConfig(StandardAnalyzer())).use { writer ->
                        for ((index, vector) in listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)).withIndex()) {
                            writer.addDocument(Document().apply {
                                add(StoredField("line_id", (shardIndex * 2 + index + 1).toLong()))
                                add(StoredField("book_id", (shardIndex * 2 + index + 11).toLong()))
                                add(IntPoint("book_id", shardIndex * 2 + index + 11))
                                add(IntPoint("is_base_book", index))
                                add(if (int8) KnnByteVectorField("vec", Int8Vectors.quantize(vector), VectorSimilarityFunction.COSINE)
                                    else KnnFloatVectorField("vec", vector, VectorSimilarityFunction.COSINE))
                            })
                        }
                    }
                }
                val properties = Properties().apply {
                    setProperty("format", "zayit-round2-1")
                    setProperty("dimension", "3")
                    setProperty("shardIndex", shardIndex.toString())
                    setProperty("shardCount", shardCount.toString())
                    setProperty("eligible", "2")
                    setProperty("indexed", "2")
                    encoding?.let { setProperty("vectorEncoding", it) }
                }
                Files.newOutputStream(shard.resolve("semantic.properties")).use { properties.store(it, null) }
            }
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
