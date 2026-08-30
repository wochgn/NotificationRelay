package com.notifrelay

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * 通知读取服务。用户需在「设置 → 特殊应用权限 → 通知使用权」里开启本 App。
 */
class RelayListenerService : NotificationListenerService() {

    // 常驻通知可能频繁触发相同内容的更新，避免重复占用 BLE 发送队列。
    private val lastPostedHashes = ConcurrentHashMap<String, Int>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        val count = try {
            activeNotifications?.size ?: 0
        } catch (e: Exception) {
            -1
        }
        EventLog.add("通知监听已连接，当前活动通知 $count 条")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
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

        val deviceName = SettingsRepository.get(this).resolvedDeviceName()
        val json = NotificationCodec.toJson(sbn, applicationContext, deviceName)
        val fingerprint = try {
            JSONObject(json).apply { remove("time") }.toString().hashCode()
        } catch (_: Exception) {
            json.hashCode()
        }
        if (lastPostedHashes.put(sbn.key, fingerprint) == fingerprint) return
        EventLog.add("本机通知 [$sbn.packageName]")
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

        lastPostedHashes.remove(sbn.key)
        val json = JSONObject()
            .put("type", "notif_remove")
            .put("key", sbn.key)
            .toString()
        EventLog.add("本机通知已清除 [${sbn.packageName}]")
        BleRelayManager.get(this).sendToRemote(json)
    }
}
