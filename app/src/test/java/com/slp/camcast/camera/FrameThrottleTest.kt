package com.slp.camcast.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameThrottleTest {
    private val ms = 1_000_000L

    @Test
    fun firstFrameAlwaysPasses() {
        assertTrue(FrameThrottle(10).accept(0))
    }

    @Test
    fun dropsFramesInsideTheInterval() {
        val t = FrameThrottle(10) // 100 ms between frames
        assertTrue(t.accept(0))
        assertFalse(t.accept(33 * ms))
        assertFalse(t.accept(99 * ms))
        assertTrue(t.accept(100 * ms))
        assertFalse(t.accept(150 * ms))
    }

    @Test
    fun thirtyFpsCameraAtTenFpsCapKeepsAboutOneInThree() {
        val t = FrameThrottle(10)
        val accepted = (0 until 300).count { t.accept(it * 33_333_333L) }
        assertEquals(100, accepted)
    }

    @Test
    fun cameraSlowerThanTheCapIsNeverThrottled() {
        val t = FrameThrottle(30)
        assertEquals(20, (0 until 20).count { t.accept(it * 200 * ms) })
    }

    @Test
    fun clockGoingBackwardsDoesNotFreezeTheStream() {
        val t = FrameThrottle(10)
        assertTrue(t.accept(10_000 * ms))
        assertTrue(t.accept(5 * ms))
        assertFalse(t.accept(6 * ms))
    }

    @Test
    fun zeroCapIsTreatedAsOneFps() {
        val t = FrameThrottle(0)
        assertTrue(t.accept(0))
        assertFalse(t.accept(900 * ms))
        assertTrue(t.accept(1_000 * ms))
    }
}
