package com.notifrelay

import android.icu.text.Transliterator
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用名排序比较器：中文按「每个汉字拼音首字母」的缩写参与排序（如 众包→ZB、京东→JD），
 * 英文与数字原样保留字母；中文缩写与英文按同一字符串统一比较，比较覆盖最后一个字符。
 * 连续汉字整体转写以正确处理「重庆」「银行」等多音词。
 * 同键时用原字符串兜底，保证全序，因此正序与倒序互为严格反转。
 * 转写按名称缓存；排序会在 IO 线程与主线程调用，故缓存使用并发容器。
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
        val key = StringBuilder(label.length)
        var i = 0
        while (i < label.length) {
            val cp = label.codePointAt(i)
            if (isHan(cp)) {
                val start = i
                while (i < label.length && isHan(label.codePointAt(i))) {
                    i += Character.charCount(label.codePointAt(i))
                }
                val pinyin = synchronized(hanLatin) { hanLatin.transliterate(label.substring(start, i)) }
                // 每个音节取首字母（声母首字母）：zhong bao -> zb
                var atSyllableStart = true
                for (c in pinyin) {
                    if (c.isLetter()) {
                        if (atSyllableStart) {
                            key.append(c.lowercaseChar())
                            atSyllableStart = false
                        }
                    } else {
                        atSyllableStart = true
                    }
                }
            } else {
                if (Character.isLetterOrDigit(cp)) key.appendCodePoint(Character.toLowerCase(cp))
                i += Character.charCount(cp)
            }
        }
        return key.toString()
    }

    private fun isHan(cp: Int): Boolean = cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF
}
