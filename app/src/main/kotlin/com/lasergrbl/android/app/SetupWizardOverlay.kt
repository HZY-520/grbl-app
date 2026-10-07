package com.lasergrbl.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.core.grbl.DeviceProfile
import com.lasergrbl.core.grbl.DeviceProfiles
import com.lasergrbl.core.grbl.Firmware
import com.lasergrbl.core.grbl.MessageType
import com.lasergrbl.glasskit.LiquidAlert
import com.lasergrbl.glasskit.LiquidAlertType
import com.lasergrbl.glasskit.LiquidSurface
import com.lasergrbl.glasskit.LiquidTextField
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 首次设置向导（4 步）—— v2 `src/ui/components/SetupWizard.vue`（505 行）的 Compose 版。
 *
 * v2 是 `App.vue` 里的全局浮层，按 `state.needsSetup` 挂载/卸载；3.0 一样，
 * 由 [AppShell] 放进 `LiquidScaffold` 的 **overlay 槽**（同窗口，玻璃才有背后内容可折射）。
 *
 * ### 四步（与 v2 的 `STEPS` 逐字一致）
 * | # | 标题 | 内容 |
 * | --- | --- | --- |
 * | 0 | 基本信息 | 设备名称、固件类型、波特率 |
 * | 1 | 行程范围 | 行程 X / Y、常用机型预设（`builtinProfiles()`） |
 * | 2 | 激光与测试 | 功率范围（S 值）、测试激光（功率 / 时长）、护目镜警告 |
 * | 3 | 雕刻参数 | 默认雕刻速度、走线质量、确认信息汇总 |
 *
 * ### 与 v2 的差异（都写清楚，不留隐患）
 * 1. v2 用 `reactive({...})` 承载表单，这里逐字段 `mutableStateOf`（语义相同）。
 * 2. v2 的步骤指示是 `span` 数组；这里用一排玻璃小方块（`refraction = false`，见 Phase 1 结论：
 *    同屏玻璃面数量要收敛）。
 * 3. 完成时除了写 `AppSettings`，还调 `DeviceProfiles.markDeviceKnown` + `saveProfile` + `setSetupDone` ——
 *    与 v2 的 `knownDevices` / `setupDone` 键语义一致，但走的是 `:core` 的正式 API。
 */
