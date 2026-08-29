package com.notifrelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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

    companion object {
        const val ACTION_FIND_REMOTE = "com.notifrelay.action.FIND_REMOTE"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val manager get() = BleRelayManager.get(this)
    private val reconnectRunnable = Runnable {
        if (SettingsRepository.get(this).foregroundEnabled &&
            SettingsRepository.get(this).savedDevices().isNotEmpty() &&
            !manager.visibleConnected() &&
            !manager.isConnecting() &&
            !manager.isAutoReconnectPaused() &&
            !manager.isDiscoveryActive()
        ) {
            // 扫描有单次超时，定期重启以覆盖远端稍后才进入可发现状态的情况。
            manager.startDiscovery()
        }
        if (SettingsRepository.get(this).foregroundEnabled) scheduleReconnect()
    }

    private val stateListener: (RelayState) -> Unit = { state ->
        handler.post {
            updateNotification(state)
            scheduleReconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        manager.observeState(stateListener)
        scheduleReconnect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FIND_REMOTE) {
            if (manager.findingRemote) manager.cancelFindRemote() else manager.findRemoteDevice()
        }
        startForeground(
            Constants.NOTIF_ID_FOREGROUND,
            buildNotification(currentState()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        updateNotification(currentState())
        scheduleReconnect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(reconnectRunnable)
        manager.removeState(stateListener)
        super.onDestroy()
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, 500L)
    }

    private fun currentState(): RelayState =
        RelayState(manager.role, manager.visibleConnected(), manager.remoteName, manager.remoteBattery, manager.remoteAndroid)

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
            val android = state.remoteAndroid.ifBlank { "版本未知" }
            val battery = if (state.remoteBattery >= 0) "${state.remoteBattery}%" else "电量未知"
            "已连接 · $name · $android · 电量 $battery"
        } else {
            "未连接"
        }

        val builder = NotificationCompat.Builder(this, Constants.CHANNEL_ID_FOREGROUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("通知流转")
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    1,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setSilent(true)
        if (state.connected) {
            val findIntent = Intent(this, RelayForegroundService::class.java)
                .setAction(ACTION_FIND_REMOTE)
            val findPendingIntent = PendingIntent.getService(
                this,
                0,
                findIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_notification,
                if (manager.findingRemote) "取消查找" else "查找设备",
                findPendingIntent
            )
        }
        return builder.build()
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
