package com.smartglasses.app

import com.smartglasses.app.ai.StreamAccumulator
import com.smartglasses.app.metrics.InteractionTimeline
import com.smartglasses.app.metrics.Stage
import org.junit.Assert.assertEquals
import org.junit.Test

class MetricsTest {

    @Test
    fun csvRowHasOffsetsFromFirstStageAndBlankForMissing() {
        var now = 1000L
        val t = InteractionTimeline("abc", "tap", "glasses", clock = { now }, wallStartMs = 0)
        t.mark(Stage.IMAGE_HEADER)
        now = 3000; t.mark(Stage.IMAGE_RECEIVED)
        now = 3200; t.mark(Stage.OCR_DONE)
        now = 9000; t.mark(Stage.ANSWER_COMPLETE)
        t.mark(Stage.ANSWER_COMPLETE, 99_999)  // second mark of a stage is ignored
        t.put("answer", "a mug, about 35 centimetres")
        t.outcome = "ok"

        val header = InteractionTimeline.CSV_HEADER.split(",")
        val row = parseCsvRow(t.toCsvRow())
        assertEquals(header.size, row.size)
        val cell = header.zip(row).toMap()
        assertEquals("1970-01-01T00:00:00.000Z", cell["started_at_utc"])
        assertEquals("0", cell["image_header_ms"])
        assertEquals("2000", cell["image_received_ms"])
        assertEquals("2200", cell["ocr_done_ms"])
        assertEquals("8000", cell["answer_complete_ms"])
        assertEquals("", cell["button_down_ms"])
        assertEquals("a mug, about 35 centimetres", cell["answer"])
        assertEquals(6000L, t.between(Stage.IMAGE_RECEIVED, Stage.ANSWER_COMPLETE))
    }

    @Test
    fun csvEscapesQuotesAndCommas() {
        assertEquals("\"say \"\"hi\"\", ok\"", InteractionTimeline.csvEscape("say \"hi\", ok"))
        assertEquals("plain", InteractionTimeline.csvEscape("plain"))
    }

    @Test
    fun streamAccumulatorHandlesDeltasAndCumulativeChunks() {
        var firsts = 0
        val deltas = StreamAccumulator { firsts++ }
        listOf("a blue", " pen", " with Pentel").forEach(deltas::add)
        assertEquals("a blue pen with Pentel", deltas.text)
        assertEquals(1, firsts)

        val cumulative = StreamAccumulator()
        listOf("a blue", "a blue pen", "a blue pen with Pentel").forEach(cumulative::add)
        assertEquals("a blue pen with Pentel", cumulative.text)
    }

    /** Minimal RFC 4180 parser for the test. */
    private fun parseCsvRow(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
