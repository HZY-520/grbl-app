package com.lasergrbl.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import com.lasergrbl.core.grbl.DeviceProfiles
import com.lasergrbl.core.grbl.Firmware
import com.lasergrbl.glasskit.LiquidTextField
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 设置 —— v2 `src/ui/views/SettingsView.vue`（421 行）的 Compose 版（`/settings`，tab settings）。
 *
 * ### 分组逐条对应（v2 的 8 个 `lg-title` 分组）
 * | v2 分组 | 这里 |
 * | --- | --- |
 * | 外观 | 主题切换（**3.0 修掉了 v2 不持久化的缺陷**） |
 * | 连接与通讯 | 默认波特率、固件类型、**串口监视**、连接时软复位、线程模式 |
 * | 设备与行程 | 行程 X / Y |
 * | 设备档案 | 内置机型 + 已存档案（套用 / 看到当前激活项） |
 * | 雕刻默认参数 | 雕刻速度、最小/最大功率、激光开/关指令、单向雕刻、禁用 G0 快速空移 |
 * | 程序头 / 尾 | Header / Footer 多行文本框 |
 * | 点动默认参数 | 点动速度、点动步长、连续点动 |
 * | 更多 | 机器参数 / 串口终端 / 关于 的入口 |
 *
 * ### 与 v2 的四处差异
 * 1. **主题持久化**（修 v2 缺陷）：v2 的 `setTheme` 从不写 localStorage，刷新就回深色；
 *    3.0 写 `AppSettings` 的 `Theme` 键。
 * 2. **键名逐字取自 `:core` 的 `DEFAULT_SETTINGS`**（28 项），不是 v2 的 localStorage 键 ——
 *    这样 `:core` 的所有读取点（`GrblCore` 构造时读 4 项、`RasterConverter` 等）都能拿到。
 * 3. `Language` 只读展示：3.0 目前只有中文界面（v2 也是只有 `zh-CN` 一套文案）。
 * 4. v2 的「高性能玻璃」开关没有直接对应物：3.0 用 `Performance Glass` 键控制
 *    `LiquidSurface(refraction = ...)`。**当前 `AppShell`/`GlassCard` 还没有读它** ——
 *    这是一个已知的待接线项，登记在 `docs/PHASE4-PROGRESS.md`。
 */
