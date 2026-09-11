package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.github.kdroidfilter.seforimlibrary.common.db.SEFORIM_DB_PAGE_SIZE_PRAGMA
import io.github.kdroidfilter.seforimlibrary.common.ids.IdAllocator
import io.github.kdroidfilter.seforimlibrary.common.ids.InMemoryIdAllocator
import io.github.kdroidfilter.seforimlibrary.common.ids.SqliteIdAllocator
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import io.github.kdroidfilter.seforimlibrary.db.SeforimDb
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * One-step conversion: Sefaria export -> SQLite (direct import, sans Otzaria intermédiaire).
 *
 * Usage:
 *   ./gradlew -p SeforimLibrary :sefariasqlite:generateSefariaSqlite -PseforimDb=/path/to.db [-PexportDir=/path/to/database_export]
 */
fun main(args: Array<String>) = runBlocking {
    Logger.setMinSeverity(Severity.Info)
    val logger = Logger.withTag("SefariaSqlite")

    val dbPath = args.getOrNull(0)
        ?: System.getProperty("seforimDb")
        ?: System.getenv("SEFORIM_DB")
        ?: Paths.get("build", "seforim.db").toString()
    val useMemoryDb = when {
        System.getProperty("inMemoryDb") != null -> System.getProperty("inMemoryDb") != "false"
        System.getenv("IN_MEMORY_DB") != null -> System.getenv("IN_MEMORY_DB") != "false"
        dbPath == ":memory:" -> true
        else -> false // bounded disk-backed generation is safe on small runners
    }
    val persistDbPath = System.getProperty("persistDb")
        ?: System.getenv("SEFORIM_DB_OUT")
        ?: dbPath

    val exportDirArg = args.getOrNull(1)
        ?: System.getProperty("exportDir")
        ?: System.getenv("SEFARIA_EXPORT_DIR")
    val exportRoot: Path = exportDirArg?.let { Paths.get(it) } ?: SefariaExportFetcher.ensureLocalExport(logger)

    require(!useMemoryDb || persistDbPath != ":memory:") {
        "inMemoryDb requires persistDb/SEFORIM_DB_OUT to name the output file"
    }

    // Every build is persisted to a private candidate. The currently published
    // DB is replaced only after every phase and the build-state snapshot pass.
    val candidateDbPath = "$persistDbPath.building"
    val workingDbPath = if (useMemoryDb) dbPath else candidateDbPath

    val candidate = Paths.get(candidateDbPath).toAbsolutePath()
    Files.createDirectories(candidate.parent)
    Files.deleteIfExists(candidate)
    Files.deleteIfExists(Paths.get("$candidateDbPath-wal"))
    Files.deleteIfExists(Paths.get("$candidateDbPath-shm"))
    logger.i { "Building private database candidate at $candidate" }

    val jdbcUrl = if (useMemoryDb) "jdbc:sqlite::memory:" else "jdbc:sqlite:$workingDbPath"
    val driver = JdbcSqliteDriver(url = jdbcUrl)
    // Must run before any table is created so the DB is born with 16 KiB pages.
    // For the in-memory path, VACUUM INTO carries this page size to the on-disk file.
    driver.execute(null, SEFORIM_DB_PAGE_SIZE_PRAGMA, 0)
    SeforimDb.Schema.create(driver)
    val repository = SeforimRepository(workingDbPath, driver)
    var repositoryClosed = false
    var allocatorClosed = false

    // ─── IdAllocator wiring (delta-update support, DELTA_UPDATE_PLAN.md §3.5) ──
    // Load the previous build_state.db so primary keys remain stable across
    // builds. Path defaults to <dbPath>.buildstate; override via -PbuildStatePath
    // or BUILD_STATE_PATH env var.
    val buildStatePath: Path = run {
        val explicit = System.getProperty("buildStatePath")
            ?: System.getenv("BUILD_STATE_PATH")
        if (explicit != null) Paths.get(explicit)
        else Paths.get("$persistDbPath.buildstate")
    }
    val buildStateCandidate = Paths.get("$buildStatePath.building")
    Files.createDirectories(buildStatePath.toAbsolutePath().parent)
    Files.deleteIfExists(buildStateCandidate)
    val prevBuildState: Path? = buildStatePath.takeIf(Files::exists)
    val lowResource = (System.getProperty("lowResource")
        ?: System.getenv("SEFORIM_LOW_RESOURCE")
        ?: "true").toBoolean()
    val allocator: IdAllocator = if (lowResource) {
        SqliteIdAllocator.open(
            previous = prevBuildState,
            working = buildStateCandidate,
            logger = Logger.withTag("IdAllocator"),
        )
    } else {
        InMemoryIdAllocator.load(
            path = prevBuildState,
            logger = Logger.withTag("IdAllocator"),
        )
    }
    if (prevBuildState != null) {
        logger.i { "Loaded previous build_state from $prevBuildState" }
    } else {
        logger.i { "No previous build_state at $buildStatePath — starting fresh." }
    }

    // Build version: pulled from -PbuildVersion / BUILD_VERSION env, defaults to current epoch seconds.
    val buildVersion: Int = (System.getProperty("buildVersion")
        ?: System.getenv("BUILD_VERSION"))
        ?.toIntOrNull()
        ?: (System.currentTimeMillis() / 1000).toInt()

    try {
        val importer = SefariaDirectImporter(
            exportRoot = exportRoot,
            repository = repository,
            allocator = allocator,
            buildVersion = buildVersion,
            logger = Logger.withTag("SefariaDirect")
        )
        importer.import()

        if (useMemoryDb) {
            // VACUUM into the same private candidate used by disk-backed builds.
            val escaped = candidateDbPath.replace("'", "''")
            logger.i { "Persisting in-memory DB to private candidate via VACUUM INTO..." }
            repository.executeRawQuery("VACUUM INTO '$escaped'")
        }

        // Fail closed: a database without its matching stable-ID state must never
        // be published, otherwise the following delta build could remap IDs.
        allocator.snapshotTo(
            target = buildStateCandidate,
            extraMeta = mapOf(
                "generator" to "sefariasqlite",
                "generated_at" to java.time.Instant.now().toString(),
                "build_version" to buildVersion.toString(),
            ),
        )
        (allocator as? AutoCloseable)?.close()
        allocatorClosed = true

        if (!useMemoryDb) {
            repository.executeRawQuery("PRAGMA wal_checkpoint(TRUNCATE)")
        }
        repository.close()
        repositoryClosed = true

        // Promote the stable-ID state first. If the DB move fails the workflow
        // fails and no release is produced; a rerun can safely reuse these IDs.
        promoteCompletedFile(buildStateCandidate, buildStatePath)
        val target = Paths.get(persistDbPath).toAbsolutePath()
        promoteCompletedFile(candidate, target)
        logger.i { "Promoted completed database to $target" }

        logger.i { "Sefaria -> SQLite completed. DB at $persistDbPath" }
    } catch (e: Exception) {
        logger.e(e) { "Error during Sefaria->SQLite generation" }
        throw e
    } finally {
        if (!repositoryClosed) repository.close()
        if (!allocatorClosed) (allocator as? AutoCloseable)?.close()
    }
}

private fun promoteCompletedFile(candidate: Path, target: Path) {
    Files.createDirectories(target.toAbsolutePath().parent)
    runCatching {
        Files.move(
            candidate,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }.getOrElse {
        Files.move(candidate, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
