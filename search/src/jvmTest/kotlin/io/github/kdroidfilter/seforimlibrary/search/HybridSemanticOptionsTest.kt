package io.github.kdroidfilter.seforimlibrary.search

import kotlinx.coroutines.test.runTest
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.KnnByteVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.store.FSDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/** Set SEFORIM_EMBED_MODEL to exercise the full adapter without committing model assets. */
class HybridSemanticOptionsTest {
    @Test
    fun thresholdPagesBeyondLegacyLimitAndSessionsSnapshotOptions() =
        runTest {
            val model = System.getenv("SEFORIM_EMBED_MODEL")?.let(Path::of) ?: return@runTest
            val embedder = SeforimEmbedder.tryLoad(model) ?: error("Configured model failed to load")
            val root = Files.createTempDirectory("hybrid-semantic-options")
            try {
                embedder.use { encoder ->
                    val query = "השבת אבדה"
                    val vector = Int8Vectors.quantize(encoder.embed(query))
                    val lexicalPath = Files.createDirectories(root.resolve("lexical"))
                    FSDirectory.open(lexicalPath).use { directory ->
                        IndexWriter(directory, IndexWriterConfig(StandardAnalyzer())).use { it.commit() }
                    }
                    val index = Files.createDirectories(root.resolve("vectors"))
                    val shard = Files.createDirectories(index.resolve("shard-00"))
                    FSDirectory.open(shard).use { directory ->
                        IndexWriter(directory, IndexWriterConfig(StandardAnalyzer())).use { writer ->
                            repeat(400) { row ->
                                writer.addDocument(
                                    Document().apply {
                                        add(StoredField("line_id", row.toLong()))
                                        add(StoredField("book_id", 7L))
                                        add(IntPoint("book_id", 7))
                                        add(IntPoint("is_base_book", 1))
                                        add(KnnByteVectorField("vec", vector, VectorSimilarityFunction.COSINE))
                                    },
                                )
                            }
                        }
                    }
                    val manifest =
                        Properties().apply {
                            setProperty("format", "zayit-round2-1")
                            setProperty("vectorEncoding", Int8Vectors.ENCODING)
                            setProperty("dimension", "256")
                            setProperty("shardIndex", "0")
                            setProperty("shardCount", "1")
                            setProperty("eligible", "400")
                            setProperty("indexed", "400")
                            setProperty("modelSha256", SemanticFileFingerprint.hash(model.resolve("seforim-embed-round2-int8.onnx")))
                            setProperty("tokenizerSha256", SemanticFileFingerprint.hash(model.resolve("tokenizer.json")))
                        }
                    Files.newOutputStream(shard.resolve("semantic.properties")).use { manifest.store(it, null) }
                    var options = SemanticSearchOptions()
                    HybridSearchEngine(
                        lexical = LuceneSearchEngine(lexicalPath),
                        modelDir = model,
                        indexDir = index,
                        sharedEmbedderProvider = { encoder },
                        optionsProvider = { options },
                        resolveLine = { lineId, bookId, _ -> LineHit(bookId, "ספר", lineId, lineId.toInt(), "", 0f, "") },
                    ).use { engine ->
                        val baseline = requireNotNull(engine.openSession(query, mode = SearchMode.SMART))
                        options = SemanticSearchOptions(minimumSimilarity = 0.99f, cacheQueries = true)
                        baseline.use { assertEquals(150L, it.nextPage(500, snippets = false)?.totalHits) }
                        requireNotNull(engine.openSession(query, mode = SearchMode.SMART)).use { session ->
                            val ids = mutableSetOf<Long>()
                            while (true) {
                                val page = session.nextPage(73, snippets = false) ?: break
                                assertEquals(400L, page.totalHits)
                                ids.addAll(page.hits.map { it.lineId })
                                if (page.isLastPage) break
                            }
                            assertEquals(400, ids.size)
                            assertEquals(SearchMode.SMART, session.effectiveMode)
                        }
                        options = options.copy(maximumResults = 47)
                        requireNotNull(engine.openSession(query, mode = SearchMode.SMART)).use {
                            assertEquals(47L, it.nextPage(100, snippets = false)?.totalHits)
                        }
                    }
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
