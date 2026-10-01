package io.github.kdroidfilter.seforimlibrary.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.nio.file.Paths
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlin.system.exitProcess
import kotlin.math.roundToInt

private const val APP_TITLE = "\u05d9\u05d1\u05d5\u05d0\u05df \u05e1\u05e4\u05e8\u05d9\u05dd"

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--worker") {
        exitProcess(runWorker(args.drop(1)))
    }
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    application {
        val pipeline = remember { ImportPipeline() }
        Window(
            onCloseRequest = {
                pipeline.cancel()
                exitApplication()
            },
            title = APP_TITLE,
        ) {
            ImporterScreen(pipeline)
        }
    }
}

@Composable
private fun ImporterScreen(pipeline: ImportPipeline) {
    var database by remember { mutableStateOf("") }
    var booksDirectory by remember { mutableStateOf("") }
    var linksDirectory by remember { mutableStateOf("") }
    var heapGb by remember { mutableStateOf("8") }
    var running by remember { mutableStateOf(false) }
    var stageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var logText by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val logScroll = rememberScrollState()

    fun appendLog(line: String) {
        EventQueue.invokeLater {
            val combined = if (logText.isEmpty()) line else "$logText\n$line"
            logText = if (combined.length > 160_000) combined.takeLast(160_000) else combined
        }
    }

    LaunchedEffect(logText.length) {
        logScroll.scrollTo(logScroll.maxValue)
    }

    MaterialTheme(colors = lightColors(primary = Color(0xFF315C4A))) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(APP_TITLE, style = MaterialTheme.typography.h4)
                    Text(
                        "\u05d1\u05d7\u05e8 \u05de\u05e1\u05d3 \u05e0\u05ea\u05d5\u05e0\u05d9\u05dd, \u05ea\u05d9\u05e7\u05d9\u05d9\u05ea \u05d0\u05d5\u05e6\u05e8\u05d9\u05d0 \u05d5\u05ea\u05d9\u05e7\u05d9\u05d9\u05ea JSON. \u05d4\u05ea\u05d5\u05db\u05e0\u05d4 \u05ea\u05d9\u05d9\u05d1\u05d0, \u05ea\u05d1\u05e0\u05d4 \u05e7\u05d8\u05dc\u05d5\u05d2 \u05d5\u05ea\u05d0\u05e0\u05d3\u05e7\u05e1.",
                    )

                    PathRow(
                        label = "\u05e7\u05d5\u05d1\u05e5 \u05de\u05e1\u05d3 \u05e0\u05ea\u05d5\u05e0\u05d9\u05dd",
                        value = database,
                        enabled = !running,
                        onValueChange = { database = it },
                        onBrowse = { chooseDatabase(database)?.let { database = it } },
                    )
                    PathRow(
                        label = "\u05ea\u05d9\u05e7\u05d9\u05d9\u05ea \u05d4\u05e1\u05e4\u05e8\u05d9\u05dd (\u05d0\u05d5\u05e6\u05e8\u05d9\u05d0)",
                        value = booksDirectory,
                        enabled = !running,
                        onValueChange = { booksDirectory = it },
                        onBrowse = { chooseDirectory(booksDirectory)?.let { booksDirectory = it } },
                    )
                    PathRow(
                        label = "\u05ea\u05d9\u05e7\u05d9\u05d9\u05ea \u05e7\u05d1\u05e6\u05d9 \u05d4\u05e7\u05d9\u05e9\u05d5\u05e8\u05d9\u05dd (JSON)",
                        value = linksDirectory,
                        enabled = !running,
                        onValueChange = { linksDirectory = it },
                        onBrowse = { chooseDirectory(linksDirectory)?.let { linksDirectory = it } },
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("\u05d6\u05d9\u05db\u05e8\u05d5\u05df \u05de\u05e8\u05d1\u05d9 (GB)")
                        Spacer(Modifier.width(12.dp))
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            OutlinedTextField(
                                value = heapGb,
                                onValueChange = { value -> heapGb = value.filter(Char::isDigit).take(2) },
                                enabled = !running,
                                modifier = Modifier.width(100.dp),
                                singleLine = true,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = !running,
                            onClick = {
                                running = true
                                failed = false
                                resultMessage = null
                                logText = ""
                                progress = 0f
                                scope.launch {
                                    try {
                                        val config = ImportConfig(
                                            database = Paths.get(database),
                                            booksDirectory = Paths.get(booksDirectory),
                                            linksDirectory = Paths.get(linksDirectory),
                                            maxHeapGb = heapGb.toIntOrNull() ?: 8,
                                        )
                                        withContext(Dispatchers.IO) {
                                            pipeline.run(
                                                config = config,
                                                onStage = { index, count, stage ->
                                                    EventQueue.invokeLater {
                                                        stageTitle = stage.title
                                                        progress = index.toFloat() / count
                                                    }
                                                },
                                                onLog = ::appendLog,
                                            )
                                        }
                                        progress = 1f
                                        resultMessage = "\u05d4\u05d9\u05d9\u05d1\u05d5\u05d0 \u05d4\u05d5\u05e9\u05dc\u05dd \u05d1\u05d4\u05e6\u05dc\u05d7\u05d4"
                                    } catch (error: Exception) {
                                        failed = true
                                        resultMessage = error.message ?: error::class.simpleName
                                        appendLog("ERROR: ${error.stackTraceToString()}")
                                    } finally {
                                        running = false
                                    }
                                }
                            },
                        ) { Text("\u05d4\u05ea\u05d7\u05dc \u05d9\u05d9\u05d1\u05d5\u05d0") }

                        Button(
                            enabled = running,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF9B3D3D)),
                            onClick = {
                                pipeline.cancel()
                                appendLog("Import cancelled by user")
                            },
                        ) { Text("\u05d1\u05d9\u05d8\u05d5\u05dc") }
                    }

                    if (running || progress > 0f) {
                        Text(if (stageTitle.isBlank()) "\u05de\u05ea\u05d7\u05d9\u05dc..." else stageTitle)
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).roundToInt()}%")
                    }
                    resultMessage?.let { message ->
                        Text(message, color = if (failed) Color(0xFFB00020) else Color(0xFF1B6E3A))
                    }

                    Text("\u05d9\u05d5\u05de\u05df \u05e4\u05e2\u05d5\u05dc\u05d5\u05ea", style = MaterialTheme.typography.h6)
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(logScroll).padding(8.dp),
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(logText.ifEmpty { "Logs will appear here" }, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PathRow(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(enabled = enabled, onClick = onBrowse) { Text("\u05d1\u05d7\u05d9\u05e8\u05d4...") }
    }
}

private fun chooseDatabase(current: String): String? = JFileChooser().run {
    dialogTitle = "\u05d1\u05d7\u05e8 \u05d0\u05d5 \u05e6\u05d5\u05e8 \u05de\u05e1\u05d3 \u05e0\u05ea\u05d5\u05e0\u05d9\u05dd"
    selectedFile = current.takeIf(String::isNotBlank)?.let(::File)
    if (showSaveDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile.absolutePath else null
}

private fun chooseDirectory(current: String): String? = JFileChooser().run {
    dialogTitle = "\u05d1\u05d7\u05e8 \u05ea\u05d9\u05e7\u05d9\u05d9\u05d4"
    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    currentDirectory = current.takeIf(String::isNotBlank)?.let(::File)
    if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile.absolutePath else null
}
