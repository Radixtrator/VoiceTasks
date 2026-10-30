package com.voicetasks

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/**
 * Receives transcripts dictated on the watch, saves them like a normal capture,
 * and sends the result back so the watch can show "Added 2 tasks…".
 *
 * The watch sends a data item rather than a message, so captures made while the phone
 * is out of range are queued and arrive here once it reconnects.
 */
class WatchCaptureService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        // The buffer is only valid during this call, so copy out what we need first.
        val captures = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith(CAPTURE_PATH) == true }
            .map { it.dataItem.uri to DataMapItem.fromDataItem(it.dataItem).dataMap.getString(KEY_TEXT).orEmpty() }

        // Listener callbacks run on a background thread, so blocking here is fine.
        for ((uri, text) in captures) {
            val reply = if (text.isBlank()) {
                "Didn't catch that"
            } else {
                runBlocking {
                    runCatching { TaskSaver.save(applicationContext, text) }
                        .fold({ "${it.summary}\n(${it.engine.label})" }, { "Couldn't save: ${it.message}" })
                }
            }
            runCatching {
                val watch = uri.host ?: return@runCatching
                Tasks.await(
                    Wearable.getMessageClient(this).sendMessage(watch, "$RESULT_PATH/${uri.lastPathSegment}", reply.toByteArray()),
                )
            }.onFailure { Log.w(TAG, "Couldn't send the result to the watch", it) }
            runCatching { Tasks.await(Wearable.getDataClient(this).deleteDataItems(uri)) }
        }
    }

    private companion object {
        const val TAG = "WatchCapture"

        // Must match the watch app.
        const val CAPTURE_PATH = "/voicetasks/capture"
        const val RESULT_PATH = "/voicetasks/result"
        const val KEY_TEXT = "text"
    }
}
