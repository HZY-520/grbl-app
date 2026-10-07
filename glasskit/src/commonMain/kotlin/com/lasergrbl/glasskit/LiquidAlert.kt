package com.lasergrbl.glasskit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lasergrbl.glasskit.theme.LiquidTheme
import com.lasergrbl.glasskit.theme.LiquidType

/** 警告条类型（对应 v2 `GlassAlert` 的 type）。 */
enum class LiquidAlertType { Info, Success, Warning, Error }

/**
 * 提示条（对应 v2 的 `GlassAlert`）。
 *
 * 外观沿用 v2：左侧一根语义色竖条 + 淡色底 + 语义色标题，右侧可放任意内容。
 */
@Composable
fun LiquidAlert(
    title: String,
    modifier: Modifier = Modifier,
    type: LiquidAlertType = LiquidAlertType.Info,
    message: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = LiquidTheme.colors
    val accent = when (type) {
        LiquidAlertType.Info -> colors.info
        LiquidAlertType.Success -> colors.success
        LiquidAlertType.Warning -> colors.warning
        LiquidAlertType.Error -> colors.danger
    }

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LiquidTheme.dimens.fieldRadius))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 28.dp)
                .clip(CircleShape)
                .background(accent)
        )

        Column(Modifier.weight(1f)) {
            BasicText(title, style = LiquidType.label.copy(color = accent))
            if (message != null) {
                BasicText(message, style = LiquidType.caption.copy(color = colors.textDim))
            }
        }

        trailing?.invoke()
    }
}

/** 供调用方按类型取色（例如图标着色）。 */
@Composable
fun LiquidAlertType.color(): Color = when (this) {
    LiquidAlertType.Info -> LiquidTheme.colors.info
    LiquidAlertType.Success -> LiquidTheme.colors.success
    LiquidAlertType.Warning -> LiquidTheme.colors.warning
    LiquidAlertType.Error -> LiquidTheme.colors.danger
}
