package com.voicetasks

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The floating card: listens, parses, appends to the vault, then closes itself.
 *
 * Speech recognizers stop at the first pause, so after each result it quietly listens again
 * and only finishes once you've been silent for [KEEP_LISTENING_MS].
 */
class CaptureActivity : ComponentActivity() {

    private sealed interface UiState {
        data object Listening : UiState
        data class Working(val transcript: String) : UiState
        data class Saved(val saved: TaskSaver.Saved) : UiState
        data class Failed(val message: String, val transcript: String? = null, val needsSetup: Boolean = false) : UiState
    }

    private var state by mutableStateOf<UiState>(UiState.Listening)
    private var liveText by mutableStateOf("")
    private var level by mutableFloatStateOf(0f)

    private val settings by lazy { Settings(this) }
    private val segments = mutableListOf<String>()
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = true
    private var finishTimer: Job? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else fail("Voice Tasks needs the microphone to hear your task.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { CaptureCard() } }

        if (settings.vaultUri == null) {
            fail("Choose your Obsidian vault folder first.", needsSetup = true)
            return
        }
        // Text shared from another app (or Tasker) skips listening and goes straight to parsing.
        val shared = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        if (!shared.isNullOrBlank()) {
            liveText = shared
            process()
            return
        }
        // Load the model while you're talking, so parsing is quick once you stop.
        lifecycleScope.launch { TaskParser.warmUp(applicationContext) }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    private fun startListening() {
        recognizer?.destroy()
        val useOnDevice = onDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        recognizer = (
            if (useOnDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
            ).apply {
            setRecognitionListener(Listener(useOnDevice))
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
            )
        }
    }

    private inner class Listener(private val isOnDevice: Boolean) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() {
            finishTimer?.cancel()
        }
        override fun onRmsChanged(rmsdB: Float) {
            level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (state != UiState.Listening) return
            val partial = partialResults.bestText()
            liveText = (segments + partial).filter { it.isNotBlank() }.joinToString(" ")
        }

        override fun onResults(results: Bundle?) {
            if (state != UiState.Listening) return
            val text = results.bestText()
            if (text.isNotBlank()) segments += text
            liveText = segments.joinToString(" ")
            if (segments.isEmpty()) {
                fail("Didn't catch that.")
                return
            }
            // Keep listening in case you pause mid-thought; finish if nothing more comes.
            startListening()
            finishTimer?.cancel()
            finishTimer = lifecycleScope.launch {
                delay(KEEP_LISTENING_MS)
                process()
            }
        }

        override fun onError(error: Int) {
            if (state != UiState.Listening) return
            val languageMissing = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            when {
                // The on-device recognizer lacks a language pack; fall back to the default one.
                isOnDevice && languageMissing && segments.isEmpty() -> {
                    onDevice = false
                    startListening()
                }
                segments.isNotEmpty() || liveText.isNotBlank() -> process()
                error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    fail("Didn't catch that.")
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    fail("Voice Tasks needs the microphone to hear your task.")
                else -> fail("Speech recognition failed (error $error).")
            }
        }
    }

    private fun process() {
        if (state != UiState.Listening) return
        finishTimer?.cancel()
        recognizer?.destroy()
        recognizer = null
        val transcript = liveText.trim()
        if (transcript.isEmpty()) {
            fail("Didn't catch that.")
            return
        }
        if (settings.vaultUri == null) return fail("Choose your Obsidian vault folder first.", needsSetup = true)

        state = UiState.Working(transcript)
        lifecycleScope.launch {
            val saved = try {
                TaskSaver.save(applicationContext, transcript)
            } catch (e: Exception) {
                fail("Couldn't write to ${TaskSaver.destination(settings, LocalDate.now())}: ${e.message}", transcript)
                return@launch
            }
            state = UiState.Saved(saved)
            delay(SHOW_SAVED_MS)
            finish()
        }
    }

    private fun retry() {
        segments.clear()
        liveText = ""
        state = UiState.Listening
        startListening()
    }

    private fun fail(message: String, transcript: String? = null, needsSetup: Boolean = false) {
        finishTimer?.cancel()
        recognizer?.destroy()
        recognizer = null
        state = UiState.Failed(message, transcript ?: liveText.takeIf { it.isNotBlank() }, needsSetup)
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Task", text))
    }

    private fun Bundle?.bestText(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    @Composable
    private fun CaptureCard() {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (state !is UiState.Working) finish()
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    // Swallow taps on the card so they don't close it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (val s = state) {
                        UiState.Listening -> Listening()
                        is UiState.Working -> Working(s)
                        is UiState.Saved -> Saved(s)
                        is UiState.Failed -> Failed(s)
                    }
                }
            }
        }
    }

    @Composable
    private fun Listening() {
        val pulse by animateFloatAsState(1f + level * 0.5f, label = "pulse")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(18.dp)
                    .scale(pulse)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Text("Listening…", style = MaterialTheme.typography.titleMedium)
        }
        if (liveText.isBlank()) {
            Text(
                "Say a task, like “call mom tomorrow, it's important”",
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(liveText, style = MaterialTheme.typography.bodyLarge)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = ::finish) { Text("Cancel") }
            Spacer(Modifier.size(8.dp))
            Button(onClick = ::process, enabled = liveText.isNotBlank()) { Text("Done") }
        }
    }

    @Composable
    private fun Working(s: UiState.Working) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Adding…", style = MaterialTheme.typography.titleMedium)
        }
        Text("“${s.transcript}”", style = MaterialTheme.typography.bodyLarge)
    }

    @Composable
    private fun Saved(s: UiState.Saved) {
        Text("✓ ${s.saved.summary}", style = MaterialTheme.typography.titleMedium)
        s.saved.tasks.forEach { Text(it.preview, style = MaterialTheme.typography.bodyMedium) }
        Text(
            "Parsed with ${s.saved.engine.label}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    private fun Failed(s: UiState.Failed) {
        Text(s.message, style = MaterialTheme.typography.titleMedium)
        s.transcript?.let { Text("“$it”", style = MaterialTheme.typography.bodyLarge) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = ::finish) { Text("Close") }
            s.transcript?.let { TextButton(onClick = { copy(it) }) { Text("Copy text") } }
            Spacer(Modifier.size(8.dp))
            if (s.needsSetup) {
                Button(onClick = {
                    startActivity(Intent(this@CaptureActivity, MainActivity::class.java))
                    finish()
                }) { Text("Open settings") }
            } else {
                Button(onClick = ::retry) { Text("Try again") }
            }
        }
    }

    private companion object {
        const val KEEP_LISTENING_MS = 1_500L
        const val SHOW_SAVED_MS = 1_600L
    }
}
