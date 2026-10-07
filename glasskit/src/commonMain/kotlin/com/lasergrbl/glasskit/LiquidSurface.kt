package com.lasergrbl.glasskit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 玻璃容器 —— iGRBL 3.0 的视觉基元（对应 v2 的 `GlassSurface`）。
 *
 * **全部效果都用他的引擎原语拼装**，配方直接取自他写的 `LiquidButton.kt`：
 * `vibrancy() + blur() + lens()` + `Highlight.Default` + `Shadow.Default`；
 * 参数按「卡片」而不是「按钮」微调（他按钮是 2dp 模糊 + 12/24dp 透镜，卡片更大更平）。
 *
 * 能力降级（这是他库的设计，不是我们发明的）：
 *   * API ≥ 33：完整折射（RuntimeShader）
 *   * API 31–32：只有模糊 + 高光，没有折射
 *   * API < 31：库里所有效果静默跳过 → 我们画一张**纯色卡片**兜底
 */
@Composable
fun LiquidSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LiquidTheme.dimens.cardRadius),
    /** 玻璃之上再叠一层表面色（v2 的 --g-fill 语义）；不指定则不叠 */
    surfaceColor: Color = Color.Unspecified,
    /** false = 关掉折射（对应 v2 设置页的「高性能玻璃」关闭态），只留模糊 + 高光 */
    refraction: Boolean = true,
    blurRadius: Dp = 2.dp,
    refractionHeight: Dp = 10.dp,
    refractionAmount: Dp = 20.dp,
    depthEffect: Boolean = false,
    chromaticAberration: Boolean = false,
    highlight: Highlight? = Highlight.Default,
    shadow: Shadow? = Shadow.Default,
    innerShadow: InnerShadow? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    contentPadding: PaddingValues = PaddingValues(LiquidTheme.dimens.pad),
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LiquidTheme.colors
    val renderEffectSupported = remember { isRenderEffectSupported() }
    val runtimeShaderSupported = remember { isRuntimeShaderSupported() }
    val useRefraction = refraction && runtimeShaderSupported

    Box(
        modifier
            // API < 31：他的引擎什么都不画，兜一张纯色卡片，避免界面变成一片透明
            .then(
                if (renderEffectSupported) {
                    Modifier
                } else {
                    Modifier
                        .clip(shape)
                        .background(surfaceColor.takeIf { it.isSpecified } ?: colors.fallbackSurface)
                }
            )
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    if (renderEffectSupported) {
                        vibrancy()
                        blur(blurRadius.toPx())
                        if (useRefraction) {
                            lens(
                                refractionHeight = refractionHeight.toPx(),
                                refractionAmount = refractionAmount.toPx(),
                                depthEffect = depthEffect,
                                chromaticAberration = chromaticAberration
                            )
                        }
                    }
                },
                highlight = { if (renderEffectSupported) highlight else null },
                shadow = { if (renderEffectSupported) shadow else null },
                innerShadow = { if (renderEffectSupported) innerShadow else null },
                onDrawSurface = {
                    if (surfaceColor.isSpecified && renderEffectSupported) {
                        drawRect(surfaceColor)
                    }
                }
            )
            .padding(contentPadding),
        contentAlignment = contentAlignment,
        content = content
    )
}

/**
 * 与 [LiquidSurface] 同料但**不带高光/阴影**的版本（对应他的 `drawPlainBackdrop`）——
 * 用在嵌套玻璃、列表行这类「只想轻微折射、不想再加一圈边光」的地方。
 */
@Composable
fun LiquidPlainSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LiquidTheme.dimens.controlRadius),
    refraction: Boolean = true,
    blurRadius: Dp = 2.dp,
    refractionHeight: Dp = 8.dp,
    refractionAmount: Dp = 16.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    contentPadding: PaddingValues = PaddingValues(LiquidTheme.dimens.pad),
    content: @Composable BoxScope.() -> Unit
) {
    val renderEffectSupported = remember { isRenderEffectSupported() }
    val runtimeShaderSupported = remember { isRuntimeShaderSupported() }

    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    if (renderEffectSupported) {
                        vibrancy()
                        blur(blurRadius.toPx())
                        if (refraction && runtimeShaderSupported) {
                            lens(refractionHeight.toPx(), refractionAmount.toPx())
                        }
                    }
                },
                highlight = { null },
                shadow = { null }
            )
            .padding(contentPadding),
        contentAlignment = contentAlignment,
        content = content
    )
}
