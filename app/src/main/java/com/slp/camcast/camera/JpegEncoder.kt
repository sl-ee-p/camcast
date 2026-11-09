package com.slp.camcast.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/** Turns a camera bitmap into an upright JPEG. */
object JpegEncoder {
    /** [rotationDegrees] is what CameraX reports: the clockwise turn that makes the image upright. */
    fun encode(bitmap: Bitmap, rotationDegrees: Int, quality: Int): ByteArray {
        val upright = if (rotationDegrees % 360 == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true,
            )
        }
        val out = ByteArrayOutputStream(upright.width * upright.height / 8)
        upright.compress(
            Bitmap.CompressFormat.JPEG,
            quality.coerceIn(StreamConfig.MIN_QUALITY, StreamConfig.MAX_QUALITY),
            out,
        )
        if (upright !== bitmap) upright.recycle()
        return out.toByteArray()
    }
}
