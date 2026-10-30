package com.voicetasks

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/**
 * Turns spoken date phrases ("next friday", "on the 1st", "in two weeks") into dates.
 *
 * The language model only copies the phrase out of the transcript; the arithmetic happens
 * here, because small on-device models are unreliable at it.
 */
object DateResolver {

    data class Match(val range: IntRange, val date: LocalDate)

    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "couple" to 2, "couple of" to 2, "few" to 3,
    )

    private val ordinalWords: Map<String, Int> = buildMap {
        val ones = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth")
        ones.forEachIndexed { i, w -> put(w, i + 1) }
        listOf(
            "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth",
            "sixteenth", "seventeenth", "eighteenth", "nineteenth",
        ).forEachIndexed { i, w -> put(w, i + 10) }
        put("twentieth", 20)
        put("thirtieth", 30)
        ones.forEachIndexed { i, w ->
            put("twenty $w", 21 + i)
            put("twenty-$w", 21 + i)
        }
        put("thirty first", 31)
        put("thirty-first", 31)
    }

    private val months = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3,
        "april" to 4, "apr" to 4, "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8, "september" to 9, "sept" to 9, "sep" to 9, "october" to 10,
        "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
    )

    private val weekdays = DayOfWeek.entries.associateBy { it.name.lowercase() }

    private fun alternatives(words: Collection<String>) =
        words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    // Optional lead-in that belongs to the date, so it gets stripped along with it.
    private const val LEAD = """(?:(?:due\s+)?(?:on|by|before|for)\s+|due\s+)?"""
    private val NUM = """\d+|${alternatives(numberWords.keys)}"""
    private val MONTH = alternatives(months.keys)
    private val WEEKDAY = alternatives(weekdays.keys)
    private val ORD_WORDS = alternatives(ordinalWords.keys)
    // "the 3" alone is too ambiguous ("buy the 3 books"), so a bare "the" needs an ordinal.
    private val ORD_STRICT = """\d{1,2}(?:st|nd|rd|th)|$ORD_WORDS"""
    private val ORD_LOOSE = """\d{1,2}(?:st|nd|rd|th)?|$ORD_WORDS"""

    private class Rule(pattern: String, val resolve: (MatchResult, LocalDate) -> LocalDate?) {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    private val rules = listOf(
        Rule("""\b$LEAD(?:the\s+)?day\s+after\s+tomorrow\b""") { _, t -> t.plusDays(2) },
        Rule("""\b$LEAD(?:tomorrow|tmrw)(?:\s+(?:morning|afternoon|evening|night))?\b""") { _, t -> t.plusDays(1) },
        Rule("""\b$LEAD(?:today|tonight|this\s+(?:morning|afternoon|evening))\b""") { _, t -> t },
        Rule("""\bin\s+(?:a\s+)?($NUM)\s+(day|week|month)s?\b""") { m, t ->
            val n = number(m.groupValues[1]) ?: return@Rule null
            when (m.groupValues[2].lowercase()) {
                "day" -> t.plusDays(n.toLong())
                "week" -> t.plusWeeks(n.toLong())
                else -> t.plusMonths(n.toLong())
            }
        },
        Rule("""\b$LEAD(?:(this|next|coming)\s+)?($WEEKDAY)\b""") { m, t ->
            weekday(t, weekdays.getValue(m.groupValues[2].lowercase()), m.groupValues[1].lowercase())
        },
        Rule("""\b${LEAD}next\s+week\b""") { _, t -> t.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) },
        Rule("""\b$LEAD(?:this\s+)?weekend\b""") { _, t -> t.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)) },
        Rule("""\b$LEAD(?:the\s+)?end\s+of\s+(?:the\s+|this\s+)?week\b""") { _, t ->
            t.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
        },
        Rule("""\b${LEAD}next\s+month\b""") { _, t -> t.plusMonths(1).withDayOfMonth(1) },
        Rule("""\b$LEAD(?:the\s+)?end\s+of\s+(?:the\s+|this\s+)?month\b""") { _, t ->
            t.with(TemporalAdjusters.lastDayOfMonth())
        },
        Rule("""\b$LEAD($MONTH)\.?\s+(?:the\s+)?($ORD_LOOSE)\b""") { m, t ->
            monthDay(t, months.getValue(m.groupValues[1].lowercase()), ordinal(m.groupValues[2]))
        },
        Rule("""\b${LEAD}the\s+($ORD_LOOSE)\s+of\s+($MONTH)\b""") { m, t ->
            monthDay(t, months.getValue(m.groupValues[2].lowercase()), ordinal(m.groupValues[1]))
        },
        Rule("""\b${LEAD}the\s+($ORD_STRICT)\b""") { m, t -> dayOfMonth(t, ordinal(m.groupValues[1])) },
        Rule("""\b$LEAD(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""") { m, t ->
            val year = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            if (year != null) {
                safeDate(year, m.groupValues[1].toInt(), m.groupValues[2].toInt())
            } else {
                monthDay(t, m.groupValues[1].toInt(), m.groupValues[2].toInt())
            }
        },
        Rule("""\b$LEAD(\d{4})-(\d{2})-(\d{2})\b""") { m, _ ->
            safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        },
    )

    /** Finds the first date phrase in [text], including any "on"/"by"/"due" in front of it. */
    fun find(text: String, today: LocalDate): Match? = rules
        .flatMap { rule -> rule.regex.findAll(text).mapNotNull { m -> rule.resolve(m, today)?.let { Match(m.range, it) } } }
        .minWithOrNull(compareBy<Match> { it.range.first }.thenByDescending { it.range.last })

    /** Resolves a phrase that is (or contains) a date, such as "next friday" or "2026-11-03". */
    fun resolve(phrase: String, today: LocalDate): LocalDate? = find(phrase, today)?.date

    private fun number(word: String): Int? =
        word.toIntOrNull() ?: numberWords[word.lowercase().replace(Regex("""\s+"""), " ")]

    private fun ordinal(word: String): Int? =
        Regex("""\d+""").find(word)?.value?.toInt()
            ?: ordinalWords[word.lowercase().replace(Regex("""\s+"""), " ")]

    private fun weekday(today: LocalDate, day: DayOfWeek, modifier: String): LocalDate {
        if (modifier == "this") return today.with(TemporalAdjusters.nextOrSame(day))
        val upcoming = today.with(TemporalAdjusters.next(day))
        // "next sunday" said on a Monday means the one after this week's Sunday.
        val sameWeek = upcoming.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return if (modifier == "next" && sameWeek) upcoming.plusWeeks(1) else upcoming
    }

    /** The next date (today or later) with this month and day. */
    private fun monthDay(today: LocalDate, month: Int, day: Int?): LocalDate? {
        if (day == null) return null
        val thisYear = safeDate(today.year, month, day) ?: return null
        return if (thisYear < today) safeDate(today.year + 1, month, day) else thisYear
    }

    /** The next date (today or later) falling on this day of the month. */
    private fun dayOfMonth(today: LocalDate, day: Int?): LocalDate? {
        if (day == null || day !in 1..31) return null
        var month = today.withDayOfMonth(1)
        repeat(12) {
            val candidate = safeDate(month.year, month.monthValue, day)
            if (candidate != null && candidate >= today) return candidate
            month = month.plusMonths(1)
        }
        return null
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            null
        }
}
