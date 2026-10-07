package com.lasergrbl.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.core.grbl.JogDirection
import com.lasergrbl.glasskit.LiquidTextField
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 运动控制 —— v2 `src/ui/views/JogView.vue`（342 行）的 Compose 版（`/jog`，tab jog）。
 *
 * ### 逐块对应
 * | v2 | 这里 |
 * | --- | --- |
 * | 九宫格点动（`lg-pad`，9 个按钮） | [GlassArrowPad] |
 * | 步长预设 + 自定义输入 + 速度滑块 | 「步长与速度」卡片 |
 * | 绝对移动（X / Y + 「移动」） | 「绝对移动」卡片 |
 * | 覆盖倍率（进给 / 快速 / 功率 + 重置 100%） | 「覆盖倍率」卡片（0x90–0x9D 由 `GrblCore` 发） |
 * | 激光测试（功率 + 时长） | 「激光测试」卡片（`grbl.laserTest`，50–10000 ms 钳制） |
 *
 * ### 与 v2 的差异
 * v2 的九宫格是 CSS grid；Compose 没有 grid，这里用三行 `Row` + `weight` 排列
 * （比 `LazyVerticalGrid` 更省、更稳，九宫格也不需要滚动）。
 */
@Composable
fun JogScreen(container: AppContainer, backdrop: Backdrop) {
    val grbl = container.grbl
    val colors = LiquidTheme.colors

    var stepText by remember { mutableStateOf(grbl.jogStep.toString()) }
    var absX by remember { mutableStateOf("0") }
    var absY by remember { mutableStateOf("0") }
    var laserPower by remember { mutableIntStateOf(200) }
    var laserDuration by remember { mutableIntStateOf(300) }

    GlassScreenBody {
        // ---- 坐标 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    GlassStat("X", formatMm(grbl.pos.X))
                    GlassStat("Y", formatMm(grbl.pos.Y))
                    GlassStat("Z", formatMm(grbl.pos.Z))
                }
                GlassKeyValue("状态", if (grbl.connecting) "连接中" else grbl.status.label())
            }
        }

        // ---- 九宫格 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSectionHeader("点动（步长 ${grbl.jogStep} mm）")
                GlassArrowPad(backdrop, enabled = grbl.connected) { dir -> grbl.jog(dir) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "target",
                        label = "设为零点",
                        onClick = { grbl.setNewZero() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Mini,
                        plain = true,
                        enabled = grbl.connected
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "homeZero",
                        label = "回零",
                        onClick = { grbl.homing() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Mini,
                        plain = true,
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
                        enabled = grbl.connected
                    )
                }
            }
        }

        // ---- 步长与速度 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("步长")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JOG_STEPS.forEach { preset ->
                        GlassButton(
                            backdrop = backdrop,
                            onClick = { grbl.setJogParams(step = preset) },
                            modifier = Modifier.weight(1f),
                            size = GlassButtonSize.Mini,
                            plain = grbl.jogStep != preset,
                            accent = grbl.jogStep == preset
                        ) {
                            GlassButtonLabel("$preset", fontSize = 11.sp)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    GlassText("自定义步长 (mm)", color = colors.textDim, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    LiquidTextField(
                        value = stepText,
                        onValueChange = { stepText = it },
                        backdrop = backdrop,
                        onCommit = {
                            val parsed = it.trim().toDoubleOrNull()
                            if (parsed != null && parsed > 0) {
                                grbl.setJogParams(step = parsed.toInt().coerceAtLeast(1))
                            } else {
                                // 非法值回退（v2 的数值输入契约）
                                stepText = grbl.jogStep.toString()
                            }
                        }
                    )
                }
                GlassSliderRow(
                    backdrop = backdrop,
                    title = "点动速度 (mm/min)",
                    value = grbl.jogSpeed.toFloat(),
                    onValueChange = { grbl.setJogParams(speed = it.toInt()) },
                    valueRange = JOG_SPEED_RANGE,
                    decimals = 0
                )
            }
        }

        // ---- 绝对移动 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSectionHeader("绝对移动")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        GlassText("X (mm)", color = colors.textDim, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        LiquidTextField(
                            value = absX,
                            onValueChange = { absX = it },
                            backdrop = backdrop
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        GlassText("Y (mm)", color = colors.textDim, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        LiquidTextField(
                            value = absY,
                            onValueChange = { absY = it },
                            backdrop = backdrop
                        )
                    }
                }
                GlassIconButton(
                    backdrop = backdrop,
                    icon = "move",
                    label = "移动到指定坐标",
                    onClick = {
                        val x = absX.trim().toDoubleOrNull()
                        val y = absY.trim().toDoubleOrNull()
                        if (x != null && y != null) grbl.moveTo(x, y)
                    },
                    block = true,
                    size = GlassButtonSize.Small,
                    enabled = grbl.connected
                )
            }
        }

        // ---- 覆盖倍率 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("覆盖倍率")
                OverrideRow(backdrop, "进给", grbl.targetOverrides.feed, grbl.connected) {
                    grbl.setTargetOverride(OverrideKind.Feed, it)
                }
                OverrideRow(backdrop, "快速移动", grbl.targetOverrides.rapids, grbl.connected) {
                    grbl.setTargetOverride(OverrideKind.Rapids, it)
                }
                OverrideRow(backdrop, "激光功率", grbl.targetOverrides.power, grbl.connected) {
                    grbl.setTargetOverride(OverrideKind.Power, it)
                }
            }
        }

        // ---- 激光测试 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("激光测试")
                GlassSliderRow(
                    backdrop = backdrop,
                    title = "功率 (S)",
                    value = laserPower.toFloat(),
                    onValueChange = { laserPower = it.toInt() },
                    valueRange = 1f..1000f,
                    decimals = 0
                )
                GlassSliderRow(
                    backdrop = backdrop,
                    title = "时长 (ms)",
                    value = laserDuration.toFloat(),
                    onValueChange = { laserDuration = it.toInt() },
                    valueRange = 50f..10000f,
                    decimals = 0
                )
                GlassIconButton(
                    backdrop = backdrop,
                    icon = "flame",
                    label = if (grbl.connected) "开始测试（${laserDuration} ms）" else "未连接设备",
                    onClick = { grbl.laserTest(laserPower, laserDuration) },
                    block = true,
                    size = GlassButtonSize.Small,
                    enabled = grbl.connected && !grbl.running
                )
                GlassText(
                    "测试会短暂出光，请佩戴护目镜并确认光路安全。时长自动钳到 50–10000 ms。",
                    color = colors.warning,
                    fontSize = 11.5.sp
                )
            }
        }
    }
}

