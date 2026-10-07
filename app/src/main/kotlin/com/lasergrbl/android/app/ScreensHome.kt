package com.lasergrbl.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.core.grbl.GrblCore
import com.lasergrbl.core.grbl.MacStatus
import com.lasergrbl.glasskit.theme.LiquidTheme
import java.util.Locale

/**
 * 首页 —— v2 `src/ui/views/HomeView.vue`（280 行）的 Compose 版（`/home`，tab home）。
 *
 * ### 逐块对应
 * | v2 | 这里 |
 * | --- | --- |
 * | 状态 / 固件 / 版本 + 三轴坐标 + 进给主轴（`lg-stat` × 6） | 第一张卡片 |
 * | `GcodePreview`（`progress` 驱动） | [GcodePreview]，`progress = executed / total` |
 * | 任务进度 + 开始 / 暂停 / 继续 / 中止 | 第二张卡片 |
 * | 快捷动作（回零 / 解锁 / 设零 / 清 WCO） | 第二张卡片的下排（v2 也有这 4 个） |
 * | 快捷入口卡片（`lg-grid-2`） | [GlassNavCard] × 7 |
 * | `LogList` | [GlassLogList] |
 *
 * ### 与 v2 的两处差异（行为等价）
 * 1. v2 在 store 的 `runFile()` 里启动保活；3.0 在 `GrblController.runFile` 里做 —— 位置不同、行为相同。
 * 2. v2 的跳转是硬编码路径字符串；3.0 用 [Screen] 类型（`Screen.fromPath` 保留了路径语义）。
 */
