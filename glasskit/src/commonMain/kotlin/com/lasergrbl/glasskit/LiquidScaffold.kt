package com.lasergrbl.glasskit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 屏幕骨架 —— 把「折射源 + 顶栏 + 内容 + 底栏」串起来，等价于他 demo 里的 `BackdropDemoScaffold`，
 * 但换成 iGRBL 的结构（玻璃顶栏、极光背景、底部可放标签栏）。
 *
 * 关键点（库 FAQ 要求）：折射源那一层必须挂 `Modifier.layerBackdrop(backdrop)`，
 * 其余玻璃组件再用同一个 `backdrop` 去 `drawBackdrop`。
 *
 * [overlay] 槽用于**同窗口**的浮层（底部弹层 / 对话框 / Toast）：它们必须和内容共用一个折射源
 * 才能真的折射背景；用 `Popup`/`Dialog` 会另开窗口，`LayerBackdrop` 就取不到内容了。
 */
@Composable
fun LiquidScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    auroraAnimated: Boolean = true,
    showTopBar: Boolean = true,
    bottomBar: @Composable (backdrop: LayerBackdrop) -> Unit = {},
    overlay: @Composable (backdrop: LayerBackdrop) -> Unit = {},
    content: @Composable (backdrop: LayerBackdrop) -> Unit
) {
    val colors = LiquidTheme.colors
    val dimens = LiquidTheme.dimens
    val backdrop = rememberLayerBackdrop()

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .then(modifier)
    ) {
        // ── 折射源层 ──────────────────────────────────────────────────────────
        Box(
            Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
        ) {
            LiquidAurora(Modifier.fillMaxSize(), animated = auroraAnimated)
        }

        // ── 前景 ─────────────────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            if (showTopBar) {
                LiquidTopBar(
                    title = title,
                    backdrop = backdrop,
                    modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 8.dp),
                    subtitle = subtitle,
                    navigation = navigation,
                    actions = actions
                )
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                content(backdrop)
            }

            bottomBar(backdrop)
        }

        // ── 同窗口浮层（弹层 / 对话框 / Toast），必须放在最后才盖在最上层 ──
        overlay(backdrop)
    }
}
