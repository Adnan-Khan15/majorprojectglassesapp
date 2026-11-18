package com.smartglasses.app.audio

/**
 * IMA ADPCM (4 bits/sample). Twin of esp32-firmware/lib/glasses_core/src/ima_adpcm.c;
 * both are tested against the golden vectors from scripts/gen_adpcm_vectors.py.
 * Nibbles are packed low nibble first.
 */
class ImaAdpcm(var predictor: Int = 0, var stepIndex: Int = 0) {

    private fun step(nibble: Int): Int {
        val s = STEP[stepIndex]
        var diff = s shr 3
        if (nibble and 4 != 0) diff += s
        if (nibble and 2 != 0) diff += s shr 1
        if (nibble and 1 != 0) diff += s shr 2
        predictor = (if (nibble and 8 != 0) predictor - diff else predictor + diff).coerceIn(-32768, 32767)
        stepIndex = (stepIndex + INDEX[nibble and 15]).coerceIn(0, 88)
        return predictor
    }

    private fun encodeOne(sample: Int): Int {
        var s = STEP[stepIndex]
        var d = sample - predictor
        var nibble = 0
        if (d < 0) { nibble = 8; d = -d }
        if (d >= s) { nibble = nibble or 4; d -= s }
        s = s shr 1
        if (d >= s) { nibble = nibble or 2; d -= s }
        s = s shr 1
        if (d >= s) nibble = nibble or 1
        step(nibble)
        return nibble
    }

    /** Encodes pcm[from, from+n) (n even) into n/2 bytes. */
    fun encode(pcm: ShortArray, from: Int = 0, n: Int = pcm.size - from): ByteArray {
        val out = ByteArray(n / 2)
        var i = 0
        while (i + 1 < n) {
            val lo = encodeOne(pcm[from + i].toInt())
            val hi = encodeOne(pcm[from + i + 1].toInt())
            out[i / 2] = (lo or (hi shl 4)).toByte()
            i += 2
        }
        return out
    }

    /** Decodes [data] into 2 samples per byte. */
    fun decode(data: ByteArray): ShortArray {
        val out = ShortArray(data.size * 2)
        for (i in data.indices) {
            val b = data[i].toInt() and 0xFF
            out[2 * i] = step(b and 15).toShort()
            out[2 * i + 1] = step(b shr 4).toShort()
        }
        return out
    }

    companion object {
        private val STEP = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
            253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
            1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
            3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442,
            11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
        )
        private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    }
}
