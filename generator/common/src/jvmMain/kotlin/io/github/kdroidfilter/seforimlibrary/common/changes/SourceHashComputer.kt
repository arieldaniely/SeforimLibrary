package io.github.kdroidfilter.seforimlibrary.common.changes

import io.github.kdroidfilter.seforimlibrary.common.buildstate.BookKey
import io.github.kdroidfilter.seforimlibrary.common.buildstate.BookSourceHash
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Computes a canonical sha256 hash per book from its source artefact.
 *
 * The hash is opaque — its only contract is "identical input ⇒ identical
 * hash"; it has no meaning beyond detecting *whether* a book's source has
 * changed since the previous build. See `DELTA_UPDATE_PLAN.md` §6.2.
 *
 * Implementations:
 * - [SefariaSourceHashComputer] : hashes `merged.json` + accompanying schema.
 * - [OtzariaSourceHashComputer] : reads from `files_manifest.json` (per-file
 *   sha256 already provided by the upstream export).
 */
interface SourceHashComputer {

    /**
     * Walks the source tree under [root] and emits one entry per discovered
     * book. The [BookKey] follows the natural key used by [io.github.
     * kdroidfilter.seforimlibrary.common.ids.IdAllocator.bookId].
     */
    fun compute(root: Path, version: Int): Map<BookKey, BookSourceHash>
}

/**
 * Sefaria implementation: walks `json/<...>/<book>/merged.json` and hashes
 * `merged.json` bytes plus the matching `schemas/<title>.json` if present
 * (the schema influences the rendered output and so contributes to the
 * "did the source change?" signal).
 *
 * The book natural key is `(sourceName="Sefaria", canonicalHeTitle=<title from merged.json>)`
 * to match `SefariaDirectImporter.canonicalHeTitle`.
 */
class SefariaSourceHashComputer(
    private val sourceName: String = "Sefaria",
    private val titleExtractor: (Path) -> String? = ::defaultExtractHeTitle,
) : SourceHashComputer {

    override fun compute(root: Path, version: Int): Map<BookKey, BookSourceHash> {
        val jsonDir = root.resolve("json")
        val schemaDir = root.resolve("schemas")
        require(Files.isDirectory(jsonDir)) { "Missing json/ under $root" }

        val out = HashMap<BookKey, BookSourceHash>()
        Files.walk(jsonDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().equals("merged.json", ignoreCase = true) }
                .forEach { mergedPath ->
                    val heTitle = titleExtractor(mergedPath) ?: return@forEach
                    val md = MessageDigest.getInstance("SHA-256")
                    md.update(Files.readAllBytes(mergedPath))
                    val schemaPath = schemaDir.resolve(sanitizeSchemaFilename(heTitle) + ".json")
                    if (Files.exists(schemaPath)) md.update(Files.readAllBytes(schemaPath))
                    out[BookKey(sourceName, heTitle)] = BookSourceHash(md.digest(), version)
                }
        }
        return out
    }

    companion object {
        /**
         * Pulls `heTitle` directly from the merged.json header. We avoid a
         * full JSON parse to keep this fast; merged.json always starts with a
         * top-level object whose key order is stable in the upstream export.
         */
        fun defaultExtractHeTitle(mergedJson: Path): String? {
            val text = Files.readString(mergedJson)
            val heTitleMatch = Regex(""""heTitle"\s*:\s*"((?:\\.|[^"\\])*)"""").find(text)
                ?: return null
            return heTitleMatch.groupValues[1]
                // Unescape minimal JSON escapes that show up in Hebrew titles
                .replace("\\\"", "\"").replace("\\\\", "\\")
        }

        private fun sanitizeSchemaFilename(title: String): String =
            title.replace(' ', '_').replace("/", "_")
    }
}

/**
 * Otzaria implementation: reads `files_manifest.json` and exposes its
 * pre-computed sha256 per file. The natural key is
 * `(sourceName=<resolved manifest source>, canonicalHeTitle=<filename without ext>)`.
 *
 * For the Phase-2 detector we don't need to mimic the importer's actual
 * source resolution; the manifest's sha256 already pinpoints exactly the
 * touched files. The natural key must match what the importer records, which
 * is `(source-for-manifest-key, normalizeBookTitle(filename-minus-ext))` — so
 * both the source name and the title are resolved through injected functions.
 *
 * [sourceNameResolver] receives the **raw manifest key** (e.g.
 * `"DictaToOtzaria/…/אוצריא/…/X.txt"`) — NOT a filesystem Path — and must return
 * the same source name the importer assigns to that book (the manifest key's
 * source prefix, resolved through the importer's own `manifestSourcesByRel`).
 * Passing a Path to the importer's `getSourceNameFor` does NOT work here: it
 * relativizes against `libraryRoot` (which may be uninitialized this early, and
 * in any case does not match a manifest key that carries a source prefix), so
 * every book collapses to "Unknown" and its BookKey never matches the allocator.
 *
 * [titleNormalizer] MUST be the importer's `normalizeBookTitle`; the identity
 * default is for tests only. Without it, books whose filename differs from its
 * normalized title (trailing spaces, `''`→`״`, …) get a key the importer never
 * records → `peekBookId` misses → their change detection silently breaks.
 */
class OtzariaSourceHashComputer(
    private val sourceNameResolver: (String) -> String = { "Otzaria" },
    private val titleNormalizer: (String) -> String = { it },
) : SourceHashComputer {

    override fun compute(root: Path, version: Int): Map<BookKey, BookSourceHash> {
        val manifest = root.resolve("files_manifest.json")
        require(Files.isRegularFile(manifest)) { "Missing files_manifest.json under $root" }

        val text = Files.readString(manifest)
        // files_manifest.json is NESTED: `"<path>": {"hash": "<sha256>"}` (verified against
        // the real manifest — every entry is an object, never a bare string). The path key is
        // followed by its object; we pull the `hash` field from within that single object.
        // `[^{}]*?` keeps the match inside one object so it can't span into a sibling entry.
        val regex = Regex(""""([^"\\]*\.(?:txt|json))"\s*:\s*\{[^{}]*?"hash"\s*:\s*"([0-9a-fA-F]{64})"""")
        val out = HashMap<BookKey, BookSourceHash>()
        regex.findAll(text).forEach { m ->
            val relPath = m.groupValues[1]
            val sha256Hex = m.groupValues[2]
            if (!relPath.endsWith(".txt")) return@forEach
            val rawTitle = relPath.substringAfterLast('/').substringBeforeLast('.')
            // Normalize identically to the importer (`normalizeBookTitle(rawTitle)`) so the
            // BookKey matches what it records — see the injected [titleNormalizer].
            val title = titleNormalizer(rawTitle)
            if (title.isBlank()) return@forEach
            val source = sourceNameResolver(relPath)
            val hash = ByteArray(32) { i ->
                val hi = Character.digit(sha256Hex[i * 2], 16)
                val lo = Character.digit(sha256Hex[i * 2 + 1], 16)
                ((hi shl 4) or lo).toByte()
            }
            out[BookKey(source, title)] = BookSourceHash(hash, version)
        }
        return out
    }
}
