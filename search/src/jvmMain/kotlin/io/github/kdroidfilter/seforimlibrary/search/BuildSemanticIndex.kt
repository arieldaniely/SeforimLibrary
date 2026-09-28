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

/** Build one independently searchable shard of the optional Round 2 index. */
fun main(args: Array<String>) {
    require(args.size in 3..5) {
        "Usage: BuildSemanticIndex <seforim.db> <model-dir> <output-root> [shard-index] [shard-count]"
    }
    val db = Path.of(args[0]).toAbsolutePath()
    val modelDir = Path.of(args[1]).toAbsolutePath()
    val root = Path.of(args[2]).toAbsolutePath()
    val shardIndex = args.getOrNull(3)?.toInt() ?: 0
    val shardCount = args.getOrNull(4)?.toInt() ?: 1
    require(shardCount > 0 && shardIndex in 0 until shardCount)
    require(Files.isRegularFile(db)) { "Missing database: $db" }
    val model = modelDir.resolve("seforim-embed-round2-int8.onnx")
    val tokenizer = modelDir.resolve("tokenizer.json")
    require(Files.isRegularFile(model) && Files.isRegularFile(tokenizer)) { "Missing Round 2 model files" }

    val out = root.resolve("shard-%02d".format(shardIndex))
    Files.createDirectories(out)
    var eligible = 0L
    var indexed = 0L
    val encoder = requireNotNull(SeforimEmbedder.tryLoad(modelDir)) { "Unable to load Round 2 model" }
    encoder.use { embedder ->
        FSDirectory.open(out).use { directory ->
            IndexWriter(directory, IndexWriterConfig(StandardAnalyzer()).apply { openMode = IndexWriterConfig.OpenMode.CREATE }).use { writer ->
                DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT l.id, l.bookId, l.content, b.isBaseBook " +
                                "FROM line l JOIN book b ON b.id = l.bookId WHERE l.id % $shardCount = $shardIndex ORDER BY l.id",
                        ).use { rows ->
                            while (rows.next()) {
                                val lineId = rows.getLong(1)
                                val bookId = rows.getLong(2)
                                val text = rows.getString(3)
                                val isBaseBook = rows.getInt(4)
                                if (Round2Normalizer.clean(text).isBlank()) continue
                                eligible++
                                val vector = embedder.embed(text, SeforimEmbedder.Role.PASSAGE)
                                val document = Document().apply {
                                    add(StoredField("line_id", lineId))
                                    add(StoredField("book_id", bookId))
                                    add(IntPoint("book_id", bookId.toInt()))
                                    add(IntPoint("is_base_book", isBaseBook))
                                    add(KnnFloatVectorField("vec", vector, VectorSimilarityFunction.COSINE))
                                }
                                writer.addDocument(document)
                                indexed++
                                if (indexed % 10_000L == 0L) println("shard $shardIndex: $indexed / $eligible encoded")
                            }
                        }
                    }
                }
                writer.commit()
            }
        }
    }
    require(indexed == eligible && indexed > 0) { "Incomplete or empty semantic shard" }
    val properties = Properties().apply {
        setProperty("format", "zayit-round2-1")
        setProperty("dimension", "256")
        setProperty("shardIndex", shardIndex.toString())
        setProperty("shardCount", shardCount.toString())
        setProperty("eligible", eligible.toString())
        setProperty("indexed", indexed.toString())
        setProperty("databaseSha256", sha256(db))
        setProperty("modelSha256", sha256(model))
        setProperty("tokenizerSha256", sha256(tokenizer))
    }
    Files.newOutputStream(out.resolve("semantic.properties")).use { properties.store(it, "Zayit Round 2 index") }
    println("Completed shard $shardIndex/$shardCount: $indexed vectors in $out")
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
