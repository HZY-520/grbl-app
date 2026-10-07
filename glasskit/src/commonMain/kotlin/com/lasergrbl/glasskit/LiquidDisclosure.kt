package com.lasergrbl.glasskit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.theme.LiquidTheme
import com.lasergrbl.glasskit.theme.LiquidType

/**
 * 折叠面板（对应 v2 的 `GlassCollapse` + `GlassCollapseItem`）。
 *
 * 头部复用 [LiquidRow]（箭头旋转 90° 表示展开），内容用 `AnimatedVisibility` 做高度动画
 * —— v2 是直接卸载内容、没有动画；这里的动画属于交互改进，视觉仍沿用他的玻璃语言。
 *
 * [expanded] 传值即为受控；不传则内部自管状态。
 */
@Composable
fun LiquidDisclosure(
    title: String,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    description: String? = null,
    initiallyExpanded: Boolean = false,
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
    content: @Composable () -> Unit
) {
    val colors = LiquidTheme.colors
    var internalExpanded by remember { mutableStateOf(initiallyExpanded) }
    val isExpanded = expanded ?: internalExpanded

    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        label = "disclosureChevron"
    )

    Column(modifier) {
        LiquidRow(
            title = title,
            backdrop = backdrop,
            description = description,
            trailing = {
                Image(
                    imageVector = LiquidIcons.get("forward"),
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation),
                    colorFilter = ColorFilter.tint(colors.textFaint)
                )
            },
            onClick = {
                val next = !isExpanded
                if (expanded == null) internalExpanded = next
                onExpandedChange?.invoke(next)
            }
        )

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(Modifier.padding(contentPadding)) {
                content()
            }
        }
    }
}

/** 区块小标题（纯排版helper，不是新的视觉组件）。 */
@Composable
fun LiquidSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = text,
            modifier = Modifier.weight(1f),
            style = LiquidType.sectionTitle.copy(color = LiquidTheme.colors.text)
        )
        trailing?.invoke()
    }
}
