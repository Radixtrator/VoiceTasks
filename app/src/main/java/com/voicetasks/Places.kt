package com.voicetasks

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A saved place you can say in a task: "when I get to the gym". */
data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = DEFAULT_RADIUS,
    val id: String = UUID.randomUUID().toString(),
) {
    /** How the place is written into notes: "Grocery Store" → "grocery-store". */
    val slug: String get() = name.trim().lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "-").trim('-')

    companion object {
        const val DEFAULT_RADIUS = 150f

        /** "48.8584, 2.2945" (as copied from Google Maps) → the pair, or null. */
        fun parseCoordinates(text: String): Pair<Double, Double>? {
            val parts = text.split(',').map { it.trim().toDoubleOrNull() }
            val lat = parts.getOrNull(0) ?: return null
            val lng = parts.getOrNull(1) ?: return null
            return if (parts.size == 2 && lat in -90.0..90.0 && lng in -180.0..180.0) lat to lng else null
        }
    }
}

class PlaceStore(context: Context) {

    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)

    fun all(): List<Place> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Place(
                o.getString("name"), o.getDouble("lat"), o.getDouble("lng"),
                o.optDouble("radius", Place.DEFAULT_RADIUS.toDouble()).toFloat(), o.getString("id"),
            )
        }
    }

    fun save(places: List<Place>) {
        val array = JSONArray()
        places.forEach {
            array.put(
                JSONObject().put("id", it.id).put("name", it.name).put("lat", it.latitude)
                    .put("lng", it.longitude).put("radius", it.radiusMeters.toDouble()),
            )
        }
        prefs.edit { putString(KEY, array.toString()) }
    }

    private companion object {
        const val KEY = "places"
    }
}

/** Finds a saved place mentioned in a task, like "when I get home" or "at the gym". */
object PlaceResolver {

    data class Match(val range: IntRange, val place: Place)

    // "when I get to", "once I'm at", "next time I'm at", "as soon as I arrive at", "when I get back to"…
    private const val ARRIVE =
        """(?:when|once|after|as\s+soon\s+as|next\s+time|the\s+next\s+time)\s+i(?:['’]m|\s+am|\s+get|\s+arrive|\s+reach|\s+go|\s+stop)?""" +
            """(?:\s+back)?(?:\s+(?:to|at|in|by|near))?"""
    private const val ARTICLE = """(?:(?:the|my)\s+)?"""

    /** Looks for an arrival phrase ("when I get to the gym") or a plain "at the gym". */
    fun find(text: String, places: List<Place>): Match? = places
        .sortedByDescending { it.name.length }
        .flatMap { place ->
            val name = place.name.trim().split(Regex("""\s+""")).joinToString("""\s+""") { Regex.escape(it) }
            listOf("""\b$ARRIVE\s+$ARTICLE$name\b""", """\b(?:at|near)\s+$ARTICLE$name\b""")
                .mapNotNull { Regex(it, RegexOption.IGNORE_CASE).find(text) }
                .map { Match(it.range, place) }
        }
        .minWithOrNull(compareBy<Match> { it.range.first }.thenByDescending { it.range.last })

    /** For a phrase that's only about the place (the model's "where"), the bare name is enough. */
    fun resolve(phrase: String, places: List<Place>): Place? =
        find(phrase, places)?.place ?: places.sortedByDescending { it.name.length }.firstOrNull { place ->
            val name = place.name.trim().split(Regex("""\s+""")).joinToString("""\s+""") { Regex.escape(it) }
            Regex("""\b$name\b""", RegexOption.IGNORE_CASE).containsMatchIn(phrase)
        }
}
