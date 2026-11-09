package com.slp.camcast.camera

/**
 * Caps the frame rate: [accept] says whether a frame stamped [timestampNanos] should be encoded.
 * Rejected frames cost nothing, so the check runs before any pixel work. Not thread safe; the
 * analyzer calls it from a single executor.
 */
class FrameThrottle(fpsCap: Int) {
    private val intervalNanos = 1_000_000_000L / fpsCap.coerceAtLeast(1)

    // Camera timestamps jitter: a 30 fps stream stamps frames 99.99 ms apart, not 100. Without some slack a
    // 10 fps cap would skip that third frame and deliver 7.5 fps.
    private val minGapNanos = intervalNanos * 9 / 10
    private var lastAccepted = Long.MIN_VALUE

    fun accept(timestampNanos: Long): Boolean {
        // The first frame, or a clock that went backwards, always passes.
        if (lastAccepted == Long.MIN_VALUE || timestampNanos < lastAccepted ||
            timestampNanos - lastAccepted >= minGapNanos
        ) {
            lastAccepted = timestampNanos
            return true
        }
        return false
    }
}
