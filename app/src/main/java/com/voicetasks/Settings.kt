package com.voicetasks

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var vaultUri: Uri?
        get() = prefs.getString(KEY_VAULT, null)?.toUri()
        set(value) = prefs.edit { putString(KEY_VAULT, value?.toString()) }

    var inboxPath: String
        get() = prefs.getString(KEY_INBOX, null) ?: DEFAULT_INBOX
        set(value) = prefs.edit { putString(KEY_INBOX, value) }

    var format: TaskFormat
        get() = prefs.getString(KEY_FORMAT, null)?.let { runCatching { TaskFormat.valueOf(it) }.getOrNull() }
            ?: TaskFormat.EMOJI
        set(value) = prefs.edit { putString(KEY_FORMAT, value.name) }

    var saveMode: SaveMode
        get() = prefs.getString(KEY_SAVE_MODE, null)?.let { runCatching { SaveMode.valueOf(it) }.getOrNull() }
            ?: SaveMode.ONE_FILE
        set(value) = prefs.edit { putString(KEY_SAVE_MODE, value.name) }

    /** Folder inside the vault for [SaveMode.FILE_PER_TASK]; blank means the vault's top level. */
    var taskFolder: String
        get() = prefs.getString(KEY_TASK_FOLDER, null) ?: DEFAULT_TASK_FOLDER
        set(value) = prefs.edit { putString(KEY_TASK_FOLDER, value) }

    var noteStyle: NoteStyle
        get() = prefs.getString(KEY_NOTE_STYLE, null)?.let { runCatching { NoteStyle.valueOf(it) }.getOrNull() }
            ?: NoteStyle.TASK_LINE
        set(value) = prefs.edit { putString(KEY_NOTE_STYLE, value.name) }

    /** "19:00" by default; blank turns the defaults off. */
    var defaultDueTime: String
        get() = prefs.getString(KEY_DEFAULT_TIME, null) ?: DEFAULT_DUE_TIME
        set(value) = prefs.edit { putString(KEY_DEFAULT_TIME, value) }

    /** The parsed [defaultDueTime], or null when it's blank or not a valid time. */
    val defaultDueTimeOrNull: LocalTime?
        get() = parseTime(defaultDueTime)

    companion object {
        const val DEFAULT_INBOX = "Inbox.md"
        const val DEFAULT_DUE_TIME = "19:00"
        private const val KEY_DEFAULT_TIME = "default_due_time"

        fun parseTime(value: String): LocalTime? =
            runCatching { LocalTime.parse(value.trim(), DateTimeFormatter.ofPattern("H:mm")) }.getOrNull()
        private const val KEY_NOTE_STYLE = "note_style"
        const val DEFAULT_TASK_FOLDER = "Tasks"
        private const val KEY_VAULT = "vault"
        private const val KEY_INBOX = "inbox"
        private const val KEY_FORMAT = "format"
        private const val KEY_SAVE_MODE = "save_mode"
        private const val KEY_TASK_FOLDER = "task_folder"
    }
}

enum class SaveMode {
    /** Append every task as a line in one file, like Inbox.md. */
    ONE_FILE,

    /** Create a new note for each task, named after it. */
    FILE_PER_TASK,
}

/** What goes inside each note in [SaveMode.FILE_PER_TASK]. */
enum class NoteStyle {
    /** A single Obsidian Tasks line, in the chosen [TaskFormat]. */
    TASK_LINE,

    /** TaskNotes properties (status, priority, due, tags…) in the frontmatter. */
    TASKNOTES,
}
