package io.github.kdroidfilter.seforimlibrary.search

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.store.FSDirectory
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

private const val VECTOR_DIMENSION = 256
private const val RECORD_BYTES = 8 + 8 + 4 + VECTOR_DIMENSION * 4

/** Index GPU-generated little-endian records: line_id, book_id, is_base_book, 256 float32 values. */
fun main(args: Array<String>) {
    require(args.size == 6) {
        "Usage: BuildSemanticIndexFromVectors <seforim.db> <model-dir> <vectors.bin> <output-root> <shard-index> <shard-count>"
    }
    val db = Path.of(args[0]).toAbsolutePath()
    val modelDir = Path.of(args[1]).toAbsolutePath()
    val vectors = Path.of(args[2]).toAbsolutePath()
    val root = Path.of(args[3]).toAbsolutePath()
    val shardIndex = args[4].toInt()
    val shardCount = args[5].toInt()
    require(shardCount > 0 && shardIndex in 0 until shardCount)
    require(Files.isRegularFile(db) && Files.isRegularFile(vectors))
    require(Files.size(vectors) % RECORD_BYTES == 0L) { "Truncated vector file: $vectors" }
    val model = modelDir.resolve("seforim-embed-round2-int8.onnx")
    val tokenizer = modelDir.resolve("tokenizer.json")
    require(Files.isRegularFile(model) && Files.isRegularFile(tokenizer))
    val expected = Files.size(vectors) / RECORD_BYTES
    require(expected > 0) { "Empty vector file: $vectors" }
    val output = root.resolve("shard-%02d".format(shardIndex))
    Files.createDirectories(output)
    val record = ByteArray(RECORD_BYTES)
    var indexed = 0L
    FSDirectory.open(output).use { directory ->
        IndexWriter(directory, IndexWriterConfig(StandardAnalyzer()).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE
        }).use { writer ->
            DataInputStream(BufferedInputStream(Files.newInputStream(vectors), 1 shl 20)).use { input ->
                while (indexed < expected) {
                    input.readFully(record)
                    val bytes = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
                    val lineId = bytes.long
                    val bookId = bytes.long
                    val isBaseBook = bytes.int
                    require(lineId > 0 && lineId % shardCount == shardIndex.toLong() && bookId > 0)
                    val vector = FloatArray(VECTOR_DIMENSION) { bytes.float }
                    require(vector.all { it.isFinite() }) { "Invalid vector for line $lineId" }
                    writer.addDocument(Document().apply {
                        add(StoredField("line_id", lineId))
                        add(StoredField("book_id", bookId))
                        add(IntPoint("book_id", bookId.toInt()))
                        add(IntPoint("is_base_book", isBaseBook))
                        add(KnnFloatVectorField("vec", vector, VectorSimilarityFunction.COSINE))
                    })
                    indexed++
                    if (indexed % 100_000L == 0L) println("shard $shardIndex: indexed $indexed/$expected")
                }
            }
            writer.commit()
        }
    }
    val properties = Properties().apply {
        setProperty("format", "zayit-round2-1")
        setProperty("dimension", VECTOR_DIMENSION.toString())
        setProperty("shardIndex", shardIndex.toString())
        setProperty("shardCount", shardCount.toString())
        setProperty("eligible", indexed.toString())
        setProperty("indexed", indexed.toString())
        setProperty("databaseSha256", sha256(db))
        setProperty("modelSha256", sha256(model))
        setProperty("tokenizerSha256", sha256(tokenizer))
    }
    Files.newOutputStream(output.resolve("semantic.properties")).use { properties.store(it, "Zayit Round 2 GPU index") }
    println("Completed shard $shardIndex/$shardCount: $indexed vectors")
}
