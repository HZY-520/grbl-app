package com.lasergrbl.glasskit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.theme.LiquidTheme
import com.lasergrbl.glasskit.theme.LiquidType

/**
 * 分段控件（对应 v2 的 `GlassSegmented`）。
 *
 * 说明：v2 的 13 个页面**从未用过**这个组件（分段 UI 都是自己拼的 GlassButton 行），
 * 所以 3.0 先给一个规规矩矩的实现：外层圆角容器 + 选中项玻璃药丸。
 * 等他日真有页面需要「滑块跟随」那种动效，再按他的 `LiquidSlider`/`DampedDragAnimation` 补。
 */
@Composable
fun <T> LiquidSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null
) {
    val colors = LiquidTheme.colors
    val dimens = LiquidTheme.dimens
    val shape = RoundedCornerShape(dimens.controlRadius)

    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.fill1)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            SegmentItem(
                text = label(option),
                selected = option == selected,
                backdrop = backdrop,
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun RowScope.SegmentItem(
    text: String,
    selected: Boolean,
    backdrop: Backdrop?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LiquidTheme.colors
    val shape = RoundedCornerShape(LiquidTheme.dimens.controlRadius - 3.dp)

    if (selected && backdrop != null) {
        LiquidPlainSurface(
            backdrop = backdrop,
            modifier = modifier,
            shape = shape,
            refractionHeight = 6.dp,
            refractionAmount = 12.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
            SegmentLabel(text, selected = true, modifier = Modifier.clickable(onClick = onClick))
        }
    } else {
        Box(
            modifier
                .clip(shape)
                .then(
                    if (selected) Modifier.background(colors.accentSoft) else Modifier
                )
                .clickable(onClick = onClick)
        ) {
            SegmentLabel(text, selected)
        }
    }
}

@Composable
private fun SegmentLabel(text: String, selected: Boolean, modifier: Modifier = Modifier) {
    val colors = LiquidTheme.colors
    BasicText(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp, horizontal = 6.dp),
        style = LiquidType.label.copy(
            color = if (selected) colors.text else colors.textDim,
            textAlign = TextAlign.Center
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