@Composable
fun HomeScreen(container: AppContainer, backdrop: Backdrop) {
    val grbl = container.grbl
    val navigator = container.navigator
    val colors = LiquidTheme.colors

    GlassScreenBody {
        // ---- 状态与坐标 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassBadge(
                        text = if (grbl.connecting) "连接中" else grbl.status.label(),
                        kind = statusBadgeKind(grbl.status, grbl.connected, grbl.connecting)
                    )
                    GlassText(
                        text = if (grbl.connected) {
                            FIRMWARE_LABELS[grbl.firmware] ?: grbl.firmware.name
                        } else {
                            "未连接设备"
                        },
                        color = colors.textDim,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.weight(1f))
                    if (grbl.version.isNotEmpty()) {
                        GlassText(grbl.version, color = colors.textFaint, fontSize = 11.5.sp)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    GlassStat("X", formatMm(grbl.pos.X))
                    GlassStat("Y", formatMm(grbl.pos.Y))
                    GlassStat("Z", formatMm(grbl.pos.Z))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    GlassStat("工作 X", formatMm(grbl.wpos.X))
                    GlassStat("工作 Y", formatMm(grbl.wpos.Y))
                    GlassStat("工作 Z", formatMm(grbl.wpos.Z))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    GlassStat("进给 F", formatSpeed(grbl.feedValue))
                    GlassStat("主轴 S", grbl.spindle.toInt().toString())
                    GlassStat("偏移", "${grbl.wco.X.toInt()},${grbl.wco.Y.toInt()}")
                }
            }
        }

        val file = grbl.file

        // ---- 路径预览（进度驱动）----
        if (file != null) {
            GlassCard(backdrop, padding = PaddingValues(10.dp)) {
                Column {
                    GlassSectionHeader(file.name)
                    Spacer(Modifier.height(8.dp))
                    GcodePreview(
                        preview = file.preview,
                        bbox = file.stats.bbox,
                        progress = progressFraction(grbl.progress),
                        height = 200.dp
                    )
                }
            }
        }

        // ---- 任务控制 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("任务")
                GlassProgressLine(progressFraction(grbl.progress), showLabel = true)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassText(
                        text = "${grbl.progress.executed} / ${grbl.progress.total}",
                        color = colors.textDim,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.weight(1f))
                    GlassText(
                        text = if (grbl.running) "运行中" else "空闲",
                        color = if (grbl.running) colors.success else colors.textFaint,
                        fontSize = 12.sp
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "play",
                        label = "运行",
                        onClick = { grbl.runFile() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        accent = true,
                        // 四列并排：**不显示图标** —— 中文标签 + 图标在 1/4 宽度里放不下会被截成「运…」
                        showIcon = false,
                        enabled = grbl.connected && !grbl.running && file != null
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "pause",
                        label = "暂停",
                        onClick = { grbl.feedHold() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        showIcon = false,
                        enabled = grbl.connected && grbl.running
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "forward",
                        label = "继续",
                        onClick = { grbl.cycleStart() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        showIcon = false,
                        enabled = grbl.connected
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "stop",
                        label = "中止",
                        onClick = { grbl.abortProgram() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        showIcon = false,
                        enabled = grbl.running
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "homeZero",
                        label = "回零",
                        onClick = { grbl.homing() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Mini,
                        plain = true,
                        showIcon = false,
                        enabled = grbl.connected
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "unlock",
                        label = "解锁",
                        onClick = { grbl.unlock() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Mini,
                        plain = true,
                        showIcon = false,
                        enabled = grbl.connected
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "target",
                        label = "设零",
                        onClick = { grbl.setNewZero() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Mini,
                        plain = true,
                        showIcon = false,
                        enabled = grbl.connected
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "refresh",
                        label = "清 WCO",
                        onClick = { grbl.resetWCO() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Mini,
                        plain = true,
                        showIcon = false,
                        enabled = grbl.connected
                    )
                }
            }
        }

        // ---- 快捷入口 ----
        GlassNavCard(
            backdrop = backdrop,
            icon = "usb",
            title = "连接设备",
            description = if (grbl.connected) "已连接" else "USB / 蓝牙串口",
            onClick = { navigator.push(Screen.Connect) }
        )
        GlassNavCard(
            backdrop = backdrop,
            icon = "image",
            title = "生成图案",
            description = "图片 / 文字 / SVG 转 G 代码",
            onClick = { navigator.selectTab(Tab.Convert) }
        )
        GlassNavCard(
            backdrop = backdrop,
            icon = "file",
            title = "雕刻文件",
            description = if (file == null) "载入本地 G 代码" else file.name,
            onClick = { navigator.selectTab(Tab.File) }
        )
        GlassNavCard(
            backdrop = backdrop,
            icon = "move",
            title = "运动控制",
            description = "点动 / 步长 / 覆盖倍率",
            onClick = { navigator.selectTab(Tab.Jog) }
        )
        GlassNavCard(
            backdrop = backdrop,
            icon = "config",
            title = "机器参数",
            description = "读取 / 写入 GRBL 设置",
            onClick = { navigator.push(Screen.Config) }
        )
        GlassNavCard(
            backdrop = backdrop,
            icon = "terminal",
            title = "串口终端",
            description = "直接发送 G 代码命令",
            onClick = { navigator.push(Screen.Terminal) }
        )
        if (file != null) {
            GlassNavCard(
                backdrop = backdrop,
                icon = "eye",
                title = "路径预览",
                description = "放大查看路径与统计",
                onClick = { navigator.push(Screen.Preview()) }
            )
        }

        // ---- 日志 ----
        GlassCard(backdrop, padding = PaddingValues(10.dp)) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassSectionHeader("日志", Modifier.weight(1f))
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "trash",
                        label = "清空",
                        onClick = { grbl.clearLog() },
                        size = GlassButtonSize.Mini,
                        plain = true
                    )
                }
                Spacer(Modifier.height(6.dp))
                GlassLogList(log = grbl.log, showTime = false)
            }
        }
    }
}

/** 进度比例（`executed / total`；`total = 0` 时为 0，不除零 —— 与 v2 的 `percentOf` 一致）。 */
fun progressFraction(progress: GrblCore.Progress): Float {
    if (progress.total <= 0) return 0f
    return (progress.executed.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
}

/** 毫米坐标显示（3 位小数，与 G 代码输出精度一致）。 */
fun formatMm(v: Double): String = String.format(Locale.US, "%.3f", v)

/** 速度显示（整数）。 */
fun formatSpeed(v: Double): String = v.toInt().toString()

/** 当前状态是否为"运行类"（v2 用它决定徽标色）。 */
fun MacStatus.isRunLike(): Boolean = this == MacStatus.Run || this == MacStatus.Jog
