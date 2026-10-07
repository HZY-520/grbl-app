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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.core.grbl.parseSettingsPreset
import com.lasergrbl.glasskit.LiquidAlert
import com.lasergrbl.glasskit.LiquidAlertType
import com.lasergrbl.glasskit.LiquidTextField
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 机器参数 —— v2 `src/ui/views/ConfigView.vue`（303 行）的 Compose 版（`/config`）。
 *
 * ### 逐块对应
 * | v2 | 这里 |
 * | --- | --- |
 * | 「读取设置」按钮（发 `$$`） | 顶部操作行 |
 * | 参数列表（`$NUM` + 当前值 + 中文说明） | [ConfigEntryRow] 列表（按编号升序） |
 * | 单条写入（输入新值 → 写 `$NUM=VAL`） | 行内输入 + 「写入」 |
 * | `.nc` 预设导入（解析 `$NUM=VAL` 行） | 「导入预设」按钮（[parseSettingsPreset]）+ 批量写入 |
 * | 未连接时的空状态 | `GlassEmpty` |
 *
 * ### 与 v2 的三处差异
 * 1. **`configRev` 驱动重载**：v2 用 `watch(() => state.configRev)`；这里 `LaunchedEffect(grbl.configRev)`
 *    重新快照一次参数表（`:core` 的 `GrblConfiguration` 是可变对象，Compose 看不到它的内部变化）。
 * 2. **预设导入是"逐条写入"而不是"一次性"**：GRBL 没有批量写接口，v2 也是一条条发；
 *    3.0 一样，但把"跳过/无效行数"也报给用户（`PresetParseResult.skipped`）。
 * 3. **中文说明取自 `:core`**：`grbl.settingInfo(id)` 返回的说明来自 CsvData 的设置表
 *    （与 v2 同一份数据），所以文案天然一致。
 */
@Composable
fun ConfigScreen(container: AppContainer, backdrop: Backdrop) {
    val grbl = container.grbl
    val colors = LiquidTheme.colors

    // `:core` 的 GrblConfiguration 是可变对象，Compose 观察不到它内部变化 ——
    // 所以用 configRev 做 key 重新快照一次
    var entries by remember(grbl.configRev) { mutableStateOf(grbl.configEntries()) }
    var pendingImport by remember { mutableStateOf<Int?>(null) }

    // 导入 .nc 预设：解析后逐条写入
    val picker = rememberGcodeFilePicker(container.context) { name, text ->
        val parsed = parseSettingsPreset(text)
        if (parsed.entries.isEmpty()) {
            container.toasts.show("「$name」里没有可用的 \$\$ 参数行")
        } else {
            pendingImport = parsed.entries.size
            parsed.entries.forEach { e -> grbl.writeMachineSetting(e.id, e.value) }
            container.toasts.show(
                "已从「$name」写入 ${parsed.entries.size} 条参数" +
                    if (parsed.skipped > 0) "（跳过 ${parsed.skipped} 行）" else ""
            )
            entries = grbl.configEntries()
        }
    }

    // 进入页面自动读一次（已连接时）
    LaunchedEffect(grbl.connected) {
        if (grbl.connected) grbl.readMachineConfig()
    }

    GlassScreenBody {
        // ---- 操作 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("机器参数（\$\$）")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "refresh",
                        label = "读取设置",
                        onClick = {
                            grbl.readMachineConfig()
                            entries = grbl.configEntries()
                        },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        accent = true,
                        enabled = grbl.connected
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "download",
                        label = "导入预设",
                        onClick = { picker.launch() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        plain = true,
                        enabled = grbl.connected
                    )
                }
                GlassText(
                    "导入的 .nc 文件里 `\$数字=值` 形式的行会被逐条写入固件；其余行会被跳过。",
                    color = colors.textFaint,
                    fontSize = 11.sp
                )
                if (!grbl.connected) {
                    LiquidAlert(
                        title = "未连接设备：读取与写入都需要先连接 GRBL。",
                        type = LiquidAlertType.Warning
                    )
                }
            }
        }

        // ---- 参数列表 ----
        GlassCard(backdrop, padding = PaddingValues(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSectionHeader("当前参数（${entries.size} 项）")
                if (entries.isEmpty()) {
                    GlassEmpty(if (grbl.connected) "尚未读取，点上方「读取设置」" else "未连接设备")
                } else {
                    entries.forEach { (id, value) ->
                        ConfigEntryRow(
                            backdrop = backdrop,
                            id = id,
                            value = value,
                            description = grbl.settingInfo(id).firstOrNull(),
                            enabled = grbl.connected,
                            onWrite = { newValue ->
                                grbl.writeMachineSetting(id, newValue)
                                entries = grbl.configEntries()
                                container.toasts.show("已写入 \$$id=$newValue")
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单条参数行：`$编号` + 中文说明 + 当前值 + 可编辑输入 + 「写入」。
 *
 * 数值输入契约与其它屏一致：提交时解析，非法值**回退**（不写入固件）。
 */
@Composable
private fun ConfigEntryRow(
    backdrop: Backdrop,
    id: Int,
    value: Double,
    description: String?,
    enabled: Boolean,
    onWrite: (Double) -> Unit
) {
    val colors = LiquidTheme.colors
    var text by remember(id, value) {
        mutableStateOf(if (value == value.toLong().toDouble()) value.toInt().toString() else value.toString())
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassText(
                "\$$id",
                color = colors.accent,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                if (description != null && description.isNotEmpty()) {
                    GlassText(description, color = colors.textDim, fontSize = 11.5.sp, maxLines = 2)
                }
            }
            GlassText(
                formatConfigNumber(value),
                color = colors.text,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiquidTextField(
                value = text,
                onValueChange = { text = it },
                backdrop = backdrop,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onCommit = {
                    val parsed = it.trim().toDoubleOrNull()
                    if (parsed != null && parsed.isFinite()) {
                        onWrite(parsed)
                    } else {
                        // 非法值回退（不写固件）
                        text = if (value == value.toLong().toDouble()) {
                            value.toInt().toString()
                        } else {
                            value.toString()
                        }
                    }
                }
            )
            GlassButton(
                backdrop = backdrop,
                onClick = {
                    val parsed = text.trim().toDoubleOrNull()
                    if (parsed != null && parsed.isFinite()) {
                        onWrite(parsed)
                    } else {
                        text = formatConfigNumber(value)
                    }
                },
                size = GlassButtonSize.Mini,
                enabled = enabled
            ) {
                GlassText("写入", fontSize = 12.sp)
            }
        }
    }
}

/** 参数值显示：整数不带小数点（GRBL 的参数大多是整数）。 */
private fun formatConfigNumber(v: Double): String =
    if (v == v.toLong().toDouble()) v.toInt().toString() else v.toString()
