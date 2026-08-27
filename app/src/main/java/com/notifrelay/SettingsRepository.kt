package com.notifrelay

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 已配对设备（记住设备即可，不做系统蓝牙绑定）。以稳定 deviceId 为身份，不用会轮换的 MAC。 */
data class SavedDevice(val deviceId: String, val name: String, val android: String)

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
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_SAVED_DEVICES = "saved_devices"
        private const val KEY_DEVICE_ID = "device_id"
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

    // 是否已完成首次启动引导
    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(v) = prefs.edit().putBoolean(KEY_ONBOARDED, v).apply()

    // 本机稳定标识：首次生成后持久化（BLE MAC 会轮换，不能作身份）
    fun deviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

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

    fun setWhitelistForAll(pkgs: List<String>, enabled: Boolean) {
        val set = whitelist.toMutableSet()
        if (enabled) set.addAll(pkgs) else set.removeAll(pkgs)
        prefs.edit().putStringSet(KEY_WHITELIST, set).apply()
    }

    // ---- 已配对设备（记住设备即可）----

    fun savedDevices(): List<SavedDevice> {
        val raw = prefs.getString(KEY_SAVED_DEVICES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SavedDevice(
                    o.optString("id"),
                    o.optString("name"),
                    o.optString("android")
                ).takeIf { it.deviceId.isNotBlank() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveDevice(device: SavedDevice) {
        if (device.deviceId.isBlank()) return
        val list = savedDevices().filter { it.deviceId != device.deviceId }.toMutableList()
        list.add(0, device)
        prefs.edit().putString(KEY_SAVED_DEVICES, toJsonArray(list).toString()).apply()
    }

    fun removeDevice(deviceId: String) {
        val list = savedDevices().filter { it.deviceId != deviceId }
        prefs.edit().putString(KEY_SAVED_DEVICES, toJsonArray(list).toString()).apply()
    }

    fun findByDeviceId(deviceId: String): SavedDevice? =
        savedDevices().firstOrNull { it.deviceId == deviceId }

    private fun toJsonArray(list: List<SavedDevice>): JSONArray =
        JSONArray().apply {
            list.forEach { d ->
                put(JSONObject().apply {
                    put("id", d.deviceId)
                    put("name", d.name)
                    put("android", d.android)
                })
            }
        }

    private val whitelist: Set<String>
        get() = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
}
