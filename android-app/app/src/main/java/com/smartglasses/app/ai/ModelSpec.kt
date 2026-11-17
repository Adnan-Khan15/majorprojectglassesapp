package com.smartglasses.app.ai

import com.google.gson.JsonParser

/** One entry of `assets/model-manifest.json`: what file the app expects and how to check it. */
data class ModelSpec(
    val id: String,
    val name: String,
    val version: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val url: String?,
    val license: String?,
) {
    companion object {
        /** Parses the manifest by hand (no reflection, so R8 can't break it). */
        fun parseManifest(json: String): List<ModelSpec> {
            val root = JsonParser.parseString(json).asJsonObject
            return root.getAsJsonArray("models").map { el ->
                val o = el.asJsonObject
                fun str(k: String) = o.get(k)?.takeUnless { it.isJsonNull }?.asString
                ModelSpec(
                    id = str("id") ?: error("model id missing"),
                    name = str("name") ?: error("model name missing"),
                    version = str("version") ?: error("model version missing"),
                    fileName = str("fileName") ?: error("model fileName missing"),
                    sizeBytes = o.get("size")?.asLong ?: error("model size missing"),
                    sha256 = (str("sha256") ?: error("model sha256 missing")).lowercase(),
                    url = str("url"),
                    license = str("license"),
                ).also {
                    require(it.sha256.length == 64 && it.sha256.all { c -> c in "0123456789abcdef" }) { "bad sha256 for ${it.id}" }
                }
            }
        }
    }
}
