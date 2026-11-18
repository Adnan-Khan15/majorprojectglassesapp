package com.smartglasses.app.voice

import android.content.Context
import android.os.SystemClock
import com.smartglasses.app.ai.SceneDescriber
import com.smartglasses.app.audio.Wav
import com.smartglasses.app.metrics.InteractionTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 3 STT spike (docs/STT_SPIKE.md): runs every WAV in the spike folder through
 * each available recogniser and writes results.csv next to them. Score it on a
 * computer with scripts/wer.py against your reference transcripts.
 *
 * Folder: /sdcard/Android/data/com.smartglasses.app/files/stt_spike/
 * (record clips in the app, or adb push 16 kHz mono WAVs there).
 */
class SttSpike(context: Context, private val describer: SceneDescriber, private val android: AndroidOnDeviceStt) {
    val dir: File = File(context.getExternalFilesDir(null), "stt_spike").apply { mkdirs() }

    fun clips(): List<File> = dir.listFiles { f -> f.extension.equals("wav", true) }.orEmpty().sortedBy { it.name }

    /** Saves a phone-mic recording as the next qNN.wav. */
    suspend fun save(pcm16k: ShortArray): File = withContext(Dispatchers.IO) {
        val n = (clips().mapNotNull { it.nameWithoutExtension.removePrefix("q").toIntOrNull() }.maxOrNull() ?: 0) + 1
        File(dir, "q%02d.wav".format(n)).apply { writeBytes(Wav.write(pcm16k, 16000)) }
    }

    /** Returns results.csv. [progress] gets (done, total, line). */
    suspend fun run(progress: (Int, Int, String) -> Unit): File = withContext(Dispatchers.IO) {
        val files = clips()
        val engines = buildList<Pair<String, suspend (ShortArray) -> String>> {
            if (describer.gemmaHearsAudio) add("gemma_audio" to { pcm -> describer.transcribeWithGemma(pcm) })
            if (android.available) add("android_on_device" to { pcm -> android.transcribe(pcm) })
        }
        check(engines.isNotEmpty()) { "No recogniser available (Gemma audio not loaded, no on-device recogniser)" }
        val out = File(dir, "results.csv")
        out.writeText("file,engine,transcript,ms,audio_ms,error\n")
        var done = 0
        val total = files.size * engines.size
        for (f in files) {
            val pcm = runCatching { Wav.parse(f.readBytes()) }.getOrNull()
            for ((name, engine) in engines) {
                val row = if (pcm == null || pcm.sampleRate != 16000) {
                    listOf(f.name, name, "", "", "", "not a 16 kHz PCM WAV")
                } else {
                    val t0 = SystemClock.elapsedRealtime()
                    val r = runCatching { engine(pcm.samples) }
                    val ms = SystemClock.elapsedRealtime() - t0
                    listOf(f.name, name, r.getOrDefault(""), ms.toString(), (pcm.samples.size / 16).toString(),
                        r.exceptionOrNull()?.message.orEmpty())
                }
                out.appendText(row.joinToString(",") { InteractionTimeline.csvEscape(it) } + "\n")
                progress(++done, total, "${f.name} · $name: ${row[2].ifBlank { row[5] }}")
            }
        }
        out
    }
}
