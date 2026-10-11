package io.github.kdroidfilter.seforimlibrary.search

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.KnnByteVectorField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.AcceptDocs
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.TopDocs
import org.apache.lucene.search.TotalHits
import org.apache.lucene.search.knn.KnnCollectorManager
import org.apache.lucene.store.ByteBuffersDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class CompleteKnnByteVectorQueryTest {
    @Test
    fun completesExhaustedGraphResultsWithoutLosingBookFilters() {
        ByteBuffersDirectory().use { directory ->
            IndexWriter(directory, IndexWriterConfig(StandardAnalyzer())).use { writer ->
                repeat(100) { number ->
                    writer.addDocument(
                        Document().apply {
                            add(KnnByteVectorField("vec", byteArrayOf(127, number.toByte()), VectorSimilarityFunction.COSINE))
                            add(IntPoint("book_id", number))
                        },
                    )
                }
                writer.forceMerge(1)
            }
            DirectoryReader.open(directory).use { reader ->
                val searcher = IndexSearcher(reader)
                for (filter in listOf(null, IntPoint.newRangeQuery("book_id", 10, 29))) {
                    for (relation in TotalHits.Relation.values()) {
                        val query = truncatedGraphQuery(filter, relation)
                        val results = searcher.search(query, 10)
                        val first = if (filter == null) 0 else 10
                        assertEquals((first until first + 10).toList(), results.scoreDocs.map { it.doc })
                    }
                }
            }
        }
    }

    private fun truncatedGraphQuery(filter: Query?, relation: TotalHits.Relation): CompleteKnnByteVectorQuery =
        object : CompleteKnnByteVectorQuery("vec", byteArrayOf(127, 0), 10, filter) {
            override fun approximateCandidates(
                context: LeafReaderContext,
                acceptDocs: AcceptDocs,
                visitedLimit: Int,
                collectorManager: KnnCollectorManager,
            ): TopDocs {
                val candidates = super.approximateCandidates(context, acceptDocs, visitedLimit, collectorManager)
                // Simulate the real exhausted component that returns fewer than k candidates.
                return TopDocs(TotalHits(3, relation), candidates.scoreDocs.take(3).toTypedArray())
            }
        }
}
