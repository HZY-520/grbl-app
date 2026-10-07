package com.lasergrbl.glasskit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.theme.LiquidTheme
import com.lasergrbl.glasskit.theme.LiquidType

/**
 * 玻璃列表行（对应 v2 的 `GlassCell`）。
 *
 * 结构照抄 v2：左图标 → 标题 + 描述 → 右侧附加内容 → 可选箭头。
 * [backdrop] 为 null 时退化成纯色底（弹层/对话框内使用）。
 */
@Composable
fun LiquidRow(
    title: String,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showChevron: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
) {
    val colors = LiquidTheme.colors
    val dimens = LiquidTheme.dimens

    val content: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            leading?.invoke()

            Column(Modifier.weight(1f)) {
                BasicText(
                    text = title,
                    style = LiquidType.body.copy(color = if (enabled) colors.text else colors.textFaint),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (description != null) {
                    BasicText(
                        text = description,
                        style = LiquidType.caption.copy(color = colors.textDim),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            trailing?.invoke()

            if (showChevron) {
                Image(
                    imageVector = LiquidIcons.get("forward"),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    colorFilter = ColorFilter.tint(colors.textFaint)
                )
            }
        }
    }

    val clickable = if (onClick != null && enabled) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

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
