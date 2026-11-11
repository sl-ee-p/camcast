package com.slp.camcast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.slp.camcast.MainActivity
import com.slp.camcast.R

class StreamNotification(private val context: Context) {
    init {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(
            NotificationChannel(PROBLEM_CHANNEL_ID, context.getString(R.string.notification_channel_problems), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 1, Intent(context, StreamService::class.java).setAction(StreamController.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun update(text: String) {
        context.getSystemService(NotificationManager::class.java).notify(ID, build(text))
    }

    /** A dismissable note for when nobody is looking at the app (a failed start at boot); tapping it opens the app. */
    fun postProblem(text: String) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val note = NotificationCompat.Builder(context, PROBLEM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(context.getString(R.string.notification_problem_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(PROBLEM_ID, note)
        } catch (e: SecurityException) {
            // Notifications are denied: nothing else to do.
        }
    }

    companion object {
        const val ID = 1
        private const val PROBLEM_ID = 2
        private const val PROBLEM_CHANNEL_ID = "problems"
        private const val CHANNEL_ID = "stream"
    }
}
