package com.voicetasks

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

/**
 * A task tied to a place. [line] is the exact task line for Obsidian Tasks files; null means
 * a TaskNotes note, which is checked through its status property instead.
 */
data class PlaceReminder(val placeId: String, val title: String, val fileUri: String, val line: String?)

/** Remembers which tasks belong to which place, and notifies about the open ones when you arrive. */
object PlaceReminders {

    private const val PREFS = "place_reminders"
    private const val KEY = "reminders"
    private const val CHANNEL = "places"

    // TaskNotes statuses that mean the task is finished.
    private val DONE_STATUS = Regex("""^status:\s*["']?(done|completed|complete|cancelled|canceled)["']?\s*$""", RegexOption.MULTILINE)

    @Synchronized
    fun all(context: Context): List<PlaceReminder> {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            PlaceReminder(o.getString("place"), o.getString("title"), o.getString("uri"), o.optString("line").ifEmpty { null })
        }
    }

    @Synchronized
    fun add(context: Context, reminders: List<PlaceReminder>) = save(context, all(context) + reminders)

    /** Forgets every reminder for a place, e.g. when the place is deleted. */
    @Synchronized
    fun removePlace(context: Context, placeId: String) = save(context, all(context).filterNot { it.placeId == placeId })

    /** Called by the geofence when you arrive at one of [placeIds]. */
    fun onArrive(context: Context, placeIds: List<String>) {
        val places = PlaceStore(context).all().associateBy { it.id }
        val writer = VaultWriter(context)
        val (here, elsewhere) = all(context).partition { it.placeId in placeIds }

        // Tasks completed or deleted in TaskForge/Obsidian are dropped for good.
        val open = here.filter { isOpen(writer, it) }
        synchronized(this) { save(context, all(context).filter { it in elsewhere || it in open }) }

        open.groupBy { it.placeId }.forEach { (placeId, reminders) ->
            val place = places[placeId] ?: return@forEach
            notify(context, place, reminders)
        }
    }

    private fun isOpen(writer: VaultWriter, reminder: PlaceReminder): Boolean {
        val text = writer.read(reminder.fileUri.toUri()) ?: return false
        return if (reminder.line != null) text.lineSequence().any { it.trimEnd() == reminder.line } else !DONE_STATUS.containsMatchIn(text)
    }

    private fun notify(context: Context, place: Place, reminders: List<PlaceReminder>) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Place reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tasks for a place, shown when you get there"
            },
        )
        val open = taskForgeIntent(context) ?: Intent(context, MainActivity::class.java)
        val tap = PendingIntent.getActivity(
            context, place.id.hashCode(), open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val titles = reminders.map { it.title }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("At ${place.name}")
            .setContentText(titles.joinToString(", "))
            .setStyle(NotificationCompat.InboxStyle().also { style -> titles.forEach { style.addLine(it) } })
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(context).notify(place.id.hashCode(), notification)
    }

    /** TaskForge's launcher intent; looked up by name because its package id isn't fixed here. */
    private fun taskForgeIntent(context: Context): Intent? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val app = pm.queryIntentActivities(launcher, 0)
            .firstOrNull { it.activityInfo.packageName.endsWith("taskforge", ignoreCase = true) }
            ?: return null
        return pm.getLaunchIntentForPackage(app.activityInfo.packageName)
    }

    private fun save(context: Context, reminders: List<PlaceReminder>) {
        val array = JSONArray()
        reminders.forEach {
            array.put(
                JSONObject().put("place", it.placeId).put("title", it.title).put("uri", it.fileUri).put("line", it.line ?: ""),
            )
        }
        prefs(context).edit { putString(KEY, array.toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
