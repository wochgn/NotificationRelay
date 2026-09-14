package com.notifrelay

import android.icu.text.Transliterator
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用名排序比较器：中文逐字转写为拼音（Han-Latin）后与英文/数字统一按字符串比较，
 * 中文与英文按拼音首字母混排；比较覆盖最后一个字符（含多音字转写结果）。
 * 比较结果保证是全序（同键时用原字符串兜底），因此正序与倒序互为严格反转。
 * 转写按名称缓存；排序会在 IO 线程与主线程调用，故共享缓存使用并发容器。
 */
object AppLabelComparator : Comparator<String> {
    private val hanLatin = Transliterator.getInstance("Han-Latin; Latin-ASCII")
    private val keyCache = ConcurrentHashMap<String, String>()

    override fun compare(a: String, b: String): Int {
        val result = keyOf(a).compareTo(keyOf(b))
        return if (result != 0) result else a.compareTo(b)
    }

    private fun keyOf(label: String): String = keyCache.getOrPut(label) { buildKey(label) }

    private fun buildKey(label: String): String {
        // 整串转写可让词典处理「重庆」等多音词；非汉字原样保留
        val transliterated = synchronized(hanLatin) { hanLatin.transliterate(label) }
        val key = StringBuilder(transliterated.length)
        for (c in transliterated) {
            if (c.isLetterOrDigit()) key.append(c.lowercaseChar())
        }
        return key.toString()
    }
}
