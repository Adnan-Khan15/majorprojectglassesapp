package com.smartglasses.app.metrics

import android.util.Log
import java.io.File

/**
 * Appends one CSV row per finished interaction to `files/latency/latency.csv`.
 * If the header ever changes (new stage or column), the old file is renamed
 * aside rather than mixing two layouts in one file.
 */
class LatencyLog(dir: File) {
    val file = File(dir, "latency/latency.csv")

    @Synchronized
    fun append(timeline: InteractionTimeline) {
        try {
            file.parentFile?.mkdirs()
            if (file.exists() && file.useLines { it.firstOrNull() } != InteractionTimeline.CSV_HEADER) {
                file.renameTo(File(file.parentFile, "latency-old-${System.currentTimeMillis()}.csv"))
            }
            if (!file.exists()) file.writeText(InteractionTimeline.CSV_HEADER + "\n")
            file.appendText(timeline.toCsvRow() + "\n")
        } catch (e: Exception) {
            Log.w("LatencyLog", "could not write latency row", e)
        }
    }

    @Synchronized
    fun rowCount(): Int = if (file.exists()) (file.useLines { it.count() } - 1).coerceAtLeast(0) else 0

    @Synchronized
    fun clear() {
        file.delete()
    }
}
