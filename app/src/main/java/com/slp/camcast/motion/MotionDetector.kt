package com.slp.camcast.motion

import kotlin.math.abs

/** How much of the picture has to change: low ignores small things (a cat far away), high catches them. */
enum class MotionSensitivity(val changedShare: Double) {
    LOW(0.04),
    MEDIUM(0.015),
    HIGH(0.005),
}

/**
 * Frame-differencing motion detection on a [LumaGrid], no AI. Each frame is compared with a slowly adapting background:
 * a cell counts as changed when it differs by more than [cellThreshold] from the background after a per-frame gain and
 * offset are taken out (auto exposure brightens or darkens everything at once; a least-squares fit finds how much).
 * Motion is a changed share above the sensitivity on [framesToTrigger] frames in a row, so one noisy frame does not
 * count. When most of the picture changes, or its average brightness jumps (a light switched on), the background is
 * learned again instead. Not thread safe: one camera thread calls it.
 */
class MotionDetector(
    private val sensitivity: MotionSensitivity,
    private val cellThreshold: Int = 18,
    private val framesToTrigger: Int = 2,
    private val warmupMillis: Long = 2_000,
    private val learnRate: Float = 0.05f,
) {
    class Result(val changedShare: Double, val motion: Boolean)

    private var background: FloatArray? = null
    private var startedMillis = 0L
    private var streak = 0

    fun update(grid: IntArray, timeMillis: Long): Result {
        val bg = background
        if (bg == null || bg.size != grid.size) {
            relearn(grid)
            startedMillis = timeMillis
            return Result(0.0, false)
        }
        // Fit grid ~ gain * background + offset.
        var meanGrid = 0.0
        var meanBg = 0.0
        for (i in grid.indices) {
            meanGrid += grid[i]
            meanBg += bg[i]
        }
        meanGrid /= grid.size
        meanBg /= grid.size
        var cov = 0.0
        var variance = 0.0
        for (i in grid.indices) {
            val b = bg[i] - meanBg
            cov += b * (grid[i] - meanGrid)
            variance += b * b
        }
        val gain = if (variance < 1e-3) 1.0 else (cov / variance).coerceIn(MIN_GAIN, MAX_GAIN)
        val offset = meanGrid - gain * meanBg
        var changed = 0
        for (i in grid.indices) {
            if (abs(grid[i] - (gain * bg[i] + offset)) > cellThreshold) changed++
            bg[i] += learnRate * (grid[i] - bg[i])
        }
        val share = changed.toDouble() / grid.size
        val brightnessJump = abs(meanGrid - meanBg) > meanBg * LIGHTING_JUMP + cellThreshold
        if (share > LIGHTING_SHARE || brightnessJump) {
            relearn(grid)
            return Result(share, false)
        }
        if (timeMillis - startedMillis < warmupMillis) return Result(share, false)
        streak = if (share >= sensitivity.changedShare) streak + 1 else 0
        return Result(share, streak >= framesToTrigger)
    }

    private fun relearn(grid: IntArray) {
        background = FloatArray(grid.size) { grid[it].toFloat() }
        streak = 0
    }

    private companion object {
        const val LIGHTING_SHARE = 0.6
        const val LIGHTING_JUMP = 0.3 // a 30 % brighter or darker frame from one moment to the next is the lighting
        const val MIN_GAIN = 0.5
        const val MAX_GAIN = 2.0
    }
}
