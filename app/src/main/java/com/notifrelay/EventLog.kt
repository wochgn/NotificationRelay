package com.notifrelay

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 极简事件日志：BLE 回调线程写入，UI 线程观察显示。
 */
object EventLog {
    private const val TAG = "NotifRelay"
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun add(msg: String) {
        Log.d(TAG, msg)
        listeners.forEach { it(msg) }
    }

    fun observe(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}
