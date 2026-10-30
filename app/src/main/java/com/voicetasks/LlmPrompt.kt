package com.voicetasks

import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** The prompt sent to the on-device model, and the reader for its JSON answer. */
object LlmPrompt {

    fun build(transcript: String, now: LocalDateTime): String {
        // The example's dates are worked out for real, so the model sees correct arithmetic.
        val today = now.toLocalDate()
        val monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val inTwenty = now.plusMinutes(20)
        return """
        Extract the to-do items from a dictated voice note.

        Today is ${today.format(LONG_DATE)} ($today). The time is ${now.format(HOUR_MINUTE)}.

        Reply with JSON only, in this shape:
        {"tasks":[{"text":"...","when":"...","date":"...","time":"...","where":"...","priority":"...","tags":["..."]}]}

        Rules:
        - One entry per separate thing the speaker needs to do.
        - "text": a short task in the speaker's words, starting with a verb. Leave out filler such as "remind me to" or "I need to", and leave out the date, time, place and priority words.
        - "when": the date and time words exactly as spoken, such as "tomorrow at 3 pm", "next friday", "on the 1st at 8:30 am", "tonight" or "in two weeks". Do not turn them into a date or time. Use "" when no date or time is said. Durations count: "in 20 minutes", "in an hour and a half".
        - "date": the day "when" means, as YYYY-MM-DD, worked out from today's date. "" when no day is said.
        - "time": the time of day "when" means, as 24-hour HH:MM, such as "08:00" for "first thing" or "17:00" for "end of day". "" when no time of day is said.
        - "where": the place words exactly as spoken when the task is tied to arriving somewhere, such as "when I get to the gym", "when I get home" or "at the store". Otherwise "".
        - "priority": "highest", "high", "medium", "low" or "lowest", only when the speaker says how important or urgent it is. Otherwise "".
        - "tags": words the speaker says right after "tag" or "hashtag", in lowercase. Otherwise [].

        Example voice note: remind me to email Sam on Monday at 10 am it's urgent, oh and buy milk when I get to the store, and call mom in 20 minutes
        Example reply: {"tasks":[{"text":"Email Sam","when":"on Monday at 10 am","date":"$monday","time":"10:00","where":"","priority":"high","tags":[]},{"text":"Buy milk","when":"","date":"","time":"","where":"when I get to the store","priority":"","tags":[]},{"text":"Call mom","when":"in 20 minutes","date":"${inTwenty.toLocalDate()}","time":"${inTwenty.format(HOUR_MINUTE)}","where":"","priority":"","tags":[]}]}

        Voice note: ${transcript.trim()}
        Reply:
        """.trimIndent()
    }

    /**
     * Reads the model's reply, or returns null when it isn't the JSON we asked for.
     * [transcript] lets it drop priorities the speaker never mentioned.
     */
    fun parse(
        output: String,
        now: LocalDateTime,
        places: List<Place> = emptyList(),
        transcript: String? = null,
    ): List<ParsedTask>? {
        // Small models like to mark things "high" on their own; only trust a priority that was said.
        val prioritySaid = transcript == null || PRIORITY_WORDS.containsMatchIn(transcript)
        val today = now.toLocalDate()
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val tasks = try {
            JSONObject(output.substring(start, end + 1)).optJSONArray("tasks")
        } catch (_: JSONException) {
            null
        } ?: return null

        return (0 until tasks.length()).mapNotNull { i ->
            val item = tasks.optJSONObject(i) ?: return@mapNotNull null
            var text = item.optString("text").trim()
            if (text.isEmpty()) return@mapNotNull null

            val whenPhrase = item.optString("when")
            val relative = RelativeTimeResolver.find(whenPhrase, now)
            val dateMatch = if (relative == null) DateResolver.find(whenPhrase, today) else null
            val timeMatch = if (relative == null) TimeResolver.find(whenPhrase) else null
            var due = relative?.dateTime?.toLocalDate() ?: dateMatch?.date
            var time = relative?.dateTime?.toLocalTime() ?: timeMatch?.time
            // Phrases the code doesn't know ("first thing tomorrow", "quarter to 6"): fall back to
            // the model's own date and time, but only for words the code couldn't account for.
            val unexplained = whenPhrase.removeRanges(listOfNotNull(relative?.range, dateMatch?.range, timeMatch?.range))
            if (unexplained.replace(FILLER_WORDS, "").isNotBlank()) {
                if (due == null) due = modelDate(item.optString("date"), today)
                if (time == null) time = modelTime(item.optString("time"))
            }
            var place = PlaceResolver.resolve(item.optString("where"), places)
            // Small models sometimes leave the date, time or place inside the text instead.
            val leftovers = mutableListOf<IntRange>()
            if (place == null) PlaceResolver.find(text, places)?.let { place = it.place; leftovers += it.range }
            if (due == null && time == null) {
                RelativeTimeResolver.find(text, now)?.let {
                    due = it.dateTime.toLocalDate()
                    time = it.dateTime.toLocalTime()
                    leftovers += it.range
                }
            }
            if (due == null) DateResolver.find(text, today)?.let { due = it.date; leftovers += it.range }
            if (time == null) TimeResolver.find(text)?.let { time = it.time; leftovers += it.range }
            text = text.removeRanges(leftovers)

            val tags = item.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            }.orEmpty()

            ParsedTask(RuleParser.clean(text), due, Priority.parse(item.optString("priority")).takeIf { prioritySaid }, tags, time, place)
        }.filter { it.text.isNotEmpty() }
    }

    private val PRIORITY_WORDS = Regex(
        """\b(?:urgent|important|priority|asap|as\s+soon\s+as\s+possible|critical|whenever|no\s+rush)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val LONG_DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)
    private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")
    private val FILLER_WORDS = Regex("""\b(?:on|at|by|in|the|and|around|about)\b|[\s,.]""", RegexOption.IGNORE_CASE)

    // Sanity bounds, so a garbled answer can't put a task years away.
    private fun modelDate(value: String, today: LocalDate): LocalDate? =
        runCatching { LocalDate.parse(value.trim()) }.getOrNull()
            ?.takeIf { !it.isBefore(today.minusDays(1)) && it.isBefore(today.plusYears(3)) }

    private fun modelTime(value: String): LocalTime? =
        runCatching { LocalTime.parse(value.trim(), DateTimeFormatter.ofPattern("H:mm")) }.getOrNull()
}
