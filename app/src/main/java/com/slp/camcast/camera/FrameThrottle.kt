package com.slp.camcast.camera

/**
 * Caps the frame rate: [accept] says whether a frame stamped [timestampNanos] should be encoded.
 * Rejected frames cost nothing, so the check runs before any pixel work. Not thread safe; the
 * analyzer calls it from a single executor.
 *
 * Frames are taken against a schedule (one every interval) rather than a minimum gap, so the average rate
 * reaches the cap whatever the camera's own rate. With a minimum gap, a camera at 16.7 fps (dim light) under a
 * 10 fps cap would keep every second frame and deliver 8.3 fps.
 */
class FrameThrottle(fpsCap: Int) {
    private val intervalNanos = 1_000_000_000L / fpsCap.coerceAtLeast(1)

    // Camera timestamps jitter: a 30 fps stream stamps frames 99.99 ms apart, not 100. Without some slack a
    // 10 fps cap would skip that third frame and deliver 7.5 fps.
    private val slackNanos = intervalNanos / 10
    private var lastAccepted = Long.MIN_VALUE
    private var nextDue = Long.MIN_VALUE

    fun accept(timestampNanos: Long): Boolean {
        // The first frame, or a clock that went backwards, always passes and restarts the schedule.
        if (lastAccepted == Long.MIN_VALUE || timestampNanos < lastAccepted) {
            lastAccepted = timestampNanos
            nextDue = timestampNanos + intervalNanos
            return true
        }
        if (timestampNanos < nextDue - slackNanos) return false
        lastAccepted = timestampNanos
        // A camera slower than the cap leaves the schedule behind; restart it so a later speed-up cannot burst.
        nextDue = if (timestampNanos - nextDue >= intervalNanos) timestampNanos + intervalNanos else nextDue + intervalNanos
        return true
    }
}
