package com.voicetasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ParsingTest {

    // A Friday.
    private val today = LocalDate.of(2026, 10, 30)
    private val now = today.atTime(14, 10)

    private fun date(phrase: String) = DateResolver.resolve(phrase, today)

    @Test
    fun relativeDays() {
        assertEquals(today, date("today"))
        assertEquals(LocalDate.of(2026, 10, 31), date("tomorrow"))
        assertEquals(LocalDate.of(2026, 11, 1), date("the day after tomorrow"))
        assertEquals(LocalDate.of(2026, 11, 13), date("in two weeks"))
        assertEquals(LocalDate.of(2026, 11, 2), date("in a few days"))
    }

    @Test
    fun weekdays() {
        assertEquals(LocalDate.of(2026, 11, 2), date("monday"))
        assertEquals(LocalDate.of(2026, 11, 2), date("on Monday"))
        assertEquals(LocalDate.of(2026, 11, 6), date("friday"))
        assertEquals(today, date("this friday"))
        assertEquals(LocalDate.of(2026, 11, 6), date("next friday"))
        // Sunday is still in this ISO week, so "next sunday" skips past it.
        assertEquals(LocalDate.of(2026, 11, 8), date("next sunday"))
        assertEquals(LocalDate.of(2026, 11, 2), date("next week"))
    }

    @Test
    fun calendarDates() {
        assertEquals(LocalDate.of(2026, 11, 1), date("the first"))
        assertEquals(LocalDate.of(2026, 11, 1), date("on the 1st"))
        assertEquals(LocalDate.of(2026, 11, 3), date("november 3rd"))
        assertEquals(LocalDate.of(2026, 11, 21), date("the twenty first of november"))
        // Already past this year, so next year.
        assertEquals(LocalDate.of(2027, 3, 5), date("March 5"))
        assertEquals(LocalDate.of(2026, 12, 24), date("12/24"))
        assertEquals(LocalDate.of(2026, 11, 3), date("2026-11-03"))
        assertEquals(LocalDate.of(2026, 10, 31), date("end of month"))
    }

    @Test
    fun ignoresNonDates() {
        assertNull(date("buy the 3 books"))
        assertNull(date("call mom"))
        assertNull(date(""))
    }

    @Test
    fun ruleParserSplitsOutDatePriorityAndTags() {
        val task = RuleParser.parse("Remind me to call the dentist tomorrow, it's urgent, hashtag health", now).single()
        assertEquals("Call the dentist", task.text)
        assertEquals(LocalDate.of(2026, 10, 31), task.due)
        assertEquals(Priority.HIGH, task.priority)
        assertEquals(listOf("health"), task.tags)
    }

    @Test
    fun ruleParserHandlesNegatedUrgency() {
        val task = RuleParser.parse("I need to fix the bike, not urgent", now).single()
        assertEquals("Fix the bike", task.text)
        assertEquals(Priority.LOW, task.priority)
        assertNull(task.due)
    }

    @Test
    fun llmReplyIsParsedAndDatesResolvedInCode() {
        val reply = """
            Sure! {"tasks":[
              {"text":"Email Sam","when":"on Monday","priority":"high","tags":["work"]},
              {"text":"Pay rent on the 1st","when":"","priority":"","tags":[]},
              {"text":"","when":"","priority":"","tags":[]}
            ]}
        """.trimIndent()
        val tasks = LlmPrompt.parse(reply, now)!!
        assertEquals(2, tasks.size)
        assertEquals(ParsedTask("Email Sam", LocalDate.of(2026, 11, 2), Priority.HIGH, listOf("work")), tasks[0])
        // The date left inside the text gets pulled out.
        assertEquals(ParsedTask("Pay rent", LocalDate.of(2026, 11, 1)), tasks[1])
    }

    private fun time(phrase: String) = TimeResolver.find(phrase)?.time

    @Test
    fun spokenTimes() {
        assertEquals(LocalTime.of(15, 0), time("at 3 p.m."))
        assertEquals(LocalTime.of(15, 0), time("3pm"))
        assertEquals(LocalTime.of(8, 30), time("8:30 a.m."))
        assertEquals(LocalTime.of(0, 0), time("12 am"))
        assertEquals(LocalTime.of(12, 0), time("at noon"))
        assertEquals(LocalTime.of(17, 30), time("at 5:30"))
        assertEquals(LocalTime.of(9, 15), time("at 9:15"))
        assertEquals(LocalTime.of(15, 0), time("at 3 o'clock"))
        assertEquals(LocalTime.of(9, 0), time("tomorrow morning"))
        assertEquals(LocalTime.of(20, 0), time("tonight"))
        assertNull(time("at the 3rd"))
        assertNull(time("call mom"))
        assertNull(time("in 2 days"))
    }

    @Test
    fun ruleParserPullsOutDateAndTime() {
        val task = RuleParser.parse("call the dentist tomorrow at 3 p.m.", now).single()
        assertEquals("Call the dentist", task.text)
        assertEquals(LocalDate.of(2026, 10, 31), task.due)
        assertEquals(LocalTime.of(15, 0), task.time)

        val morning = RuleParser.parse("water the plants tomorrow morning", now).single()
        assertEquals("Water the plants", morning.text)
        assertEquals(LocalDate.of(2026, 10, 31), morning.due)
        assertEquals(LocalTime.of(9, 0), morning.time)
    }

    @Test
    fun llmWhenPhraseCarriesTime() {
        val reply = """{"tasks":[
            {"text":"Email Sam","when":"on Monday at 10 am","priority":"","tags":[]},
            {"text":"Call mom at 6 pm","when":"","priority":"","tags":[]}
        ]}"""
        val tasks = LlmPrompt.parse(reply, now)!!
        assertEquals(ParsedTask("Email Sam", LocalDate.of(2026, 11, 2), time = LocalTime.of(10, 0)), tasks[0])
        assertEquals(ParsedTask("Call mom", time = LocalTime.of(18, 0)), tasks[1])
    }

    @Test
    fun dueDefaults() {
        val seven = LocalTime.of(19, 0)
        assertEquals(ParsedTask("Buy milk", today, time = seven), ParsedTask("Buy milk").withDueDefaults(today, seven))
        val friday = LocalDate.of(2026, 11, 6)
        assertEquals(friday to seven, ParsedTask("X", friday).withDueDefaults(today, seven).let { it.due to it.time })
        val noon = LocalTime.NOON
        assertEquals(today to noon, ParsedTask("X", time = noon).withDueDefaults(today, seven).let { it.due to it.time })
        assertEquals(LocalTime.of(7, 5), Settings.parseTime(" 7:05 "))
        assertNull(Settings.parseTime("7pm"))
    }

    private fun inTime(phrase: String) = RelativeTimeResolver.find(phrase, now)?.dateTime

    @Test
    fun relativeTimes() {
        assertEquals(today.atTime(14, 30), inTime("in 20 minutes"))
        assertEquals(today.atTime(15, 10), inTime("in an hour"))
        assertEquals(today.atTime(15, 40), inTime("in an hour and a half"))
        assertEquals(today.atTime(16, 40), inTime("in 2 and a half hours"))
        assertEquals(today.atTime(15, 25), inTime("in 1 hour and 15 minutes"))
        assertEquals(today.atTime(14, 40), inTime("in half an hour"))
        assertEquals(today.atTime(14, 25), inTime("in a quarter of an hour"))
        assertEquals(today.atTime(17, 10), inTime("3 hours from now"))
        assertEquals(today.atTime(15, 40), inTime("in 1.5 hours"))
        assertEquals(LocalDate.of(2026, 10, 31).atTime(2, 10), inTime("in twelve hours"))
        assertNull(inTime("in 2 days"))

        val task = RuleParser.parse("remind me to take the laundry out in 45 minutes", now).single()
        assertEquals(ParsedTask("Take the laundry out", today, time = LocalTime.of(14, 55)), task)
    }

    private val gym = Place("Gym", 51.50, -0.12, id = "gym")
    private val store = Place("Grocery Store", 51.51, -0.13, id = "store")
    private val home = Place("Home", 51.52, -0.14, id = "home")
    private val places = listOf(gym, store, home)

    @Test
    fun placesInTasks() {
        assertEquals(store, PlaceResolver.find("buy milk when I get to the grocery store", places)?.place)
        assertEquals(home, PlaceResolver.find("call mom when I get home", places)?.place)
        assertEquals(home, PlaceResolver.find("once I'm home take out the trash", places)?.place)
        assertEquals(gym, PlaceResolver.find("grab my shoes at the gym", places)?.place)
        assertNull(PlaceResolver.find("buy milk when I get to work", places))
        assertEquals(gym, PlaceResolver.resolve("the gym", places))

        val task = RuleParser.parse("buy protein when I get to the gym", now, places).single()
        assertEquals(ParsedTask("Buy protein", place = gym), task)
        // No 19:00 default for place tasks that didn't say a time.
        assertEquals(task, task.withDueDefaults(today, LocalTime.of(19, 0)))

        val reply = """{"tasks":[{"text":"Buy milk","when":"","where":"when I get to the grocery store","priority":"","tags":[]}]}"""
        assertEquals(ParsedTask("Buy milk", place = store), LlmPrompt.parse(reply, now, places)!!.single())

        assertEquals("grocery-store", store.slug)
        assertEquals("- [ ] Buy milk #place/grocery-store", TaskFormatter.format(ParsedTask("Buy milk", place = store), TaskFormat.EMOJI))
        assert("contexts:\n  - grocery-store\n" in TaskNotesFormatter.format(ParsedTask("Buy milk", place = store), now.atZone(java.time.ZoneOffset.UTC)))
        assertEquals(48.8584 to 2.2945, Place.parseCoordinates("48.8584, 2.2945"))
        assertNull(Place.parseCoordinates("48.8584"))
    }

    @Test
    fun clockExpressionsAndWordHours() {
        assertEquals(LocalTime.of(15, 15), time("quarter past 3"))
        assertEquals(LocalTime.of(17, 30), time("at half past five"))
        assertEquals(LocalTime.of(17, 45), time("quarter to 6"))
        assertEquals(LocalTime.of(9, 45), time("quarter to ten am"))
        assertEquals(LocalTime.of(16, 10), time("ten after 4"))
        assertEquals(LocalTime.of(18, 40), time("twenty to seven"))
        assertEquals(LocalTime.of(15, 0), time("three pm"))
        assertEquals(LocalTime.of(19, 30), time("at seven thirty p.m."))
        assertEquals(LocalTime.of(15, 0), time("at three"))
        assertEquals(LocalTime.of(17, 0), time("at five o'clock"))
        assertEquals(LocalTime.of(10, 0), time("at ten"))
        assertNull(time("at one point"))
        assertNull(time("at one of the stores"))
    }

    @Test
    fun modelDateTimeFillsOnlyWhatCodeCantExplain() {
        fun task(whenPhrase: String, date: String, time: String) = LlmPrompt.parse(
            """{"tasks":[{"text":"Do it","when":"$whenPhrase","date":"$date","time":"$time","where":"","priority":"","tags":[]}]}""",
            now,
        )!!.single()

        // Unknown phrase: the model's answer is used.
        assertEquals(LocalDate.of(2026, 10, 31) to LocalTime.of(8, 0), task("first thing tomorrow", "2026-10-31", "08:00").let { it.due to it.time })
        assertEquals(today to LocalTime.of(17, 0), task("end of day", "2026-10-30", "17:00").let { it.due to it.time })
        // Fully understood phrase: a made-up model time is ignored, and the code's date wins.
        assertEquals(LocalDate.of(2026, 10, 31) to null, task("tomorrow", "2026-11-05", "09:00").let { it.due to it.time })
        // No phrase at all: nothing from the model.
        assertEquals(null to null, task("", "2026-10-30", "19:00").let { it.due to it.time })
        // Garbage or far-off answers are dropped.
        assertEquals(null to null, task("someday", "2031-01-01", "25:00").let { it.due to it.time })
    }

    @Test
    fun unspokenPriorityFromModelIsDropped() {
        val reply = """{"tasks":[{"text":"Call the bank","when":"first thing tomorrow","date":"2026-10-31","time":"08:00","where":"","priority":"high","tags":[]}]}"""
        assertNull(LlmPrompt.parse(reply, now, transcript = "call the bank first thing tomorrow")!!.single().priority)
        assertEquals(Priority.HIGH, LlmPrompt.parse(reply, now, transcript = "call the bank tomorrow, it's urgent")!!.single().priority)
    }

    @Test
    fun promptCarriesTodayAndWorkedExample() {
        val prompt = LlmPrompt.build("call mom", now)
        assert("Today is Friday, October 30, 2026 (2026-10-30). The time is 14:10." in prompt)
        assert("\"date\":\"2026-11-02\",\"time\":\"10:00\"" in prompt)
        assert("\"time\":\"14:30\"" in prompt)
    }

    @Test
    fun badLlmReplyReturnsNull() {
        assertNull(LlmPrompt.parse("I can't help with that", now))
        assertNull(LlmPrompt.parse("{not json}", now))
    }

    @Test
    fun formatsBothStyles() {
        val task = ParsedTask("Email Sam", LocalDate.of(2026, 11, 2), Priority.HIGH, listOf("Work", "2026", "big project"))
        assertEquals("- [ ] Email Sam #work #bigproject ⏫ 📅 2026-11-02", TaskFormatter.format(task, TaskFormat.EMOJI))
        assertEquals(
            "- [ ] Email Sam #work #bigproject [priority:: high] [due:: 2026-11-02]",
            TaskFormatter.format(task, TaskFormat.DATAVIEW),
        )
        assertEquals("- [ ] Buy milk", TaskFormatter.format(ParsedTask("Buy milk"), TaskFormat.EMOJI))
    }

    @Test
    fun formatsTaskNotes() {
        val now = java.time.ZonedDateTime.of(2026, 10, 30, 14, 5, 9, 123_000_000, java.time.ZoneOffset.ofHours(-6))
        val task = ParsedTask("Email Sam", LocalDate.of(2026, 11, 2), Priority.HIGHEST, listOf("Work", "task"))
        assert("due: \"2026-11-02T08:30\"" in TaskNotesFormatter.format(task.copy(time = LocalTime.of(8, 30)), now))
        assertEquals(
            """
            ---
            status: open
            priority: high
            due: 2026-11-02
            tags:
              - task
              - work
            dateCreated: 2026-10-30T14:05:09.123-06:00
            dateModified: 2026-10-30T14:05:09.123-06:00
            ---

            """.trimIndent(),
            TaskNotesFormatter.format(task, now),
        )
        val plain = TaskNotesFormatter.format(ParsedTask("Buy milk"), now)
        assert("priority: normal" in plain && "due:" !in plain)
    }

    @Test
    fun dateTokenInPath() {
        assertEquals("Daily/2026-10-30.md", VaultWriter.resolvePath(" Daily/{date}.md ", today))
        assertEquals(listOf("Tasks", "Inbox"), VaultWriter.splitPath("/Tasks//Inbox/ "))
        assertEquals(emptyList<String>(), VaultWriter.splitPath(""))
    }

    @Test
    fun noteFileNames() {
        assertEquals("Email Sam.md", VaultWriter.noteFileName("Email Sam", emptySet()))
        assertEquals("Email Sam 3.md", VaultWriter.noteFileName("Email Sam", setOf("email sam.md", "email sam 2.md")))
        assertEquals("Fix A B bug.md", VaultWriter.noteFileName("Fix A/B: bug?", emptySet()))
        assertEquals("Task.md", VaultWriter.noteFileName("...", emptySet()))
        assertEquals(103, VaultWriter.noteFileName("x".repeat(300), emptySet()).length)
    }
}
