package com.slp.camcast.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConfigTest {
    @Test
    fun sanitizedClampsFpsAndQuality() {
        val low = StreamConfig(fpsCap = 0, jpegQuality = 0).sanitized()
        assertEquals(StreamConfig.MIN_FPS, low.fpsCap)
        assertEquals(StreamConfig.MIN_QUALITY, low.jpegQuality)
        val high = StreamConfig(fpsCap = 500, jpegQuality = 1000).sanitized()
        assertEquals(StreamConfig.MAX_FPS, high.fpsCap)
        assertEquals(StreamConfig.MAX_QUALITY, high.jpegQuality)
    }

    @Test
    fun sanitizedLeavesValidValuesAlone() {
        val c = StreamConfig(CameraFacing.FRONT, StreamResolution.P1080, 15, 85)
        assertEquals(c, c.sanitized())
    }

    @Test
    fun defaultsAreWithinLimits() {
        val c = StreamConfig()
        assertTrue(c.fpsCap in StreamConfig.MIN_FPS..StreamConfig.MAX_FPS)
        assertTrue(c.jpegQuality in StreamConfig.MIN_QUALITY..StreamConfig.MAX_QUALITY)
    }

    @Test
    fun presetsGrowInPixelCount() {
        val pixels = StreamResolution.entries.map { it.width * it.height }
        assertEquals(pixels.sorted(), pixels)
    }
}