@Composable
fun SettingsScreen(container: AppContainer, backdrop: Backdrop) {
    val grbl = container.grbl
    val navigator = container.navigator
    val colors = LiquidTheme.colors

    fun setting(key: String): Any? = grbl.settings[key]

    fun set(key: String, value: Any?) = grbl.updateSetting(key, value)

    GlassScreenBody {
        // ---- 外观 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("外观")
                GlassSwitchRow(
                    backdrop = backdrop,
                    title = "浅色主题",
                    checked = grbl.themeIsLight,
                    onCheckedChange = { grbl.setTheme(it) },
                    description = "3.0 起会持久化保存（v2 刷新后会回到深色）"
                )
                GlassSwitchRow(
                    backdrop = backdrop,
                    title = "高性能玻璃",
                    checked = setting("Performance Glass") as? Boolean ?: true,
                    onCheckedChange = { set("Performance Glass", it) },
                    description = "关闭后减少折射计算（低端机更流畅）"
                )
            }
        }

        // ---- 连接与通讯 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("连接与通讯")
                GlassSwitchRow(
                    backdrop = backdrop,
                    title = "连接时软复位",
                    checked = setting("Reset Grbl On Connect") as? Boolean ?: true,
                    onCheckedChange = { set("Reset Grbl On Connect", it) },
                    description = "对应 v2 的「Reset Grbl On Connect」"
                )
                GlassSwitchRow(
                    backdrop = backdrop,
                    title = "串口监视",
                    checked = grbl.serialMonitor,
                    onCheckedChange = { set("Serial Monitor", it) },
                    description = "在日志里显示原始收发字节"
                )

                GlassText("默认波特率", color = colors.textDim, fontSize = 12.sp)
                BAUD_RATES.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { rate ->
                            GlassButton(
                                backdrop = backdrop,
                                onClick = { grbl.updateBaud(rate) },
                                modifier = Modifier.weight(1f),
                                size = GlassButtonSize.Mini,
                                plain = grbl.baud != rate,
                                accent = grbl.baud == rate
                            ) {
                                GlassButtonLabel(formatBaud(rate), fontSize = 11.sp)
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                GlassText("固件类型", color = colors.textDim, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FIRMWARES.forEach { (value, label) ->
                        val current = setting("Firmware Type")?.toString() ?: Firmware.Grbl.value
                        GlassButton(
                            backdrop = backdrop,
                            onClick = { set("Firmware Type", value) },
                            modifier = Modifier.weight(1f),
                            size = GlassButtonSize.Mini,
                            plain = current != value,
                            accent = current == value
                        ) {
                            GlassButtonLabel(label, fontSize = 10.5.sp)
                        }
                    }
                }

                GlassText("通讯速度模式", color = colors.textDim, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    THREADING_MODES.forEach { mode ->
                        val current = setting("Threading Mode")?.toString() ?: "Fast"
                        GlassButton(
                            backdrop = backdrop,
                            onClick = { set("Threading Mode", mode) },
                            modifier = Modifier.weight(1f),
                            size = GlassButtonSize.Mini,
                            plain = current != mode,
                            accent = current == mode
                        ) {
                            GlassButtonLabel(mode, fontSize = 11.sp)
                        }
                    }
                }
                GlassText(
                    "状态查询间隔：Insane 200ms / UltraFast 250ms / Fast 500ms / Quiet 1s / Slow 2s",
                    color = colors.textFaint,
                    fontSize = 11.sp
                )
            }
        }

        // ---- 设备与行程 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("设备与行程")
                NumberSettingRow(
                    backdrop = backdrop,
                    label = "行程 X (mm)",
                    key = "Travel X",
                    value = (setting("Travel X") as? Number)?.toDouble() ?: 300.0,
                    onSet = { set("Travel X", it) }
                )
                NumberSettingRow(
                    backdrop = backdrop,
                    label = "行程 Y (mm)",
                    key = "Travel Y",
                    value = (setting("Travel Y") as? Number)?.toDouble() ?: 200.0,
                    onSet = { set("Travel Y", it) }
                )
            }
        }

        // ---- 设备档案 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("设备档案")
                val builtins = remember { DeviceProfiles.builtinProfiles() }
                val saved = remember(grbl.settings) { DeviceProfiles.listSavedProfiles() }
                GlassText("内置机型", color = colors.textDim, fontSize = 12.sp)
                builtins.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            GlassText(p.name, color = colors.text, fontSize = 13.5.sp)
                            GlassText(
                                "${p.travelX.toInt()} × ${p.travelY.toInt()} mm · ${p.firmware}",
                                color = colors.textFaint,
                                fontSize = 11.sp
                            )
                        }
                        GlassButton(
                            backdrop = backdrop,
                            onClick = {
                                DeviceProfiles.applyProfileToSettings(p)
                                grbl.reloadSettings()
                                container.toasts.show("已套用「${p.name}」")
                            },
                            size = GlassButtonSize.Mini,
                            accent = true
                        ) {
                            GlassText("套用", fontSize = 12.sp)
                        }
                    }
                }
                if (saved.isNotEmpty()) {
                    GlassText("已存档案", color = colors.textDim, fontSize = 12.sp)
                    saved.forEach { p ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                GlassText(p.name, color = colors.text, fontSize = 13.5.sp)
                                GlassText(
                                    "设备 #${p.deviceId?.toInt() ?: 0} · ${p.travelX.toInt()} × ${p.travelY.toInt()} mm",
                                    color = colors.textFaint,
                                    fontSize = 11.sp
                                )
                            }
                            GlassButton(
                                backdrop = backdrop,
                                onClick = {
                                    DeviceProfiles.applyProfileToSettings(p)
                                    grbl.reloadSettings()
                                    container.toasts.show("已套用「${p.name}」")
                                },
                                size = GlassButtonSize.Mini
                            ) {
                                GlassText("套用", fontSize = 12.sp)
                            }
                        }
                    }
                }
                GlassText(
                    "参数档案把「行程 / 功率 / 速度」按机型存起来；连接已绑定档案的设备时会自动套用。",
                    color = colors.textFaint,
                    fontSize = 11.sp
                )
            }
        }

        // ---- 雕刻默认参数 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("雕刻默认参数")
                NumberSettingRow(backdrop, "雕刻速度 (mm/min)", "Mark Speed", setting("Mark Speed"), { set("Mark Speed", it.toInt()) })
                NumberSettingRow(backdrop, "空移速度 (mm/min)", "Border Speed", setting("Border Speed"), { set("Border Speed", it.toInt()) })
                NumberSettingRow(backdrop, "最小功率 (S)", "Min Power", setting("Min Power"), { set("Min Power", it.toInt()) })
                NumberSettingRow(backdrop, "最大功率 (S)", "Max Power", setting("Max Power"), { set("Max Power", it.toInt()) })
                TextSettingRow(backdrop, "激光开启指令", "Laser On Command", setting("Laser On Command"), { set("Laser On Command", it) })
                TextSettingRow(backdrop, "激光关闭指令", "Laser Off Command", setting("Laser Off Command"), { set("Laser Off Command", it) })
                GlassSwitchRow(
                    backdrop = backdrop,
                    title = "硬件 PWM（S 值渐变）",
                    checked = setting("Support Hardware PWM") as? Boolean ?: true,
                    onCheckedChange = { set("Support Hardware PWM", it) }
                )
                GlassSwitchRow(
                    backdrop = backdrop,
                    title = "单向雕刻",
                    checked = setting("Unidirectional Engraving") as? Boolean ?: false,
                    onCheckedChange = { set("Unidirectional Engraving", it) }
                )
                GlassSwitchRow(
                    backdrop = backdrop,
                    title = "禁用 G0 快速空移",
                    checked = setting("Disable G0 fast skip") as? Boolean ?: false,
                    onCheckedChange = { set("Disable G0 fast skip", it) }
                )
            }
        }

        // ---- 程序头 / 尾 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("程序头 / 尾")
                MultiLineSettingRow(backdrop, "程序头", "Header", setting("Header"), { set("Header", it) })
                MultiLineSettingRow(backdrop, "程序尾", "Footer", setting("Footer"), { set("Footer", it) })
            }
        }

        // ---- 点动默认参数 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("点动默认参数")
                NumberSettingRow(backdrop, "点动速度 (mm/min)", "Jog Speed", setting("Jog Speed"), { set("Jog Speed", it.toInt()) })
                NumberSettingRow(backdrop, "点动步长 (mm)", "Jog Step", setting("Jog Step"), { set("Jog Step", it.toInt()) })
                GlassSwitchRow(
                    backdrop = backdrop,
                    title = "连续点动",
                    checked = setting("Enable Continuous Jog") as? Boolean ?: false,
                    onCheckedChange = { set("Enable Continuous Jog", it) }
                )
                GlassSwitchRow(
                    backdrop = backdrop,
                    title = "连接后自动回零",
                    checked = setting("Auto Home On Connect") as? Boolean ?: false,
                    onCheckedChange = { set("Auto Home On Connect", it) }
                )
            }
        }

        // ---- 更多 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSectionHeader("更多")
                GlassKeyValue("界面语言", setting("Language")?.toString() ?: "zh-CN")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 三列并排：**不显示图标** —— 图标 + 4 个汉字在 1/3 屏宽里放不下，
                    // 实测被省略成「机器…」「串口…」。去掉图标后标签完整可读。
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "config",
                        label = "机器参数",
                        onClick = { navigator.push(Screen.Config) },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        plain = true,
                        showIcon = false
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "terminal",
                        label = "串口终端",
                        onClick = { navigator.push(Screen.Terminal) },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        plain = true,
                        showIcon = false
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "info",
                        label = "关于",
                        onClick = { navigator.push(Screen.About) },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        plain = true,
                        showIcon = false
                    )
                }
                GlassIconButton(
                    backdrop = backdrop,
                    icon = "settings",
                    label = "重新运行初始化向导",
                    onClick = { grbl.openSetupWizard(grbl.setupDeviceId) },
                    block = true,
                    size = GlassButtonSize.Small
                )
            }
        }
    }
}

