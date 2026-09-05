package com.notifrelay

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * 通知读取服务。用户需在「设置 → 特殊应用权限 → 通知使用权」里开启本 App。
 */
class RelayListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var isConnectedToListener: Boolean = false
            private set

        /**
         * 应用更新/进程重启后，MIUI 等系统可能保留「通知使用权」授权却不重新绑定监听服务，
         * 导致真实应用的通知不再转发。主动请求系统重新绑定。
         */
        fun requestListenerRebind(context: Context) {
            if (isConnectedToListener) return
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(context, RelayListenerService::class.java)
                )
                EventLog.add("已请求系统重新绑定通知监听")
            } catch (_: Exception) {
            }
        }
    }

    // 发送端全量流转；重复通知的去重在接收端（BleRelayManager）按用户开关执行
    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnectedToListener = true
        val count = try {
            activeNotifications?.size ?: 0
        } catch (e: Exception) {
            -1
        }
        EventLog.add("通知监听已连接，当前活动通知 $count 条")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnectedToListener = false
        EventLog.add("通知监听已断开")
        // 部分 ROM 在应用更新或进程重启后不会自动恢复绑定，主动请求系统重连。
        requestRebind(ComponentName(this, RelayListenerService::class.java))
    }

    // 必须用带 RankingMap 的两参数版本。单参数版本已废弃，部分系统/ROM 不会回调它。
    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        // 回环防护：忽略本 App 自己发出的通知（那些正是从对端流转过来后本地弹出的），
        // 否则会把通知再转发回对端，形成无限循环。
        if (sbn.packageName == packageName) return

        // 只过滤「前台服务」通知（如 Clash/VPN 的常驻通知），避免刷屏。
        // 注意不能用 !isClearable：HyperOS 会把验证码短信也标记为不可清除，会被误伤。
        val isForegroundService =
            (sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (isForegroundService) return

        // 应用过滤：开启「仅转发选中」后，只转发白名单中的应用
        if (!SettingsRepository.get(this).isAppEnabled(sbn.packageName)) return

        val settings = SettingsRepository.get(this)
        // 常驻/不可清除通知（音乐播放、下载进度等）按用户开关决定是否流转
        if (!settings.relayOngoingEnabled && !sbn.isClearable) return

        val deviceName = settings.resolvedDeviceName()
        val json = NotificationCodec.toJson(sbn, applicationContext, deviceName)
        EventLog.add("本机通知 [${sbn.packageName}]")
        BleRelayManager.get(this).apply {
            sendToRemote(json)
            sendAppIconIfNeeded(sbn.packageName)
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int
    ) {
        if (sbn.packageName == packageName) return
        if ((sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        if (!SettingsRepository.get(this).isAppEnabled(sbn.packageName)) return
        if (sbn.key.isBlank()) return

        // 流转通知不随原机通知消失而消失：不再向远端发送 notif_remove，
        // 远端通知保留，由用户在本机自行清除。
        EventLog.add("本机通知已清除 [${sbn.packageName}]（远端保留）")
    }
}
