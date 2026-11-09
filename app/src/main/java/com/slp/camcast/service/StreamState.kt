package com.slp.camcast.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface StreamState {
    data object Stopped : StreamState

    data object Starting : StreamState

    data class Running(
        val host: String,
        val port: Int,
        val viewers: Int,
        val frames: Long,
        val fps: Double,
        val width: Int,
        val height: Int,
    ) : StreamState {
        val videoUrl get() = "http://$host:$port/video"
    }

    /** The last run ended with a problem; the next start clears it. */
    data class Failed(val message: String) : StreamState
}

/** The app-side handle on the service: what it is doing, and start/stop commands. */
object StreamController {
    const val ACTION_START = "com.slp.camcast.START"
    const val ACTION_STOP = "com.slp.camcast.STOP"

    private val mutableState = MutableStateFlow<StreamState>(StreamState.Stopped)
    val state: StateFlow<StreamState> = mutableState.asStateFlow()

    internal fun publish(state: StreamState) {
        mutableState.value = state
    }

    /** Call from the foreground (an activity): that is what lets the service start with the camera. */
    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java).setAction(ACTION_START))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, StreamService::class.java).setAction(ACTION_STOP))
    }
}
