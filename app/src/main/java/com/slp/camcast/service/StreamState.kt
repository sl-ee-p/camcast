package com.slp.camcast.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface StreamState {
    /** The service is on (starting, streaming or recovering): the settings are locked and the button says Stop. */
    val busy: Boolean get() = this !is Stopped && this !is Failed

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

    /** The camera failed or went quiet; the server is still up and the service is trying again. */
    data class Recovering(val host: String, val port: Int, val attempt: Int) : StreamState {
        val videoUrl get() = "http://$host:$port/video"
    }

    /** The last run ended with a problem; the next start clears it. */
    data class Failed(val message: String) : StreamState
}

/** The app-side handle on the service: what it is doing, and start/stop commands. */
object StreamController {
    const val ACTION_START = "com.slp.camcast.START"
    const val ACTION_STOP = "com.slp.camcast.STOP"
    const val EXTRA_FROM_BOOT = "fromBoot"

    private val mutableState = MutableStateFlow<StreamState>(StreamState.Stopped)
    val state: StateFlow<StreamState> = mutableState.asStateFlow()

    internal fun publish(state: StreamState) {
        mutableState.value = state
    }

    /** Call from the foreground (an activity): that is what lets the service start with the camera. */
    fun start(context: Context, fromBoot: Boolean = false) {
        val intent = Intent(context, StreamService::class.java).setAction(ACTION_START).putExtra(EXTRA_FROM_BOOT, fromBoot)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, StreamService::class.java).setAction(ACTION_STOP))
    }
}
