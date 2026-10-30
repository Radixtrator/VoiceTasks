package com.voicetasks

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Turns spoken times ("at 3 p.m.", "8:30 am", "at noon", "tomorrow morning") into times of day. */
object TimeResolver {

    data class Match(val range: IntRange, val time: LocalTime)

    private const val LEAD = """(?:(?:at|by|around|before|until)\s+)"""
    private const val AM_PM = """(?:\s*([ap])\.?\s?m\b\.?)?"""

    private val hourWords = listOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
    ).withIndex().associate { (i, w) -> w to i + 1 }
    private val minuteWords = mapOf(
        "o'clock" to 0, "oclock" to 0, "o clock" to 0, "oh five" to 5, "ten" to 10, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "forty five" to 45, "forty-five" to 45, "fifty" to 50,
    )
    private val clockWords = mapOf(
        "quarter" to 15, "a quarter" to 15, "half" to 30, "five" to 5, "ten" to 10, "twenty" to 20,
        "twenty five" to 25, "twenty-five" to 25,
    )

    private fun alternatives(words: Collection<String>) =
        words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private val HOUR_WORD = alternatives(hourWords.keys)
    private val HOUR = """\d{1,2}|$HOUR_WORD"""
    private val MINUTE_WORD = alternatives(minuteWords.keys)
    private val CLOCK_MINUTES = """\d{1,2}|${alternatives(clockWords.keys)}"""

    private fun hourValue(word: String): Int? = word.toIntOrNull() ?: hourWords[word.lowercase()]
    private fun minuteValue(word: String): Int = minuteWords[word.lowercase().replace(Regex("""\s+"""), " ")] ?: 0
    private fun clockMinutes(word: String): Int? =
        word.toIntOrNull()?.takeIf { it in 1..59 } ?: clockWords[word.lowercase().replace(Regex("""\s+"""), " ")]

    private class Rule(pattern: String, val resolve: (MatchResult) -> LocalTime?) {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    private val rules = listOf(
        // "3 p.m.", "at 8:30am", "by 11 PM"
        Rule("""\b$LEAD?(\d{1,2})(?::(\d{2}))?\s*([ap])\.?\s?m\b\.?""") { m ->
            twelveHour(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].lowercase() == "p")
        },
        // "at 8:30", "at 15:00": no am/pm, so guess from the hour.
        Rule("""\b$LEAD(\d{1,2}):(\d{2})\b""") { m -> guess(m.groupValues[1].toInt(), m.groupValues[2].toInt()) },
        // "at 3", "at 3 o'clock", but not "at the 3rd" or "at 2 stores"-style counts with units.
        Rule("""\b$LEAD(\d{1,2})(?:\s*o'?\s*clock)?\b(?!\s*(?:st|nd|rd|th|/|-|:|days?|weeks?|months?|hours?|minutes?)\b)""") { m ->
            guess(m.groupValues[1].toInt(), 0)
        },
        // "quarter past 3", "half past five pm", "quarter to 6", "10 after 4", "twenty to seven"
        Rule("""\b$LEAD?($CLOCK_MINUTES)\s+(?:minutes?\s+)?(past|after|to|till|til)\s+($HOUR)$AM_PM""") { m ->
            val hour = hourValue(m.groupValues[3]) ?: return@Rule null
            val minutes = clockMinutes(m.groupValues[1]) ?: return@Rule null
            val before = m.groupValues[2].lowercase() !in setOf("past", "after")
            val h = if (before) (if (hour == 1) 12 else hour - 1) else hour
            val min = if (before) 60 - minutes else minutes
            if (m.groupValues[4].isNotEmpty()) twelveHour(h, min, m.groupValues[4].lowercase() == "p") else guess(h, min)
        },
        // "three pm", "at seven thirty p.m."
        Rule("""\b$LEAD?($HOUR_WORD)(?:\s+($MINUTE_WORD))?\s*([ap])\.?\s?m\b\.?""") { m ->
            twelveHour(hourValue(m.groupValues[1]) ?: return@Rule null, minuteValue(m.groupValues[2]), m.groupValues[3].lowercase() == "p")
        },
        // "at three", "at seven thirty", "at five o'clock", but not "at one point" or "at one of the stores".
        Rule("""\b$LEAD($HOUR_WORD)(?:\s+($MINUTE_WORD))?\b(?!\s+(?:of|point|time|more|another|thing|day|week|month|year|place)s?\b)""") { m ->
            guess(hourValue(m.groupValues[1]) ?: return@Rule null, minuteValue(m.groupValues[2]))
        },
        Rule("""\b$LEAD?(?:noon|midday|lunch(?:time)?)\b""") { LocalTime.NOON },
        Rule("""\b(?:in\s+the\s+|this\s+)?morning\b""") { LocalTime.of(9, 0) },
        Rule("""\b(?:in\s+the\s+|this\s+)?afternoon\b""") { LocalTime.of(15, 0) },
        Rule("""\b(?:in\s+the\s+|this\s+)?evening\b""") { LocalTime.of(19, 0) },
        Rule("""\b(?:tonight|at\s+night)\b""") { LocalTime.of(20, 0) },
    )

    /** Finds the first time phrase in [text], preferring the longest one at a position. */
    fun find(text: String): Match? = rules
        .flatMap { rule -> rule.regex.findAll(text).mapNotNull { m -> rule.resolve(m)?.let { Match(m.range, it) } } }
        .minWithOrNull(compareBy<Match> { it.range.first }.thenByDescending { it.range.last })

    private fun twelveHour(hour: Int, minute: Int, pm: Boolean): LocalTime? {
        if (hour !in 1..12 || minute !in 0..59) return null
        val h = when {
            hour == 12 -> if (pm) 12 else 0
            pm -> hour + 12
            else -> hour
        }
        return LocalTime.of(h, minute)
    }

    // Without am/pm, 1–7 almost always means afternoon or evening ("at 3" → 15:00).
    private fun guess(hour: Int, minute: Int): LocalTime? {
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(if (hour in 1..7) hour + 12 else hour, minute)
    }
}

