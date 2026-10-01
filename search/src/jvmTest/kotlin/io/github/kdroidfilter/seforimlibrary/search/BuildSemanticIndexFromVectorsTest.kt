package io.github.kdroidfilter.seforimlibrary.search

import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.FieldInfos
import org.apache.lucene.index.VectorEncoding
import org.apache.lucene.store.FSDirectory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BuildSemanticIndexFromVectorsTest {
    @Test
    fun importsInt8AndLegacyRecordsAsByteVectors() {
        for (int8 in listOf(true, false)) withFixture(int8) { args, _ ->
            buildSemanticIndexFromVectors(args)
            val root = java.nio.file.Path.of(args[3])
            FSDirectory.open(root.resolve("shard-00")).use { directory ->
                DirectoryReader.open(directory).use { reader ->
                    assertEquals(VectorEncoding.BYTE, FieldInfos.getMergedFieldInfos(reader).fieldInfo("vec").vectorEncoding)
                }
            }
            VectorSearcher(root, dbPath = java.nio.file.Path.of(args[0]), modelDir = java.nio.file.Path.of(args[1])).use {
                assertEquals(1L, it.search(FloatArray(256) { index -> if (index == 0) 1f else 0f }, 1).single().lineId)
            }
        }
    }

    @Test
    fun rejectsTruncatedRecordAndWrongDimension() {
        withFixture(true) { args, bytes ->
            Files.write(java.nio.file.Path.of(args[2]), bytes.copyOf(bytes.size - 1))
            assertFailsWith<IllegalArgumentException> { buildSemanticIndexFromVectors(args) }
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 128)
            Files.write(java.nio.file.Path.of(args[2]), bytes)
            assertFailsWith<IllegalArgumentException> { buildSemanticIndexFromVectors(args) }
        }
    }

    @Test
    fun combinesGpuFilesIntoOneSearchableSegment() {
        withFixture(true) { args, bytes ->
            val vectors = Files.createDirectory(java.nio.file.Path.of(args[2]).resolveSibling("gpu-vectors"))
            Files.write(vectors.resolve("shard-00.bin"), bytes)
            val second = bytes.copyOf()
            ByteBuffer.wrap(second).order(ByteOrder.LITTLE_ENDIAN).putLong(12, 2L).putLong(20, 12L)
            Files.write(vectors.resolve("shard-01.bin"), second)
            // A GPU may have no eligible rows; its header-only stream is valid.
            Files.write(vectors.resolve("shard-02.bin"), bytes.copyOf(12))
            args[2] = vectors.toString()
            buildSemanticIndexFromVectors(args)
            val root = java.nio.file.Path.of(args[3])
            FSDirectory.open(root.resolve("shard-00")).use { directory ->
                DirectoryReader.open(directory).use { reader ->
                    assertEquals(2, reader.numDocs())
                    assertEquals(1, reader.leaves().size)
                }
            }
            VectorSearcher(root).use { searcher ->
                val query = FloatArray(256) { if (it == 0) 1f else 0f }
                repeat(3) {
                    assertEquals(
                        setOf(1L, 2L),
                        searcher.search(query, 2)
                            .map { it.lineId }
                            .toSet(),
                    )
                    assertEquals(
                        listOf(2L),
                        searcher.search(query, 2, bookIds = listOf(12L))
                            .map { it.lineId },
                    )
                }
            }
        }
    }

    @Test
    fun failedRebuildRemovesCompletionManifest() {
        withFixture(true) { args, bytes ->
            buildSemanticIndexFromVectors(args)
            val manifest = java.nio.file.Path.of(args[3]).resolve("shard-00/semantic.properties")
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 128)
            Files.write(java.nio.file.Path.of(args[2]), bytes)
            assertFailsWith<IllegalArgumentException> { buildSemanticIndexFromVectors(args) }
            assertEquals(false, Files.exists(manifest))
        }
    }

    private fun withFixture(int8: Boolean, block: (Array<String>, ByteArray) -> Unit) {
        val root = Files.createTempDirectory("semantic-record-test")
        try {
            val db = Files.write(root.resolve("seforim.db"), byteArrayOf(1))
            val model = Files.createDirectory(root.resolve("model"))
            Files.write(model.resolve("seforim-embed-round2-int8.onnx"), byteArrayOf(2))
            Files.write(model.resolve("tokenizer.json"), byteArrayOf(3))
            val buffer = ByteBuffer.allocate((if (int8) 12 else 0) + 20 + 256 * (if (int8) 1 else 4))
                .order(ByteOrder.LITTLE_ENDIAN)
            if (int8) buffer.put(Int8Vectors.FILE_MAGIC.toByteArray(Charsets.US_ASCII)).putInt(256)
            buffer.putLong(1).putLong(11).putInt(1)
            for (index in 0 until 256) {
                if (int8) buffer.put(if (index == 0) 127.toByte() else 0.toByte())
                else buffer.putFloat(if (index == 0) 1f else 0f)
            }
            val vectors = Files.write(root.resolve("vectors.bin"), buffer.array())
            block(arrayOf(db.toString(), model.toString(), vectors.toString(), root.resolve("index").toString(), "0", "1"),
                buffer.array())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
