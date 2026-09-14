package com.notifrelay

import java.text.Collator
import java.util.Locale

/**
 * 应用名排序比较器：中文按拼音排序（Collator），英文忽略大小写。
 * 仅主线程（组合/排序）使用。
 */
object AppLabelComparator : Comparator<String> {
    private val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply {
        strength = Collator.PRIMARY
    }

    override fun compare(a: String, b: String): Int = collator.compare(a, b)
}
