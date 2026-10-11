package io.github.kdroidfilter.seforimlibrary.search

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SemanticFileFingerprintTest {
    @Test
    fun reusesInstalledDigestAndInvalidatesChangedFile() {
        val directory = Files.createTempDirectory("semantic-fingerprint-test-")
        val file = directory.resolve("corpus.db")
        val cache = directory.resolve(".corpus.db.sha256-cache")
        try {
            Files.writeString(file, "original")
            val original = sha256(file)
            SemanticFileFingerprint.remember(file, original)
            assertEquals(original, SemanticFileFingerprint.hash(file))
            Files.writeString(file, "modified")
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000))
            assertNotEquals(original, SemanticFileFingerprint.hash(file))
            assertEquals(sha256(file), SemanticFileFingerprint.hash(file))
            Files.writeString(cache, "damaged cache")
            assertEquals(sha256(file), SemanticFileFingerprint.hash(file))
        } finally {
            Files.deleteIfExists(cache)
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
