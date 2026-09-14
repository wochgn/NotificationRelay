package com.notifrelay.ui.miuix

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.cos
import kotlin.math.floor
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
 * 关于页背景彩色流光（参考 HyperAudio/HyperCeiler 关于页）：
 * 4 个彩色光斑在页面上方区域内缓慢摆动（smoothstep 平滑），
 * 颜色按周期循环渐变，半径轻微脉动。
 *
 * @param areaFraction 流光区域占屏幕高度的比例（从屏幕顶部开始）。
 * @param timeState 共享动画时钟。仅在绘制阶段读取，避免每帧重组页面。
 */
@Composable
fun FlowingGlowBackground(
    modifier: Modifier = Modifier,
    areaFraction: Float = 0.5f,
    glowAlpha: Float = 1f,
    timeState: FloatState,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f

    Canvas(modifier = modifier) {
        if (glowAlpha <= 0.001f || size.width <= 0f || size.height <= 0f) return@Canvas

        val w = size.width
        val h = size.height
        val areaH = h * areaFraction
        val areaW = min(areaH, w)
        val left = (w - areaW) / 2f
        val time = timeState.floatValue
        val palette = if (isDark) DarkGlowPalette else LightGlowPalette
        val colors = interpolateGlowColors(palette, time)
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
            val radius = p.r * min(areaW, areaH) * 0.9f * pulse
            val sourceColor = colors[i]
            val color = sourceColor.copy(alpha = sourceColor.alpha * palette.alphaMulti * glowAlpha)
            val center = Offset(left + px * areaW, py * areaH)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = color.alpha * 0.8f),
                        color.copy(alpha = color.alpha * 0.45f),
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
