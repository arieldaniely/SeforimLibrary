package io.github.kdroidfilter.seforimlibrary.bookimporter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.notExists

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "זית – יבוא ספרים לבסיס נתונים") {
        MaterialTheme {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ImporterApp()
                }
            }
        }
    }
}

@Composable
private fun ImporterApp() {
    val scope = rememberCoroutineScope()
    var dbPath by remember { mutableStateOf("") }
    val libraryRoots = remember { mutableStateListOf<String>() }
    var preview by remember { mutableStateOf<PreviewResult?>(null) }
    var runningJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("כדי להתחיל: בחרו קובץ DB ותיקיית ספרים") }
    var includeCatalog by remember { mutableStateOf(true) }
    var includeIndexes by remember { mutableStateOf(false) }
    var duplicatePolicy by remember { mutableStateOf(DuplicatePolicy.SKIP) }
    var policyMenuExpanded by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("מיזוג ספרים ל-DB של זית", style = MaterialTheme.typography.headlineSmall)
        Text(
            "כל הגדרה כוללת הסבר קצר כדי שהעבודה תהיה ברורה ופשוטה. " +
                "התוכנה יוצרת גיבוי אוטומטי לפני כל יבוא.",
            style = MaterialTheme.typography.bodyMedium
        )

        SettingCard(title = "1) קובץ בסיס נתונים (DB)", helper = "בחרו את קובץ ה-DB הקיים של זית שאליו יתווספו הספרים.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = dbPath,
                    onValueChange = { dbPath = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("נתיב קובץ DB") },
                    singleLine = true
                )
                Button(onClick = { pickFilePath()?.let { dbPath = it } }) { Text("בחירה") }
            }
        }

        SettingCard(
            title = "2) תיקיות ספרים",
            helper = "אפשר לבחור תיקייה אחת או כמה תיקיות. בכל נתיב צריכה להיות התיקייה 'אוצריא'."
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { pickDirectoryPath()?.let { libraryRoots.add(it) } }) { Text("הוספת תיקייה") }
                TextButton(onClick = { if (libraryRoots.isNotEmpty()) libraryRoots.removeLast() }) { Text("הסר אחרונה") }
                TextButton(onClick = { libraryRoots.clear() }) { Text("נקה הכל") }
            }

            if (libraryRoots.isEmpty()) {
                Text("עדיין לא נבחרו תיקיות.", style = MaterialTheme.typography.bodySmall)
            } else {
                Spacer(Modifier.height(6.dp))
                libraryRoots.forEachIndexed { index, path ->
                    Text("${index + 1}. $path", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SettingCard(
            title = "3) מדיניות כפילויות",
            helper = "בחירה זו מתועדת בלוגים לצורך שקיפות. מנגנון הייבוא מפעיל גם בדיקות כפילויות פנימיות."
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("המדיניות שנבחרה:")
                TextButton(onClick = { policyMenuExpanded = true }) { Text(duplicatePolicy.label) }
                DropdownMenu(expanded = policyMenuExpanded, onDismissRequest = { policyMenuExpanded = false }) {
                    DuplicatePolicy.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                duplicatePolicy = option
                                policyMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        SettingCard(
            title = "4) פעולות נוספות",
            helper = "עדכון קטלוג מומלץ ברוב המקרים. בניית אינדקסים מומלצת אם אתם רוצים חיפוש מעודכן מיד אחרי הייבוא."
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { includeCatalog = !includeCatalog }) {
                    Text(if (includeCatalog) "☑ עדכון catalog.pb" else "☐ עדכון catalog.pb")
                }
                TextButton(onClick = { includeIndexes = !includeIndexes }) {
                    Text(if (includeIndexes) "☑ בניית אינדקס חיפוש" else "☐ בניית אינדקס חיפוש")
                }
            }
        }

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("5) התחלת עבודה", style = MaterialTheme.typography.titleMedium)
                Text(
                    "קודם מומלץ להריץ סריקה מקדימה כדי לראות כמה ספרים ייקלטו. " +
                        "לאחר מכן הריצו יבוא מלא.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val validation = ImportPreflight.validateInputs(dbPath, libraryRoots.toList())
                                if (!validation.valid) {
                                    status = validation.message
                                    logs.add("[ERROR] ${validation.message}")
                                    return@launch
                                }
                                status = "מתבצעת סריקה מקדימה..."
                                preview = ImportPreflight.scanPreview(libraryRoots.map { File(it).toPath() })
                                status = "הסריקה המקדימה הסתיימה"
                            }
                        },
                        enabled = runningJob == null
                    ) { Text("סריקה מקדימה") }

                    Button(
                        onClick = {
                            val job = scope.launch(Dispatchers.IO) {
                                logs.clear()
                                progress = 0f
                                val validation = ImportPreflight.validateInputs(dbPath, libraryRoots.toList())
                                if (!validation.valid) {
                                    status = validation.message
                                    logs.add("[ERROR] ${validation.message}")
                                    return@launch
                                }

                                val runner = ImportCoordinator(
                                    dbPath = File(dbPath).toPath(),
                                    roots = libraryRoots.map { File(it).toPath() },
                                    includeCatalog = includeCatalog,
                                    includeIndexes = includeIndexes,
                                    duplicatePolicy = duplicatePolicy,
                                    progress = { ratio, message ->
                                        progress = ratio
                                        status = message
                                    },
                                    log = { logs.add(it) }
                                )

                                runCatching { runner.execute() }
                                    .onSuccess {
                                        progress = 1f
                                        status = "הייבוא הסתיים בהצלחה"
                                    }
                                    .onFailure {
                                        status = "הייבוא נכשל: ${it.message}"
                                    }
                            }
                            runningJob = job
                            job.invokeOnCompletion { runningJob = null }
                        },
                        enabled = runningJob == null
                    ) { Text("התחלת יבוא") }

                    Button(
                        onClick = {
                            runningJob?.cancel()
                            status = "נשלחה בקשת ביטול לתהליך"
                        },
                        enabled = runningJob != null
                    ) { Text("ביטול") }
                }
            }
        }

        if (runningJob != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp))
                Text("תהליך הייבוא רץ כרגע...")
            }
        }

        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text("סטטוס נוכחי: $status")

        preview?.let {
            ElevatedCard {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("תוצאות סריקה מקדימה", style = MaterialTheme.typography.titleMedium)
                    Text("ספרים שזוהו: ${it.totalBooks}")
                    Text("מספר קטגוריות: ${it.categories.size}")
                    if (it.categories.isNotEmpty()) {
                        Text("קטגוריות לדוגמה: ${it.categories.take(8).joinToString()}")
                    }
                    if (it.missingRoots.isNotEmpty()) {
                        Text("נתיבים לא תקינים: ${it.missingRoots.joinToString()}")
                    }
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("לוג תהליך", style = MaterialTheme.typography.titleMedium)
                Text("מוצגות עד 200 שורות אחרונות", style = MaterialTheme.typography.bodySmall)
                logs.takeLast(200).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    helper: String,
    content: @Composable () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(helper, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

private fun pickFilePath(): String? {
    val dialog = FileDialog(null as Frame?, "בחר קובץ DB", FileDialog.LOAD)
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(directory, file).absolutePath
}

private fun pickDirectoryPath(): String? {
    val chooser = javax.swing.JFileChooser().apply {
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        isMultiSelectionEnabled = false
    }
    val result = chooser.showOpenDialog(null)
    return if (result == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}

data class PreviewResult(
    val totalBooks: Int,
    val categories: Set<String>,
    val missingRoots: List<String>
)

enum class DuplicatePolicy(val label: String) {
    SKIP("דלג על כפילויות"),
    UPDATE("עדכן רשומות קיימות"),
    MERGE("נסה מיזוג חכם")
}

data class InputValidation(val valid: Boolean, val message: String)

private object ImportPreflight {
    fun validateInputs(dbPath: String, roots: List<String>): InputValidation {
        if (dbPath.isBlank()) return InputValidation(false, "יש לבחור קובץ DB")
        val db = File(dbPath)
        if (!db.exists() || !db.isFile) return InputValidation(false, "קובץ DB לא קיים")
        if (db.extension.lowercase() != "db") return InputValidation(false, "קובץ DB חייב להסתיים ב-.db")
        if (roots.isEmpty()) return InputValidation(false, "יש לבחור לפחות תיקייה אחת")
        val missing = roots.filter { File(it).toPath().let { p -> p.notExists() || !p.isDirectory() } }
        if (missing.isNotEmpty()) return InputValidation(false, "תיקיות לא תקינות: ${missing.joinToString()}")

        val invalidStructure = roots
            .map { File(it).toPath() }
            .filter { it.resolve("אוצריא").notExists() && it.fileName?.toString() != "אוצריא" }
        if (invalidStructure.isNotEmpty()) {
            return InputValidation(
                false,
                "כל נתיב חייב להיות תיקיית מקור של אוצריא (שמכילה 'אוצריא') או התיקייה 'אוצריא' עצמה: ${invalidStructure.joinToString()}"
            )
        }

        return InputValidation(true, "OK")
    }

    fun scanPreview(roots: List<Path>): PreviewResult {
        val missingRoots = roots.filter { it.notExists() || !it.isDirectory() }.map { it.toString() }
        val validRoots = roots - roots.filter { it.notExists() || !it.isDirectory() }
        val categories = mutableSetOf<String>()
        val books = validRoots.sumOf { root ->
            val library = resolveLibraryDir(root)
            if (library == null || library.notExists()) return@sumOf 0
            java.nio.file.Files.walk(library).use { stream ->
                stream.filter { java.nio.file.Files.isRegularFile(it) && it.extension == "txt" }
                    .peek { categories.add(it.parent?.fileName?.toString() ?: "") }
                    .count()
            }.toInt()
        }
        return PreviewResult(
            totalBooks = books,
            categories = categories.filter { it.isNotBlank() }.toSet(),
            missingRoots = missingRoots
        )
    }

    private fun resolveLibraryDir(root: Path): Path? {
        return when {
            root.fileName?.toString() == "אוצריא" -> root
            root.resolve("אוצריא").exists() -> root.resolve("אוצריא")
            else -> null
        }
    }
}
