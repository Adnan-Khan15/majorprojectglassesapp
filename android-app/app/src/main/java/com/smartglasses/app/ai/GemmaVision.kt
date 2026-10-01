package com.smartglasses.app.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gemma 4 E2B (vision) on LiteRT-LM. All calls are confined to one thread:
 * the native engine is not meant to be driven concurrently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GemmaVision(private val cacheDir: File) {

    private val thread = Dispatchers.IO.limitedParallelism(1)
    private var engine: Engine? = null

    var backendName: String = "-"
        private set

    /** Tries GPU first (much faster prefill), then CPU. */
    suspend fun load(modelFile: File) = withContext(thread) {
        if (engine != null) return@withContext
        var lastError: Throwable? = null
        for ((name, backend) in listOf("GPU" to { Backend.GPU() }, "CPU" to { Backend.CPU() })) {
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = modelFile.path,
                        backend = backend(),
                        visionBackend = backend(),
                        maxNumImages = 1,
                        cacheDir = cacheDir.path,
                    )
                )
                e.initialize()
                engine = e
                backendName = name
                return@withContext
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException("Gemma failed to start on GPU and CPU: ${lastError?.message}", lastError)
    }

    /** Returns the model's short description of the main object. */
    suspend fun describe(jpeg: ByteArray, ocrHint: String?): String = withContext(thread) {
        val e = engine ?: error("Model not loaded")
        val config = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_PROMPT),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
            maxOutputToken = 48,
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
        e.createConversation(config).use { conversation ->
            val reply = conversation.sendMessage(
                Contents.of(Content.ImageBytes(jpeg), Content.Text(userPrompt(ocrHint)))
            )
            reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
    }

    suspend fun close() = withContext(thread) {
        engine?.close()
        engine = null
    }

    private fun userPrompt(ocrHint: String?) = buildString {
        append("What is the main object in the centre of this photo? ")
        append("Answer with ONE short noun phrase of at most 12 words: its colour, what it is, ")
        append("and any brand name or words printed on it. ")
        append("Example answers: \"a blue pen with Pentel written on it\", \"a red Coca-Cola can\", \"a white ceramic mug\". ")
        append("Do not add anything else.")
        if (!ocrHint.isNullOrBlank()) {
            append("\nText found in the image by a separate OCR pass (may contain mistakes): \"")
            append(ocrHint.take(200))
            append("\"")
        }
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You are the voice of camera glasses worn by a blind or low-vision person. " +
                "You describe exactly what the camera sees, briefly and accurately. Never guess brand names that are not visible."
    }
}
