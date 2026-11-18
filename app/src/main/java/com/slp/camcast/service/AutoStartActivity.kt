package com.slp.camcast.service

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * An invisible activity that starts the stream for [BootReceiver]. A foreground service started from the background
 * gets no camera on Android 11 ("Camera disabled by policy"), but one started while an activity of the app is showing
 * does. Android lets an app that may display over other apps open an activity from the background, and this one turns
 * the screen on and shows over the lock screen (a black screen for a few seconds), so the phone does not have to be
 * unlocked after a reboot or an update.
 */
class AutoStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // An activity is only resumed with the screen on, and only an opaque one is shown over the lock screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    private var started = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started) return
        started = true
        // Android decides whether the service may use the camera from the app's process state when the service
        // starts. Right after a cold start (the receiver woke the process) that state still says background for a
        // moment, so wait until this window is focused and a little longer.
        window.decorView.postDelayed(::startStream, START_DELAY_MILLIS)
    }

    private fun startStream() {
        StreamController.start(
            this,
            fromBoot = intent.getBooleanExtra(StreamController.EXTRA_FROM_BOOT, false),
            afterUpdate = intent.getBooleanExtra(StreamController.EXTRA_AFTER_UPDATE, false),
        )
        // Stay in front until the service has gone foreground itself, then get out of the way.
        window.decorView.postDelayed(::finish, FINISH_DELAY_MILLIS)
    }

    private companion object {
        const val START_DELAY_MILLIS = 500L
        const val FINISH_DELAY_MILLIS = 3_000L
    }
}
