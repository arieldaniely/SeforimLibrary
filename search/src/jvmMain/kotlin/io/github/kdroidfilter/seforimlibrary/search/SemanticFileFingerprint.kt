package io.github.kdroidfilter.seforimlibrary.search

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties

/** Reuses SHA-256 for an unchanged immutable corpus file across app launches. */
object SemanticFileFingerprint {
    @Synchronized
    fun hash(path: Path): String {
        val stamp = stamp(path)
        val cached =
            runCatching {
                Properties().apply { Files.newInputStream(cachePath(path)).use { load(it) } }
            }.getOrNull()
        val value = cached?.getProperty("sha256")
        if (cached?.getProperty("stamp") == stamp && value?.matches(Regex("[0-9a-f]{64}")) == true) {
            return value
        }
        val digest = sha256(path)
        check(stamp(path) == stamp) { "File changed during semantic fingerprint validation: $path" }
        remember(path, digest)
        return digest
    }

    /** Records a digest computed from the actual bytes while installing the database. */
    @Synchronized
    fun remember(
        path: Path,
        digest: String,
    ) {
        require(digest.matches(Regex("[0-9a-f]{64}")))
        // Read-only installations can still search; cache persistence is optional.
        runCatching {
            val properties =
                Properties().apply {
                    setProperty("stamp", stamp(path))
                    setProperty("sha256", digest)
                }
            val target = cachePath(path)
            val temporary = Files.createTempFile(target.parent, ".fingerprint-", ".tmp")
            try {
                Files.newOutputStream(temporary).use { properties.store(it, "Immutable semantic asset fingerprint") }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    private fun cachePath(path: Path): Path = path.resolveSibling(".${path.fileName}.sha256-cache")

    private fun stamp(path: Path): String {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        return listOf(
            path.toRealPath(),
            attributes.size(),
            attributes.lastModifiedTime(),
            attributes.creationTime(),
            attributes.fileKey(),
        ).joinToString("|")
    }
}