// ===================== 设置行的小组件 =====================

/** 数值设置行：提交时解析，非法值回退原值（v2 的数值输入契约）。 */
@Composable
private fun NumberSettingRow(
    backdrop: Backdrop,
    label: String,
    key: String,
    value: Any?,
    onSet: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LiquidTheme.colors
    val current = (value as? Number)?.toDouble() ?: 0.0
    var text by remember(key, current) {
        mutableStateOf(if (current == current.toLong().toDouble()) current.toInt().toString() else current.toString())
    }
    Column(modifier.fillMaxWidth()) {
        GlassText(label, color = colors.textDim, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        LiquidTextField(
            value = text,
            onValueChange = { text = it },
            backdrop = backdrop,
            onCommit = {
                val parsed = it.trim().toDoubleOrNull()
                if (parsed != null && parsed.isFinite()) {
                    onSet(parsed)
                } else {
                    // 非法值回退到当前设置值（v2 的输入契约）
                    text = if (current == current.toLong().toDouble()) {
                        current.toInt().toString()
                    } else {
                        current.toString()
                    }
                }
            }
        )
    }
}

/** 单行文本设置行（指令类）。 */
@Composable
private fun TextSettingRow(
    backdrop: Backdrop,
    label: String,
    key: String,
    value: Any?,
    onSet: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LiquidTheme.colors
    var text by remember(key, value) { mutableStateOf(value?.toString() ?: "") }
    Column(modifier.fillMaxWidth()) {
        GlassText(label, color = colors.textDim, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        LiquidTextField(
            value = text,
            onValueChange = { text = it },
            backdrop = backdrop,
            onCommit = { if (it.isNotBlank()) onSet(it.trim()) }
        )
    }
}

/** 多行文本设置行（程序头 / 尾）。 */
@Composable
private fun MultiLineSettingRow(
    backdrop: Backdrop,
    label: String,
    key: String,
    value: Any?,
    onSet: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LiquidTheme.colors
    var text by remember(key, value) { mutableStateOf(value?.toString() ?: "") }
    Column(modifier.fillMaxWidth()) {
        GlassText(label, color = colors.textDim, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        LiquidTextField(
            value = text,
            onValueChange = { text = it },
            backdrop = backdrop,
            singleLine = false,
            minLines = 3,
            onCommit = { onSet(it) }
        )
        Spacer(Modifier.height(4.dp))
        GlassText(
            "每行一条命令，空行会被跳过（与 v2 的处理一致）",
            color = colors.textFaint,
            fontSize = 11.sp
        )
    }
}

/** 波特率档位（与连接页共用同一组值）。 */
private val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400, 250000)

/** 固件选项。 */
private val FIRMWARES = listOf(
    Firmware.Grbl.value to "GRBL",
    Firmware.Smoothie.value to "Smoothie",
    Firmware.Marlin.value to "Marlin",
    Firmware.Vigo.value to "Vigo"
)

/** 通讯速度模式（与 `:core` 的 `ThreadingMode.all` 同序）。 */
private val THREADING_MODES = listOf("Slow", "Quiet", "Fast", "UltraFast", "Insane")
