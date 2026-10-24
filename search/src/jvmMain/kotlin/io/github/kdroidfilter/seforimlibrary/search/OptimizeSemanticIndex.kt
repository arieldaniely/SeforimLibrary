package io.github.kdroidfilter.seforimlibrary.search

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.index.ConcurrentMergeScheduler
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.FSDirectory
import java.nio.file.Files
import java.nio.file.Path

/** CPU-only release step: merge the already encoded index, without loading any inference model. */
fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: OptimizeSemanticIndex <database> <model-directory> <index-directory>" }
    val database = Path.of(args[0])
    val model = Path.of(args[1])
    val index = Path.of(args[2])
    VectorSearcher(index, 256, database, model).use { }
    Files.list(index).use { paths ->
        paths.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("shard-") }.sorted().forEach { shard ->
            optimizeSemanticShard(shard)
        }
    }
    VectorSearcher(index, 256, database, model).use { }
}

internal fun optimizeSemanticShard(shard: Path) {
    FSDirectory.open(shard).use { directory ->
        val segments = DirectoryReader.open(directory).use { it.leaves().size }
        if (segments > 1) {
            IndexWriter(
                directory,
                IndexWriterConfig(StandardAnalyzer()).apply {
                    openMode = IndexWriterConfig.OpenMode.APPEND
                    ramBufferSizeMB = 256.0
                    mergeScheduler = ConcurrentMergeScheduler().apply { setMaxMergesAndThreads(2, 1) }
                },
            ).use { writer ->
                writer.forceMerge(1)
                writer.commit()
            }
        }
        println("Optimized ${shard.fileName}: $segments segments -> 1")
    }
}
