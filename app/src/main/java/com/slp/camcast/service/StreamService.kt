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
import com.slp.camcast.camera.StreamConfig
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
    private var streamConfig: StreamConfig? = null
    private var generation = 0 // bumped on every camera (re)start, so errors from a replaced session are ignored
    private var attempt = 0 // consecutive camera restarts; a delivered frame resets it
    private var recovering = false
    private var fromBoot = false
    private var cameraStartedMillis = 0L

    override fun onCreate() {
        super.onCreate()
        notification = StreamNotification(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == StreamController.ACTION_STOP) {
            finish(null)
        } else {
            if (!active) fromBoot = intent?.getBooleanExtra(StreamController.EXTRA_FROM_BOOT, false) == true
            begin()
        }
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
            // At boot this is the expected refusal on newer Android: say so instead of quoting the platform.
            finish(if (fromBoot) getString(R.string.boot_blocked) else getString(R.string.camera_error, e.message ?: e.javaClass.simpleName))
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
            streamConfig = settings.stream
            startCamera()
            lastTickMillis = System.currentTimeMillis()
            handler.post(ticker)
        }
    }

    private fun startCamera() {
        val config = streamConfig ?: return
        val buffer = frames ?: return
        val mine = ++generation
        cameraStartedMillis = System.currentTimeMillis()
        session = CameraSession(applicationContext, buffer, config) { error ->
            handler.post { if (mine == generation && active) onCameraProblem(error) }
        }.also { it.start(this) }
    }

    /** The camera threw, or stopped delivering frames ([error] is null). Retry with a growing delay, or give up. */
    private fun onCameraProblem(error: Throwable?) {
        if (error != null && !RestartPolicy.isRetryable(error)) {
            finish(cameraErrorText(error))
            return
        }
        generation++ // drop any further errors from the session being replaced
        session?.stop()
        session = null
        attempt++
        if (RestartPolicy.shouldGiveUp(attempt)) {
            finish(error?.let(::cameraErrorText) ?: getString(R.string.camera_stalled))
            return
        }
        recovering = true
        StreamController.publish(StreamState.Recovering(host, port, attempt))
        notification.update(getString(R.string.notification_recovering, host, port))
        lastViewers = -1
        handler.postDelayed(restart, RestartPolicy.delayMillis(attempt))
    }

    private val restart = Runnable {
        if (active) {
            recovering = false
            startCamera()
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
            if (recovering) {
                handler.postDelayed(this, TICK_MILLIS)
                return
            }
            val lastFrame = maxOf(frame?.timestampMillis ?: 0L, cameraStartedMillis)
            if (RestartPolicy.isStalled(lastFrame, now)) {
                onCameraProblem(null)
                handler.postDelayed(this, TICK_MILLIS)
                return
            }
            if (frame != null && frame.sequence != lastSequence) attempt = 0
            if (attempt > 0) {
                // Restarted but no new frame yet: stay in Recovering instead of flashing back to Running.
                StreamController.publish(StreamState.Recovering(host, port, attempt))
                handler.postDelayed(this, TICK_MILLIS)
                return
            }
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
        handler.removeCallbacks(restart)
        generation++
        recovering = false
        attempt = 0
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
        // Started at boot, nobody is looking at the app: say why it is not streaming.
        if (error != null && fromBoot) notification.postProblem(error)
        fromBoot = false
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
