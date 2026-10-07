package com.lasergrbl.android

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.catalog.components.LiquidSlider
import com.kyant.backdrop.catalog.components.LiquidToggle
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlin.math.roundToInt

/**
 * iGRBL 3.0 · Phase 0 垂直切片
 *
 * 目的只有一个：证明「原生工程 + io.github.kyant0:backdrop + 他写的 5 个示例组件」
 * 这条链路在本机跑得通，并且玻璃效果真的出得来。
 *
 * 本屏**只用他原样内联的组件**，没有任何自研组件；背景的极光/参考图案是临时验证素材，
 * 最终视觉在 Phase 1 定稿（见 docs/UI-3.0-PLAN.md §4）。
 */
@Composable
fun Phase0GlassDemo() {
    val backdrop = rememberLayerBackdrop()

    var toggle by remember { mutableStateOf(true) }
    var slider by remember { mutableFloatStateOf(0.42f) }
    var tab by remember { mutableIntStateOf(0) }

    val tabs = listOf("首页", "图案", "文件", "控制", "设置")
    val runtimeShaderSupported = remember { isRuntimeShaderSupported() }
    val renderEffectSupported = remember { isRenderEffectSupported() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF05060A))
    ) {
        // ── 折射源：这一层会被 backdrop 录制成玻璃的「背景」────────────────────
        Box(
            Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
        ) {
            AuroraBlobs(
                Modifier
                    .fillMaxSize()
                    .blur(72.dp)
            )
            RefractionProbe(Modifier.fillMaxSize())
        }

        // ── 前景：全部使用他写的组件 ────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText(
                "iGRBL 3.0",
                style = TextStyle(Color.White, 26.sp, FontWeight.SemiBold)
            )
            BasicText(
                buildString {
                    append("Android ").append(Build.VERSION.SDK_INT)
                    append(" · ")
                    append(
                        when {
                            runtimeShaderSupported -> "完整折射（RuntimeShader）"
                            renderEffectSupported -> "部分效果（RenderEffect）"
                            else -> "无效果（纯色降级）"
                        }
                    )
                },
                style = TextStyle(Color.White.copy(alpha = 0.62f), 12.sp)
            )

            LiquidButton({}, backdrop) {
                BasicText("透明 Liquid 按钮", style = TextStyle(Color.White, 15.sp))
            }
            LiquidButton({}, backdrop, surfaceColor = Color.White.copy(alpha = 0.26f)) {
                BasicText("Surface Liquid 按钮", style = TextStyle(Color.White, 15.sp))
            }
            LiquidButton({}, backdrop, tint = Color(0xFFFF8A2B)) {
                BasicText("iGRBL 主色按钮", style = TextStyle(Color.White, 15.sp))
            }

            LiquidToggle(
                selected = { toggle },
                onSelect = { toggle = it },
                backdrop = backdrop
            )

            LiquidSlider(
                value = { slider },
                onValueChange = { slider = it },
                valueRange = 0f..1f,
                visibilityThreshold = 0.001f,
                backdrop = backdrop,
                modifier = Modifier.width(240.dp)
            )
            BasicText(
                "LiquidSlider = ${(slider * 100f).roundToInt()}%　·　LiquidToggle = ${if (toggle) "开" else "关"}",
                style = TextStyle(Color.White.copy(alpha = 0.72f), 13.sp)
            )

            Box(Modifier.weight(1f))

            LiquidBottomTabs(
                selectedTabIndex = { tab },
                onTabSelected = { tab = it },
                backdrop = backdrop,
                tabsCount = tabs.size
            ) {
                tabs.forEachIndexed { index, label ->
                    LiquidBottomTab(onClick = { tab = index }) {
                        BasicText(
                            label,
                            style = TextStyle(
                                if (tab == index) Color.White else Color.White.copy(alpha = 0.55f),
                                11.sp
                            )
                        )
                    }
                }
            }
            Box(Modifier.height(72.dp))
        }
    }
}

/** 极光背景：四团弥散色块（沿用 v2 的配色，Phase 1 再定稿）。 */
@Composable
private fun AuroraBlobs(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height

        fun blob(cx: Float, cy: Float, radius: Float, color: Color) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                radius = radius,
                center = Offset(cx, cy)
            )
        }

        blob(w * 0.12f, h * 0.10f, w * 0.90f, Color(0xFFFF8A2B).copy(alpha = 0.55f))
        blob(w * 0.98f, h * 0.28f, w * 0.80f, Color(0xFF5E3EF0).copy(alpha = 0.45f))
        blob(w * 0.20f, h * 0.64f, w * 0.85f, Color(0xFF10A8C4).copy(alpha = 0.38f))
        blob(w * 0.88f, h * 0.94f, w * 0.75f, Color(0xFFE03094).copy(alpha = 0.40f))
    }
}

/**
 * 折射参考图案：同心圆 + 点阵。玻璃压在上面时，只有真的做了折射/放大，
 * 才能看到这些细线被「透镜」扭曲——这是 Phase 0 的肉眼验收依据。
 */
@Composable
private fun RefractionProbe(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height

        val center = Offset(w * 0.5f, h * 0.34f)
        for (i in 1..7) {
            drawCircle(
                color = Color.White.copy(alpha = 0.12f),
                radius = w * 0.085f * i,
                center = center,
                style = Stroke(width = 2f)
            )
        }

        val dotRadius = 3f
        var y = h * 0.06f
        while (y < h * 0.98f) {
            var x = w * 0.06f
            while (x < w * 0.96f) {
                drawCircle(Color.White.copy(alpha = 0.22f), dotRadius, Offset(x, y))
                x += w * 0.11f
            }
            y += h * 0.055f
        }
    }
}
