package com.notifrelay

/**
 * 只识别带明确验证码语义的数字，避免把手机号、金额或订单号当成验证码。
 */
object OtpDetector {
    private val keywordPattern = Regex(
        "验证码|校验码|动态码|安全码|verification\\s*code|one[- ]time password|\\bOTP\\b|security code",
        RegexOption.IGNORE_CASE
    )
    private val numberPattern = Regex("(?<!\\d)(\\d(?:[ -]?\\d){3,7})(?!\\d)")

    fun hasKeyword(title: String, text: String): Boolean =
        keywordPattern.containsMatchIn(listOf(title, text).filter { it.isNotBlank() }.joinToString(" "))

    fun detect(title: String, text: String): String? {
        val source = listOf(title, text).filter { it.isNotBlank() }.joinToString(" ")
        if (!hasKeyword(title, text)) return null

        val keywords = keywordPattern.findAll(source).toList()
        return numberPattern.findAll(source)
            .mapNotNull { match ->
                val code = match.value.replace(" ", "").replace("-", "")
                if (code.length !in 4..8) return@mapNotNull null
                val distance = keywords.minOf { keyword ->
                    when {
                        match.range.last < keyword.range.first -> keyword.range.first - match.range.last
                        keyword.range.last < match.range.first -> match.range.first - keyword.range.last
                        else -> 0
                    }
                }
                if (distance > 80) null else distance to code
            }
            .minByOrNull { it.first }
            ?.second
    }
}
