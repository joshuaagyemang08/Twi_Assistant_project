package com.example.twiassistant.hotword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.twiassistant.R

class HotwordService : Service() {

    private var engine: HotwordEngine? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine = SimpleKeywordHotwordEngine(this, keyword = "sheri")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_PAUSE -> {
                engine?.stop()
                running = false
                return START_STICKY
            }
            ACTION_RESUME, null -> {
                if (!running) {
                    running = true
                    engine?.start {
                        sendWakeBroadcast()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        running = false
        super.onDestroy()
    }

    private fun sendWakeBroadcast() {
        val wakeIntent = Intent(ACTION_HOTWORD_WAKE)
        sendBroadcast(wakeIntent)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sheri is listening")
            .setContentText("Say your wake phrase")
            .setSmallIcon(R.drawable.ic_phone_helper)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hotword",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_HOTWORD_WAKE = "com.example.twiassistant.HOTWORD_WAKE"
        const val ACTION_PAUSE = "com.example.twiassistant.HOTWORD_PAUSE"
        const val ACTION_RESUME = "com.example.twiassistant.HOTWORD_RESUME"
        private const val CHANNEL_ID = "hotword_channel"
        private const val NOTIFICATION_ID = 42
    }
}
