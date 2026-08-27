package com.notifrelay

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置（SharedPreferences 持久化）。
 * 采用同步读写：通知监听服务（binder 线程）与 UI 都能直接调用。
 */
class SettingsRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("relay_settings", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }

        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_ONLY_WHITELIST = "only_whitelist"
        private const val KEY_WHITELIST = "whitelist"
        private const val KEY_FOREGROUND_ENABLED = "foreground_enabled"
    }

    // 设备名：null 表示使用系统设备名
    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(v) = prefs.edit().apply {
            if (v.isNullOrBlank()) remove(KEY_DEVICE_NAME) else putString(KEY_DEVICE_NAME, v)
        }.apply()

    // 是否「仅转发白名单中的应用」（默认 false = 全部转发）
    var onlyWhitelist: Boolean
        get() = prefs.getBoolean(KEY_ONLY_WHITELIST, false)
        set(v) = prefs.edit().putBoolean(KEY_ONLY_WHITELIST, v).apply()

    // 是否开启常驻后台（前台服务 + 常驻通知）
    var foregroundEnabled: Boolean
        get() = prefs.getBoolean(KEY_FOREGROUND_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_FOREGROUND_ENABLED, v).apply()

    // 解析后的设备名：用户自定义优先，否则系统设备名
    fun resolvedDeviceName(): String {
        val custom = deviceName
        return if (!custom.isNullOrBlank()) custom else DeviceInfo.systemName(appContext)
    }

    // 某应用的通知是否应被转发（受「仅转发白名单」模式影响）
    fun isAppEnabled(pkg: String): Boolean {
        if (!onlyWhitelist) return true
        return whitelist.contains(pkg)
    }

    fun isAppInWhitelist(pkg: String): Boolean = whitelist.contains(pkg)

    fun setAppEnabled(pkg: String, enabled: Boolean) {
        val set = whitelist.toMutableSet()
        if (enabled) set.add(pkg) else set.remove(pkg)
        prefs.edit().putStringSet(KEY_WHITELIST, set).apply()
    }

    private val whitelist: Set<String>
        get() = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
}
