package com.voicetasks

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

/** Settings: vault folder, inbox file, task format, which parser is active, and setup shortcuts. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { SettingsScreen() } }
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }

    var vault by remember { mutableStateOf(settings.vaultUri) }
    var inbox by remember { mutableStateOf(settings.inboxPath) }
    var saveMode by remember { mutableStateOf(settings.saveMode) }
    var taskFolder by remember { mutableStateOf(settings.taskFolder) }
    var noteStyle by remember { mutableStateOf(settings.noteStyle) }
    var format by remember { mutableStateOf(settings.format) }
    var hasMic by remember { mutableStateOf(false) }
    // Bumped whenever something that affects the active parser changes.
    var engineVersion by remember { mutableIntStateOf(0) }

    // Re-check things that can change while the app is in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            engineVersion++
        }
    }

    val pickVault = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            settings.vaultUri = uri
            vault = uri
        }
    }
    val tileLabel = stringResource(R.string.capture_label)
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Voice Tasks", style = MaterialTheme.typography.headlineMedium)

                Section("Obsidian vault") {
                    Text(VaultWriter.vaultName(context, vault)?.let { "Folder: $it" } ?: "No folder chosen yet")
                    Button(onClick = { pickVault.launch(vault) }) {
                        Text(if (vault == null) "Choose vault folder" else "Change folder")
                    }
                }

                Section("Where tasks go") {
                    SaveMode.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = saveMode == option) {
                                    saveMode = option
                                    settings.saveMode = option
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = saveMode == option, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                when (option) {
                                    SaveMode.ONE_FILE -> {
                                        Text("All in one file")
                                        Text("Each task is a line in the same note", style = MaterialTheme.typography.bodySmall)
                                    }
                                    SaveMode.FILE_PER_TASK -> {
                                        Text("A separate note for each task")
                                        Text("Named after the task, e.g. Email Sam.md", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    when (saveMode) {
                        SaveMode.ONE_FILE -> OutlinedTextField(
                            value = inbox,
                            onValueChange = {
                                inbox = it
                                settings.inboxPath = it.ifBlank { Settings.DEFAULT_INBOX }
                            },
                            label = { Text("File to add tasks to") },
                            supportingText = {
                                Text("Inside the vault. {date} becomes today, e.g. Daily/{date}.md")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SaveMode.FILE_PER_TASK -> {
                            OutlinedTextField(
                                value = taskFolder,
                                onValueChange = {
                                    taskFolder = it
                                    settings.taskFolder = it
                                },
                                label = { Text("Folder for task notes") },
                                supportingText = {
                                    Text("Inside the vault, e.g. Tasks or Tasks/Inbox. Leave empty for the top level.")
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("Inside each note", style = MaterialTheme.typography.labelLarge)
                            NoteStyle.entries.forEach { option ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .selectable(selected = noteStyle == option) {
                                            noteStyle = option
                                            settings.noteStyle = option
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = noteStyle == option, onClick = null)
                                    Column(Modifier.padding(start = 8.dp)) {
                                        when (option) {
                                            NoteStyle.TASK_LINE -> {
                                                Text("Task line")
                                                Text(
                                                    "One Obsidian Tasks line, in the format below",
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                            NoteStyle.TASKNOTES -> {
                                                Text("TaskNotes")
                                                Text(
                                                    "Status, priority, due date and tags as note properties",
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Section("Task format") {
                    val example = ParsedTask("Email Sam", LocalDate.now().plusDays(1), Priority.HIGH)
                    TaskFormat.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = format == option) {
                                    format = option
                                    settings.format = option
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = format == option, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(if (option == TaskFormat.EMOJI) "Emoji" else "Dataview")
                                Text(
                                    TaskFormatter.format(example, option),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                PlacesSection()

                Section("Due dates") {
                    var defaultTime by remember { mutableStateOf(settings.defaultDueTime) }
                    val valid = defaultTime.isBlank() || Settings.parseTime(defaultTime) != null
                    OutlinedTextField(
                        value = defaultTime,
                        onValueChange = {
                            defaultTime = it
                            if (it.isBlank() || Settings.parseTime(it) != null) settings.defaultDueTime = it.trim()
                        },
                        label = { Text("Default due time") },
                        isError = !valid,
                        supportingText = {
                            Text(
                                if (valid) {
                                    "Tasks with no date are due today; tasks with no time get this time. " +
                                        "24-hour, e.g. 19:00. Leave empty to turn off."
                                } else {
                                    "Use 24-hour time, like 19:00"
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Times are saved in TaskNotes notes. Obsidian Tasks lines only hold a date.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                ParserSection(engineVersion) { engineVersion++ }

                Section("Quick access") {
                    if (!hasMic) {
                        Button(onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) }) {
                            Text("Allow microphone")
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        OutlinedButton(onClick = {
                            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                                ComponentName(context, CaptureTileService::class.java),
                                tileLabel,
                                Icon.createWithResource(context, R.drawable.ic_mic),
                                ContextCompat.getMainExecutor(context),
                            ) {}
                        }) { Text("Add Quick Settings tile") }
                    }
                    Text(
                        "Side key: Settings › Advanced features › Side button › Double press › Open app › Quick Task.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { context.startActivity(Intent(context, CaptureActivity::class.java)) }) {
                        Text("Try it now")
                    }
                }

                TryParserSection(format)
            }
        }
    }
}

@Composable
private fun ParserSection(engineVersion: Int, onEngineChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf<TaskParser.Engine?>(null) }
    var nanoStatus by remember { mutableStateOf<Int?>(null) }
    var nanoProgress by remember { mutableStateOf<String?>(null) }
    var importProgress by remember { mutableStateOf<String?>(null) }
    val modelFile = remember { TaskParser.gemmaModelFile(context) }
    val hasGemma = engineVersion >= 0 && modelFile.exists()

    LaunchedEffect(engineVersion) {
        nanoStatus = TaskParser.nanoStatus()
        active = TaskParser.activeEngine(context)
    }

    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importProgress = "Copying…"
                importProgress = runCatching { copyModel(context, uri, modelFile) { importProgress = it } }
                    .fold({ null }, { "Import failed: ${it.message}" })
                TaskParser.unloadGemma()
                onEngineChanged()
            }
        }
    }

    Section("Parser") {
        Text("In use: ${active?.label ?: "…"}", style = MaterialTheme.typography.titleSmall)

        Text("Gemini Nano (built into the phone)", style = MaterialTheme.typography.labelLarge)
        Text(
            when (nanoStatus) {
                null -> "Checking…"
                FeatureStatus.AVAILABLE -> "Ready"
                FeatureStatus.DOWNLOADABLE -> "Supported, needs a one-time download"
                FeatureStatus.DOWNLOADING -> "Downloading…"
                else -> "Not available on this phone"
            },
        )
        nanoProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (nanoStatus == FeatureStatus.DOWNLOADABLE) {
            Button(onClick = {
                scope.launch {
                    TaskParser.downloadNano().collect { status ->
                        nanoProgress = when (status) {
                            is DownloadStatus.DownloadStarted -> "Starting download…"
                            is DownloadStatus.DownloadProgress -> "Downloaded ${status.totalBytesDownloaded / 1_000_000} MB"
                            is DownloadStatus.DownloadCompleted -> "Download complete"
                            is DownloadStatus.DownloadFailed -> "Download failed: ${status.e.message}"
                        }
                    }
                    onEngineChanged()
                }
            }) { Text("Download Gemini Nano") }
        }

        Text("Gemma (fallback model you import)", style = MaterialTheme.typography.labelLarge)
        if (hasGemma) {
            Text("Imported (${modelFile.length() / 1_000_000} MB)")
            OutlinedButton(onClick = {
                scope.launch {
                    TaskParser.unloadGemma()
                    modelFile.delete()
                    onEngineChanged()
                }
            }) { Text("Remove model") }
        } else {
            Text(
                "Only needed if Gemini Nano isn't available. Download gemma3-1b-it-int4.task from " +
                    "huggingface.co/litert-community/Gemma3-1B-IT, then import it here.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { importModel.launch(arrayOf("*/*")) }, enabled = importProgress == null) {
                Text("Import model file")
            }
        }
        importProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Type a sentence and see the task lines it would produce, without writing anything. */
