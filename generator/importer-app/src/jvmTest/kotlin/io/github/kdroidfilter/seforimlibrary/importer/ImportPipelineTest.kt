package io.github.kdroidfilter.seforimlibrary.importer

import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportPipelineTest {
    @Test
    fun acceptsDirectBooksDirectoryWithAnyName() {
        val root = Files.createTempDirectory("import-layout")
        val books = root.resolve("my-books").createDirectory()

        assertEquals(root, ImportPipeline.sourceRootFor(books))
        assertEquals(books, ImportPipeline.libraryDirectoryFor(books))
    }

    @Test
    fun acceptsSourceRootContainingOtzariaDirectory() {
        val root = Files.createTempDirectory("import-layout")
        val books = root.resolve("\u05d0\u05d5\u05e6\u05e8\u05d9\u05d0").createDirectory()

        assertEquals(root, ImportPipeline.sourceRootFor(root))
        assertEquals(books, ImportPipeline.libraryDirectoryFor(root))
    }

    @Test
    fun pipelinePassesSeparateInputPathsAndOrdersAllStages() {
        val root = Files.createTempDirectory("import-layout")
        val books = root.resolve("books").createDirectory()
        val links = root.resolve("links").createDirectory()
        val db = root.resolve("library.db")
        val config = ImportConfig(db, books, links)

        val stages = ImportPipeline(
            javaExecutable = Files.createTempFile("java", ".exe"),
            classpath = "test",
        ).stages(config)

        assertEquals(4, stages.size)
        assertTrue(stages[0].mainClass.endsWith("GenerateLinesKt"))
        assertEquals(books.toAbsolutePath().toString(), stages[0].properties["booksDir"])
        assertTrue(stages[1].mainClass.endsWith("GenerateLinksKt"))
        assertEquals(links.toAbsolutePath().toString(), stages[1].properties["linksDir"])
        assertTrue(stages[2].mainClass.endsWith("BuildCatalogKt"))
        assertTrue(stages[3].mainClass.endsWith("BuildLuceneIndexKt"))
    }

    @Test
    fun workerAppliesPropertiesAndInvokesArrayMain() {
        WorkerFixture.receivedArguments = emptyArray()
        val exitCode = runWorker(
            listOf(
                WorkerFixture::class.java.name,
                "1",
                "importer.test.property=expected",
                "first",
                "second",
            )
        )

        assertEquals(0, exitCode)
        assertEquals("expected", System.getProperty("importer.test.property"))
        assertContentEquals(arrayOf("first", "second"), WorkerFixture.receivedArguments)
    }
}

object WorkerFixture {
    var receivedArguments: Array<String> = emptyArray()

    @JvmStatic
    fun main(args: Array<String>) {
        receivedArguments = args
    }
}
