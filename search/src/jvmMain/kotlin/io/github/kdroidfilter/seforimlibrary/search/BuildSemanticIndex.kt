package io.github.kdroidfilter.seforimlibrary.search

import org.apache.lucene.document.Document
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.store.FSDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Build one independently searchable shard of the optional Round 2 index. */
fun main(args: Array<String>) {
    require(args.size in 3..6) {
        "Usage: BuildSemanticIndex <seforim.db> <model-dir> <output-root> [shard-index] [shard-count] [workers]"
    }
    val db = Path.of(args[0]).toAbsolutePath()
    val modelDir = Path.of(args[1]).toAbsolutePath()
    val root = Path.of(args[2]).toAbsolutePath()
    val shardIndex = args.getOrNull(3)?.toInt() ?: 0
    val shardCount = args.getOrNull(4)?.toInt() ?: 1
    val workers = args.getOrNull(5)?.toInt() ?: 1
    require(shardCount > 0 && shardIndex in 0 until shardCount)
    require(workers in 1..Runtime.getRuntime().availableProcessors())
    require(Files.isRegularFile(db)) { "Missing database: $db" }
    val model = modelDir.resolve("seforim-embed-round2-int8.onnx")
    val tokenizer = modelDir.resolve("tokenizer.json")
    require(Files.isRegularFile(model) && Files.isRegularFile(tokenizer)) { "Missing Round 2 model files" }
    val databaseSha256 = sha256(db)

    val out = root.resolve("shard-%02d".format(shardIndex))
    Files.createDirectories(out)
    var eligible = 0L
    val indexed = AtomicLong()
    val startedAt = System.nanoTime()
    val encoders = ConcurrentLinkedQueue<SeforimEmbedder>()
    val failure = AtomicReference<Throwable?>()
    val localEncoder = ThreadLocal.withInitial {
        requireNotNull(SeforimEmbedder.tryLoad(modelDir)) { "Unable to load Round 2 model" }
            .also(encoders::add)
    }
    val executor = ThreadPoolExecutor(
        workers, workers, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(workers * 4),
    ) { task, pool ->
        if (pool.isShutdown) throw RejectedExecutionException("Semantic index executor stopped")
        pool.queue.put(task)
    }
    try {
        FSDirectory.open(out).use { directory ->
            IndexWriter(directory, IndexWriterConfig(StandardAnalyzer()).apply { openMode = IndexWriterConfig.OpenMode.CREATE }).use { writer ->
                try {
                    DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery(
                                "SELECT l.id, l.bookId, l.content, b.isBaseBook " +
                                    "FROM line l JOIN book b ON b.id = l.bookId " +
                                    "WHERE l.id % $shardCount = $shardIndex ORDER BY l.id",
                            ).use { rows ->
                                while (rows.next()) {
                                    val lineId = rows.getLong(1)
                                    val bookId = rows.getLong(2)
                                    val text = rows.getString(3)
                                    val isBaseBook = rows.getInt(4)
                                    val cleanText = Round2Normalizer.clean(text)
                                    if (cleanText.isBlank()) continue
                                    eligible++
                                    executor.execute {
                                        if (failure.get() != null) return@execute
                                        try {
                                            val vector = localEncoder.get().embedClean(cleanText, SeforimEmbedder.Role.PASSAGE)
                                            val document = Document().apply {
                                                add(StoredField("line_id", lineId))
                                                add(StoredField("book_id", bookId))
                                                add(IntPoint("book_id", bookId.toInt()))
                                                add(IntPoint("is_base_book", isBaseBook))
                                                add(KnnFloatVectorField("vec", vector, VectorSimilarityFunction.COSINE))
                                            }
                                            writer.addDocument(document)
                                            val done = indexed.incrementAndGet()
                                            if (done % 10_000L == 0L) {
                                                val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
                                                println("shard $shardIndex: $done encoded in %.1fs (%.1f lines/s)".format(
                                                    elapsed, done / elapsed,
                                                ))
                                            }
                                        } catch (error: Throwable) {
                                            failure.compareAndSet(null, error)
                                        }
                                    }
                                    failure.get()?.let { throw it }
                                }
                            }
                        }
                    }
                } finally {
                    executor.shutdown()
                    while (!executor.awaitTermination(30, TimeUnit.SECONDS)) { /* wait for queued embeddings */ }
                }
                failure.get()?.let { throw it }
                writer.commit()
            }
        }
    } finally {
        encoders.forEach { runCatching { it.close() } }
    }
    require(indexed.get() == eligible && eligible > 0) { "Incomplete or empty semantic shard" }
    require(sha256(db) == databaseSha256) { "Database changed while building semantic shard" }
    val properties = Properties().apply {
        setProperty("format", "zayit-round2-1")
        setProperty("dimension", "256")
        setProperty("shardIndex", shardIndex.toString())
        setProperty("shardCount", shardCount.toString())
        setProperty("eligible", eligible.toString())
        setProperty("indexed", indexed.get().toString())
        setProperty("databaseSha256", databaseSha256)
        setProperty("modelSha256", sha256(model))
        setProperty("tokenizerSha256", sha256(tokenizer))
    }
    Files.newOutputStream(out.resolve("semantic.properties")).use { properties.store(it, "Zayit Round 2 index") }
    val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
    println("Completed shard $shardIndex/$shardCount: ${indexed.get()} vectors in $out (%.1fs, %.1f lines/s)".format(
        elapsed, indexed.get() / elapsed,
    ))
}

internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
