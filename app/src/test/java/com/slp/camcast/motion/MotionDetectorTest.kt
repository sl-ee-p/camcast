package com.slp.camcast.motion

import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {
    private val cols = LumaGrid.COLS
    private val rows = LumaGrid.ROWS
    private val random = Random(42)

    /** A textured scene with sensor-like noise of +-[noise]. */
    private val scene = IntArray(cols * rows) { 60 + (it * 37) % 120 }
    private fun frame(noise: Int = 4, edit: (IntArray) -> Unit = {}) =
        IntArray(scene.size) { (scene[it] + random.nextInt(-noise, noise + 1)).coerceIn(0, 255) }.also(edit)

    /** Something bright entering a w x h block of cells at column [x], row [y]. */
    private fun block(x: Int, y: Int, w: Int, h: Int, delta: Int = 70): (IntArray) -> Unit = { g ->
        for (r in y until y + h) for (c in x until x + w) g[r * cols + c] = (g[r * cols + c] + delta).coerceIn(0, 255)
    }

    /** Runs [frames] through a detector that is already past its warm-up, 100 ms apart. */
    private fun run(detector: MotionDetector, frames: List<IntArray>, start: Long = 10_000): List<Boolean> =
        frames.mapIndexed { i, f -> detector.update(f, start + i * 100L).motion }

    private fun warmed(sensitivity: MotionSensitivity = MotionSensitivity.MEDIUM) = MotionDetector(sensitivity).also { d ->
        repeat(30) { d.update(frame(), it * 100L) } // 3 s of a still scene
    }

    @Test
    fun aStillNoisySceneIsNotMotion() {
        val d = warmed(MotionSensitivity.HIGH)
        assertTrue(run(d, List(100) { frame(noise = 8) }).none { it })
    }

    @Test
    fun somethingMovingIsMotionFromTheSecondFrame() {
        val d = warmed()
        // A 10x8-cell object (2.6 % of the picture) walking across.
        val result = run(d, List(10) { i -> frame(edit = block(5 + i * 3, 20, 10, 8)) })
        assertFalse(result[0])
        assertTrue(result.drop(1).all { it })
    }

    @Test
    fun oneFlickeringFrameIsNotMotion() {
        val d = warmed()
        val result = run(d, listOf(frame(edit = block(10, 10, 12, 12)), frame(), frame(), frame()))
        assertTrue(result.none { it })
    }

    @Test
    fun autoExposureBrighteningEverythingIsNotMotion() {
        val d = warmed(MotionSensitivity.HIGH)
        val result = run(d, (1..20).map { step -> frame { g -> for (i in g.indices) g[i] = (g[i] + step * 2).coerceAtMost(255) } })
        assertTrue(result.none { it })
    }

    @Test
    fun aLightSwitchedOnIsLearnedNotReported() {
        val d = warmed(MotionSensitivity.HIGH)
        // Roughly multiplicative and clipped at white, with uneven light: a plain brightness shift does not explain it.
        val lit = IntArray(scene.size) { (scene[it] * 2.2 + (it % 7) * 6).toInt().coerceAtMost(255) }
        val result = run(d, List(20) { IntArray(lit.size) { i -> (lit[i] + random.nextInt(-4, 5)).coerceIn(0, 255) } })
        assertTrue(result.none { it })
    }

    @Test
    fun anObjectThatStopsIsAbsorbedIntoTheBackground() {
        val d = warmed()
        val parked = block(20, 20, 10, 8)
        val result = run(d, List(80) { frame(edit = parked) }) // 8 s standing still
        assertTrue(result[2])
        assertFalse(result.last())
    }

    @Test
    fun sensitivityDecidesHowSmallAThingCounts() {
        val small = List(5) { frame(edit = block(30, 30, 4, 4)) } // 16 cells, 0.5 % of the picture
        assertTrue(run(warmed(MotionSensitivity.HIGH), small).last())
        assertFalse(run(warmed(MotionSensitivity.MEDIUM), small).last())
        val medium = List(5) { frame(edit = block(30, 30, 8, 8)) } // 2.1 %
        assertTrue(run(warmed(MotionSensitivity.MEDIUM), medium).last())
        assertFalse(run(warmed(MotionSensitivity.LOW), medium).last())
    }

    @Test
    fun nothingIsReportedDuringTheWarmUp() {
        val d = MotionDetector(MotionSensitivity.HIGH)
        d.update(frame(), 0)
        val result = (1..15).map { d.update(frame(edit = block(10, 10, 20, 20)), it * 100L).motion } // first 1.5 s
        assertTrue(result.none { it })
    }

    @Test
    fun gridAveragesCellsAndHonoursStrides() {
        // 8x4 image with a row stride of 10 (padding bytes are 255 and must be ignored), 2x2 grid.
        val width = 8
        val height = 4
        val stride = 10
        val bytes = ByteArray(stride * height) { 255.toByte() }
        for (y in 0 until height) for (x in 0 until width) bytes[y * stride + x] = (if (x < 4) 10 else 200).toByte()
        val grid = LumaGrid.fromYPlane(ByteBuffer.wrap(bytes), stride, 1, width, height, cols = 2, rows = 2)
        assertArrayEquals(intArrayOf(10, 200, 10, 200), grid)
        assertEquals(LumaGrid.COLS * LumaGrid.ROWS, LumaGrid.fromYPlane(ByteBuffer.allocate(640 * 480), 640, 1, 640, 480).size)
    }
}
