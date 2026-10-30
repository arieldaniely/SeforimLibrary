package io.github.kdroidfilter.seforimlibrary.search

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.nio.file.Files
import java.nio.file.Path

/**
 * Hybrid search = lexical (BM25 + MagicDictionary, [LuceneSearchEngine]) fused with
 * dense semantic search (Round 2 embedding + [VectorSearcher]) via Reciprocal Rank Fusion.
 *
 *   RRF(doc) = Σ 1/(K + rank_in_list)
 *
 * Implements [SearchEngine] so the app uses it transparently: [openSession] runs the
 * fused search (the session pages over the in-memory fused list); all other methods
 * (facets, snippets, title prefix) delegate to the lexical engine.
 *
 * Falls back to pure lexical when: the model/vector index is absent, OR the filters
 * are not supported by the dense index (categoryFilter / lineIds). Book / base-book
 * filters ARE supported by both paths.
 *
 * Dense-only hits (lines the lexical path missed) are turned into full [LineHit]s via
 * [resolveLine], supplied by the caller (fetches title + text + snippet by line id).
 */
class HybridSearchEngine(
    private val lexical: LuceneSearchEngine,
    private val modelDir: Path?,
    private val indexDir: Path,
    private val dbPath: Path? = null,
    private val rrfK: Int = 60,
    private val candidates: Int = 150,
    private val sharedEmbedderProvider: (suspend () -> SeforimEmbedder?)? = null,
    private val optionsProvider: () -> SemanticSearchOptions = { SemanticSearchOptions(candidates = candidates) },
    private val resolveLine: suspend (lineId: Long, bookId: Long, query: String) -> LineHit?,
) : SearchEngine {

    private val logger = Logger.withTag("HybridSearch")

    // The embedder (heavy OrtSession) + vector searcher are loaded LAZILY on the first
    // dense search, off the main thread (see [ensureDense]) — so the first query just
    // shows the normal search spinner instead of freezing the UI while the model loads.
    @Volatile private var embedder: SeforimEmbedder? = null
    @Volatile private var vectorSearcher: VectorSearcher? = null
    @Volatile private var denseTried = false
    private val denseMutex = Mutex()
    private val queryCache = QueryEmbeddingCache()

    // Cheap, no-load check: are the model + vector index even present? Decides whether to
    // take the dense path (then load lazily); the actual OrtSession is built in fuse().
    private val denseConfigured: Boolean
        get() = Files.isDirectory(indexDir) && SeforimEmbedder.isAvailable(modelDir)

    val denseEnabled: Boolean get() = embedder != null && vectorSearcher != null

    /** Borrow the base model; callers must never close it. No model is loaded at startup. */
    suspend fun sharedEmbedder(): SeforimEmbedder? {
        ensureDense()
        return embedder
    }

    /** Load the embedder + vector searcher once, on a background thread. Idempotent. */
    private suspend fun ensureDense() {
        if (denseTried && (denseEnabled || !denseConfigured)) return
        denseMutex.withLock {
            if (denseTried && (denseEnabled || !denseConfigured)) return
            withContext(Dispatchers.IO) {
                var emb: SeforimEmbedder? = null
                var vs: VectorSearcher? = null
                var published = false
                try {
                    coroutineScope {
                        val modelTask = async {
                            emb = if (sharedEmbedderProvider != null) sharedEmbedderProvider.invoke()
                            else SeforimEmbedder.tryLoad(modelDir)
                        }
                        val indexTask = async {
                            if (Files.isDirectory(indexDir)) {
                                vs = runCatching { VectorSearcher(indexDir, 256, dbPath, modelDir) }
                                    .onFailure { logger.w(it) { "Invalid semantic index" } }
                                    .getOrNull()
                            }
                        }
                        modelTask.await()
                        indexTask.await()
                    }
                    if (emb != null && vs != null) {
                        embedder = emb; vectorSearcher = vs
                        published = true
                        logger.i { "dense ready (vector index $indexDir)" }
                    } else {
                        logger.i { "dense unavailable -> lexical only" }
                    }
                } finally {
                    if (!published) {
                        runCatching { vs?.close() }
                        if (sharedEmbedderProvider == null) runCatching { emb?.close() }
                    }
                }
            }
            denseTried = true
        }
    }

    override fun openSession(
        query: String,
        near: Int,
        bookFilter: Long?,
        categoryFilter: Long?,
        bookIds: Collection<Long>?,
        lineIds: Collection<Long>?,
        baseBookOnly: Boolean,
        mode: SearchMode,
    ): SearchSession? {
        if (query.isBlank()) return null
        if (mode != SearchMode.SMART || SearchQueryParser.parse(query).hasExactPhrases) {
            return lexical.openSession(query, near, bookFilter, categoryFilter, bookIds, lineIds, baseBookOnly, mode)
        }
        // Dense index supports book / base-book filters only. For category/line filters
        // (or when dense isn't configured), use pure lexical to stay correct. The model
        // itself is loaded lazily in fuse() — denseConfigured is a cheap no-load check.
        val denseOk = denseConfigured && categoryFilter == null && lineIds == null
        if (!denseOk) {
            val session = lexical.openSession(query, near, bookFilter, categoryFilter, bookIds, lineIds, baseBookOnly)
                ?: return null
            return object : SearchSession by session {
                override val effectiveMode: SearchMode = SearchMode.FLEXIBLE
            }
        }
        val effBookIds = bookIds ?: bookFilter?.let { listOf(it) }
        return HybridSession(query, near, effBookIds, baseBookOnly, optionsProvider())
    }

    override fun searchBooksByTitlePrefix(query: String, limit: Int): List<Long> =
        lexical.searchBooksByTitlePrefix(query, limit)

    override fun buildSnippet(rawText: String, query: String, near: Int): String =
        lexical.buildSnippet(rawText, query, near)

    override fun highlightRanges(text: String, query: String): List<IntRange> = lexical.highlightRanges(text, query)

    override fun rangeSnippet(text: String, ranges: List<IntRange>): String = lexical.rangeSnippet(text, ranges)

    override fun findInBookCandidates(query: String, bookId: Long): LongArray? = lexical.findInBookCandidates(query, bookId)

    override suspend fun attachSnippets(hits: List<LineHit>, query: String, near: Int): List<LineHit> =
        lexical.attachSnippets(hits, query, near)

    override suspend fun warmUp() = lexical.warmUp()

    override suspend fun passagesByMeaning(query: String, texts: List<String>): List<String?>? =
        passagesByMeaning(query, texts, SeforimEmbedder.Role.QUERY)

    override suspend fun passagesInBookByMeaning(query: String, texts: List<String>): List<String?>? =
        passagesByMeaning(query, texts, SeforimEmbedder.Role.PASSAGE)

    private suspend fun passagesByMeaning(
        query: String,
        texts: List<String>,
        role: SeforimEmbedder.Role,
    ): List<String?>? {
        ensureDense()
        val emb = embedder ?: return null
        return withContext(Dispatchers.Default) {
            val vector = emb.embed(query, role)
            texts.map { semanticSpan(emb, vector, it) }
        }
    }

    override fun buildHighlightTerms(query: String): List<String> =
        lexical.buildHighlightTerms(query)

    /**
     * Picks the passage of [text] closest in meaning to [query], using the SAME dense
     * encoder as semantic search — so the highlight reflects meaning instead of scattering
     * dictionary word matches. Returns the winning passage verbatim, or null when dense is
     * unavailable or the text isn't worth localizing (a single short clause).
     */
    override suspend fun semanticSpan(query: String, text: String): String? {
        if (query.isBlank() || text.isBlank()) return null
        ensureDense()
        val emb = embedder ?: return null
        return withContext(Dispatchers.Default) {
            semanticSpan(emb, emb.embed(query), text)
        }
    }

    private suspend fun semanticSpan(emb: SeforimEmbedder, qVec: FloatArray, text: String): String? {
        val clauses = splitClauses(text)
        if (clauses.size < 2) return null
        return withContext(Dispatchers.Default) {
            clauses
                .map {
                    currentCoroutineContext().ensureActive()
                    it to cosine(qVec, emb.embed(it, SeforimEmbedder.Role.PASSAGE))
                }.maxByOrNull { it.second }
                ?.first
        }
    }

    /**
     * Embedding-based find-in-page in one book: dense KNN over the index scoped to [bookId].
     * Reuses the line vectors already in the index, so this is just the query embedding + a
     * filtered KNN — the per-line passage is computed by the caller on the displayed text.
     */
    override suspend fun denseReady(): Boolean {
        ensureDense()
        return denseEnabled
    }

    override suspend fun semanticFind(query: String, bookId: Long, limit: Int): List<Long> {
        if (query.isBlank()) return emptyList()
        return fuse(
            query, near = 5, bookIds = listOf(bookId), baseOnly = false, role = SeforimEmbedder.Role.PASSAGE,
        ).ranking.take(limit).map { it.lineId }
    }

    override fun computeFacets(
        query: String, near: Int, bookFilter: Long?, categoryFilter: Long?,
        bookIds: Collection<Long>?, lineIds: Collection<Long>?, baseBookOnly: Boolean,
        mode: SearchMode,
    ): SearchFacets? = lexical.computeFacets(query, near, bookFilter, categoryFilter, bookIds, lineIds, baseBookOnly, mode)

    override fun close() {
        queryCache.clear()
        runCatching { vectorSearcher?.close() }
        if (sharedEmbedderProvider == null) runCatching { embedder?.close() }
        runCatching { lexical.close() }
    }

    /** The fused, RRF-ordered hits plus the ids that the lexical path matched (so the dense-only
     *  ones can get a meaning-based snippet in the background). */
    private class FusedResult(
        val ranking: List<RankedLine>,
        val lexicalIds: Set<Long>,
        val queryVector: FloatArray? = null,
    )

    /** Lexical page + dense KNN, fused by RRF, resolved to full LineHits. */
    private suspend fun fuse(
        query: String,
        near: Int,
        bookIds: Collection<Long>?,
        baseOnly: Boolean,
        role: SeforimEmbedder.Role = SeforimEmbedder.Role.QUERY,
        options: SemanticSearchOptions = optionsProvider(),
    ): FusedResult = coroutineScope {
        val lexicalTask = async(Dispatchers.IO) {
            lexical.openSession(query, near = near, bookIds = bookIds, baseBookOnly = baseOnly)
                ?.use { it.nextPage(options.candidates, snippets = false)?.hits ?: emptyList() } ?: emptyList()
        }
        ensureDense()   // first call loads the model off-main (covered by the search spinner)

        // Dense failed to load -> lexical-only result (still correct, just no semantic recall).
        val emb = embedder
        val vs = vectorSearcher
        if (emb == null || vs == null) {
            val lexHits = lexicalTask.await()
            return@coroutineScope lexicalResult(lexHits)
        }

        // Don't let a dense failure sink the whole search: degrade to lexical and log. This
        // also catches native-image gaps (e.g. a missing Lucene KNN vectors-format SPI entry).
        var queryVector: FloatArray? = null
        val denseHits = try {
            withContext(Dispatchers.Default) {
                val qVec = if (options.cacheQueries) queryCache.embed(query, role, emb::embedClean) else emb.embed(query, role)
                queryVector = qVec
                val context = currentCoroutineContext()
                vs.search(qVec, options.copy(maximumResults = null), baseOnly, bookIds, context::ensureActive)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "dense KNN failed; falling back to lexical-only" }
            val lexHits = lexicalTask.await()
            return@coroutineScope lexicalResult(lexHits)
        }

        val lexHits = lexicalTask.await()
        val lexById = lexHits.associateBy { it.lineId }
        val ranking = SemanticFusion.rank(
            lexHits.map { DenseHit(it.lineId, it.bookId, it.score) },
            denseHits,
            options,
            rrfK,
        ).map { hit ->
            RankedLine(hit.lineId, hit.bookId, hit.score, lexById[hit.lineId])
        }
        FusedResult(ranking, lexById.keys, queryVector)
    }

    private fun lexicalResult(hits: List<LineHit>): FusedResult = FusedResult(
        hits.map { RankedLine(it.lineId, it.bookId, it.score, it) },
        hits.map { it.lineId }.toSet(),
    )

    /**
     * Snippet for a dense-only hit: the lexical builder can't anchor (the query words aren't in
     * the text) and, fed the passage, it tokenizes and bolds each word separately — so short
     * function words (ואם, או…) get highlighted on their own. Instead, locate the passage closest
     * in meaning and bold it as ONE contiguous span inside a context window. Null -> keep lexical.
     */
    private suspend fun semanticSnippet(queryVector: FloatArray, rawText: String, near: Int): String? {
        // Jsoup.clean returns HTML-escaped text; substrings stay escaped, so we splice <b> in
        // directly (same convention as the lexical snippet builder).
        val clean = Jsoup.clean(rawText, Safelist.none())
        val emb = embedder ?: return null
        val passage = semanticSpan(emb, queryVector, clean) ?: return null
        val idx = clean.indexOf(passage)
        if (idx < 0) return lexical.buildSnippet(rawText, passage, near)
        val from = (idx - SNIPPET_CONTEXT).coerceAtLeast(0)
        val to = (idx + passage.length + SNIPPET_CONTEXT).coerceAtMost(clean.length)
        return buildString {
            if (from > 0) append("…")
            append(clean, from, idx)
            append("<b>").append(passage).append("</b>")
            append(clean, idx + passage.length, to)
            if (to < clean.length) append("…")
        }
    }

    private inner class HybridSession(
        private val query: String,
        private val near: Int,
        private val bookIds: Collection<Long>?,
        private val baseOnly: Boolean,
        private val options: SemanticSearchOptions,
    ) : SearchSession {
        override var effectiveMode: SearchMode = SearchMode.SMART
            private set
        private var fused: FusedResult? = null
        private var pages: RankedSearchResults? = null

        override suspend fun nextPage(limit: Int, snippets: Boolean): SearchPage? {
            val all = fused ?: fuse(query, near, bookIds, baseOnly, options = options).also { fused = it }
            effectiveMode = if (all.queryVector != null) SearchMode.SMART else SearchMode.FLEXIBLE
            val results = pages ?: RankedSearchResults(all.ranking) { lineId, bookId ->
                resolveLine(lineId, bookId, query)
            }.also { pages = it }
            return results.nextPage(limit) { pageHits ->
                if (snippets) lexical.attachSnippets(pageHits, query, near) else pageHits
            }
        }

        override suspend fun refinedSnippets(hits: List<LineHit>): Map<Long, String> {
            val snippets = mutableMapOf<Long, String>()
            highlightUpdates(hits).collect { snippets[it.lineId] = it.snippet }
            return snippets
        }

        override fun highlightUpdates(hits: List<LineHit>): Flow<LineHit> = flow {
            val all = fused ?: return@flow
            val queryVector = all.queryVector ?: return@flow
            for (hit in hits) {
                currentCoroutineContext().ensureActive()
                if (hit.lineId in all.lexicalIds) continue
                val snippet = try {
                    semanticSnippet(queryVector, hit.rawText, near)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(e) { "Semantic snippet failed for line ${hit.lineId}" }
                    null
                }
                if (snippet != null) emit(hit.copy(snippet = snippet))
            }
        }.flowOn(Dispatchers.Default)

        override fun close() {}
    }

    /**
     * Splits [text] into passage candidates: first on strong clause delimiters (incl. the
     * Hebrew sof-pasuk ׃), then sliding word windows over any segment too long to localize.
     * Substrings are verbatim slices of [text] (original spacing preserved) so the caller
     * can match them back diacritic-insensitively. Tiny fragments are dropped to keep a
     * highlight that is a *passage*, not a stray word.
     */
    private fun splitClauses(text: String): List<String> {
        val delimiters = charArrayOf('.', '!', '?', ':', ';', '׃', '\n')
        val segments = ArrayList<IntRange>()
        var start = 0
        for (i in text.indices) {
            if (text[i] in delimiters) {
                if (i > start) segments += start..i
                start = i + 1
            }
        }
        if (start < text.length) segments += start until text.length

        val out = ArrayList<String>()
        for (seg in segments) {
            val sub = text.substring(seg.first, seg.last + 1)
            val wordRanges = WORD_RE.findAll(sub).map { it.range }.toList()
            if (wordRanges.size < MIN_PASSAGE_WORDS) continue
            if (wordRanges.size <= MAX_PASSAGE_WORDS) {
                out += sub.substring(wordRanges.first().first, wordRanges.last().last + 1)
            } else {
                // Segment too long to localize: slide word windows over the original slice.
                var w = 0
                while (w < wordRanges.size) {
                    val from = wordRanges[w].first
                    val toIdx = minOf(w + MAX_PASSAGE_WORDS - 1, wordRanges.size - 1)
                    out += sub.substring(from, wordRanges[toIdx].last + 1)
                    if (toIdx == wordRanges.size - 1) break
                    w += WINDOW_STRIDE_WORDS
                }
            }
        }
        return if (out.isEmpty()) listOf(text.trim()) else out
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    companion object {
        private val logger = Logger.withTag("HybridSearch")

        private val WORD_RE = Regex("\\S+")
        private const val MIN_PASSAGE_WORDS = 3
        private const val MAX_PASSAGE_WORDS = 14
        private const val WINDOW_STRIDE_WORDS = 8
        private const val SNIPPET_CONTEXT = 90 // chars of context kept on each side of the passage

    }
}
