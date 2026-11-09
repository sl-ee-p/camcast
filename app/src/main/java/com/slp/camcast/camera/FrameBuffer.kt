package com.slp.camcast.camera

/** One encoded camera frame. [sequence] grows by one per published frame, starting at 1. */
class JpegFrame(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val timestampMillis: Long,
    val sequence: Long,
)

/**
 * Holds the newest frame and lets any number of readers (HTTP clients) wait for the next one.
 * Slow readers simply skip frames: only the latest is kept.
 */
class FrameBuffer {
    private val lock = Object()
    private var latest: JpegFrame? = null
    private var sequence = 0L
    private var closed = false

    /** Publishes a frame and wakes the waiting readers. Returns the frame as stored. */
    fun publish(jpeg: ByteArray, width: Int, height: Int, timestampMillis: Long): JpegFrame {
        synchronized(lock) {
            val frame = JpegFrame(jpeg, width, height, timestampMillis, ++sequence)
            latest = frame
            lock.notifyAll()
            return frame
        }
    }

    fun latest(): JpegFrame? = synchronized(lock) { latest }

    /**
     * Blocks until a frame newer than [afterSequence] exists, [timeoutMillis] pass or the buffer is
     * closed. Returns null on timeout or close.
     */
    fun awaitNewer(afterSequence: Long, timeoutMillis: Long): JpegFrame? {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        synchronized(lock) {
            while (true) {
                latest?.let { if (it.sequence > afterSequence) return it }
                if (closed) return null
                val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMillis <= 0) return null
                lock.wait(remainingMillis)
            }
        }
    }

    /** Releases every waiting reader; later waits return null at once. */
    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }
}
