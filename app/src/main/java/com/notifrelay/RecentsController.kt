package com.notifrelay

import android.app.ActivityManager
import android.content.Context

/**
 * 隐藏后台卡片控制器：通过公开 API ActivityManager.AppTask.setExcludeFromRecents
 * 控制本应用任务是否显示在最近任务（多任务）中。
 * 桌面图标、前台服务、通知监听与 BLE 连接均不受影响。
 */
object RecentsController {

    /** 将本应用全部任务标记为排除/包含最近任务显示（对已存在的任务立即生效）。 */
    fun apply(context: Context, excluded: Boolean) {
        try {
            context.getSystemService(ActivityManager::class.java)
                .appTasks
                .forEach { it.setExcludeFromRecents(excluded) }
        } catch (_: Exception) {
        }
    }

    /** 按持久化设置应用（Activity onCreate/onResume 调用，兼容应用内升级后的旧任务）。 */
    fun applyFromSettings(context: Context) {
        apply(context, SettingsRepository.get(context).hideFromRecents)
    }
}
