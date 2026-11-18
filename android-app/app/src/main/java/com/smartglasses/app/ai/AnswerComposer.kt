package com.smartglasses.app.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smartglasses.app.ble.TofReading
import com.smartglasses.app.settings.AnswerLength
import com.smartglasses.app.settings.DistanceRegion
import com.smartglasses.app.settings.Units
import kotlin.math.roundToInt

enum class Found { YES, NO, UNSURE }
enum class Confidence { HIGH, MEDIUM, LOW }
enum class Position(val wire: String, val col: Int, val row: Int) {
    TOP_LEFT("top-left", 0, 0), TOP("top", 1, 0), TOP_RIGHT("top-right", 2, 0),
    LEFT("left", 0, 1), CENTER("center", 1, 1), RIGHT("right", 2, 1),
    BOTTOM_LEFT("bottom-left", 0, 2), BOTTOM("bottom", 1, 2), BOTTOM_RIGHT("bottom-right", 2, 2);

    companion object {
        fun parse(s: String?): Position? {
            val k = s?.trim()?.lowercase()?.replace('_', '-')?.replace(' ', '-')?.replace("centre", "center") ?: return null
            return entries.firstOrNull { it.wire == k } ?: if (k == "middle") CENTER else null
        }
    }
}

/** The model's reply in question mode (the JSON contract in Prompts.questionJson). */
data class AiAnswer(
    val heard: String,
    val target: String,
    val found: Found,
    val position: Position?,
    val answer: String,
    val confidence: Confidence,
) {
    companion object {
        /**
         * Validates the model's JSON. Tolerates code fences or chatter around the object
         * and unknown extra keys; returns null if there's no usable answer.
         */
        fun parse(raw: String): AiAnswer? {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o: JsonObject = runCatching { JsonParser.parseString(raw.substring(start, end + 1)).asJsonObject }.getOrNull() ?: return null
            fun str(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val answer = str("answer")
            if (answer.isBlank()) return null
            return AiAnswer(
                heard = str("heard"),
                target = str("target"),
                found = when (str("found").lowercase()) { "yes", "true" -> Found.YES; "no", "false" -> Found.NO; else -> Found.UNSURE },
                position = Position.parse(str("position")),
                answer = answer,
                confidence = when (str("confidence").lowercase()) { "high" -> Confidence.HIGH; "low" -> Confidence.LOW; else -> Confidence.MEDIUM },
            )
        }
    }
}

/** Settings the composer needs (a plain snapshot, so it stays a pure function). */
data class ComposeSettings(
    val units: Units = Units.METRIC,
    val region: DistanceRegion = DistanceRegion.CENTER_ONLY,
    val tofOffsetMm: Int = 0,
    val tofScale: Float = 1f,
    val length: AnswerLength = AnswerLength.BRIEF,
)

/**
 * Builds the sentence the wearer hears. Every number in it comes from the distance
 * sensor, never from the model, and the safety rules are enforced here even if the
 * model ignores its instructions.
 */
object AnswerComposer {
    /** The VL53L0X's dependable range in default mode (datasheet: ~50 mm to 1.2 m). */
    const val MAX_RELIABLE_MM = 1200
    const val NO_DISTANCE = "I couldn't get a reliable distance."

    /** Tap / describe mode: the ToF points straight ahead, i.e. at the thing in the middle of the view. */
    fun composeDescribe(phrase: String, tof: TofReading?, s: ComposeSettings = ComposeSettings()): String {
        val p = phrase.trim().trimEnd('.', ',')
        if (tof == null) return p  // gallery photo: there is no distance sensor
        val mm = calibrated(tof, s) ?: return "${capitalize(p)}. $NO_DISTANCE"
        return "$p, ${formatDistance(mm, s.units)}"
    }

    /** Question mode. */
    fun composeAnswer(a: AiAnswer, tof: TofReading?, s: ComposeSettings = ComposeSettings()): String {
        val target = a.target.trim().ifBlank { null }
        if (a.found == Found.NO && target != null) return "I can't see ${withArticle(target)}."

        var answer = enforceSafety(stripModelDistances(a.answer))
        answer = limitSentences(answer, if (s.length == AnswerLength.BRIEF) 2 else 4)
        if (answer.isBlank()) answer = if (target != null) "I can see ${withArticle(target)}." else "I'm not sure."
        if ((a.confidence == Confidence.LOW || a.found == Found.UNSURE) && !isHedged(answer)) {
            answer = "I think " + lowerFirst(answer)
        }
        answer = ensurePeriod(answer)

        // Distance only for a target the ToF is actually pointing at.
        if (target == null || tof == null || a.found == Found.NO) return answer
        val pos = a.position ?: return answer
        return if (inToFRegion(pos, s.region)) {
            val mm = calibrated(tof, s)
            if (mm == null) "$answer $NO_DISTANCE" else "$answer It's ${formatDistance(mm, s.units)} away."
        } else {
            "$answer ${directionHint(pos)}"
        }
    }

    fun inToFRegion(p: Position, region: DistanceRegion) = when (region) {
        DistanceRegion.CENTER_ONLY -> p == Position.CENTER
        DistanceRegion.CENTER_COLUMN -> p.col == 1
    }

    /** Never a distance for an off-axis object: tell the wearer where to turn instead. */
    fun directionHint(p: Position): String = when (p.col) {
        0 -> "It's on your left. Turn left and ask again for the distance."
        2 -> "It's on your right. Turn right and ask again for the distance."
        else -> if (p.row == 0) "It's above the middle of your view. Tilt your head up and ask again for the distance."
        else "It's below the middle of your view. Tilt your head down and ask again for the distance."
    }

    /** Calibrated distance in mm, or null if the reading isn't trustworthy. */
    fun calibrated(tof: TofReading, s: ComposeSettings): Int? {
        if (!tof.isValid) return null
        val mm = (tof.medianMm * s.tofScale + s.tofOffsetMm).roundToInt()
        return if (mm in 1..MAX_RELIABLE_MM) mm else null
    }

    /** "about 35 centimetres": nearest 5 cm under 1 m, nearest 10 cm above; imperial: inches, then half feet. */
    fun formatDistance(mm: Int, units: Units): String = when (units) {
        Units.METRIC -> if (mm < 1000) {
            val cm = (((mm / 10.0) / 5).roundToInt() * 5).coerceAtLeast(5)
            if (cm >= 100) "about 1 metre" else "about $cm centimetres"
        } else {
            val m = (mm / 100.0).roundToInt() / 10.0
            if (m == 1.0) "about 1 metre" else if (m % 1.0 == 0.0) "about ${m.toInt()} metres" else "about $m metres"
        }
        Units.IMPERIAL -> {
            val inches = mm / 25.4
            if (inches < 36) {
                val n = inches.roundToInt().coerceAtLeast(1)
                "about $n inch${if (n == 1) "" else "es"}"
            } else {
                val halfFeet = (inches / 6).roundToInt()  // half-foot steps
                val whole = halfFeet / 2
                if (halfFeet % 2 == 0) "about $whole feet" else "about $whole and a half feet"
            }
        }
    }

    // ---- safety and clean-up ----

    private val DISTANCE_NUMBER = Regex(
        """\b\d+(?:[.,]\d+)?\s*(?:cm|centimet(?:er|re)s?|mm|millimet(?:er|re)s?|m|met(?:er|re)s?|inch(?:es)?|in|feet|foot|ft)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val UNSAFE_CLAIM = Regex(
        """\b(?:it(?:'s| is)|looks|seems|appears)\s+(?:completely\s+|perfectly\s+|totally\s+)?safe\b|\bsafe to (?:cross|walk|go|use|step|climb|descend)\b|\b(?:path|way|road|street|crossing|stairs?|steps?)\s+(?:is|are|looks|seems)\s+(?:clear|safe)\b|\bclear to (?:cross|walk|go)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Drops sentences in which the model stated a distance (only the sensor may). */
    fun stripModelDistances(s: String): String =
        sentences(s).filterNot { DISTANCE_NUMBER.containsMatchIn(it) }.joinToString(" ")

    private val NEGATED = Regex("""\b(?:can't|cannot|can not|not|don't|isn't|aren't|unsure|no way)\b""", RegexOption.IGNORE_CASE)
    private fun claimsSafe(sentence: String) = UNSAFE_CLAIM.containsMatchIn(sentence) && !NEGATED.containsMatchIn(sentence)

    /** Replaces any "it's safe / clear to cross" claim with the required wording. */
    fun enforceSafety(s: String): String {
        val parts = sentences(s)
        if (parts.none(::claimsSafe)) return s
        val kept = parts.filterNot(::claimsSafe)
        return (kept + "I can't tell if it's safe.").joinToString(" ")
    }

    private fun sentences(s: String) = s.trim().split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    private fun limitSentences(s: String, n: Int) = sentences(s).take(n).joinToString(" ")
    private fun isHedged(s: String) = Regex("^(i think|i believe|it might|it may|maybe|possibly|probably|it looks like|i'm not sure|i can't)", RegexOption.IGNORE_CASE).containsMatchIn(s.trim())
    private fun ensurePeriod(s: String) = s.trim().let { if (it.endsWith('.') || it.endsWith('!') || it.endsWith('?')) it else "$it." }
    private fun lowerFirst(s: String) = if (s.startsWith("I ") || s.startsWith("I'")) s else s.replaceFirstChar { it.lowercase() }
    private fun capitalize(s: String) = s.replaceFirstChar { it.uppercase() }
    private fun withArticle(t: String): String {
        val l = t.lowercase()
        if (l.startsWith("the ") || l.startsWith("a ") || l.startsWith("an ") || l.startsWith("my ") || l.startsWith("your ")) {
            return t.replaceFirst(Regex("^(?i)my "), "your ")
        }
        return "the $t"
    }
}
