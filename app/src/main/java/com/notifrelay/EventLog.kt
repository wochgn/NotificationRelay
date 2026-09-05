package com.notifrelay

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 极简事件日志：BLE 回调线程写入，UI 线程观察显示。
 *
 * 日志分两级：
 *  - 详细日志（verbose）：连接过程、扫描、分片等全部事件，仅「启用日志显示」开关开启时记录
 *  - 一般日志（general）：错误、警告等关键事件，始终记录
 */
object EventLog {
    private const val TAG = "NotifRelay"
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile
    var verboseEnabled: Boolean = false

    fun add(msg: String) {
        if (verboseEnabled) {
            Log.d(TAG, msg)
            listeners.forEach { it(msg) }
        }
    }

    /** 一般日志（错误/警告等关键事件），不受开关影响，始终记录。 */
    fun addGeneral(msg: String) {
        Log.w(TAG, msg)
        listeners.forEach { it(msg) }
    }

    fun observe(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}
