package io.github.kdroidfilter.seforimlibrary.search

import org.apache.lucene.document.IntPoint
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.FieldInfos
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.search.Query
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.store.NIOFSDirectory
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** A dense semantic hit. */
data class DenseHit(val lineId: Long, val bookId: Long, val score: Float)

/**
 * Runs filtered KNN over independently downloadable semantic index shards.
 *
 * Returns line ids that are joined back to the DB by the caller.
 */
class VectorSearcher(
    indexDir: Path,
    expectedDim: Int = 256,
    dbPath: Path? = null,
    modelDir: Path? = null,
) : Closeable {
    // GraalVM native image can't instantiate MMapDirectory's MemorySegmentIndexInputProvider
    // (Panama foreign downcalls) — use NIOFSDirectory there, like LuceneSearchEngine does.
    private val shardPaths = Files.list(indexDir).use { stream ->
        stream.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("shard-") }
            .sorted()
            .toList()
    }
    private val dirs = shardPaths.map { path ->
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) NIOFSDirectory(path)
        else FSDirectory.open(path)
    }

    init {
        try {
            require(dirs.isNotEmpty()) { "No semantic index shards in $indexDir" }
            val manifests = shardPaths.map { path ->
                Properties().apply {
                    Files.newInputStream(path.resolve("semantic.properties")).use { load(it) }
                }
            }
            val first = manifests.first()
            val shardCount = first.getProperty("shardCount")?.toIntOrNull()
            require(first.getProperty("format") == "zayit-round2-1" && shardCount == dirs.size)
            require(manifests.map { it.getProperty("shardIndex")?.toIntOrNull() }.toSet() ==
                (0 until dirs.size).toSet()) { "Missing semantic index shard" }
            require(manifests.all {
                it.getProperty("format") == first.getProperty("format") &&
                    it.getProperty("databaseSha256") == first.getProperty("databaseSha256") &&
                    it.getProperty("modelSha256") == first.getProperty("modelSha256") &&
                    it.getProperty("tokenizerSha256") == first.getProperty("tokenizerSha256") &&
                    it.getProperty("dimension") == expectedDim.toString() &&
                    it.getProperty("indexed") == it.getProperty("eligible")
            }) { "Inconsistent semantic index shards" }
            if (dbPath != null) require(sha256(dbPath) == first.getProperty("databaseSha256")) {
                "Semantic index belongs to a different database"
            }
            if (modelDir != null) {
                require(sha256(modelDir.resolve("seforim-embed-round2-int8.onnx")) == first.getProperty("modelSha256")) {
                    "Semantic index belongs to a different model"
                }
                require(sha256(modelDir.resolve("tokenizer.json")) == first.getProperty("tokenizerSha256")) {
                    "Semantic index belongs to a different tokenizer"
                }
            }
            dirs.forEach { dir ->
                DirectoryReader.open(dir).use { reader ->
                    val dimension = FieldInfos.getMergedFieldInfos(reader).fieldInfo("vec")?.vectorDimension
                    require(dimension == expectedDim) {
                        "Semantic index has dimension $dimension; expected $expectedDim"
                    }
                }
            }
        } catch (failure: Throwable) {
            dirs.forEach { runCatching { it.close() } }
            throw failure
        }
    }

    private fun filterQuery(baseBookOnly: Boolean, bookIds: Collection<Long>?): Query? {
        val b = BooleanQuery.Builder()
        var any = false
        if (baseBookOnly) {
            // Field name matches the fused text index (LuceneTextIndexWriter.FIELD_IS_BASE_BOOK).
            b.add(IntPoint.newExactQuery("is_base_book", 1), BooleanClause.Occur.FILTER); any = true
        }
        if (!bookIds.isNullOrEmpty()) {
            b.add(IntPoint.newSetQuery("book_id", *bookIds.map { it.toInt() }.toIntArray()), BooleanClause.Occur.FILTER); any = true
        }
        return if (any) b.build() else null
    }

    fun search(query: FloatArray, k: Int, baseBookOnly: Boolean = false, bookIds: Collection<Long>? = null): List<DenseHit> {
        return dirs.flatMap { dir ->
            DirectoryReader.open(dir).use { reader ->
                val searcher = IndexSearcher(reader)
                val knn = KnnFloatVectorQuery("vec", query, k, filterQuery(baseBookOnly, bookIds))
                val top = searcher.search(knn, k)
                val stored = searcher.storedFields()
                top.scoreDocs.map { sd ->
                    val d = stored.document(sd.doc)
                    DenseHit(
                        lineId = d.getField("line_id").numericValue().toLong(),
                        bookId = d.getField("book_id").numericValue().toLong(),
                        score = sd.score,
                    )
                }
            }
        }.sortedByDescending { it.score }.take(k)
    }

    override fun close() { dirs.forEach { it.close() } }
}