@Composable
private fun TryParserSection(format: TaskFormat) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<String?>(null) }

    Section("Test the parser") {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Type what you'd say") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            scope.launch {
                output = "Parsing…"
                val result = TaskSaver.parse(context, input)
                // Show exactly what would be written with the current settings.
                val settings = Settings(context)
                val tasknotes = settings.saveMode == SaveMode.FILE_PER_TASK && settings.noteStyle == NoteStyle.TASKNOTES
                val now = ZonedDateTime.now()
                output = result.tasks.joinToString("\n") {
                    if (tasknotes) "${it.text}.md\n${TaskNotesFormatter.format(it, now)}" else TaskFormatter.format(it, format)
                } + "\n\n(${result.engine.label})"
            }
        }, enabled = input.isNotBlank()) { Text("Parse") }
        output?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private suspend fun copyModel(context: android.content.Context, source: Uri, target: File, onProgress: (String) -> Unit) {
    val partial = File(target.path + ".part")
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Can't open the file" }
            partial.outputStream().use { out ->
                val buffer = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    copied += n
                    if (copied % (50L shl 20) < n) {
                        withContext(Dispatchers.Main) { onProgress("Copied ${copied / 1_000_000} MB…") }
                    }
                }
            }
        }
        if (!partial.renameTo(target)) error("Couldn't save the model")
    }
}
