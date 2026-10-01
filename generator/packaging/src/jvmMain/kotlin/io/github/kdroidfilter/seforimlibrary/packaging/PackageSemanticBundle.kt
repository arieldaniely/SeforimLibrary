package io.github.kdroidfilter.seforimlibrary.packaging

import com.github.luben.zstd.ZstdOutputStream
import io.github.kdroidfilter.seforimlibrary.search.VectorSearcher
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private const val MODEL_FILE = "seforim-embed-round2-int8.onnx"
private const val MODEL_SHA = "659226865abd3a1bc833565ae6b2e2f48abdd7136285824a12966d4d3294cbf8"
private const val TOKENIZER_SHA = "0664287976ecb078bdfd8f5e5515dc87d8cb7f985a79a481aa1cdf7a7321c0e9"

/** Package a complete local semantic index without including the database itself. */
fun main(args: Array<String>) {
    require(args.size in 5..6) {
        "Usage: PackageSemanticBundle <seforim.db> <model-dir> <index-dir> <output.tar.zst> <split-bytes> [zstd-level]"
    }
    val db = Path.of(args[0]).toAbsolutePath()
    val model = Path.of(args[1]).toAbsolutePath()
    val index = Path.of(args[2]).toAbsolutePath()
    val output = Path.of(args[3]).toAbsolutePath()
    val splitBytes = args[4].toLong()
    val compressionLevel = args.getOrNull(5)?.toInt() ?: 22
    require(compressionLevel in 1..22)
    require(splitBytes > 0 && output.fileName.toString().endsWith(".tar.zst"))
    require(Files.isRegularFile(db) && Files.isDirectory(index))
    require(sha256(model.resolve(MODEL_FILE)) == MODEL_SHA) { "Unexpected Round 2 model" }
    require(sha256(model.resolve("tokenizer.json")) == TOKENIZER_SHA) { "Unexpected Round 2 tokenizer" }
    VectorSearcher(index, 256, db, model).use { }

    val files = Files.walk(index).use { stream ->
        stream.filter { Files.isRegularFile(it) }.sorted().toList()
    }
    require(files.isNotEmpty()) { "The semantic index is empty" }
    Files.createDirectories(output.parent)
    val split = SplitArchiveOutput(output, splitBytes)
    try {
        ZstdOutputStream(BufferedOutputStream(split, 1 shl 20), compressionLevel).use { zstd ->
            TarArchiveOutputStream(zstd).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                add(tar, model.resolve(MODEL_FILE), "model/$MODEL_FILE")
                add(tar, model.resolve("tokenizer.json"), "model/tokenizer.json")
                files.forEach { file ->
                    add(tar, file, "index/${index.relativize(file).toString().replace('\\', '/')}")
                }
                tar.finish()
            }
        }
        val archives = split.finish()
        val manifest = output.resolveSibling("semantic-bundle.json")
        val databaseSha = sha256(db)
        Files.writeString(
            manifest,
            """{"format":"zayit-round2-1","databaseSha256":"$databaseSha","archiveType":"tar.zst","parts":${archives.size}}""" + "\n",
        )
        println("Semantic bundle ready: ${archives.joinToString()} (database SHA-256 $databaseSha)")
        println("Manifest: $manifest")
    } catch (failure: Throwable) {
        split.abort()
        throw failure
    }
}

private fun add(tar: TarArchiveOutputStream, file: Path, name: String) {
    val entry = TarArchiveEntry(file.toFile(), name)
    tar.putArchiveEntry(entry)
    Files.newInputStream(file).use { input -> input.copyTo(tar, 1 shl 20) }
    tar.closeArchiveEntry()
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** One tar.zst file below the threshold; otherwise sequential parts of one compressed stream. */
private class SplitArchiveOutput(private val target: Path, private val maxPartBytes: Long) : OutputStream() {
    private var part = 1
    private var partBytes = 0L
    private var closed = false
    private var output = BufferedOutputStream(Files.newOutputStream(temporary(part)), 1 shl 20)

    private fun temporary(number: Int): Path = target.resolveSibling("${target.fileName}.building.part%02d".format(number))

    override fun write(value: Int) {
        val one = byteArrayOf(value.toByte())
        write(one, 0, 1)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!closed)
        var position = offset
        var remaining = length
        while (remaining > 0) {
            if (partBytes == maxPartBytes) {
                output.close()
                part++
                partBytes = 0
                output = BufferedOutputStream(Files.newOutputStream(temporary(part)), 1 shl 20)
            }
            val count = minOf(remaining.toLong(), maxPartBytes - partBytes).toInt()
            output.write(bytes, position, count)
            position += count
            remaining -= count
            partBytes += count
        }
    }

    override fun flush() = output.flush()

    override fun close() {
        if (!closed) {
            output.close()
            closed = true
        }
    }

    fun finish(): List<Path> {
        close()
        val paths = (1..part).map { number ->
            if (part == 1) target else target.resolveSibling("${target.fileName}.part%02d".format(number))
        }
        require(paths.none { Files.exists(it) }) { "Semantic bundle output already exists" }
        val moved = mutableListOf<Path>()
        try {
            paths.forEachIndexed { index, path ->
                Files.move(temporary(index + 1), path, StandardCopyOption.ATOMIC_MOVE)
                moved.add(path)
            }
        } catch (failure: Throwable) {
            moved.forEach { runCatching { Files.deleteIfExists(it) } }
            throw failure
        }
        return paths
    }

    fun abort() {
        runCatching { close() }
        (1..part).forEach { runCatching { Files.deleteIfExists(temporary(it)) } }
    }
}
