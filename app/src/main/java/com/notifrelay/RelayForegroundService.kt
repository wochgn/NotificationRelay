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
 * 前台服务：保活 + 常驻状态通知（连接状态 + 各远端电量）。
 * Android 14+ 必须声明 connectedDevice 类型（见 AndroidManifest）。
 */
class RelayForegroundService : Service() {

    companion object {
        const val ACTION_FIND_REMOTE = "com.notifrelay.action.FIND_REMOTE"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val manager get() = BleRelayManager.get(this)
    private var lastScanRestartAt = 0L
    private val reconnectRunnable = Runnable {
        if (SettingsRepository.get(this).foregroundEnabled &&
            SettingsRepository.get(this).savedDevices().isNotEmpty()
        ) {
            val background = !manager.isUiVisible()
            // 后台不持续扫描：仅在完全断连（需要找回已配对设备）时低频补扫；
            // 前台按原节奏扫描以发现新设备。
            val allowScan = !background || !manager.hasVisibleConnections()
            val minIntervalMs = if (background) 45_000L else 0L
            if (allowScan &&
                manager.hasMissingSavedPeer() &&
                !manager.isConnecting() &&
                !manager.isAutoReconnectPaused() &&
                !manager.isDiscoveryScanning() &&
                android.os.SystemClock.elapsedRealtime() - lastScanRestartAt >= minIntervalMs
            ) {
                lastScanRestartAt = android.os.SystemClock.elapsedRealtime()
                manager.startDiscovery()
            }
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
        // 后台自启时同样确保通知监听服务已绑定（应用更新后系统常不自动重绑）
        RelayListenerService.requestListenerRebind(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FIND_REMOTE) {
            if (manager.isFindingRemote()) manager.cancelFindRemote() else manager.findRemoteDevice()
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

    private fun currentState(): RelayState = manager.currentState()

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

        val text = when {
            state.peers.isEmpty() -> "未连接"
            else -> "已连接 ${state.peers.size} 台 · " + state.peers.joinToString("、") { peer ->
                val name = peer.name.ifBlank { "未知设备" }
                val battery = if (peer.battery >= 0) " ${peer.battery}%" else ""
                "$name$battery"
            }
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
        if (state.peers.isNotEmpty()) {
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
                if (manager.isFindingRemote()) "取消查找" else "查找设备",
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
        // BLUETOOTH_CONNECT 未授予时启动 connectedDevice 类型前台服务会直接崩溃；
        // 此时跳过启动，状态卡会显示"蓝牙权限未开启"，授权后再次开启即可。
        val granted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val intent = Intent(context, RelayForegroundService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, RelayForegroundService::class.java))
    }
}
