package com.notifrelay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat

object FindDeviceController {
    const val ACTION_STOP = "com.notifrelay.action.STOP_FIND_DEVICE"
    private const val AUTO_STOP_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private val autoStop = Runnable { stopAndNotify() }
    private var appContext: Context? = null

    fun start(context: Context, remoteName: String) {
        stopInternal()
        appContext = context.applicationContext
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
        showNotification(context, remoteName)
        handler.postDelayed(autoStop, AUTO_STOP_MS)
        EventLog.add("远端发起查找设备，开始响铃")
    }

    fun stop() {
        stopInternal()
        EventLog.add("查找设备响铃已停止")
    }

    fun stopAndNotify() {
        val context = appContext
        stopInternal()
        if (context != null) {
            BleRelayManager.get(context).sendToRemote(
                "{\"type\":\"find_stopped\"}"
            )
        }
        EventLog.add("查找设备响铃已停止")
    }

    private fun stopInternal() {
        handler.removeCallbacks(autoStop)
        ringtone?.stop()
        ringtone = null
        appContext?.getSystemService(NotificationManager::class.java)
            ?.cancel(Constants.NOTIF_ID_FIND_DEVICE)
        appContext = null
    }

    private fun showNotification(context: Context, remoteName: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                Constants.CHANNEL_ID_FIND_DEVICE,
                "查找设备",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "远端设备发起查找时显示停止响铃入口"
                setSound(null, null)
            }
        )

        val stopIntent = Intent(context, FindDeviceReceiver::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val source = remoteName.ifBlank { "远端设备" }
        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_ID_FIND_DEVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在查找这台设备")
            .setContentText("$source 请求设备响铃")
            .setContentIntent(stopPendingIntent)
            .addAction(R.drawable.ic_notification, "停止响铃", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        nm.notify(Constants.NOTIF_ID_FIND_DEVICE, notification)
    }
}

class FindDeviceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == FindDeviceController.ACTION_STOP) {
            FindDeviceController.stopAndNotify()
        }
    }
}
