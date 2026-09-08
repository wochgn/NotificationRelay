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
        // 后台重连指数退避：15 秒扫描窗口后依次等待 1/2/5/15 分钟，上限 15 分钟
        private val RECONNECT_BACKOFF_MS = longArrayOf(60_000L, 120_000L, 300_000L, 900_000L)
        // 通知监听健康检查：最多 15 分钟一次"已授权但未绑定"，仅异常时请求重绑
        private const val LISTENER_HEALTH_INTERVAL_MS = 15 * 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val manager get() = BleRelayManager.get(this)
    private var reconnectAttempt = 0
    private var reconnectScheduled = false

    /** 后台补扫：由事件触发（断连/扫描结束/服务启动/进程恢复），到达后按条件执行一次扫描。 */
    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        if (!shouldBackgroundReconnect()) return@Runnable
        val delay = RECONNECT_BACKOFF_MS[reconnectAttempt.coerceAtMost(RECONNECT_BACKOFF_MS.lastIndex)]
        reconnectAttempt++
        EventLog.add("后台重连：第 $reconnectAttempt 轮补扫（下轮间隔 ${delay / 60_000} 分钟）")
        manager.startDiscovery()
    }

    private fun shouldBackgroundReconnect(): Boolean {
        if (!SettingsRepository.get(this).foregroundEnabled) return false
        if (manager.isUiVisible() || manager.hasVisibleConnections()) return false
        if (manager.isAutoReconnectPaused()) return false
        if (SettingsRepository.get(this).savedDevices().isEmpty()) return false
        if (!manager.hasMissingSavedPeer()) return false
        if (manager.isConnecting() || manager.isDiscoveryScanning()) return false
        return true
    }

    /** 事件驱动的重连调度：仅在需要后台找回已配对设备时安排下一轮补扫。 */
    private fun scheduleBackgroundReconnect() {
        if (reconnectScheduled) return
        if (!shouldBackgroundReconnect()) return
        reconnectScheduled = true
        val delay = RECONNECT_BACKOFF_MS[reconnectAttempt.coerceAtMost(RECONNECT_BACKOFF_MS.lastIndex)]
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun cancelBackgroundReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
    }

    /** 连接成功/用户打开 App/蓝牙重新开启：重置退避。 */
    private fun resetReconnectBackoff() {
        cancelBackgroundReconnect()
        reconnectAttempt = 0
    }

    private val stateListener: (RelayState) -> Unit = { state ->
        handler.post {
            updateNotification(state)
            if (state.connected) {
                // 连接成功：取消待发补扫并重置退避
                resetReconnectBackoff()
            } else {
                // 意外断开/握手失败：按退避安排下一轮补扫
                scheduleBackgroundReconnect()
            }
        }
    }

    // 扫描结束仍缺已配对设备 → 安排下一轮退避补扫（事件驱动，无轮询）
    private val discoveryListener: (DiscoveryState) -> Unit = { state ->
        handler.post {
            if (!state.scanning) scheduleBackgroundReconnect()
        }
    }

    // 通知监听健康检查：低频、handler 驱动（随服务生命周期，不用 AlarmManager）
    private val listenerHealthRunnable = object : Runnable {
        override fun run() {
            RelayListenerService.requestListenerRebind(this@RelayForegroundService)
            handler.postDelayed(this, LISTENER_HEALTH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        manager.observeState(stateListener)
        manager.observeDiscovery(discoveryListener)
        // 后台自启时同样确保通知监听服务已绑定（应用更新后系统常不自动重绑）
        RelayListenerService.requestListenerRebind(this)
        handler.postDelayed(listenerHealthRunnable, LISTENER_HEALTH_INTERVAL_MS)
        scheduleBackgroundReconnect()
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
        // 系统恢复进程（START_STICKY 重建）或用户切回：立即检查是否需要补扫
        resetReconnectBackoff()
        scheduleBackgroundReconnect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelBackgroundReconnect()
        handler.removeCallbacks(listenerHealthRunnable)
        manager.removeState(stateListener)
        manager.removeDiscovery(discoveryListener)
        super.onDestroy()
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
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                        if (SettingsRepository.get(this@RelayForegroundService).hideFromRecents) {
                            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        }
                    },
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
