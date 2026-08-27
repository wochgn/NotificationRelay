package com.notifrelay

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * 从 StatusBarNotification 提取标题/正文，序列化为 JSON 供 BLE 传输。
 */
object NotificationCodec {

    // 截断正文，避免超大文本（保证单条通知 JSON 远小于 64KB，配合 16bit 帧头）
    private const val MAX_TEXT_LEN = 2000

    fun toJson(sbn: StatusBarNotification, context: Context): String {
        val n = sbn.notification
        val extras = n.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        if (bigText.isNotBlank()) text = bigText

        val appLabel = try {
            val ai = context.packageManager.getApplicationInfo(sbn.packageName, 0)
            val label = context.packageManager.getApplicationLabel(ai)
            if (label == null) sbn.packageName else label.toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        return JSONObject().apply {
            put("pkg", sbn.packageName)
            put("app", appLabel)
            put("title", title)
            put("text", text.take(MAX_TEXT_LEN))
            put("key", sbn.key)
            put("time", sbn.postTime)
            // 是否常驻/不可清除类通知（如 ONGOING_EVENT|NO_CLEAR|SILENT 的场景调度通知）
            put("ongoing", !sbn.isClearable)
        }.toString()
    }
}