/** "in 20 minutes", "in an hour and a half", "2 hours from now" → a moment counted from now. */
object RelativeTimeResolver {

    data class Match(val range: IntRange, val dateTime: LocalDateTime)

    private val numberWords = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0,
        "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0,
        "twelve" to 12.0, "fifteen" to 15.0, "twenty" to 20.0, "thirty" to 30.0, "forty" to 40.0,
        "forty five" to 45.0, "forty-five" to 45.0, "fifty" to 50.0, "sixty" to 60.0, "ninety" to 90.0,
        "couple" to 2.0, "couple of" to 2.0, "few" to 3.0,
    )
    private val NUM = """\d+(?:\.\d+)?|""" + numberWords.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private const val ABOUT = """(?:(?:about|around|like|roughly)\s+)?"""
    private const val HOURS = """(?:hours?|hrs?)"""
    private const val MINUTES = """(?:minutes?|mins?)"""

    private class Rule(pattern: String, val minutes: (MatchResult) -> Double?) {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    private val rules = listOf(
        // "in 2 hours", "in an hour and a half", "in 2 and a half hours", "in 1 hour and 15 minutes"
        Rule("""\bin\s+$ABOUT($NUM)\s+(and\s+a\s+half\s+)?$HOURS(\s+and\s+a\s+half)?(?:\s+and\s+($NUM)\s+$MINUTES)?\b""") { m ->
            val hours = number(m.groupValues[1]) ?: return@Rule null
            val half = if (m.groupValues[2].isNotEmpty() || m.groupValues[3].isNotEmpty()) 30.0 else 0.0
            hours * 60 + half + (number(m.groupValues[4]) ?: 0.0)
        },
        Rule("""\bin\s+$ABOUT($NUM)\s+$MINUTES\b""") { m -> number(m.groupValues[1]) },
        Rule("""\bin\s+$ABOUT(?:a\s+)?half\s+(?:an?\s+)?hour\b""") { 30.0 },
        Rule("""\bin\s+$ABOUT(?:a\s+)?quarter\s+(?:of\s+)?(?:an?\s+)?hour\b""") { 15.0 },
        Rule("""\b($NUM)\s+($HOURS|$MINUTES)\s+from\s+now\b""") { m ->
            val n = number(m.groupValues[1]) ?: return@Rule null
            if (m.groupValues[2].lowercase().startsWith("h")) n * 60 else n
        },
    )

    fun find(text: String, now: LocalDateTime): Match? = rules
        .flatMap { rule ->
            rule.regex.findAll(text).mapNotNull { m ->
                rule.minutes(m)?.takeIf { it > 0 }?.let {
                    Match(m.range, now.plusSeconds((it * 60).toLong()).truncatedTo(ChronoUnit.MINUTES))
                }
            }
        }
        .minWithOrNull(compareBy<Match> { it.range.first }.thenByDescending { it.range.last })

    private fun number(word: String): Double? =
        word.toDoubleOrNull() ?: numberWords[word.lowercase().replace(Regex("""\s+"""), " ")]
}

/** Removes several (possibly overlapping) ranges from [text]. */
fun String.removeRanges(ranges: List<IntRange>): String {
    var result = this
    ranges.sortedByDescending { it.first }
        .fold(mutableListOf<IntRange>()) { merged, r ->
            val last = merged.lastOrNull()
            if (last != null && r.last >= last.first) merged[merged.size - 1] = r.first..maxOf(r.last, last.last) else merged += r
            merged
        }
        .forEach { result = result.removeRange(it) }
    return result
}