/** 九宫格点动盘。 */
@Composable
private fun GlassArrowPad(backdrop: Backdrop, enabled: Boolean, onJog: (JogDirection) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PadRow(
            backdrop, enabled,
            listOf("↖" to JogDirection.NW, "↑" to JogDirection.N, "↗" to JogDirection.NE),
            onJog
        )
        PadRow(backdrop, enabled, listOf("←" to JogDirection.W, "⌖" to null, "→" to JogDirection.E), onJog)
        PadRow(
            backdrop, enabled,
            listOf("↙" to JogDirection.SW, "↓" to JogDirection.S, "↘" to JogDirection.SE),
            onJog
        )
    }
}

@Composable
private fun PadRow(
    backdrop: Backdrop,
    enabled: Boolean,
    cells: List<Pair<String, JogDirection?>>,
    onJog: (JogDirection) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        cells.forEach { (label, dir) ->
            if (dir == null) {
                // 中心格：v2 是十字准星，这里用同样的符号并禁用
                GlassButton(
                    backdrop = backdrop,
                    onClick = { },
                    modifier = Modifier.weight(1f).height(56.dp),
                    plain = true,
                    enabled = false
                ) {
                    GlassButtonLabel(label, fontSize = 18.sp)
                }
            } else {
                GlassButton(
                    backdrop = backdrop,
                    onClick = { onJog(dir) },
                    modifier = Modifier.weight(1f).height(56.dp),
                    enabled = enabled
                ) {
                    GlassButtonLabel(label, fontSize = 20.sp)
                }
            }
        }
    }
}

/** 覆盖倍率一行：标题 + 当前值 + 「-10」/「-1」/「100%」/「+1」/「+10」。 */
@Composable
private fun OverrideRow(
    backdrop: Backdrop,
    label: String,
    value: Int,
    enabled: Boolean,
    onSet: (Int) -> Unit
) {
    val colors = LiquidTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassText(label, color = colors.text, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
            GlassText("$value%", color = colors.accent, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GlassButton(
                backdrop = backdrop,
                onClick = { onSet((value - 10).coerceAtLeast(MIN_OVERRIDE)) },
                modifier = Modifier.weight(1f),
                size = GlassButtonSize.Mini,
                enabled = enabled
            ) { GlassText("-10", fontSize = 12.sp) }
            GlassButton(
                backdrop = backdrop,
                onClick = { onSet((value - 1).coerceAtLeast(MIN_OVERRIDE)) },
                modifier = Modifier.weight(1f),
                size = GlassButtonSize.Mini,
                enabled = enabled
            ) { GlassText("-1", fontSize = 12.sp) }
            GlassButton(
                backdrop = backdrop,
                onClick = { onSet(100) },
                modifier = Modifier.weight(1f),
                size = GlassButtonSize.Mini,
                enabled = enabled
            ) { GlassText("100%", fontSize = 12.sp) }
            GlassButton(
                backdrop = backdrop,
                onClick = { onSet((value + 1).coerceAtMost(MAX_OVERRIDE)) },
                modifier = Modifier.weight(1f),
                size = GlassButtonSize.Mini,
                enabled = enabled
            ) { GlassText("+1", fontSize = 12.sp) }
            GlassButton(
                backdrop = backdrop,
                onClick = { onSet((value + 10).coerceAtMost(MAX_OVERRIDE)) },
                modifier = Modifier.weight(1f),
                size = GlassButtonSize.Mini,
                enabled = enabled
            ) { GlassText("+10", fontSize = 12.sp) }
        }
    }
}

/** 步长预设（v2 `JogView.vue` 的常用值）。 */
private val JOG_STEPS = listOf(0, 1, 5, 10, 50, 100)

/** 点动速度范围（v2 滑块的范围）。 */
private val JOG_SPEED_RANGE = 10f..10000f

/** 覆盖倍率钳制范围（GRBL 的倍率是百分比，10%–200%）。 */
private const val MIN_OVERRIDE = 10
private const val MAX_OVERRIDE = 200
