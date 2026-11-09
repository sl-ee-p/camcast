package com.slp.camcast.camera

/**
 * Caps the frame rate: [accept] says whether a frame stamped [timestampNanos] should be encoded.
 * Rejected frames cost nothing, so the check runs before any pixel work. Not thread safe; the
 * analyzer calls it from a single executor.
 */
class FrameThrottle(fpsCap: Int) {
    private val intervalNanos = 1_000_000_000L / fpsCap.coerceAtLeast(1)
    private var lastAccepted = Long.MIN_VALUE

    fun accept(timestampNanos: Long): Boolean {
        // The first frame, or a clock that went backwards, always passes.
        if (lastAccepted == Long.MIN_VALUE || timestampNanos < lastAccepted ||
            timestampNanos - lastAccepted >= intervalNanos
        ) {
            lastAccepted = timestampNanos
            return true
        }
        return false
    }
}
