package com.slp.camcast.clips

import com.slp.camcast.camera.JpegFrame
import com.slp.camcast.server.MjpegWriter
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * Turns frames + a motion flag into clips: keeps the last [preMillis] of frames in memory (at most [maxBufferBytes]),
 * and when motion starts writes them and every following frame until [postMillis] after the last motion, or until the
 * clip is [maxClipMillis] long (motion that is still going on then starts the next clip). File work runs on [writer]
 * (one thread, in order), so the camera thread only queues. [onFrame] is called from the camera thread only.
 */
class ClipRecorder(
    private val store: ClipStore,
    private val writer: Executor,
    private val preMillis: Long = 30_000,
    private val postMillis: Long = 30_000,
    private val maxClipMillis: Long = 5 * 60_000,
    private val maxBufferBytes: Long = 64L * 1024 * 1024,
) {
    private class Recording(
        val id: String,
        val startMillis: Long,
        val motionMillis: Long,
        var endMillis: Long,
        var lastFrameMillis: Long = 0,
        var frames: Int = 0,
        var bytes: Long = 0,
        var width: Int = 0,
        var height: Int = 0,
    )

    private val buffer = ArrayDeque<JpegFrame>()
    private var bufferBytes = 0L
    private var recording: Recording? = null
    private var lastWrittenMillis = Long.MIN_VALUE
    private var out: OutputStream? = null // used on the writer thread only

    /** When movement was last seen, or null. Read from other threads. */
    @Volatile var lastMotionMillis: Long? = null
        private set

    @Volatile var isRecording = false
        private set

    fun onFrame(frame: JpegFrame, motion: Boolean) {
        val now = frame.timestampMillis
        if (motion) lastMotionMillis = now
        remember(frame)
        var rec = recording
        if (rec == null && motion) rec = start(frame)
        if (rec == null) return
        if (motion) rec.endMillis = maxOf(rec.endMillis, now + postMillis)
        write(rec, frame)
        if (now >= rec.endMillis || now - rec.startMillis >= maxClipMillis) finish()
    }

    /** Ends the current clip now (the stream is stopping). */
    fun close() {
        if (recording != null) finish()
    }

    private fun remember(frame: JpegFrame) {
        buffer.addLast(frame)
        bufferBytes += frame.jpeg.size
        while (buffer.isNotEmpty() &&
            (buffer.first.timestampMillis < frame.timestampMillis - preMillis || bufferBytes > maxBufferBytes)
        ) {
            bufferBytes -= buffer.removeFirst().jpeg.size
        }
    }

    private fun start(trigger: JpegFrame): Recording {
        val id = store.newId(trigger.timestampMillis)
        // Pre-roll, minus anything the previous clip already holds; the trigger frame itself is written by onFrame.
        val preRoll = buffer.filter { it.timestampMillis > lastWrittenMillis && it !== trigger }
        val start = preRoll.firstOrNull()?.timestampMillis ?: trigger.timestampMillis
        val rec = Recording(id, start, trigger.timestampMillis, trigger.timestampMillis + postMillis)
        recording = rec
        isRecording = true
        val thumb = trigger.jpeg
        writer.execute {
            runCatching {
                store.thumbFile(id).writeBytes(thumb)
                out = BufferedOutputStream(store.partFile(id).outputStream(), 256 * 1024)
            }
        }
        preRoll.forEach { write(rec, it) }
        return rec
    }

    private fun write(rec: Recording, frame: JpegFrame) {
        rec.frames++
        rec.bytes += frame.jpeg.size
        rec.width = frame.width
        rec.height = frame.height
        rec.lastFrameMillis = frame.timestampMillis
        lastWrittenMillis = frame.timestampMillis
        writer.execute {
            // Flushed per frame, so a killed process loses at most the frame being written.
            runCatching { out?.let { MjpegWriter.writeFrame(it, frame.jpeg, frame.timestampMillis) } }
        }
    }

    private fun finish() {
        val rec = recording ?: return
        recording = null
        isRecording = false
        val info = ClipInfo(rec.id, rec.startMillis, rec.motionMillis, rec.lastFrameMillis, rec.frames, 0, rec.width, rec.height)
        writer.execute {
            runCatching {
                out?.close()
                out = null
                store.publish(info.copy(bytes = store.partFile(info.id).length()))
                store.enforceLimits()
            }
        }
    }
}
