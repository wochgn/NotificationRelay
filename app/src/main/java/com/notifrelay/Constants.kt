package com.notifrelay

import java.util.UUID

object Constants {
    // 自定义 128-bit BLE 服务 UUID（两台手机必须用同一套 UUID 才能互相发现）
    val SERVICE_UUID: UUID = UUID.fromString("a7e5c3f0-1b2a-4d5e-8f6a-9b0c1d2e3f4a")

    // 中心 -> 外设 的数据特征值（中心 write）
    val CHAR_FROM_CENTRAL: UUID = UUID.fromString("a7e5c3f0-1b2a-4d5e-8f6a-9b0c1d2e3f4b")

    // 外设 -> 中心 的数据特征值（外设 notify）
    val CHAR_FROM_PERIPHERAL: UUID = UUID.fromString("a7e5c3f0-1b2a-4d5e-8f6a-9b0c1d2e3f4c")

    // 标准 CCCD 描述符 UUID（客户端用它订阅 notify/indicate）
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // 本地通知 channel id（v2：带默认铃声/震动/横幅；旧 channel 设置创建后不可改，迁移新 id）
    const val CHANNEL_ID = "relay_v2"              // 普通流转通知
    const val CHANNEL_ID_ONGOING = "relay_ongoing_v2"  // 常驻/不可清除流转通知（默认静默）
    const val CHANNEL_ID_FOREGROUND = "relay_fg"    // 前台服务常驻通知（连接状态+电量）
    const val CHANNEL_ID_FIND_DEVICE = "relay_find" // 查找设备响铃控制通知

    // v1 旧通道，创建新通道时清理
    val LEGACY_CHANNEL_IDS = listOf("relay", "relay_ongoing")

    const val NOTIF_ID_FOREGROUND = 1  // 前台服务通知固定 id
    const val NOTIF_ID_FIND_DEVICE = 2 // 查找设备通知固定 id

    // 广播里放本机稳定 deviceId 用的「厂商数据」公司标识（自定义协议，任意值即可）
    const val MANUFACTURER_ID = 0x0D0D
}
