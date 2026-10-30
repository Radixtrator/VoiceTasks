package com.voicetasks

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** Transcript in, tasks in the vault out. Shared by the capture card, the share sheet and the watch. */
object TaskSaver {

    data class Saved(val tasks: List<ParsedTask>, val destination: String, val engine: TaskParser.Engine) {
        val summary: String
            get() = "Added ${if (tasks.size == 1) "1 task" else "${tasks.size} tasks"} to $destination"
    }

    /**
     * Parses [transcript] the way [save] would, including the default date and time,
     * without writing anything.
     */
    suspend fun parse(context: Context, transcript: String, now: LocalDateTime = LocalDateTime.now()): TaskParser.Result {
        val today = now.toLocalDate()
        val result = TaskParser.parse(context, transcript, now)
        val defaultTime = Settings(context).defaultDueTimeOrNull ?: return result
        return result.copy(tasks = result.tasks.map { it.withDueDefaults(today, defaultTime) })
    }

    /** Where tasks will go with the current settings, for messages like "Added 2 tasks to Inbox.md". */
    fun destination(settings: Settings, today: LocalDate): String = when (settings.saveMode) {
        SaveMode.ONE_FILE -> VaultWriter.resolvePath(settings.inboxPath, today)
        SaveMode.FILE_PER_TASK ->
            VaultWriter.resolvePath(settings.taskFolder, today).trim('/').ifEmpty { "the vault" }.let { "$it/" }
    }

    /** Parses [transcript] and writes the tasks. Throws [IOException] if the vault can't be written. */
    suspend fun save(context: Context, transcript: String, now: LocalDateTime = LocalDateTime.now()): Saved {
        val today = now.toLocalDate()
        val settings = Settings(context)
        val vault = settings.vaultUri ?: throw IOException("Choose your Obsidian vault folder in Voice Tasks first")
        val result = parse(context, transcript, now)
        val lines = result.tasks.map { TaskFormatter.format(it, settings.format) }
        val writer = VaultWriter(context)

        val reminders = withContext(Dispatchers.IO) {
            when (settings.saveMode) {
                SaveMode.ONE_FILE -> {
                    val file = writer.append(vault, settings.inboxPath, lines, today)
                    result.tasks.zip(lines).mapNotNull { (task, line) ->
                        task.place?.let { PlaceReminder(it.id, task.text, file.toString(), line) }
                    }
                }
                SaveMode.FILE_PER_TASK -> {
                    val created = ZonedDateTime.now()
                    val notes = result.tasks.zip(lines) { task, line ->
                        task.text to when (settings.noteStyle) {
                            NoteStyle.TASK_LINE -> "$line\n"
                            NoteStyle.TASKNOTES -> TaskNotesFormatter.format(task, created)
                        }
                    }
                    val files = writer.createNotes(vault, settings.taskFolder, notes, today)
                    result.tasks.indices.mapNotNull { i ->
                        val task = result.tasks[i]
                        task.place?.let {
                            val line = if (settings.noteStyle == NoteStyle.TASK_LINE) lines[i] else null
                            PlaceReminder(it.id, task.text, files[i].toString(), line)
                        }
                    }
                }
            }
        }
        if (reminders.isNotEmpty()) PlaceReminders.add(context, reminders)
        return Saved(result.tasks, destination(settings, today), result.engine)
    }
}
