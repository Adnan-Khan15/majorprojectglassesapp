package com.smartglasses.app.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/**
 * Turns the glasses' photo upright. The OV2640 can only mirror/flip, not rotate by
 * 90°, so this is done on the phone, once, as soon as the photo arrives: OCR, Gemma
 * (and its left/right grid positions), the result card and History all see the
 * upright photo.
 */
object PhotoRotation {
    private const val JPEG_QUALITY = 92

    /** [degrees] clockwise (0, 90, 180, 270). Returns the input unchanged if it is 0 or the JPEG can't be decoded. */
    fun rotateJpeg(jpeg: ByteArray, degrees: Int): ByteArray {
        if (Math.floorMod(degrees, 360) == 0) return jpeg
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        return ByteArrayOutputStream().also { rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
            .also { if (rotated !== src) rotated.recycle(); src.recycle() }
    }
}
