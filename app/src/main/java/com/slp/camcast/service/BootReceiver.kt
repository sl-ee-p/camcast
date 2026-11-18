package com.slp.camcast.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.slp.camcast.R
import com.slp.camcast.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Starts the stream after a reboot when "Start when the phone boots" is on, and after an app update if it was
 * streaming (installing kills the running service). A camera service started from the background gets no camera
 * (Android 11+), so when CamCast may display over other apps it goes through [AutoStartActivity]. Otherwise, or if
 * that activity never ran, it starts the service directly; if Android refuses or the camera stays disabled, the
 * user gets a notification that opens the app instead.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        if (!boot && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = SettingsStore(app)
                val start = if (boot) store.load().startOnBoot else store.isStreamWanted()
                if (start && Settings.canDrawOverlays(app)) {
                    app.startActivity(
                        Intent(app, AutoStartActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(StreamController.EXTRA_FROM_BOOT, boot)
                            .putExtra(StreamController.EXTRA_AFTER_UPDATE, !boot),
                    )
                    // MIUI can still block it (its own "open new windows from the background" switch).
                    delay(ACTIVITY_GRACE_MILLIS)
                }
                if (start && StreamController.state.value == StreamState.Stopped) {
                    try {
                        StreamController.start(app, fromBoot = boot, afterUpdate = !boot)
                    } catch (e: RuntimeException) { // ForegroundServiceStartNotAllowedException and friends
                        val text = if (boot) R.string.boot_blocked else R.string.update_blocked
                        StreamNotification(app).postProblem(app.getString(text))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTIVITY_GRACE_MILLIS = 5_000L
    }
}
