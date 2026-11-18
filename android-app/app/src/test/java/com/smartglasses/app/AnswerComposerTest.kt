package com.smartglasses.app

import com.smartglasses.app.ai.AiAnswer
import com.smartglasses.app.ai.AnswerComposer
import com.smartglasses.app.ai.ComposeSettings
import com.smartglasses.app.ai.Confidence
import com.smartglasses.app.ai.Found
import com.smartglasses.app.ai.Position
import com.smartglasses.app.ble.Frames
import com.smartglasses.app.ble.TofReading
import com.smartglasses.app.settings.AnswerLength
import com.smartglasses.app.settings.DistanceRegion
import com.smartglasses.app.settings.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerComposerTest {
    private fun tof(mm: Int) = TofReading(mm, Frames.TOF_FLAG_VALID, 5, 5, 4)
    private val noReading = TofReading(0, Frames.TOF_FLAG_OUT_OF_RANGE, 0, 5, 0)
    private fun ans(answer: String, target: String = "house keys", found: Found = Found.YES,
                    position: Position? = Position.CENTER, confidence: Confidence = Confidence.HIGH) =
        AiAnswer("where are my house keys", target, found, position, answer, confidence)

    // ---------------- rounding and units ----------------

    @Test
    fun metricRounding() {
        val m = Units.METRIC
        assertEquals("about 35 centimetres", AnswerComposer.formatDistance(362, m))   // nearest 5 cm
        assertEquals("about 25 centimetres", AnswerComposer.formatDistance(255, m))
        assertEquals("about 5 centimetres", AnswerComposer.formatDistance(31, m))
        assertEquals("about 1 metre", AnswerComposer.formatDistance(990, m))          // 99 cm -> 100 cm
        assertEquals("about 1 metre", AnswerComposer.formatDistance(1040, m))         // nearest 10 cm above 1 m
        assertEquals("about 1.2 metres", AnswerComposer.formatDistance(1180, m))
    }

    @Test
    fun imperialRounding() {
        val i = Units.IMPERIAL
        assertEquals("about 14 inches", AnswerComposer.formatDistance(362, i))
        assertEquals("about 3 feet", AnswerComposer.formatDistance(920, i))
        assertEquals("about 3 and a half feet", AnswerComposer.formatDistance(1070, i))
    }

    // ---------------- describe (tap) mode ----------------

    @Test
    fun describeAddsAboutDistanceOrSaysUnreliable() {
        assertEquals("a blue pen with Pentel written on it, about 35 centimetres",
            AnswerComposer.composeDescribe("a blue pen with Pentel written on it", tof(362)))
        assertEquals("A white mug. I couldn't get a reliable distance.", AnswerComposer.composeDescribe("a white mug", noReading))
        assertEquals("A white mug. I couldn't get a reliable distance.", AnswerComposer.composeDescribe("a white mug", tof(1500)))  // beyond 1.2 m
        assertEquals("a white mug", AnswerComposer.composeDescribe("a white mug", null))  // gallery: no sensor at all
    }

    @Test
    fun calibrationIsApplied() {
        val s = ComposeSettings(tofOffsetMm = -20, tofScale = 1.1f)
        assertEquals(378, AnswerComposer.calibrated(tof(362), s))  // 362 * 1.1 - 20
        assertNull(AnswerComposer.calibrated(tof(1150), s))         // calibrated 1245 mm > 1.2 m
    }

    // ---------------- question mode: distance only on axis ----------------

    @Test
    fun centredTargetGetsDistance() {
        assertEquals("Your keys are on the table in front of you. It's about 45 centimetres away.",
            AnswerComposer.composeAnswer(ans("Your keys are on the table in front of you."), tof(447)))
    }

    @Test
    fun offCentreTargetGetsDirectionNeverDistance() {
        val out = AnswerComposer.composeAnswer(ans("I can see your keys on the left of the table.", position = Position.LEFT), tof(447))
        assertEquals("I can see your keys on the left of the table. It's on your left. Turn left and ask again for the distance.", out)
        assertFalse(out.contains("centimetres"))
        assertTrue(AnswerComposer.composeAnswer(ans("Keys on the right.", position = Position.BOTTOM_RIGHT), tof(447)).contains("Turn right"))
        assertTrue(AnswerComposer.composeAnswer(ans("Keys above.", position = Position.TOP), tof(447)).contains("Tilt your head up"))
    }

    @Test
    fun centreColumnSettingWidensTheRegion() {
        val s = ComposeSettings(region = DistanceRegion.CENTER_COLUMN)
        assertTrue(AnswerComposer.composeAnswer(ans("Keys below.", position = Position.BOTTOM), tof(447), s).contains("about 45 centimetres"))
    }

    @Test
    fun centredButNoReadingSaysUnreliable() {
        assertEquals("The keys are ahead. I couldn't get a reliable distance.",
            AnswerComposer.composeAnswer(ans("The keys are ahead."), noReading))
    }

    @Test
    fun notFoundSaysCantSeeIt() {
        assertEquals("I can't see your keys.", AnswerComposer.composeAnswer(ans("They might be somewhere.", target = "my keys", found = Found.NO), tof(400)))
        assertEquals("I can't see the house keys.", AnswerComposer.composeAnswer(ans("x", found = Found.NO), tof(400)))
    }

    @Test
    fun lowConfidenceIsHedged() {
        assertEquals("I think the keys are on the shelf. It's about 50 centimetres away.",
            AnswerComposer.composeAnswer(ans("The keys are on the shelf.", confidence = Confidence.LOW), tof(500)))
        // already hedged: not doubled
        assertEquals("I think it's a mug.", AnswerComposer.composeAnswer(ans("I think it's a mug.", target = "", confidence = Confidence.LOW), null))
    }

    @Test
    fun modelNeverStatesDistances() {
        val out = AnswerComposer.composeAnswer(ans("The keys are on the table. They are about 40 cm from you.", position = Position.LEFT), tof(400))
        assertFalse(out.contains("40"))
        assertTrue(out.startsWith("The keys are on the table."))
    }

    @Test
    fun safetyWordingIsEnforced() {
        val out = AnswerComposer.composeAnswer(ans("The road looks clear. It's safe to cross now.", target = ""), null)
        assertEquals("I can't tell if it's safe.", out)
        assertEquals("There are stairs going down ahead. I can't tell if it's safe.",
            AnswerComposer.composeAnswer(ans("There are stairs going down ahead. The stairs are clear.", target = ""), null))
        // an honest "can't tell" is left alone
        assertEquals("I can't tell if it's safe to cross.",
            AnswerComposer.composeAnswer(ans("I can't tell if it's safe to cross.", target = ""), null))
    }

    @Test
    fun briefLimitsToTwoSentences() {
        val long = "One. Two. Three. Four. Five."
        assertEquals("One. Two.", AnswerComposer.composeAnswer(ans(long, target = ""), null))
        assertEquals("One. Two. Three. Four.", AnswerComposer.composeAnswer(ans(long, target = ""), null, ComposeSettings(length = AnswerLength.DETAILED)))
    }

    // ---------------- JSON contract ----------------

    @Test
    fun parsesTheContractIncludingFencesAndChatter() {
        val raw = """Sure! ```json
            {"heard": "where are my house keys", "target": "house keys", "found": "yes",
             "position": "top-left", "answer": "They are on the shelf.", "confidence": "medium", "extra": 1}
            ```"""
        val a = AiAnswer.parse(raw)!!
        assertEquals("where are my house keys", a.heard)
        assertEquals(Found.YES, a.found)
        assertEquals(Position.TOP_LEFT, a.position)
        assertEquals(Confidence.MEDIUM, a.confidence)
    }

    @Test
    fun parserIsLenientOnEnumsButRejectsMissingAnswer() {
        val a = AiAnswer.parse("""{"answer":"A mug.","found":"maybe","position":"Centre","confidence":"?"}""")!!
        assertEquals(Found.UNSURE, a.found)
        assertEquals(Position.CENTER, a.position)
        assertEquals(Confidence.MEDIUM, a.confidence)
        assertNull(AiAnswer.parse("""{"heard":"hi","answer":""}"""))
        assertNull(AiAnswer.parse("no json here"))
        assertNull(AiAnswer.parse("""{"answer": "unterminated"""))
    }
}
