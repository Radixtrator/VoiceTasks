package com.voicetasks

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class Priority(val emoji: String) {
    HIGHEST("🔺"),
    HIGH("⏫"),
    MEDIUM("🔼"),
    LOW("🔽"),
    LOWEST("⏬");

    val dataview: String get() = name.lowercase()

    companion object {
        fun parse(value: String?): Priority? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

data class ParsedTask(
    val text: String,
    val due: LocalDate? = null,
    val priority: Priority? = null,
    val tags: List<String> = emptyList(),
    val time: LocalTime? = null,
    val place: Place? = null,
) {
    /**
     * Fills in a missing date with [today] and a missing time with [defaultTime].
     * Place tasks with no date or time are left alone: they're due when you get there.
     */
    fun withDueDefaults(today: LocalDate, defaultTime: LocalTime): ParsedTask =
        if (place != null && due == null && time == null) this else copy(due = due ?: today, time = time ?: defaultTime)

    /** Human-readable summary for the capture card and the parser test box. */
    val preview: String
        get() = buildString {
            append(text)
            priority?.let { append(' ').append(it.emoji) }
            due?.let { append(" · ").append(it) }
            time?.let { append(if (due == null) " · " else " ").append(it) }
            place?.let { append(" · 📍").append(it.name) }
        }
}

/** The two field styles Obsidian Tasks (and TaskForge) understand. */
enum class TaskFormat { EMOJI, DATAVIEW }

object TaskFormatter {

    fun format(task: ParsedTask, format: TaskFormat): String = buildString {
        append("- [ ] ").append(task.text)
        (cleanTags(task.tags) + listOfNotNull(task.place?.let { "place/${it.slug}" })).forEach { append(" #").append(it) }
        when (format) {
            TaskFormat.EMOJI -> {
                task.priority?.let { append(' ').append(it.emoji) }
                task.due?.let { append(" 📅 ").append(it) }
            }
            TaskFormat.DATAVIEW -> {
                task.priority?.let { append(" [priority:: ").append(it.dataview).append(']') }
                task.due?.let { append(" [due:: ").append(it).append(']') }
            }
        }
    }

    // Obsidian tags can't contain spaces or be purely numeric.
    fun cleanTags(tags: List<String>): List<String> = tags
        .map { it.lowercase().replace(Regex("""[^\p{L}\p{N}_/-]"""), "") }
        .filter { it.isNotEmpty() && !it.all(Char::isDigit) }
        .distinct()
}

/**
 * A TaskNotes task note: YAML properties only, with the title kept in the file name.
 * Matches the plugin's defaults (task tag "task", statuses open/done,
 * priorities none/low/normal/high).
 */
object TaskNotesFormatter {

    private const val TASK_TAG = "task"
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")

    fun format(task: ParsedTask, now: ZonedDateTime): String = buildString {
        val timestamp = now.format(TIMESTAMP)
        appendLine("---")
        appendLine("status: open")
        appendLine("priority: ${priority(task.priority)}")
        // Same shape TaskForge writes: a quoted local date-time, or a bare date.
        task.due?.let { due ->
            appendLine(task.time?.let { "due: \"${due}T${it.format(HOUR_MINUTE)}\"" } ?: "due: $due")
        }
        appendLine("tags:")
        (listOf(TASK_TAG) + TaskFormatter.cleanTags(task.tags)).distinct().forEach { appendLine("  - $it") }
        task.place?.let {
            appendLine("contexts:")
            appendLine("  - ${it.slug}")
        }
        appendLine("dateCreated: $timestamp")
        appendLine("dateModified: $timestamp")
        appendLine("---")
    }

    // TaskNotes has four levels, so the extremes fold into high and low.
    private fun priority(priority: Priority?): String = when (priority) {
        Priority.HIGHEST, Priority.HIGH -> "high"
        Priority.MEDIUM, null -> "normal"
        Priority.LOW, Priority.LOWEST -> "low"
    }
}
