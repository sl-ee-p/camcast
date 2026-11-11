package com.slp.camcast.service

import com.slp.camcast.camera.CameraFacing
import com.slp.camcast.camera.CameraMissingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartPolicyTest {
    @Test
    fun delaysGrowThenStayAtThirtySeconds() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), (1..7).map(RestartPolicy::delayMillis))
    }

    @Test
    fun attemptZeroIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { RestartPolicy.delayMillis(0) }
    }

    @Test
    fun givesUpAfterTheMaximum() {
        assertFalse(RestartPolicy.shouldGiveUp(RestartPolicy.MAX_ATTEMPTS))
        assertTrue(RestartPolicy.shouldGiveUp(RestartPolicy.MAX_ATTEMPTS + 1))
    }

    @Test
    fun missingCameraAndRefusedPermissionAreNotRetried() {
        assertFalse(RestartPolicy.isRetryable(CameraMissingException(CameraFacing.BACK)))
        assertFalse(RestartPolicy.isRetryable(SecurityException("camera denied")))
        assertTrue(RestartPolicy.isRetryable(IllegalStateException("camera disconnected")))
    }

    @Test
    fun stallIsDetectedAtTheThreshold() {
        assertFalse(RestartPolicy.isStalled(lastFrameMillis = 1_000, nowMillis = 1_000 + RestartPolicy.STALL_MILLIS - 1))
        assertTrue(RestartPolicy.isStalled(lastFrameMillis = 1_000, nowMillis = 1_000 + RestartPolicy.STALL_MILLIS))
    }
}
