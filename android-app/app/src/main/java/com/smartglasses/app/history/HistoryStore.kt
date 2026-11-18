package com.smartglasses.app.history

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smartglasses.app.ai.SceneDescriber
import com.smartglasses.app.assistant.InteractionRecord
import com.smartglasses.app.metrics.InteractionTimeline
import com.smartglasses.app.metrics.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** One saved interaction (what History shows). Stored as JSON + a small JPEG thumbnail. */
data class HistoryItem(
    val id: String,
    val startedAtMs: Long,
    val mode: String,            // tap | question | phone_question
    val source: String,          // glasses | gallery
    val questionVia: String?,
    val heard: String?,
    val answer: String?,
    val outcome: String,
    val error: String?,
    val engine: String?,
    val stt: String?,
    val ocrText: String?,
    val modelRaw: String?,
    val distanceMm: Int?,
    val tofFlags: Int?,
    val tofValid: Int?,
    val tofSamples: Int?,
    val tofSpreadMm: Int?,
    /** Stage -> ms from the first recorded stage (the latency breakdown). */
    val stages: Map<String, Long>,
    val thumbnail: File?,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id); addProperty("started_at_ms", startedAtMs); addProperty("mode", mode)
        addProperty("source", source); addProperty("question_via", questionVia); addProperty("heard", heard)
        addProperty("answer", answer); addProperty("outcome", outcome); addProperty("error", error)
        addProperty("engine", engine); addProperty("stt", stt); addProperty("ocr_text", ocrText)
        addProperty("model_raw", modelRaw); addProperty("distance_mm", distanceMm); addProperty("tof_flags", tofFlags)
        addProperty("tof_valid", tofValid); addProperty("tof_samples", tofSamples); addProperty("tof_spread_mm", tofSpreadMm)
        add("stages_ms", JsonObject().apply { stages.forEach { (k, v) -> addProperty(k, v) } })
    }

    companion object {
        fun fromRecord(r: InteractionRecord, thumb: File?): HistoryItem {
            val t = r.timeline
            val t0 = Stage.entries.mapNotNull { t.at(it) }.minOrNull()
            return HistoryItem(
                id = t.id, startedAtMs = t.wallStartMs, mode = t.mode, source = t.source,
                questionVia = r.questionVia, heard = r.heard, answer = r.sentence, outcome = t.outcome,
                error = r.error, engine = r.engine, stt = r.stt, ocrText = r.ocrText, modelRaw = r.modelRaw,
                distanceMm = r.tof?.medianMm, tofFlags = r.tof?.flags, tofValid = r.tof?.validCount,
                tofSamples = r.tof?.sampleCount, tofSpreadMm = r.tof?.spreadMm,
                stages = Stage.entries.mapNotNull { s -> t.at(s)?.let { s.column to (it - (t0 ?: it)) } }.toMap(),
                thumbnail = thumb,
            )
        }

        fun fromJson(o: JsonObject, thumb: File?): HistoryItem {
            fun s(k: String) = o.get(k)?.takeUnless { it.isJsonNull }?.asString
            fun i(k: String) = o.get(k)?.takeUnless { it.isJsonNull }?.asInt
            return HistoryItem(
                id = s("id") ?: "?", startedAtMs = o.get("started_at_ms")?.asLong ?: 0, mode = s("mode") ?: "tap",
                source = s("source") ?: "?", questionVia = s("question_via"), heard = s("heard"), answer = s("answer"),
                outcome = s("outcome") ?: "?", error = s("error"), engine = s("engine"), stt = s("stt"),
                ocrText = s("ocr_text"), modelRaw = s("model_raw"), distanceMm = i("distance_mm"), tofFlags = i("tof_flags"),
                tofValid = i("tof_valid"), tofSamples = i("tof_samples"), tofSpreadMm = i("tof_spread_mm"),
                stages = o.getAsJsonObject("stages_ms")?.entrySet()?.associate { it.key to it.value.asLong }.orEmpty(),
                thumbnail = thumb,
            )
        }

        val CSV_COLUMNS = listOf("id", "started_at_ms", "mode", "source", "question_via", "heard", "answer", "outcome",
            "error", "engine", "stt", "distance_mm", "tof_flags", "tof_valid", "tof_samples", "tof_spread_mm") +
            Stage.entries.map { it.column + "_ms" }

        fun toCsv(items: List<HistoryItem>): String = buildString {
            append(CSV_COLUMNS.joinToString(",")).append('\n')
            for (h in items) {
                val cells = listOf(h.id, h.startedAtMs, h.mode, h.source, h.questionVia, h.heard, h.answer, h.outcome,
                    h.error, h.engine, h.stt, h.distanceMm, h.tofFlags, h.tofValid, h.tofSamples, h.tofSpreadMm) +
                    Stage.entries.map { h.stages[it.column] }
                append(cells.joinToString(",") { InteractionTimeline.csvEscape(it?.toString() ?: "") }).append('\n')
            }
        }
    }
}

/** Saves every finished interaction to `files/history/`, newest first. */
class HistoryStore(private val dir: File, private val scope: CoroutineScope) {
    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items.asStateFlow()

    fun load() = scope.launch(Dispatchers.IO) {
        dir.mkdirs()
        _items.value = dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            runCatching { HistoryItem.fromJson(JsonParser.parseString(f.readText()).asJsonObject, thumbFor(f.nameWithoutExtension)) }.getOrNull()
        }.sortedByDescending { it.startedAtMs }
    }

    private fun thumbFor(id: String) = File(dir, "$id.jpg").takeIf { it.exists() }

    fun add(r: InteractionRecord) = scope.launch(Dispatchers.IO) {
        try {
            dir.mkdirs()
            val id = r.timeline.id
            val thumb = r.jpeg?.let { jpeg ->
                File(dir, "$id.jpg").also { f ->
                    val bmp = SceneDescriber.decodeDownscaled(jpeg, THUMB_PX)
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                }
            }
            val item = HistoryItem.fromRecord(r, thumb)
            File(dir, "$id.json").writeText(item.toJson().toString())
            _items.value = (listOf(item) + _items.value).take(MAX_ITEMS)
            trim()
        } catch (e: Exception) {
            Log.w("HistoryStore", "couldn't save interaction", e)
        }
    }

    fun delete(id: String) = scope.launch(Dispatchers.IO) {
        File(dir, "$id.json").delete(); File(dir, "$id.jpg").delete()
        _items.value = _items.value.filterNot { it.id == id }
    }

    fun clear() = scope.launch(Dispatchers.IO) {
        dir.listFiles().orEmpty().forEach { it.delete() }
        _items.value = emptyList()
    }

    suspend fun exportJson(out: OutputStream) = withContext(Dispatchers.IO) {
        val arr = JsonArray().apply { _items.value.forEach { add(it.toJson()) } }
        out.use { it.write(GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(arr).toByteArray()) }
    }

    suspend fun exportCsv(out: OutputStream) = withContext(Dispatchers.IO) {
        out.use { it.write(HistoryItem.toCsv(_items.value).toByteArray()) }
    }

    private fun trim() {
        val keep = _items.value.map { it.id }.toSet()
        dir.listFiles().orEmpty().filter { it.nameWithoutExtension !in keep }.forEach { it.delete() }
    }

    companion object {
        private const val THUMB_PX = 320
        const val MAX_ITEMS = 300
    }
}
