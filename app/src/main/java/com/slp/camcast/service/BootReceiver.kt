package com.slp.camcast.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.slp.camcast.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Starts the stream after a reboot when "Start when the phone boots" is on. Newer Android versions refuse a camera
 * foreground service from the background; then the user gets a notification that opens the app instead.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (SettingsStore(app).load().startOnBoot) {
                    try {
                        StreamController.start(app, fromBoot = true)
                    } catch (e: RuntimeException) { // ForegroundServiceStartNotAllowedException and friends
                        StreamNotification(app).postProblem(app.getString(com.slp.camcast.R.string.boot_blocked))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
