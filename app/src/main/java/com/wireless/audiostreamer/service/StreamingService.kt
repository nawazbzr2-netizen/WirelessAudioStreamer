package com.wireless.audiostreamer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class StreamingService : Service() {

    companion object {
        const val ACTION_START_SENDER = "com.wireless.audiostreamer.START_SENDER"
        const val ACTION_START_RECEIVER = "com.wireless.audiostreamer.START_RECEIVER"
        const val ACTION_STOP = "com.wireless.audiostreamer.STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "streaming_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var mediaProjection: MediaProjection? = null
    // TODO: SystemAudioCapturer, UdpStreamer, NearbyDiscovery, etc.

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SENDER -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (data != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("Sender mode active"))
                    startSender(resultCode, data)
                }
            }

            ACTION_START_RECEIVER -> {
                startForeground(NOTIFICATION_ID, buildNotification("Receiver mode active"))
                startReceiver()
            }

            ACTION_STOP -> {
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startSender(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        // TODO:
        // 1. Initialize SystemAudioCapturer with mediaProjection
        // 2. Start Opus encoding
        // 3. Start UDP sender
        // 4. Start Nearby Connections advertising/discovery
    }

    private fun startReceiver() {
        // TODO:
        // 1. Start Nearby Connections discovery
        // 2. Start UDP receiver
        // 3. Start Opus decoder + low-latency AudioTrack
    }

    private fun stopEverything() {
        mediaProjection?.stop()
        mediaProjection = null
        // TODO: stop capturer, streamer, player, discovery
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while audio is being streamed"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wireless Audio Streamer")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopEverything()
        serviceScope.cancel()
        super.onDestroy()
    }
}
