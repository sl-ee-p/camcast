package com.slp.camcast.service

import com.slp.camcast.camera.CameraMissingException

/** What the service does when the camera fails or goes quiet: how long to wait, when to give up. */
object RestartPolicy {
    const val MAX_ATTEMPTS = 10
    const val STALL_MILLIS = 10_000L
    private const val BASE_MILLIS = 2_000L
    private const val MAX_MILLIS = 30_000L

    /** Wait before restart number [attempt] (1 is the first): 2, 4, 8, 16, then 30 seconds. */
    fun delayMillis(attempt: Int): Long {
        require(attempt >= 1) { "attempt starts at 1" }
        return minOf(MAX_MILLIS, BASE_MILLIS shl minOf(attempt - 1, 4))
    }

    /** A missing camera or a refused permission will not fix itself; anything else is worth another try. */
    fun isRetryable(error: Throwable) = error !is CameraMissingException && error !is SecurityException

    fun shouldGiveUp(attempt: Int) = attempt > MAX_ATTEMPTS

    /**
     * True when no frame arrived for [STALL_MILLIS]. [lastFrameMillis] is when the last frame came in, or when the
     * camera was started if none has yet, so a camera that never delivers a frame is caught too.
     */
    fun isStalled(lastFrameMillis: Long, nowMillis: Long) = nowMillis - lastFrameMillis >= STALL_MILLIS
}
