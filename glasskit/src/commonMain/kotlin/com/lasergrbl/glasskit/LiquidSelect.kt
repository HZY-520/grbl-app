package com.lasergrbl.glasskit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.theme.LiquidTheme
import com.lasergrbl.glasskit.theme.LiquidType

/**
 * 下拉选择器的**触发器**（对应 v2 `GlassSelect` 的按钮部分）。
 *
 * 弹层本身由 [LiquidBottomSheet] 负责，且必须由屏幕放进 [LiquidScaffold] 的 `overlay` 槽 —— 理由见那边的注释。
 * 这样拆分是为了把「展开与否」这个状态留在屏幕里（Compose 的惯用做法），而不是靠 provide/inject 注册选项。
 */
@Composable
fun LiquidSelect(
    label: String?,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    placeholder: String = "请选择",
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val colors = LiquidTheme.colors
    val dimens = LiquidTheme.dimens

    val content: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicText(
                text = label ?: placeholder,
                modifier = Modifier.weight(1f),
                style = LiquidType.body.copy(
                    color = when {
                        !enabled -> colors.textFaint
                        label != null -> colors.text
                        else -> colors.textFaint
                    }
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Image(
                imageVector = LiquidIcons.get("forward"),
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(90f),
                colorFilter = ColorFilter.tint(colors.textFaint)
            )
        }
    }

    val clickable = if (enabled) Modifier.clickable(onClick = onClick) else Modifier

    if (backdrop != null) {
        LiquidPlainSurface(
            backdrop = backdrop,
            modifier = modifier.then(clickable),
            shape = RoundedCornerShape(dimens.fieldRadius),
            contentPadding = PaddingValues(0.dp)
        ) {
            content()
        }
    } else {
        Box(
            modifier
                .clip(RoundedCornerShape(dimens.fieldRadius))
                .background(colors.fill1)
                .then(clickable)
        ) {
            content()
        }
    }
}
