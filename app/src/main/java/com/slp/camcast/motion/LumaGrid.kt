package com.slp.camcast.motion

import java.nio.ByteBuffer

/**
 * Shrinks the camera's luma (Y) plane to a small grid of cell averages, the only input motion detection needs.
 * Each cell averages [SAMPLES] x [SAMPLES] evenly spread pixels, so the cost does not grow with the resolution.
 */
object LumaGrid {
    const val COLS = 64
    const val ROWS = 48
    private const val SAMPLES = 4

    fun fromYPlane(
        y: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        cols: Int = COLS,
        rows: Int = ROWS,
    ): IntArray {
        val grid = IntArray(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var sum = 0
                for (sy in 0 until SAMPLES) {
                    val py = ((r * SAMPLES + sy) * 2 + 1) * height / (rows * SAMPLES * 2)
                    for (sx in 0 until SAMPLES) {
                        val px = ((c * SAMPLES + sx) * 2 + 1) * width / (cols * SAMPLES * 2)
                        sum += y.get(py * rowStride + px * pixelStride).toInt() and 0xFF
                    }
                }
                grid[r * cols + c] = sum / (SAMPLES * SAMPLES)
            }
        }
        return grid
    }
}
