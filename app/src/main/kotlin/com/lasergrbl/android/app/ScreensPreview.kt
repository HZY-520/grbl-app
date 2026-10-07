package com.lasergrbl.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 路径预览 —— v2 `src/ui/views/PreviewView.vue`（217 行）的 Compose 版（`/preview`）。
 *
 * ### 逐块对应
 * | v2 | 这里 |
 * | --- | --- |
 * | `GcodePreview`（`height` 缩放 **200–640**） | 「画布高度」滑块 + [GcodePreview] |
 * | 缩放按钮（放大 / 缩小 / 适应） | 三个按钮（±40 px / 回到 320） |
 * | 统计网格（6 项） | 「统计」卡片 |
 * | 任务控制（运行 / 暂停 / 继续 / 中止） | 「任务」卡片 |
 * | 无文件时的空状态 | `GlassEmpty` |
 *
 * ### 与 v2 的差异
 * v2 的缩放值存在组件本地 `ref(320)`；3.0 把它放进 [Screen.Preview.zoomHeight]，
 * 于是从文件页跳进来时能带上当前缩放（`navigator.push(Screen.Preview(zoomHeight = 480))`），
 * 返回再进也能保持 —— v2 每次进页面都会重置成 320。
 */
@Composable
fun PreviewScreen(container: AppContainer, backdrop: Backdrop, screen: Screen.Preview) {
    val grbl = container.grbl
    val colors = LiquidTheme.colors

    // 缩放：初值来自路由参数（v2 固定 320）
    var zoom by remember(screen.zoomHeight) {
        mutableFloatStateOf(screen.zoomHeight.toFloat().coerceIn(MIN_ZOOM, MAX_ZOOM))
    }

    val file = grbl.file

    GlassScreenBody {
        if (file == null) {
            GlassCard(backdrop) { GlassEmpty("尚未载入文件，请先到「雕刻文件」载入") }
            return@GlassScreenBody
        }

        // ---- 画布 ----
        GlassCard(backdrop, padding = PaddingValues(10.dp)) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassSectionHeader(file.name, Modifier.weight(1f))
                    GlassText("${zoom.toInt()} px", color = colors.textDim, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                GcodePreview(
                    preview = file.preview,
                    bbox = file.stats.bbox,
                    progress = progressFraction(grbl.progress),
                    height = zoom.dp
                )
            }
        }

        // ---- 缩放控制 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSectionHeader("缩放")
                GlassSliderRow(
                    backdrop = backdrop,
                    title = "画布高度",
                    value = zoom,
                    onValueChange = { zoom = it },
                    valueRange = MIN_ZOOM..MAX_ZOOM,
                    decimals = 0,
                    suffix = " px"
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "zoomOut",
                        label = "缩小",
                        onClick = { zoom = (zoom - ZOOM_STEP).coerceAtLeast(MIN_ZOOM) },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        plain = true
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "zoomIn",
                        label = "放大",
                        onClick = { zoom = (zoom + ZOOM_STEP).coerceAtMost(MAX_ZOOM) },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        plain = true
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "refresh",
                        label = "适应",
                        onClick = { zoom = DEFAULT_ZOOM },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        plain = true
                    )
                }
            }
        }

        // ---- 统计 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassSectionHeader("统计")
                GlassKeyValue("总行数", file.stats.totalLines.toString(), mono = true)
                GlassKeyValue("移动指令", file.stats.motionCommands.toString(), mono = true)
                GlassKeyValue("路径长度", "${formatOne(file.stats.pathLengthMm)} mm", mono = true)
                GlassKeyValue("预计时间", formatDuration(file.stats.estimatedSeconds), mono = true)
                GlassKeyValue("包围盒", bboxText(file.stats.bbox), mono = true)
                GlassKeyValue("预览段数", file.preview.size.toString(), mono = true)
            }
        }

        // ---- 任务控制 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSectionHeader("任务")
                GlassProgressLine(progressFraction(grbl.progress), showLabel = true)
                GlassKeyValue(
                    "进度",
                    "${grbl.progress.executed} / ${grbl.progress.total}（${(progressFraction(grbl.progress) * 100).toInt()}%）"
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "play",
                        label = "运行",
                        onClick = { grbl.runFile() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        accent = true,
                        enabled = grbl.connected && !grbl.running
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "pause",
                        label = "暂停",
                        onClick = { grbl.feedHold() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        enabled = grbl.connected && grbl.running
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "forward",
                        label = "继续",
                        onClick = { grbl.cycleStart() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        enabled = grbl.connected
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "stop",
                        label = "中止",
                        onClick = { grbl.abortProgram() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        enabled = grbl.running
                    )
                }
            }
        }
    }
}

/** 包围盒显示（无效时给中文说明，与 v2 的 `—` 语义一致）。 */
private fun bboxText(bbox: com.lasergrbl.core.gcode.BoundingBox): String =
    if (bbox.valid) {
        "${bbox.minX.toInt()} × ${bbox.minY.toInt()} → ${bbox.maxX.toInt()} × ${bbox.maxY.toInt()}"
    } else {
        "无"
    }

/** 缩放范围与步进（v2 的 200–640，步进 40）。 */
private const val MIN_ZOOM = 200f
private const val MAX_ZOOM = 640f
private const val DEFAULT_ZOOM = 320f
private const val ZOOM_STEP = 40f
