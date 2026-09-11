package com.notifrelay

import android.app.Notification
import android.app.NotificationManager
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
        EventLog.addGeneral("通知监听已断开")
        // 部分 ROM 在应用更新或进程重启后不会自动恢复绑定，主动请求系统重连。
        requestRebind(ComponentName(this, RelayListenerService::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        // 部分 ROM 销毁服务时不回调 onListenerDisconnected，必须在此清除绑定状态，
        // 否则 isConnectedToListener 保持 true，requestListenerRebind 会一直被跳过
        isConnectedToListener = false
    }

    // 通知栏隐藏判定：解锁后通知栏内不显示的通知（渠道被关闭为「无」、应用被暂停）。
    // 此类通知一律不转发；锁屏不显示（VISIBILITY_SECRET）的通知仍会正常出现在通知栏，照常转发。
    private fun isHiddenFromShade(sbn: StatusBarNotification, rankingMap: RankingMap?): Boolean {
        if (rankingMap == null) return false
        val ranking = NotificationListenerService.Ranking()
        if (!rankingMap.getRanking(sbn.key, ranking)) return false
        if (ranking.isSuspended) return true
        return ranking.channel?.importance == NotificationManager.IMPORTANCE_NONE
    }

    // 常驻类通知判定：不可清除 / 媒体播放（Android 13+ 媒体通知可滑动清除，仍属常驻类）。
    // 「流转常驻通知」关闭时，这类通知不转发。
    private fun isOngoingKind(sbn: StatusBarNotification): Boolean {
        if (!sbn.isClearable) return true
        val n = sbn.notification
        return n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            n.category == Notification.CATEGORY_TRANSPORT
    }

    // 必须用带 RankingMap 的两参数版本。单参数版本已废弃，部分系统/ROM 不会回调它。
    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        // 回环防护：忽略本 App 自己发出的通知（那些正是从对端流转过来后本地弹出的），
        // 否则会把通知再转发回对端，形成无限循环。
        if (sbn.packageName == packageName) {
            EventLog.add("过滤通知[自身应用]")
            return
        }

        // 只过滤「前台服务」通知（如 Clash/VPN 的常驻通知），避免刷屏。
        // 注意不能用 !isClearable：HyperOS 会把验证码短信也标记为不可清除，会被误伤。
        val isForegroundService =
            (sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (isForegroundService) {
            EventLog.add("过滤通知[前台服务][${sbn.packageName}]")
            return
        }

        // 应用过滤：开启「仅转发选中」后，只转发白名单中的应用
        if (!SettingsRepository.get(this).isAppEnabled(sbn.packageName)) {
            EventLog.add("过滤通知[不在白名单][${sbn.packageName}]")
            return
        }

        // 通知栏内不显示的通知一律不转发（与「流转常驻通知」开关无关）
        if (isHiddenFromShade(sbn, rankingMap)) {
            EventLog.add("过滤通知[通知栏隐藏][${sbn.packageName}]")
            return
        }

        val settings = SettingsRepository.get(this)
        // 常驻/不可清除、媒体类通知按用户开关决定是否流转
        if (!settings.relayOngoingEnabled && isOngoingKind(sbn)) {
            EventLog.add("过滤通知[常驻未开启][${sbn.packageName}]")
            return
        }

        val deviceName = settings.resolvedDeviceName()
        val json = try {
            NotificationCodec.toJson(sbn, applicationContext, deviceName)
        } catch (e: Exception) {
            EventLog.addGeneral("通知解析异常[${sbn.packageName}]：${e.message}")
            return
        }
        EventLog.add("本机通知 [${sbn.packageName}]")
        BleRelayManager.get(this).apply {
            val sent = sendToRemote(json)
            if (sent > 0) sendAppIconIfNeeded(sbn.packageName)
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int
    ) {
        if (sbn.packageName == packageName) return
        if ((sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        val settings = SettingsRepository.get(this)
        if (!settings.isAppEnabled(sbn.packageName)) return
        // 与转发条件保持一致：未流转的隐藏/常驻通知无需同步清除
        if (isHiddenFromShade(sbn, rankingMap)) return
        if (!settings.relayOngoingEnabled && isOngoingKind(sbn)) return
        if (sbn.key.isBlank()) return

        // 「同步通知清除状态」：清除事件始终上报，由接收端按其本地开关决定是否同步移除。
        // 无就绪目标时事件进入待发队列，并按 pkg|key 替换同通知的待发内容，等效撤销。
        val json = JSONObject()
            .put("type", "notif_remove")
            .put("pkg", sbn.packageName)
            .put("key", sbn.key)
            .toString()
        EventLog.add("本机通知已清除 [${sbn.packageName}]")
        BleRelayManager.get(this).sendToRemote(json)
    }
}
