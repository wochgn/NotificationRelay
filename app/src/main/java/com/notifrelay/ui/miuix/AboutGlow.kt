package com.notifrelay.ui.miuix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class GlowPoint(val x: Float, val y: Float, val r: Float)

/**
 * 关于页背景流光：4 个光斑在页面上方缓慢摆动，颜色按周期循环渐变、半径轻微脉动。
 * 光点布局与颜色参数参考 HyperCeiler 关于页背景，仅保留核心光斑混合逻辑。
 */
private val GLOW_POINTS = listOf(
    GlowPoint(0.8f, 0.2f, 1.0f),
    GlowPoint(0.8f, 0.9f, 1.0f),
    GlowPoint(0.2f, 0.9f, 1.0f),
    GlowPoint(0.2f, 0.2f, 1.0f),
)

private data class GlowPalette(
    val colors1: List<Color>,
    val colors2: List<Color>,
    val colors3: List<Color>,
    val period: Float,
    val pointOffset: Float,
    val alphaMulti: Float,
)

private fun glowColor(r: Float, g: Float, b: Float, a: Float) = Color(r, g, b, a)

private val LightGlowPalette = GlowPalette(
    colors1 = listOf(
        glowColor(1f, 0.55f, 0.72f, 1f), glowColor(0.98f, 0.6f, 0.85f, 1f),
        glowColor(0.9f, 0.5f, 0.8f, 1f), glowColor(0.6f, 0.5f, 0.98f, 1f),
    ),
    colors2 = listOf(
        glowColor(0.4f, 0.65f, 1f, 1f), glowColor(0.9f, 0.6f, 0.85f, 1f),
        glowColor(0.6f, 0.6f, 1f, 1f), glowColor(0.95f, 0.55f, 0.8f, 1f),
    ),
    colors3 = listOf(
        glowColor(0.9f, 0.55f, 0.85f, 1f), glowColor(0.4f, 0.6f, 0.98f, 1f),
        glowColor(0.8f, 0.7f, 1f, 1f), glowColor(0.45f, 0.55f, 1f, 1f),
    ),
    period = 5f,
    pointOffset = 0.2f,
    alphaMulti = 0.7f,
)

private val DarkGlowPalette = GlowPalette(
    colors1 = listOf(
        glowColor(0.2f, 0.06f, 0.88f, 0.4f), glowColor(0.3f, 0.14f, 0.55f, 0.5f),
        glowColor(0f, 0.64f, 0.96f, 0.5f), glowColor(0.11f, 0.16f, 0.83f, 0.4f),
    ),
    colors2 = listOf(
        glowColor(0.07f, 0.15f, 0.79f, 0.5f), glowColor(0.62f, 0.21f, 0.67f, 0.5f),
        glowColor(0.06f, 0.25f, 0.84f, 0.5f), glowColor(0f, 0.2f, 0.78f, 0.5f),
    ),
    colors3 = listOf(
        glowColor(0.58f, 0.3f, 0.74f, 0.4f), glowColor(0.27f, 0.18f, 0.6f, 0.5f),
        glowColor(0.66f, 0.26f, 0.62f, 0.5f), glowColor(0.12f, 0.16f, 0.7f, 0.6f),
    ),
    period = 8f,
    pointOffset = 0.4f,
    alphaMulti = 0.9f,
)

/** 三组颜色按周期循环 smoothstep 插值。 */
private fun interpolateGlowColors(palette: GlowPalette, t: Float): List<Color> {
    val cycle = t / palette.period
    val phase = cycle - floor(cycle)
    val idx = ((floor(cycle).toInt() % 3) + 3) % 3
    val from = when (idx) {
        0 -> palette.colors1
        1 -> palette.colors2
        else -> palette.colors3
    }
    val to = when (idx) {
        0 -> palette.colors2
        1 -> palette.colors3
        else -> palette.colors1
    }
    val s = phase * phase * (3f - 2f * phase)
    return from.indices.map { lerp(from[it], to[it], s) }
}

/**
 * 逐个计算当前时刻的光斑（背景与图标/文字混色共用，保证运动与颜色完全同步）。
 */
private inline fun forEachGlowSpot(
    isDark: Boolean,
    time: Float,
    area: Size,
    radiusMultiplier: Float = 1f,
    colors: List<Color>? = null,
    block: (cx: Float, cy: Float, radius: Float, color: Color) -> Unit,
) {
    val palette = if (isDark) DarkGlowPalette else LightGlowPalette
    val currentColors = colors ?: interpolateGlowColors(palette, time)
    for (i in GLOW_POINTS.indices) {
        val p = GLOW_POINTS[i]
        val drx = sin(time * 0.6f + p.y * 3f)
        val dry = cos(time * 0.5f + p.x * 3f)
        val dx = (drx + 1f) / 2f
        val dy = (dry + 1f) / 2f
        val smoothX = dx * dx * (3f - 2f * dx)
        val smoothY = dy * dy * (3f - 2f * dy)
        val px = p.x + smoothX * palette.pointOffset * 2f - palette.pointOffset
        val py = p.y + smoothY * palette.pointOffset * 2f - palette.pointOffset
        val pulse = 0.85f + 0.15f * sin(time * 0.4f + i * 1.7f)
        val radius = p.r * min(area.width, area.height) * 0.9f * pulse * radiusMultiplier
        block(
            px * area.width,
            py * area.height,
            radius,
            currentColors[i].copy(alpha = currentColors[i].alpha * palette.alphaMulti)
        )
    }
}

