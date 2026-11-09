package com.slp.camcast.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.slp.camcast.R
import com.slp.camcast.camera.CameraFacing
import com.slp.camcast.camera.CameraMissingException
import com.slp.camcast.camera.CameraSession
import com.slp.camcast.camera.FrameBuffer
import com.slp.camcast.net.LocalAddress
import com.slp.camcast.server.CamServer
import com.slp.camcast.server.Credentials
import com.slp.camcast.settings.SettingsStore
import com.slp.camcast.settings.SettingsValidator
import kotlinx.coroutines.launch

/**
 * Owns the camera and the HTTP server while streaming. The activity only configures it and sends
 * start/stop; everything here keeps running with the screen off or the app closed.
 */
class StreamService : LifecycleService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notification: StreamNotification
    private var frames: FrameBuffer? = null
    private var session: CameraSession? = null
    private var server: CamServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var active = false
    private var host = "?"
    private var port = 0
    private var lastSequence = 0L
    private var lastTickMillis = 0L
    private var lastViewers = -1

    override fun onCreate() {
        super.onCreate()
        notification = StreamNotification(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == StreamController.ACTION_STOP) finish(null) else begin()
        return START_NOT_STICKY
    }

    // The type constant is inlined at compile time, and ServiceCompat ignores the type below API 29.
    @SuppressLint("InlinedApi")
    private fun begin() {
        if (active) return
        active = true
        // The service must call startForeground soon after it is started, even if it then fails.
        try {
            ServiceCompat.startForeground(
                this, StreamNotification.ID, notification.build(getString(R.string.status_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } catch (e: SecurityException) {
            finish(getString(R.string.camera_error, e.message ?: e.javaClass.simpleName))
            return
        }
        StreamController.publish(StreamState.Starting)
        lifecycleScope.launch {
            val settings = SettingsStore(this@StreamService).load()
            if (!active) return@launch // stopped while the settings were loading
            if (SettingsValidator.validate(settings).isNotEmpty()) {
                finish(getString(R.string.settings_invalid))
                return@launch
            }
            val buffer = FrameBuffer()
            val camServer = CamServer(settings.port, buffer, Credentials(settings.username, settings.password))
            try {
                port = camServer.start()
            } catch (e: Exception) {
                finish(getString(R.string.server_error, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            frames = buffer
            server = camServer
            host = LocalAddress.find() ?: "?"
            acquireLocks()
            session = CameraSession(applicationContext, buffer, settings.stream) { error ->
                handler.post { finish(cameraErrorText(error)) }
            }.also { it.start(this@StreamService) }
            lastTickMillis = System.currentTimeMillis()
            handler.post(ticker)
        }
    }

    private fun cameraErrorText(error: Throwable) = when {
        error is CameraMissingException && error.facing == CameraFacing.FRONT -> getString(R.string.camera_missing_front)
        error is CameraMissingException -> getString(R.string.camera_missing_back)
        else -> getString(R.string.camera_error, error.message ?: error.javaClass.simpleName)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            val frame = frames?.latest()
            val now = System.currentTimeMillis()
            val seconds = (now - lastTickMillis) / 1000.0
            val viewers = server?.streamers ?: 0
            val fps = if (frame != null && seconds > 0) (frame.sequence - lastSequence) / seconds else 0.0
            lastSequence = frame?.sequence ?: lastSequence
            lastTickMillis = now
            StreamController.publish(
                StreamState.Running(host, port, viewers, frame?.sequence ?: 0, fps, frame?.width ?: 0, frame?.height ?: 0),
            )
            if (viewers != lastViewers) {
                lastViewers = viewers
                notification.update(notificationText(viewers))
            }
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    private fun notificationText(viewers: Int) =
        "$host:$port · " + resources.getQuantityString(R.plurals.viewers, viewers, viewers)

    @SuppressLint("WakelockTimeout") // Held for the whole run on purpose; released in finish().
    @Suppress("DEPRECATION") // FULL_HIGH_PERF is the mode that keeps Wi-Fi at full speed with the screen off on API 30.
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camcast:stream").apply { acquire() }
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "camcast:stream").apply { acquire() }
    }

    /** Stops everything. [error] is shown in the app afterwards; null means a normal stop. Safe to call twice. */
    private fun finish(error: String?) {
        active = false
        handler.removeCallbacks(ticker)
        session?.stop()
        session = null
        server?.stop()
        server = null
        frames?.close()
        frames = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        lastViewers = -1
        StreamController.publish(if (error == null) StreamState.Stopped else StreamState.Failed(error))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Killed or stopped by the system: release what we hold. A Failed state from finish() is kept.
        if (active) finish(null)
        super.onDestroy()
    }

    private companion object {
        const val TICK_MILLIS = 1000L
    }
}
