package com.lasergrbl.glasskit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.theme.LiquidTheme
import com.lasergrbl.glasskit.theme.LiquidType

/**
 * 玻璃底部弹层（v2 `GlassSelect` 的 `.g-sheet` 等价物）。
 *
 * ⚠️ 必须由**屏幕**放进 [LiquidScaffold] 的 `overlay` 槽里渲染，而不是塞进滚动内容中：
 * 这样它和内容在同一个窗口、共用同一个折射源，玻璃才折射得到 App 的背景；
 * 用 `Popup`/`Dialog` 会另开窗口，`LayerBackdrop` 就取不到内容了。
 *
 * 视觉要点：**整块弹层只有一层玻璃**，里面的选项行是平的（见 [LiquidSheetOption]）——
 * 每行再各自折射一次会叠成一片花，v2 的 `.g-sheet__opt` 也是平铺在玻璃上的。
 */
@Composable
fun LiquidBottomSheet(
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    maxHeightFraction: Float = 0.72f,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LiquidTheme.colors
    val dimens = LiquidTheme.dimens
    val interactionSource = remember { MutableInteractionSource() }
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val maxPanelHeight = maxHeight * maxHeightFraction

        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onDismiss
                )
        )

        AnimatedVisibility(
            visibleState = visibleState,
            modifier = Modifier.fillMaxWidth(),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            LiquidSurface(
                backdrop = backdrop,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.screenPadding, vertical = 10.dp)
                    .navigationBarsPadding()
                    .heightIn(max = maxPanelHeight),
                shape = RoundedCornerShape(dimens.cardRadius),
                surfaceColor = colors.modalSurface,
                refractionHeight = 10.dp,
                refractionAmount = 20.dp,
                blurRadius = 6.dp,
                contentPadding = PaddingValues(0.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .size(width = 38.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.fill3)
                    )

                    if (title != null) {
                        BasicText(
                            text = title,
                            modifier = Modifier.padding(top = 10.dp, start = 16.dp, end = 16.dp),
                            style = LiquidType.sectionTitle.copy(color = colors.text)
                        )
                    }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        content = content
                    )
                }
            }
        }
    }
}

/**
 * 弹层里的一个选项行：**平的**（不折射），选中态右侧一个对勾 —— 与 v2 的 `.g-sheet__opt--on` 一致。
 */
@Composable
fun LiquidSheetOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    descriptor: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val colors = LiquidTheme.colors
    val shape = RoundedCornerShape(LiquidTheme.dimens.fieldRadius)

    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (selected) Modifier.background(colors.accentSoft) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                text = label,
                style = LiquidType.body.copy(
                    color = when {
                        !enabled -> colors.textFaint
                        selected -> colors.accent
                        else -> colors.text
                    },
                    textAlign = TextAlign.Start
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (descriptor != null) {
                BasicText(
                    text = descriptor,
                    style = LiquidType.caption.copy(color = colors.textDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (selected) {
            Image(
                imageVector = LiquidIcons.get("check"),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(colors.accent)
            )
        }
    }
}
