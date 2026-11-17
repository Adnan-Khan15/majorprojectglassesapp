package com.smartglasses.app.ai

/**
 * Joins streamed model output. LiteRT-LM's docs don't say whether each streamed
 * message is a delta or the whole reply so far, so both are handled: a chunk that
 * starts with everything received so far is treated as cumulative.
 */
class StreamAccumulator(private val onFirstOutput: () -> Unit = {}) {
    private val sb = StringBuilder()
    private var gotFirst = false

    val text: String get() = sb.toString()

    fun add(chunk: String) {
        if (chunk.isEmpty()) return
        if (!gotFirst) { gotFirst = true; onFirstOutput() }
        if (sb.isNotEmpty() && chunk.length >= sb.length && chunk.startsWith(sb)) {
            sb.setLength(0)
        }
        sb.append(chunk)
    }
}
