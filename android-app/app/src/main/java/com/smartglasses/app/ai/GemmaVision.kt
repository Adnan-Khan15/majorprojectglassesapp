package com.smartglasses.app.ai

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.smartglasses.app.settings.AnswerLength
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gemma 4 E2B (vision + audio) on LiteRT-LM. All calls are confined to one thread:
 * the native engine is not meant to be driven concurrently.
 *
 * Audio input (spoken questions) needs the engine's audio encoder. If an engine with
 * an audio backend can't start on a phone, Gemma still loads without it and
 * [audioSupported] is false; the app then uses Android's on-device speech recogniser.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GemmaVision(private val cacheDir: File) {

    private val thread = Dispatchers.IO.limitedParallelism(1)
    private var engine: Engine? = null

    var backendName: String = "-"
        private set
    var audioSupported: Boolean = false
        private set

    /** Tries GPU first (much faster prefill), then CPU; each with, then without, audio input. */
    suspend fun load(modelFile: File) = withContext(thread) {
        if (engine != null) return@withContext
        var lastError: Throwable? = null
        val attempts = listOf(
            Triple("GPU", { Backend.GPU() }, true), Triple("GPU", { Backend.GPU() }, false),
            Triple("CPU", { Backend.CPU() }, true), Triple("CPU", { Backend.CPU() }, false),
        )
        for ((name, backend, withAudio) in attempts) {
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = modelFile.path,
                        backend = backend(),
                        visionBackend = backend(),
                        // The audio encoder runs on the CPU: GPU support for it isn't documented.
                        audioBackend = if (withAudio) Backend.CPU() else null,
                        maxNumImages = 1,
                        cacheDir = cacheDir.path,
                    )
                )
                e.initialize()
                engine = e
                backendName = name
                audioSupported = withAudio
                Log.d(TAG, "Gemma started on $name, audio input ${if (withAudio) "available" else "NOT available"}")
                return@withContext
            } catch (t: Throwable) {
                Log.w(TAG, "Gemma start failed on $name (audio=$withAudio): ${t.message}")
                lastError = t
            }
        }
        throw IllegalStateException("Gemma failed to start on GPU and CPU: ${lastError?.message}", lastError)
    }

    private suspend fun run(system: String, contents: Contents, maxTokens: Int, onFirstOutput: () -> Unit): String =
        withContext(thread) {
            val e = engine ?: error("Model not loaded")
            val config = ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                maxOutputToken = maxTokens,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
            e.createConversation(config).use { conversation ->
                val acc = StreamAccumulator(onFirstOutput)
                conversation.sendMessageAsync(contents)
                    .collect { msg -> acc.add(msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }) }
                acc.text
            }
        }

    /** Tap mode: a short noun phrase for the main object. */
    suspend fun describe(jpeg: ByteArray, ocrHint: String?, onFirstOutput: () -> Unit = {}): String =
        run(DESCRIBE_SYSTEM, Contents.of(Content.ImageBytes(jpeg), Content.Text(Prompts.describe(ocrHint))), 48, onFirstOutput)

    /**
     * Question mode: answers a question about the photo with the JSON contract in
     * [Prompts.questionJson]. The question is either audio ([wav], 16 kHz mono WAV) or text.
     */
    suspend fun ask(
        jpeg: ByteArray,
        wav: ByteArray?,
        questionText: String?,
        ocrHint: String?,
        length: AnswerLength,
        onFirstOutput: () -> Unit = {},
    ): String {
        require(wav != null || questionText != null) { "no question" }
        check(wav == null || audioSupported) { "Gemma's audio input isn't available on this phone" }
        val parts = buildList {
            add(Content.ImageBytes(jpeg))
            if (wav != null) add(Content.AudioBytes(wav))
            add(Content.Text(Prompts.questionJson(questionText, ocrHint, length)))
        }
        return run(Prompts.QUESTION_SYSTEM, Contents.of(*parts.toTypedArray()), 128, onFirstOutput)
    }

    /** STT spike: transcription only. */
    suspend fun transcribe(wav: ByteArray): String {
        check(audioSupported) { "Gemma's audio input isn't available on this phone" }
        return run(
            "You transcribe speech exactly.",
            Contents.of(Content.AudioBytes(wav), Content.Text("Write down exactly what is said in this audio, in English. Output only the words.")),
            64, {},
        ).trim()
    }

    suspend fun close() = withContext(thread) {
        engine?.close()
        engine = null
        audioSupported = false
    }

    companion object {
        private const val TAG = "GemmaVision"
        private const val DESCRIBE_SYSTEM =
            "You are the voice of camera glasses worn by a blind or low-vision person. " +
                "You describe exactly what the camera sees, briefly and accurately. Never guess brand names that are not visible."
    }
}

/** All prompt text in one place (pure, so tests can check what the model is told). */
object Prompts {
    fun describe(ocrHint: String?) = buildString {
        append("What is the main object in the centre of this photo? ")
        append("Answer with ONE short noun phrase of at most 12 words: its colour, what it is, ")
        append("and any brand name or words printed on it. ")
        append("Example answers: \"a blue pen with Pentel written on it\", \"a red Coca-Cola can\", \"a white ceramic mug\". ")
        append("Do not add anything else.")
        appendOcr(ocrHint)
    }

    const val QUESTION_SYSTEM =
        "You are the eyes of a blind or low-vision person wearing camera glasses. The photo is what is in front of them. " +
            "Rules you must always follow: " +
            "1) Never say that a path, stairs, a road or a crossing is safe; if asked, say you can't tell if it's safe. " +
            "2) If the thing they ask about is not clearly visible, say you can't see it. Never guess. " +
            "3) Never state distances, sizes or measurements in numbers; a separate sensor does that. " +
            "4) Describe where things are as left, right, ahead, up or down, and on a table, floor or shelf when visible. " +
            "5) Read printed text exactly when asked what something says."

    fun questionJson(questionText: String?, ocrHint: String?, length: AnswerLength) = buildString {
        if (questionText != null) append("The question is: \"${questionText.take(300)}\"\n")
        else append("The question is spoken in the audio.\n")
        append("Reply with ONLY one JSON object, no other text, with exactly these keys:\n")
        append("{\"heard\": the question exactly as asked, ")
        append("\"target\": the thing they are looking for or asking about (empty if none), ")
        append("\"found\": \"yes\" or \"no\" or \"unsure\", ")
        append("\"position\": where the target is in the photo, one of \"top-left\",\"top\",\"top-right\",\"left\",\"center\",\"right\",\"bottom-left\",\"bottom\",\"bottom-right\", or \"\" if not found, ")
        append("\"answer\": the spoken reply, ")
        append(if (length == AnswerLength.BRIEF) "at most two short sentences" else "at most four sentences")
        append(", with no numbers for distance, ")
        append("\"confidence\": \"high\" or \"medium\" or \"low\"}")
        appendOcr(ocrHint)
    }

    private fun StringBuilder.appendOcr(ocrHint: String?) {
        if (!ocrHint.isNullOrBlank()) {
            append("\nText found in the image by a separate OCR pass (may contain mistakes): \"")
            append(ocrHint.take(200))
            append("\"")
        }
    }
}
