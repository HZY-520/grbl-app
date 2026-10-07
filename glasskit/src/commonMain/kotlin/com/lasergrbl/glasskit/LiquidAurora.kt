package com.lasergrbl.glasskit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 极光背景 —— 整个 App 的**折射源**。
 *
 * 液态玻璃必须背后有东西可折射；v2 用的是四团缓动极光（`glass.css` 的 `.g-aurora`），
 * 3.0 沿用同一套色板与节奏（26/32/38/30 秒往返），只是搬到 Compose。
 *
 * ⚠️ 使用者必须把它放进 `Modifier.layerBackdrop(backdrop)` 的那一层里，
 * 否则玻璃什么都折射不到（库 FAQ 的第一条）。
 *
 * `animated = false` 时只画静态一帧：对应 v2 设置页的「高性能玻璃」关闭态，省电。
 */
@Composable
fun LiquidAurora(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    blurRadius: Dp = 72.dp
) {
    val colors = LiquidTheme.aurora
    val transition = rememberInfiniteTransition(label = "aurora")

    @Composable
    fun drift(initial: Float, target: Float, durationMillis: Int, label: String): Float {
        if (!animated) return initial
        val value by transition.animateFloat(
            initialValue = initial,
            targetValue = target,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = label
        )
        return value
    }

    val drift1 = drift(0f, 1f, 26_000, "blob1")
    val drift2 = drift(1f, 0f, 32_000, "blob2")
    val drift3 = drift(0.35f, 1f, 38_000, "blob3")
    val drift4 = drift(0.8f, 0f, 30_000, "blob4")

    Canvas(modifier.blur(blurRadius)) {
        val w = size.width
        val h = size.height

        fun blob(cx: Float, cy: Float, radius: Float, color: Color, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                radius = radius,
                center = Offset(cx, cy)
            )
        }

        // v2 的四团：左上橙、右上紫、左下青、右下品红，位置随 drift 缓慢游走
        blob(
            cx = w * (0.12f + 0.16f * drift1),
            cy = h * (0.10f + 0.10f * drift3),
            radius = w * 0.90f,
            color = colors.blob1,
            alpha = 0.55f
        )
        blob(
            cx = w * (0.98f - 0.18f * drift2),
            cy = h * (0.28f + 0.12f * drift4),
            radius = w * 0.80f,
            color = colors.blob2,
            alpha = 0.45f
        )
        blob(
            cx = w * (0.20f + 0.20f * drift3),
            cy = h * (0.64f - 0.10f * drift1),
            radius = w * 0.85f,
            color = colors.blob3,
            alpha = 0.38f
        )
        blob(
            cx = w * (0.88f - 0.16f * drift4),
            cy = h * (0.94f - 0.12f * drift2),
            radius = w * 0.75f,
            color = colors.blob4,
            alpha = 0.40f
        )
    }
}
