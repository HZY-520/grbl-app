package com.lasergrbl.glasskit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lasergrbl.glasskit.theme.LiquidTheme
import com.lasergrbl.glasskit.theme.LiquidType
import kotlin.math.roundToInt

/**
 * 进度条（对应 v2 的 `GlassProgress`）。
 *
 * v2 的进度条本身不是玻璃面（它躺在玻璃卡片里），这里保持一致：纯圆角轨道 + 填充，
 * 不额外增加一层折射开销。
 */
@Composable
fun LiquidProgress(
    value: Float,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
    color: Color = LiquidTheme.colors.accent,
    trackColor: Color = LiquidTheme.colors.fill2,
    height: Dp = 8.dp
) {
    val fraction = value.coerceIn(0f, 1f)

    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .height(height)
        ) {
            val radius = size.height / 2f
            drawRoundRect(
                color = trackColor,
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(radius, radius)
            )
            if (fraction > 0f) {
                val filled = (size.width * fraction).coerceAtLeast(size.height)
                drawRoundRect(
                    color = color,
                    topLeft = Offset.Zero,
                    size = Size(filled, size.height),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
        }

        if (showLabel) {
            BasicText(
                text = "${(fraction * 100f).roundToInt()}%",
                style = LiquidType.label.copy(color = LiquidTheme.colors.textDim)
            )
        }
    }
}