@Composable
fun SetupWizardOverlay(container: AppContainer, backdrop: Backdrop) {
    val grbl = container.grbl
    val colors = LiquidTheme.colors

    // ---- 表单初值：绑定档案优先，否则取 AppSettings 的当前值（v2 `initial()`） ----
    val bound = remember(grbl.setupDeviceId) {
        if (grbl.setupDeviceId >= 0) {
            DeviceProfiles.findProfileByDevice(grbl.setupDeviceId.toDouble())
        } else {
            null
        }
    }
    fun settingDouble(key: String, fallback: Double): Double =
        (grbl.settings[key] as? Number)?.toDouble() ?: fallback

    var name by remember {
        mutableStateOf(
            bound?.name
                ?: if (grbl.setupDeviceId >= 0) "雕刻机 #${grbl.setupDeviceId}" else "我的雕刻机"
        )
    }
    var firmware by remember {
        mutableStateOf(bound?.firmware ?: grbl.settings["Firmware Type"]?.toString() ?: Firmware.Grbl.value)
    }
    var baud by remember {
        mutableIntStateOf((bound?.baud ?: settingDouble("Last Baud", 115200.0)).toInt())
    }
    var travelX by remember { mutableStateOf(bound?.travelX ?: settingDouble("Travel X", 300.0)) }
    var travelY by remember { mutableStateOf(bound?.travelY ?: settingDouble("Travel Y", 200.0)) }
    var maxPower by remember { mutableStateOf(bound?.maxPower ?: settingDouble("Max Power", 1000.0)) }
    var minPower by remember { mutableStateOf(bound?.minPower ?: settingDouble("Min Power", 0.0)) }
    var markSpeed by remember { mutableStateOf(bound?.markSpeed ?: settingDouble("Mark Speed", 1000.0)) }
    var quality by remember { mutableStateOf(bound?.quality ?: settingDouble("Quality", 3.0)) }
    var testPower by remember {
        mutableStateOf(settingDouble("Test Laser Power", minOf(200.0, maxPower)))
    }
    var testDuration by remember {
        mutableIntStateOf(settingDouble("Test Laser Duration", 300.0).toInt())
    }

    var step by remember { mutableIntStateOf(0) }
    val isLast = step == STEPS.size - 1

    Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
        LiquidSurface(
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
            shape = RoundedCornerShape(LiquidTheme.dimens.cardRadius),
            // 模态面**必须**用 modalSurface（≈85% 不透明）：用卡片的 fill1 会让背后文字透上来
            // —— Phase 1 的架构结论（§12 Phase 1 第 2 条）
            surfaceColor = colors.modalSurface,
            contentPadding = PaddingValues(14.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                // ---- 头部 ----
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassText(
                        "设备初始化向导",
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "close",
                        label = "",
                        onClick = { grbl.clearNeedsSetup() },
                        size = GlassButtonSize.Mini,
                        plain = true
                    )
                }
                Spacer(Modifier.height(10.dp))

                // ---- 步骤指示 ----
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    STEPS.forEachIndexed { i, _ ->
                        val done = i < step
                        val active = i == step
                        Box(
                            Modifier
                                .weight(1f)
                                .height(26.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            LiquidSurface(
                                backdrop = backdrop,
                                modifier = Modifier.fillMaxWidth().height(26.dp),
                                shape = RoundedCornerShape(8.dp),
                                // 同屏玻璃面数量收敛：指示条不做折射（Phase 1 结论）
                                refraction = false,
                                surfaceColor = when {
                                    active -> colors.accentSoft
                                    done -> colors.fill2
                                    else -> colors.fill1
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    GlassText(
                                        "${i + 1}",
                                        color = if (active || done) colors.accent else colors.textFaint,
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.SemiBold else null
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                GlassText(
                    "${step + 1} / ${STEPS.size} · ${STEPS[step]}",
                    color = colors.textDim,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))

                // ---- 主体（可滚动） ----
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (step) {
                        0 -> StepBasic(backdrop, name, { name = it }, firmware, { firmware = it }, baud) {
                            baud = it
                        }

                        1 -> StepTravel(backdrop, travelX, { travelX = it }, travelY, { travelY = it }) { p ->
                            name = p.name
                            firmware = p.firmware
                            baud = p.baud.toInt()
                            travelX = p.travelX
                            travelY = p.travelY
                            maxPower = p.maxPower
                            minPower = p.minPower
                            markSpeed = p.markSpeed
                            quality = p.quality
                        }

                        2 -> StepLaser(
                            backdrop = backdrop,
                            minPower = minPower,
                            onMinPower = { minPower = it },
                            maxPower = maxPower,
                            onMaxPower = { maxPower = it },
                            testPower = testPower,
                            onTestPower = { testPower = it },
                            testDuration = testDuration,
                            onTestDuration = { testDuration = it },
                            connected = grbl.connected,
                            onTest = { grbl.laserTest(testPower.toInt(), testDuration) }
                        )

                        else -> StepSummary(
                            rows = listOf(
                                "设备名称" to name,
                                "固件 / 波特率" to "$firmware · $baud",
                                "行程 X × Y" to "${travelX.toInt()} × ${travelY.toInt()} mm",
                                "功率范围" to "${minPower.toInt()} – ${maxPower.toInt()}",
                                "雕刻速度" to "${markSpeed.toInt()} mm/min",
                                "走线质量" to "$quality 线/mm"
                            )
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ---- 底部固定操作条 ----
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step > 0) {
                        GlassIconButton(
                            backdrop = backdrop,
                            icon = "back",
                            label = "上一步",
                            onClick = { step-- },
                            modifier = Modifier.weight(1f),
                            size = GlassButtonSize.Small,
                            plain = true
                        )
                    }
                    if (!isLast) {
                        GlassIconButton(
                            backdrop = backdrop,
                            icon = "forward",
                            label = "下一步",
                            onClick = { step++ },
                            modifier = Modifier.weight(1f),
                            size = GlassButtonSize.Small,
                            accent = true
                        )
                    } else {
                        GlassIconButton(
                            backdrop = backdrop,
                            icon = "check",
                            label = "完成",
                            onClick = {
                                applyWizardResult(
                                    container = container,
                                    name = name,
                                    firmware = firmware,
                                    baud = baud,
                                    travelX = travelX,
                                    travelY = travelY,
                                    maxPower = maxPower,
                                    minPower = minPower,
                                    markSpeed = markSpeed,
                                    quality = quality,
                                    testPower = testPower,
                                    testDuration = testDuration
                                )
                            },
                            modifier = Modifier.weight(1f),
                            size = GlassButtonSize.Small,
                            accent = true
                        )
                    }
                }
            }
        }
    }
}

// ===================== 各步骤 =====================

@Composable
private fun StepBasic(
    backdrop: Backdrop,
    name: String,
    onName: (String) -> Unit,
    firmware: String,
    onFirmware: (String) -> Unit,
    baud: Int,
    onBaud: (Int) -> Unit
) {
    GlassSectionHeader("设备名称")
    LiquidTextField(
        value = name,
        onValueChange = onName,
        backdrop = backdrop,
        placeholder = "给设备起个名字"
    )

    GlassSectionHeader("固件类型")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FIRMWARES.forEach { (value, label) ->
            GlassButton(
                backdrop = backdrop,
                onClick = { onFirmware(value) },
                modifier = Modifier.weight(1f),
                size = GlassButtonSize.Mini,
                plain = firmware != value,
                accent = firmware == value
            ) {
                GlassButtonLabel(label, fontSize = 11.sp)
            }
        }
    }

    GlassSectionHeader("波特率")
    BAUD_RATES.chunked(4).forEach { rowRates ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rowRates.forEach { rate ->
                GlassButton(
                    backdrop = backdrop,
                    onClick = { onBaud(rate) },
                    modifier = Modifier.weight(1f),
                    size = GlassButtonSize.Mini,
                    plain = baud != rate,
                    accent = baud == rate
                ) {
                    GlassButtonLabel(formatBaud(rate), fontSize = 10.5.sp)
                }
            }
            // 补齐空位，避免最后一行被拉伸
            repeat(4 - rowRates.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun StepTravel(
    backdrop: Backdrop,
    travelX: Double,
    onTravelX: (Double) -> Unit,
    travelY: Double,
    onTravelY: (Double) -> Unit,
    onPreset: (DeviceProfile) -> Unit
) {
    GlassSectionHeader("行程范围（工作区域）")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("行程 X (mm)", travelX, Modifier.weight(1f), backdrop, onTravelX)
        NumberField("行程 Y (mm)", travelY, Modifier.weight(1f), backdrop, onTravelY)
    }

    GlassSectionHeader("常用机型预设")
    val presets = remember { DeviceProfiles.builtinProfiles() }
    presets.forEach { p ->
        GlassCard(backdrop, padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    GlassText(p.name, color = LiquidTheme.colors.text, fontSize = 14.sp)
                    GlassText(
                        "${p.travelX.toInt()} × ${p.travelY.toInt()} mm · ${p.firmware}",
                        color = LiquidTheme.colors.textFaint,
                        fontSize = 11.5.sp
                    )
                }
                GlassButton(
                    backdrop = backdrop,
                    onClick = { onPreset(p) },
                    size = GlassButtonSize.Mini,
                    accent = true
                ) {
                    GlassText("套用", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StepLaser(
    backdrop: Backdrop,
    minPower: Double,
    onMinPower: (Double) -> Unit,
    maxPower: Double,
    onMaxPower: (Double) -> Unit,
    testPower: Double,
    onTestPower: (Double) -> Unit,
    testDuration: Int,
    onTestDuration: (Int) -> Unit,
    connected: Boolean,
    onTest: () -> Unit
) {
    GlassSectionHeader("激光功率范围（S 值）")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("最小功率", minPower, Modifier.weight(1f), backdrop, onMinPower)
        NumberField("最大功率", maxPower, Modifier.weight(1f), backdrop, onMaxPower)
    }

    GlassSectionHeader("测试激光")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("测试功率", testPower, Modifier.weight(1f), backdrop, onTestPower)
        NumberField("时长 (ms)", testDuration.toDouble(), Modifier.weight(1f), backdrop) {
            onTestDuration(it.toInt())
        }
    }
    GlassButton(
        backdrop = backdrop,
        onClick = onTest,
        block = true,
        size = GlassButtonSize.Small,
        enabled = connected
    ) {
        GlassText(
            if (connected) "测试激光（${testDuration} ms）" else "未连接设备，无法测试",
            fontSize = 13.sp
        )
    }
    LiquidAlert(
        title = "测试激光会短暂出光，请佩戴护目镜并确认光路安全。",
        type = LiquidAlertType.Warning
    )
}

@Composable
private fun StepSummary(rows: List<Pair<String, String>>) {
    GlassSectionHeader("确认信息")
    rows.forEach { (k, v) -> GlassKeyValue(k, v, mono = true) }
}

// ===================== 小组件 =====================

/** 数字输入：提交时解析，非法值**回退原值**（v2 的数值输入契约，见 §7）。 */
@Composable
private fun NumberField(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
    backdrop: Backdrop,
    onValue: (Double) -> Unit
) {
    Column(modifier) {
        GlassText(label, color = LiquidTheme.colors.textDim, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        LiquidTextField(
            value = if (value == value.toLong().toDouble()) value.toInt().toString() else value.toString(),
            onValueChange = { },
            backdrop = backdrop,
            onCommit = { text ->
                val parsed = text.trim().toDoubleOrNull()
                if (parsed != null && parsed.isFinite()) onValue(parsed)
                // 非法值：不改动 —— LiquidTextField 的 draft 机制会在失焦时显示回原值
            }
        )
    }
}

// ===================== 完成时写设置 =====================

/**
 * 完成向导：把结果写进 `AppSettings`，并标记设备已初始化 + 存档档案。
 *
 * v2 的对应逻辑在 `SetupWizard.vue` 的 `finish()`（写 `AppSettings` + `saveKnownDevice`）；
 * 3.0 走 `:core` 的正式 API（`markDeviceKnown` / `saveProfile` / `setSetupDone`），语义一致。
 */
private fun applyWizardResult(
    container: AppContainer,
    name: String,
    firmware: String,
    baud: Int,
    travelX: Double,
    travelY: Double,
    maxPower: Double,
    minPower: Double,
    markSpeed: Double,
    quality: Double,
    testPower: Double,
    testDuration: Int
) {
    val grbl = container.grbl
    grbl.updateSetting("Firmware Type", firmware)
    grbl.updateSetting("Last Baud", baud)
    grbl.updateSetting("Travel X", travelX)
    grbl.updateSetting("Travel Y", travelY)
    grbl.updateSetting("Min Power", minPower.toInt())
    grbl.updateSetting("Max Power", maxPower.toInt())
    grbl.updateSetting("Mark Speed", markSpeed.toInt())
    grbl.updateSetting("Test Laser Power", testPower.toInt())
    grbl.updateSetting("Test Laser Duration", testDuration)
    grbl.updateSetting("Quality", quality)

    val profileId = grbl.setupDeviceId
    if (profileId >= 0) {
        DeviceProfiles.markDeviceKnown(profileId.toDouble())
        // `DeviceProfile` 的其余字段有默认值（`newProfile` 的语义），这里显式给出关键的几项
        DeviceProfiles.saveProfile(
            DeviceProfiles.newProfile(
                mapOf(
                    "name" to name,
                    "firmware" to firmware,
                    "baud" to baud.toDouble(),
                    "travelX" to travelX,
                    "travelY" to travelY,
                    "maxPower" to maxPower,
                    "minPower" to minPower,
                    "markSpeed" to markSpeed,
                    "quality" to quality,
                    "deviceId" to profileId.toDouble()
                )
            )
        )
    }
    DeviceProfiles.setSetupDone(true)
    grbl.pushLog("设备初始化完成：$name", MessageType.Feedback)
    grbl.clearNeedsSetup()
}

/** v2 的 `STEPS`（逐字）。 */
private val STEPS = listOf("基本信息", "行程范围", "激光与测试", "雕刻参数")

/** 固件选项（v2 的 `FIRMWARES`）。 */
private val FIRMWARES = listOf(
    Firmware.Grbl.value to "GRBL",
    Firmware.Smoothie.value to "Smoothieware",
    Firmware.Marlin.value to "Marlin",
    Firmware.Vigo.value to "Vigo"
)

/** 波特率选项（v2 的 `BAUD_RATES`）。 */
private val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400, 250000)
