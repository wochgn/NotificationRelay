package com.notifrelay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.BatteryManager
import android.provider.Settings

/**
 * 读取系统级设备信息。
 */
object DeviceInfo {

    /** 系统设备名（设置 → 关于手机 → 设备名称）。读取失败时回退到蓝牙名。 */
    fun systemName(context: Context): String {
        val name = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: Exception) {
            null
        }
        if (!name.isNullOrBlank()) return name

        return try {
            @Suppress("MissingPermission")
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.name ?: "未知设备"
        } catch (e: Exception) {
            "未知设备"
        }
    }

    /** 当前电量百分比，-1 表示未知。 */
    fun batteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
    }
}
