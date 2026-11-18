package com.slp.camcast.motion

import android.util.Log
import com.slp.camcast.camera.JpegFrame
import com.slp.camcast.clips.ClipRecorder
import com.slp.camcast.clips.ClipStore
import com.slp.camcast.server.ClipSource
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Motion detection + clip recording for one streaming run: the camera thread hands it each frame with its luma grid;
 * the HTTP server reads the clips through [ClipSource]. [close] finishes the clip in progress and the writes.
 */
class MotionPipeline(dir: File, sensitivity: MotionSensitivity) : ClipSource {
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "camcast-clips") }
    private val store = ClipStore(dir)
    private val detector = MotionDetector(sensitivity)
    private val recorder = ClipRecorder(store, writer)

    val lastMotionMillis: Long? get() = recorder.lastMotionMillis
    val isRecording: Boolean get() = recorder.isRecording

    init {
        writer.execute {
            runCatching {
                store.recoverPartials()
                store.enforceLimits()
            }.onFailure { Log.w(TAG, "clip folder cleanup failed", it) }
        }
    }

    /** Camera thread. A failure here must never stop the stream, so it is logged and dropped. */
    fun onFrame(frame: JpegFrame, grid: IntArray) {
        try {
            recorder.onFrame(frame, detector.update(grid, frame.timestampMillis).motion)
        } catch (e: RuntimeException) {
            Log.w(TAG, "motion step failed", e)
        }
    }

    fun close() {
        recorder.close()
        writer.shutdown()
        writer.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    override fun eventsJson(nowMillis: Long): String {
        val last = lastMotionMillis
        val moving = last != null && nowMillis - last < MOVING_MILLIS
        val clips = store.list().take(MAX_LISTED).joinToString(",") { it.toJson() }
        val since = recorder.recordingSinceMillis
        return "{\"detection\":true,\"now\":$nowMillis,\"motion\":$moving,\"recording\":$isRecording," +
            "\"recordingSince\":${since ?: "null"},\"lastMotion\":${last ?: "null"},\"clips\":[$clips]}"
    }

    override fun clipFile(id: String): File? = store.find(id)?.let { store.clipFile(it.id) }

    override fun thumbFile(id: String): File? = store.find(id)?.let { store.thumbFile(it.id) }?.takeIf { it.exists() }

    private companion object {
        const val TAG = "CamCastMotion"
        const val MOVING_MILLIS = 3_000L
        const val MAX_LISTED = 200
        const val CLOSE_WAIT_SECONDS = 5L
    }
}
