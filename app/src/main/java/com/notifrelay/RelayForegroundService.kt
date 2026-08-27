package com.notifrelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 前台服务：保活 + 常驻状态通知（连接状态 + 远端电量）。
 * Android 14+ 必须声明 connectedDevice 类型（见 AndroidManifest）。
 */
class RelayForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val manager get() = BleRelayManager.get(this)

    private val stateListener: (RelayState) -> Unit = { state ->
        handler.post { updateNotification(state) }
    }

    override fun onCreate() {
        super.onCreate()
        manager.observeState(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            Constants.NOTIF_ID_FOREGROUND,
            buildNotification(currentState()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        updateNotification(currentState())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        manager.removeState(stateListener)
        super.onDestroy()
    }

    private fun currentState(): RelayState =
        RelayState(manager.role, manager.connected, manager.remoteName, manager.remoteBattery)

    private fun buildNotification(state: RelayState): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // createNotificationChannel 幂等；用户改过的通道设置不会被覆盖
        nm.createNotificationChannel(
            NotificationChannel(
                Constants.CHANNEL_ID_FOREGROUND,
                "常驻后台状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "展示连接状态与远端电量，可在系统设置中单独管理"
            }
        )

        val text = if (state.connected) {
            val name = state.remoteName.ifBlank { "未知设备" }
            val battery = if (state.remoteBattery >= 0) "${state.remoteBattery}%" else "电量未知"
            "已连接 · $name · 电量 $battery"
        } else {
            "未连接"
        }

        return NotificationCompat.Builder(this, Constants.CHANNEL_ID_FOREGROUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("通知流转")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(state: RelayState) {
        getSystemService(NotificationManager::class.java)
            .notify(Constants.NOTIF_ID_FOREGROUND, buildNotification(state))
    }
}

/**
 * 前台服务的启动/停止入口。
 */
object ForegroundServiceController {
    fun start(context: Context) {
        val intent = Intent(context, RelayForegroundService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, RelayForegroundService::class.java))
    }
}
