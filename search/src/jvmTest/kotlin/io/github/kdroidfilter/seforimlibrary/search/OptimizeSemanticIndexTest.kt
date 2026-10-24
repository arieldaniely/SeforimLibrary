package io.github.kdroidfilter.seforimlibrary.search

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.KnnByteVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.NoMergePolicy
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnByteVectorQuery
import org.apache.lucene.store.FSDirectory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class OptimizeSemanticIndexTest {
    @Test
    fun mergePreservesVectorsAndStoredIdsAndCanRunAgain() {
        val root = Files.createTempDirectory("semantic-release-merge")
        try {
            FSDirectory.open(root).use { directory ->
                IndexWriter(directory, IndexWriterConfig(StandardAnalyzer()).apply {
                    mergePolicy = NoMergePolicy.INSTANCE
                }).use { writer ->
                    repeat(3) { position ->
                        writer.addDocument(Document().apply {
                            add(StoredField("line_id", position + 1L))
                            add(KnnByteVectorField("vec", byteArrayOf(127, position.toByte(), 0), VectorSimilarityFunction.COSINE))
                        })
                        writer.commit()
                    }
                }
                DirectoryReader.open(directory).use { assertEquals(3, it.leaves().size) }
            }
            repeat(2) {
                optimizeSemanticShard(root)
                FSDirectory.open(root).use { directory ->
                    DirectoryReader.open(directory).use { reader ->
                        assertEquals(1, reader.leaves().size)
                        assertEquals(3, reader.numDocs())
                        val searcher = IndexSearcher(reader)
                        val hits = searcher.search(KnnByteVectorQuery("vec", byteArrayOf(127, 0, 0), 3), 3)
                        assertEquals(
                            setOf(1L, 2L, 3L),
                            hits.scoreDocs.map { searcher.storedFields().document(it.doc).getField("line_id").numericValue().toLong() }.toSet(),
                        )
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
