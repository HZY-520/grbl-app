package com.lasergrbl.glasskit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.lasergrbl.glasskit.theme.LiquidTheme
import com.lasergrbl.glasskit.theme.LiquidType

/**
 * 玻璃对话框（v2 没有对应组件，v2 用 alert + toast 顶替；3.0 补一个真正能确认/取消的弹窗）。
 *
 * 与 [LiquidBottomSheet] 同理：**必须由屏幕放进 [LiquidScaffold] 的 `overlay` 槽**渲染，
 * 这样它和内容同窗口、共用折射源，玻璃才是真的折射而不是假毛玻璃。
 *
 * [destructive] 用于「软复位 / 中止任务」这类危险操作，确定按钮变红。
 */
@Composable
fun LiquidDialog(
    backdrop: Backdrop,
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    confirmText: String = "确定",
    dismissText: String? = "取消",
    destructive: Boolean = false,
    onConfirm: () -> Unit
) {
    val colors = LiquidTheme.colors
    val dimens = LiquidTheme.dimens
    val interactionSource = remember { MutableInteractionSource() }
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onDismissRequest
                )
        )

        AnimatedVisibility(
            visibleState = visibleState,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn() + scaleIn(initialScale = 0.94f),
            exit = fadeOut() + scaleOut(targetScale = 0.94f)
        ) {
            LiquidSurface(
                backdrop = backdrop,
                modifier = modifier
                    .padding(horizontal = 28.dp)
                    .widthIn(max = 420.dp),
                shape = RoundedCornerShape(dimens.cardRadius),
                surfaceColor = colors.modalSurface,
                blurRadius = 4.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BasicText(title, style = LiquidType.sectionTitle.copy(color = colors.text))
                    if (message != null) {
                        BasicText(message, style = LiquidType.label.copy(color = colors.textDim))
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (dismissText != null) {
                            Box(Modifier.weight(1f)) {
                                LiquidButton(
                                    onClick = onDismissRequest,
                                    backdrop = backdrop,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    BasicText(
                                        dismissText,
                                        style = LiquidType.body.copy(color = colors.text)
                                    )
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            LiquidButton(
                                onClick = {
                                    onConfirm()
                                    onDismissRequest()
                                },
                                backdrop = backdrop,
                                modifier = Modifier.fillMaxWidth(),
                                tint = if (destructive) colors.danger else colors.accent
                            ) {
                                BasicText(confirmText, style = LiquidType.body.copy(color = Color.White))
                            }
                        }
                    }
                }
            }
        }
    }
}
