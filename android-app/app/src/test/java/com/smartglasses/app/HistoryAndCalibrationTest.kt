package com.smartglasses.app

import com.smartglasses.app.ai.TofCalibration
import com.smartglasses.app.history.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAndCalibrationTest {
    @Test
    fun calibrationFitsScaleAndOffset() {
        // the sensor reads 5 % short plus 10 mm: measured = (true - 10) / 1.05
        val pts = listOf(200, 500, 1000).map { TofCalibration.Point(it, ((it - 10) / 1.05).toInt()) }
        val f = TofCalibration.fit(pts)!!
        assertEquals(1.05f, f.scale, 0.01f)
        assertEquals(10.0, f.offsetMm.toDouble(), 3.0)
        assertTrue(f.maxResidualMm <= 2)
    }

    @Test
    fun onePointGivesOffsetOnly() {
        val f = TofCalibration.fit(listOf(TofCalibration.Point(300, 285)))!!
        assertEquals(1f, f.scale)
        assertEquals(15, f.offsetMm)
        assertNull(TofCalibration.fit(emptyList()))
    }

    private fun item(id: String) = HistoryItem(id, 1000, "question", "glasses", "glasses mic", "where are my keys",
        "I can see your keys, about 40 centimetres", "ok", null, "Gemma", "Gemma audio", "", null, 402, 1, 5, 5, 3,
        mapOf("button_down_ms" to 0L, "play_started_ms" to 5400L), null)

    @Test
    fun historyJsonRoundTripAndCsv() {
        val h = item("abc")
        val back = HistoryItem.fromJson(h.toJson(), null)
        assertEquals(h, back)
        val csv = HistoryItem.toCsv(listOf(h)).lines()
        assertEquals(HistoryItem.CSV_COLUMNS.joinToString(","), csv[0])
        assertTrue(csv[1].startsWith("abc,1000,question,glasses,glasses mic,where are my keys,\"I can see your keys, about 40 centimetres\",ok"))
    }
}
