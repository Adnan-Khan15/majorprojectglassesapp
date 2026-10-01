package com.smartglasses.app.ai

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** ML Kit, bundled models (no download, works offline from first launch). */
class MlKitVision {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build()
    )

    /** Reads printed text, largest blocks first, joined into one line. */
    suspend fun readText(bitmap: Bitmap): String {
        val result = textRecognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks
            .sortedByDescending { b -> b.boundingBox?.let { it.width() * it.height() } ?: 0 }
            .joinToString(" ") { it.text.replace('\n', ' ') }
            .trim()
    }

    /** Fallback describer: best ImageNet-style label, e.g. "Cup". */
    suspend fun topLabel(bitmap: Bitmap): String? =
        labeler.process(InputImage.fromBitmap(bitmap, 0)).await()
            .maxByOrNull { it.confidence }?.text
}
