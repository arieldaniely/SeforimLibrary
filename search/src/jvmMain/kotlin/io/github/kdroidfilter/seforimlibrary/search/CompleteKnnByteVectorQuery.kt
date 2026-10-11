package io.github.kdroidfilter.seforimlibrary.search

import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.search.AcceptDocs
import org.apache.lucene.search.KnnByteVectorQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TopDocs
import org.apache.lucene.search.knn.KnnCollectorManager

/** Exhausted graph components can contain fewer candidates than the requested result count. */
internal open class CompleteKnnByteVectorQuery(
    field: String,
    target: ByteArray,
    k: Int,
    filter: Query?,
) : KnnByteVectorQuery(field, target, k, filter) {
    private val filtered = filter != null

    override fun approximateSearch(
        context: LeafReaderContext,
        acceptDocs: AcceptDocs,
        visitedLimit: Int,
        collectorManager: KnnCollectorManager,
    ): TopDocs {
        // Filtered graph traversal counts scored matches, not rejected graph nodes.
        // With the default base-book filter it can explore millions of rejected nodes
        // before reaching its visit limit. A sequential scan of the accepted vectors
        // is both exact and much cheaper for these corpus filters.
        if (filtered) return exactSearch(context, acceptDocs.iterator(), null)
        // Bound unfiltered traversal too, and complete short result sets exactly below.
        val traversalLimit = minOf(visitedLimit, maxOf(10_000L, k.toLong() * 100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val candidates = approximateCandidates(context, acceptDocs, traversalLimit, collectorManager)
        return if (candidates.scoreDocs.size < k) {
            exactSearch(context, acceptDocs.iterator(), null)
        } else {
            candidates
        }
    }

    protected open fun approximateCandidates(
        context: LeafReaderContext,
        acceptDocs: AcceptDocs,
        visitedLimit: Int,
        collectorManager: KnnCollectorManager,
    ): TopDocs = super.approximateSearch(context, acceptDocs, visitedLimit, collectorManager)
}
