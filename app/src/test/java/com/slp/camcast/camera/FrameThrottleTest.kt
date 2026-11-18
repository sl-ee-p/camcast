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
        assertFalse(t.accept(80 * ms))
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
    fun dimLightCameraStillReachesTheCap() {
        // 16.7 fps (60 ms apart), as the Redmi delivers indoors: a minimum-gap throttle gave 8.3 fps here.
        val t = FrameThrottle(10)
        val accepted = (0 until 1000).count { t.accept(it * 60 * ms) } // 60 s of camera frames
        assertTrue("accepted $accepted", accepted in 595..605)
    }

    @Test
    fun acceptedFramesAreNeverCloserThanOneCameraFrame() {
        val t = FrameThrottle(10)
        val stamps = (0 until 200).map { it * 60 * ms }.filter { t.accept(it) }
        assertTrue(stamps.zipWithNext().all { (a, b) -> b - a >= 60 * ms })
    }

    @Test
    fun speedingUpAfterASlowSpellDoesNotBurst() {
        val t = FrameThrottle(10)
        (0 until 10).forEach { assertTrue(t.accept(it * 500 * ms)) } // 2 fps camera, slower than the cap
        val start = 5_000 * ms
        val accepted = (0 until 30).count { t.accept(start + it * 33_333_333L) } // then 30 fps for 1 s
        assertEquals(10, accepted)
    }

    @Test
    fun jitterJustUnderTheIntervalStillPasses() {
        val t = FrameThrottle(10)
        assertTrue(t.accept(0))
        assertTrue(t.accept(99_990_000L))
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
        assertFalse(t.accept(800 * ms))
        assertTrue(t.accept(1_000 * ms))
    }
}
