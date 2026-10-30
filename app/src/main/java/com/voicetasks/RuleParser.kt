package com.voicetasks

import java.time.LocalDateTime

/**
 * The no-AI fallback: turns the whole transcript into one task, pulling out a date,
 * a priority and any spoken tags. Used when no on-device model is available or the
 * model's answer can't be read.
 */
object RuleParser {

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private val TAG = rx("""(?:\bhashtag\s+|\btag(?:\s+it)?(?:\s+(?:as|with))?\s+|#)([\p{L}\p{N}_/-]+)""")

    private const val SET = """(?:(?:(?:mark|make|set)\s+(?:it|this)\s+(?:as\s+)?|it'?s\s+|this\s+is\s+)?)"""

    // Checked in order, so "not urgent" is seen before "urgent".
    private val PRIORITIES = listOf(
        rx("""\b${SET}(?:the\s+)?lowest\s+priority\b""") to Priority.LOWEST,
        rx("""\b$SET(?:low\s+priority|not\s+(?:urgent|important)|no\s+rush|whenever)\b""") to Priority.LOW,
        rx("""\b${SET}(?:medium|normal)\s+priority\b""") to Priority.MEDIUM,
        rx("""\b$SET(?:(?:super|really|very)\s+(?:urgent|important)|top\s+priority|highest\s+priority|critical|asap|as\s+soon\s+as\s+possible)\b""") to Priority.HIGHEST,
        rx("""\b$SET(?:urgent|important|high\s+priority)\b""") to Priority.HIGH,
    )

    private val FILLER = rx("""^(?:(?:ok(?:ay)?|um+|uh+|so|hey|and|also)\b[,\s]*)+""")
    private val LEAD_IN = rx(
        """^(?:(?:please\s+)?(?:remind\s+me\s+(?:to\s+)?|remember\s+to\s+|don'?t\s+forget\s+(?:to\s+)?|add\s+(?:a\s+)?(?:task\s+)?(?:to\s+)?|""" +
            """i\s+(?:need|have|want|ought|got)\s+to\s+|i\s+(?:gotta|should|must)\s+|i'?ve\s+got\s+to\s+|to\s*do\s+))+""",
    )

    fun parse(transcript: String, now: LocalDateTime, places: List<Place> = emptyList()): List<ParsedTask> {
        val today = now.toLocalDate()
        var text = transcript.trim()
        if (text.isEmpty()) return emptyList()

        val tags = mutableListOf<String>()
        text = TAG.replace(text) { tags += it.groupValues[1].lowercase(); " " }

        var priority: Priority? = null
        for ((regex, p) in PRIORITIES) {
            val m = regex.find(text) ?: continue
            priority = p
            text = text.removeRange(m.range)
            break
        }

        val place = PlaceResolver.find(text, places)
        place?.let { text = text.removeRange(it.range) }

        // "in 20 minutes" sets both the date and the time; otherwise look for each separately.
        val relative = RelativeTimeResolver.find(text, now)
        val date = if (relative == null) DateResolver.find(text, today) else null
        val time = if (relative == null) TimeResolver.find(text) else null
        text = text.removeRanges(listOfNotNull(relative?.range, date?.range, time?.range))

        val cleaned = clean(text).ifEmpty { clean(transcript) }
        return listOf(
            ParsedTask(
                cleaned,
                relative?.dateTime?.toLocalDate() ?: date?.date,
                priority,
                tags,
                relative?.dateTime?.toLocalTime() ?: time?.time,
                place?.place,
            ),
        )
    }

    /** Tidies task text: drops filler and "remind me to", collapses spaces, capitalizes. */
    fun clean(text: String): String {
        var t = text.replace(Regex("""\s+"""), " ").trim()
        t = t.replace(Regex("""\s+([,.;:!?])"""), "$1")
        t = FILLER.replace(t, "")
        t = LEAD_IN.replace(t, "")
        t = t.trim(' ', ',', '.', ';', ':', '-')
        t = t.replace(Regex("""(?:,?\s+(?:and|but|so|it'?s|it\s+is))+$""", RegexOption.IGNORE_CASE), "")
        t = t.trim(' ', ',', '.', ';', ':', '-')
        return t.replaceFirstChar { it.uppercaseChar() }
    }
}