/**
 * 关于页背景彩色流光（参考 HyperAudio/HyperCeiler 关于页）：
 * 4 个彩色光斑在页面上方区域内缓慢摆动（smoothstep 平滑），
 * 颜色按周期循环渐变，半径轻微脉动。
 *
 * @param areaFraction 流光区域占屏幕高度的比例（从屏幕顶部开始）。
 * @param glowAlpha 透明度获取器，仅在绘制阶段读取，滚动时避免整页重组。
 * @param timeState 共享动画时钟。
 */
@Composable
fun FlowingGlowBackground(
    modifier: Modifier = Modifier,
    areaFraction: Float = 0.5f,
    glowAlpha: () -> Float = { 1f },
    timeState: FloatState,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f

    Canvas(modifier = modifier) {
        val alpha = glowAlpha()
        if (alpha <= 0.001f || size.width <= 0f || size.height <= 0f) return@Canvas

        val w = size.width
        val h = size.height
        val areaH = h * areaFraction
        val areaW = min(areaH, w)
        val left = (w - areaW) / 2f
        forEachGlowSpot(isDark, timeState.floatValue, Size(areaW, areaH)) { cx, cy, radius, color ->
            val center = Offset(left + cx, cy)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = color.alpha * alpha * 0.8f),
                        color.copy(alpha = color.alpha * alpha * 0.45f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}

/**
 * 对内容（应用图标/文字）叠加与背景流光同步的渐变换色与光斑层，
 * 参考 HyperAudio 关于页的 logo/文字混色效果。
 */
@Composable
fun GlowMixedContent(
    modifier: Modifier = Modifier,
    timeState: FloatState,
    active: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    Box(
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        content()
        Canvas(Modifier.matchParentSize()) {
            if (!active || size.width <= 0f || size.height <= 0f) return@Canvas
            val time = timeState.floatValue
            val palette = if (isDark) DarkGlowPalette else LightGlowPalette
            val paletteColors = interpolateGlowColors(palette, time)
            val baseColors = paletteColors + paletteColors.first()
            val cx = size.width / 2f
            val cy = size.height / 2f
            val len = max(size.width, size.height) * 1.5f
            // 扫掠旋转速度与背景流光漂移速度一致，保证颜色变化节奏同步
            val ang = time * 0.55f
            val cosA = cos(ang)
            val sinA = sin(ang)
            val start = Offset(cx - cosA * len, cy - sinA * len)
            val end = Offset(cx + cosA * len, cy + sinA * len)
            if (!isDark) {
                // 浅色：线性扫掠 + 反向叠加（对比度更高）
                val tinted = baseColors.map {
                    contrast(darken(saturate(it, 0.5f), 0.5f), 2.4f).copy(alpha = 1f)
                }
                drawRect(
                    brush = Brush.linearGradient(colors = tinted, start = start, end = end),
                    blendMode = BlendMode.SrcIn
                )
                drawRect(
                    brush = Brush.linearGradient(colors = tinted, start = end, end = start),
                    blendMode = BlendMode.SrcAtop
                )
            } else {
                // 深色：直接使用与背景流光相同的调色板颜色（不做色相偏移），仅轻微提亮
                val tinted = baseColors.map { it.copy(alpha = 1f) }
                drawRect(
                    brush = Brush.linearGradient(colors = tinted, start = start, end = end),
                    blendMode = BlendMode.SrcIn
                )
                drawRect(Color.White.copy(alpha = 0.26f), blendMode = BlendMode.SrcAtop)
            }
            // 与背景流光同源的光斑层
            val radiusMultiplier = max(size.width, size.height) / min(size.width, size.height) * 0.45f
            val spotContrast = if (isDark) 2f else 2.4f
            forEachGlowSpot(isDark, time, size, radiusMultiplier) { x, y, radius, color ->
                val sc = if (isDark) contrast(color, spotContrast) else contrast(darken(saturate(color, 0.5f), 0.5f), spotContrast)
                val center = Offset(x, y)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            sc.copy(alpha = 0.35f),
                            sc.copy(alpha = 0.175f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                    blendMode = BlendMode.SrcAtop,
                )
            }
        }
    }
}

/** 提升颜色饱和度：将颜色推向纯色方向（保持明度与色相不变）。 */
private fun saturate(c: Color, amount: Float): Color {
    val l = c.luminance()
    return Color(
        (c.red + (c.red - l) * amount).coerceIn(0f, 1f),
        (c.green + (c.green - l) * amount).coerceIn(0f, 1f),
        (c.blue + (c.blue - l) * amount).coerceIn(0f, 1f),
        c.alpha,
    )
}

/** 降低明度：整体压暗（RGB 等比缩小）。 */
private fun darken(c: Color, factor: Float): Color {
    return Color(c.red * factor, c.green * factor, c.blue * factor, c.alpha)
}

/** 提升对比度：以 0.5 为中心拉大色差（亮处更亮、暗处更暗）。 */
private fun contrast(c: Color, amount: Float): Color {
    fun push(v: Float): Float = ((v - 0.5f) * amount + 0.5f).coerceIn(0f, 1f)
    return Color(push(c.red), push(c.green), push(c.blue), c.alpha)
}
