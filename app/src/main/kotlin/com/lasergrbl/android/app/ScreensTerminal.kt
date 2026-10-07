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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.LiquidTextField
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 串口终端 —— v2 `src/ui/views/TerminalView.vue`（135 行）的 Compose 版（`/terminal`）。
 *
 * ### 逐块对应
 * | v2 | 这里 |
 * | --- | --- |
 * | `LogList`（日志上限同上，倒序自动滚底） | [GlassLogList]（`showTime = true`） |
 * | 命令输入 + Enter 发送 | [LiquidTextField] 的 `onCommit` |
 * | 6 个快捷命令按钮 | [QUICK_COMMANDS]（v2 的 `quick` 数组，逐字） |
 * | 「发送」按钮（未连接时禁用） | 底部按钮行 |
 * | 「清空日志」 | 标题右侧的小按钮 |
 *
 * ### 与 v2 的差异
 * v2 的输入框在 `keyup.enter` 里发送并**不**清空输入（方便重复发同一条）；3.0 沿用同一行为，
 * 但把输入框内容放进 `remember` 以便切换页面后保留。
 */
@Composable
fun TerminalScreen(container: AppContainer, backdrop: Backdrop) {
    val grbl = container.grbl
    val colors = LiquidTheme.colors
    var command by remember { mutableStateOf("") }

    GlassScreenBody {
        // ---- 日志 ----
        GlassCard(backdrop, padding = PaddingValues(10.dp)) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassSectionHeader("串口日志", Modifier.weight(1f))
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
                GlassLogList(log = grbl.log, showTime = true, maxLines = 14)
            }
        }

        // ---- 命令输入 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSectionHeader("发送命令")
                LiquidTextField(
                    value = command,
                    onValueChange = { command = it },
                    backdrop = backdrop,
                    placeholder = "输入 G 代码或 GRBL 命令后回车",
                    enabled = grbl.connected,
                    onCommit = {
                        if (it.isNotBlank()) grbl.sendCommand(it)
                        // 与 v2 一致：发送后不清空，便于重复发送
                    }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "forward",
                        label = "发送",
                        onClick = { if (command.isNotBlank()) grbl.sendCommand(command) },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        accent = true,
                        enabled = grbl.connected
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "close",
                        label = "清空输入",
                        onClick = { command = "" },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        plain = true,
                        enabled = command.isNotEmpty()
                    )
                }
                if (!grbl.connected) {
                    GlassText("未连接设备：命令只会排队，不会发出。", color = colors.warning, fontSize = 11.5.sp)
                }
            }
        }

        // ---- 快捷命令 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSectionHeader("快捷命令")
                QUICK_COMMANDS.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, cmd) ->
                            GlassButton(
                                backdrop = backdrop,
                                onClick = { grbl.sendCommand(cmd) },
                                modifier = Modifier.weight(1f),
                                size = GlassButtonSize.Small,
                                plain = true,
                                enabled = grbl.connected
                            ) {
                                GlassButtonLabel(label, fontSize = 12.sp)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        // ---- 实时控制（v2 的终端页没有，但这是最常用的三件事，放这里比去点动页快） ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSectionHeader("实时控制")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "pause",
                        label = "进给保持",
                        onClick = { grbl.feedHold() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        enabled = grbl.connected
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "play",
                        label = "循环启动",
                        onClick = { grbl.cycleStart() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        enabled = grbl.connected
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "power",
                        label = "软复位",
                        onClick = { grbl.softReset() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        enabled = grbl.connected
                    )
                }
            }
        }
    }
}

/**
 * 6 个快捷命令（v2 `TerminalView.vue` 的 `quick` 数组，**逐字**）。
 *
 * ⚠️ `?`（状态查询）与 `$X`（解锁）这类**实时字节**，v2 也是当普通行发出去的
 * （`send(cmd)` 走 `enqueueRaw`）。3.0 一样 —— 与 `GrblCore.sendImmediate` 的路径不同，
 * 但 GRBL 对两者都接受（`?` 后面有没有换行都识别）。
 */
private val QUICK_COMMANDS = listOf(
    "读取设置 \$\$" to "\$\$",
    "版本 \$I" to "\$I",
    "坐标系 \$#" to "\$#",
    "回原点 \$H" to "\$H",
    "解锁 \$X" to "\$X",
    "状态报告 ?" to "?"
)
