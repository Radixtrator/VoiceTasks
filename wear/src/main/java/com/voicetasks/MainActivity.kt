package com.voicetasks

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Opens the watch's voice input right away, sends what you said to the phone app,
 * and shows the phone's answer ("Added 2 tasks to Inbox.md").
 */
class MainActivity : ComponentActivity() {

    private sealed interface UiState {
        data object Listening : UiState
        data object Sending : UiState
        data class Done(val message: String, val ok: Boolean) : UiState
    }

    private var state by mutableStateOf<UiState>(UiState.Listening)

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !text.isNullOrBlank()) send(text.trim()) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
        if (savedInstanceState == null) listen()
    }

    private fun listen() {
        state = UiState.Listening
        try {
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a task"),
            )
        } catch (_: ActivityNotFoundException) {
            state = UiState.Done("This watch has no voice input app", ok = false)
        }
    }

    private fun send(text: String) {
        state = UiState.Sending
        val id = UUID.randomUUID().toString()
        val reply = CompletableDeferred<String>()
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == "$RESULT_PATH/$id") reply.complete(String(event.data))
        }
        val messages = Wearable.getMessageClient(this)

        lifecycleScope.launch {
            messages.addListener(listener).await()
            val done = try {
                val request = PutDataMapRequest.create("$CAPTURE_PATH/$id").apply {
                    dataMap.putString(KEY_TEXT, text)
                    dataMap.putLong(KEY_TIME, System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(this@MainActivity).putDataItem(request).await()

                val phoneNearby = Wearable.getNodeClient(this@MainActivity).connectedNodes.await().isNotEmpty()
                when {
                    !phoneNearby -> UiState.Done("Saved on the watch. It'll be added when your phone is nearby.", ok = true)
                    else -> withTimeoutOrNull(REPLY_TIMEOUT_MS) { reply.await() }
                        ?.let { UiState.Done(it, ok = !it.startsWith("Couldn't")) }
                        ?: UiState.Done("Sent to your phone", ok = true)
                }
            } catch (e: Exception) {
                UiState.Done("Couldn't send: ${e.message}", ok = false)
            } finally {
                messages.removeListener(listener)
            }
            state = done
            window.decorView.performHapticFeedback(
                if (done.ok) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT,
            )
            if (done.ok) {
                delay(SHOW_RESULT_MS)
                finish()
            }
        }
    }

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            when (val s = state) {
                UiState.Listening -> Unit
                UiState.Sending -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                    Text("Adding…")
                }
                is UiState.Done -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        (if (s.ok) "✓ " else "") + s.message,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!s.ok) Button(onClick = ::listen) { Text("Try again") }
                }
            }
        }
    }

    private companion object {
        // Must match WatchCaptureService in the phone app.
        const val CAPTURE_PATH = "/voicetasks/capture"
        const val RESULT_PATH = "/voicetasks/result"
        const val KEY_TEXT = "text"
        const val KEY_TIME = "time"

        // Room for the phone to wake up and run the on-device model.
        const val REPLY_TIMEOUT_MS = 20_000L
        const val SHOW_RESULT_MS = 2_500L
    }
}
