package com.lasergrbl.android.app

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.core.serial.SerialDeviceInfo
import com.lasergrbl.core.serial.TransportKind
import com.lasergrbl.glasskit.LiquidAlert
import com.lasergrbl.glasskit.LiquidAlertType
import com.lasergrbl.glasskit.LiquidRow
import com.lasergrbl.glasskit.LiquidSpinner
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 连接设备 —— v2 `src/ui/views/ConnectView.vue`（302 行）的 Compose 版（`/connect`）。
 *
 * ### 逐块对应
 * | v2 | 这里 |
 * | --- | --- |
 * | USB / 蓝牙分段切换（`.seg` 两个自定义按钮） | [kindSwitch] |
 * | 设备列表（`v-for` `.dev-item`，点击即连接） | [DeviceList]（`LiquidRow`） |
 * | 波特率下拉（`GlassSelect` + `GlassOption`） | 波特率按钮行（7 档，v2 的 `BAUD_RATES`） |
 * | 扫描加载态（`GlassLoading`） | [LiquidSpinner] + 「扫描中…」 |
 * | 连接 / 断开按钮 | 底部按钮行 |
 * | 「打开蓝牙设置」 | [openBluetoothSettings] |
 *
 * ### 与 v2 的三处差异
 * 1. **进入页面自动扫描**：v2 需要手动点「扫描」；3.0 在 `LaunchedEffect` 里自动扫一次
 *    （USB 枚举是同步的、蓝牙会先过权限门禁）。v2 的「扫描」按钮仍然保留。
 * 2. **蓝牙连接走 `grbl.connect`**，它内部通过 `BluetoothSerialTransport` 的权限门禁
 *    （Phase 3 审计的 D2：不能直接 `open()`）。
 * 3. v2 的波特率是 `GlassSelect` 下拉；3.0 用一排按钮 —— `LiquidSelect` 需要配合底部弹层，
 *    而 7 档值的下拉比按钮行更费操作（且同屏玻璃面更少）。
 */
@Composable
fun ConnectScreen(container: AppContainer, backdrop: Backdrop) {
    val grbl = container.grbl
    val colors = LiquidTheme.colors
    var kind by remember { mutableStateOf(TransportKind.Usb) }

    // 进入页面自动扫描一次（v2 需要手动点）
    LaunchedEffect(kind, grbl.connected) {
        if (!grbl.connected) {
            if (kind == TransportKind.Usb) grbl.refreshDevices() else grbl.refreshBluetoothDevices()
        }
    }

    val list = if (kind == TransportKind.Usb) grbl.devices else grbl.bluetoothDevices

    GlassScreenBody {
        // ---- 连接状态 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassBadge(
                        text = if (grbl.connecting) "连接中" else grbl.status.label(),
                        kind = statusBadgeKind(grbl.status, grbl.connected, grbl.connecting)
                    )
                    Spacer(Modifier.weight(1f))
                    if (grbl.connected) {
                        GlassText("设备 #${grbl.deviceId}", color = colors.textDim, fontSize = 12.sp)
                    }
                }
                if (grbl.version.isNotEmpty()) {
                    GlassKeyValue("固件版本", grbl.version, mono = true)
                }
                GlassKeyValue("传输", if (grbl.connected) grbl.deviceKind.value else kind.value)
                GlassKeyValue("波特率", "${grbl.baud} bps")
            }
        }

        // ---- USB / 蓝牙切换 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("传输方式")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransportKind.entries.forEach { k ->
                        GlassButton(
                            backdrop = backdrop,
                            onClick = { kind = k },
                            modifier = Modifier.weight(1f),
                            size = GlassButtonSize.Small,
                            plain = kind != k,
                            accent = kind == k,
                            enabled = !grbl.connected
                        ) {
                            GlassIcon(if (k == TransportKind.Usb) "usb" else "bluetooth", size = 16.dp)
                            Spacer(Modifier.width(4.dp))
                            GlassText(
                                if (k == TransportKind.Usb) "USB 串口" else "蓝牙 SPP",
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // 波特率（v2 的 BAUD_RATES）
                GlassText("波特率", color = colors.textDim, fontSize = 12.sp)
                BAUD_RATES.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { rate ->
                            GlassButton(
                                backdrop = backdrop,
                                onClick = { grbl.updateBaud(rate) },
                                modifier = Modifier.weight(1f),
                                size = GlassButtonSize.Mini,
                                plain = grbl.baud != rate,
                                accent = grbl.baud == rate,
                                enabled = !grbl.connected
                            ) {
                                GlassButtonLabel(formatBaud(rate), fontSize = 11.sp)
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        // ---- 设备列表 ----
        GlassCard(backdrop, padding = PaddingValues(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassSectionHeader(
                        if (kind == TransportKind.Usb) "USB 设备（${list.size}）" else "已配对蓝牙（${list.size}）",
                        Modifier.weight(1f)
                    )
                    if (grbl.scanning) {
                        LiquidSpinner(size = 16.dp, color = colors.accent)
                        Spacer(Modifier.width(6.dp))
                        GlassText("扫描中…", color = colors.textDim, fontSize = 12.sp)
                    } else {
                        GlassIconButton(
                            backdrop = backdrop,
                            icon = "refresh",
                            label = "扫描",
                            onClick = {
                                if (kind == TransportKind.Usb) grbl.refreshDevices() else grbl.refreshBluetoothDevices()
                            },
                            size = GlassButtonSize.Mini,
                            plain = true
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                if (list.isEmpty()) {
                    GlassEmpty(
                        if (grbl.scanning) "正在扫描…" else {
                            if (kind == TransportKind.Usb) {
                                "没有发现 USB 串口设备（请确认已插入并授权）"
                            } else {
                                "没有已配对的蓝牙设备（请先在系统设置里配对）"
                            }
                        }
                    )
                } else {
                    list.forEach { device ->
                        LiquidRow(
                            title = deviceLabel(device),
                            description = deviceDescription(device),
                            backdrop = backdrop,
                            enabled = !grbl.connected && !grbl.connecting,
                            onClick = { grbl.connect(device) },
                            trailing = {
                                GlassIcon(
                                    name = if (device.kind == TransportKind.Usb) "usb" else "bluetooth",
                                    size = 18.dp,
                                    tint = colors.accent
                                )
                            }
                        )
                    }
                }
            }
        }

        // ---- 操作 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSectionHeader("操作")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "power",
                        label = if (grbl.connected) "断开连接" else "等待选择设备",
                        onClick = { grbl.disconnect() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        enabled = grbl.connected
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "link",
                        label = "蓝牙设置",
                        onClick = { openBluetoothSettings(container) },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        plain = true
                    )
                }
                if (kind == TransportKind.Bluetooth) {
                    LiquidAlert(
                        title = "GRBL 的蓝牙模块（HC-05 / HC-06 等）需要先在系统设置里配对，再用经典蓝牙 SPP 连接。",
                        type = LiquidAlertType.Info
                    )
                }
            }
        }

        // ---- 参数档案提示 ----
        if (grbl.needsSetup) {
            GlassCard(backdrop) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassSectionHeader("新设备")
                    GlassText(
                        "这台设备是第一次连接，建议先运行一次初始化向导设置行程与功率。",
                        color = colors.textDim,
                        fontSize = 12.5.sp
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "settings",
                        label = "打开初始化向导",
                        onClick = { grbl.openSetupWizard(grbl.setupDeviceId) },
                        block = true,
                        size = GlassButtonSize.Small,
                        accent = true
                    )
                }
            }
        }
    }
}

/** 设备主标题（`product` 优先，其次 `name`，最后蓝牙地址 / 设备号）。 */
private fun deviceLabel(d: SerialDeviceInfo): String =
    d.product?.takeIf { it.isNotEmpty() }
        ?: d.name.takeIf { it.isNotEmpty() }
        ?: d.address
        ?: "设备 #${d.deviceId?.toInt()}"

/** 设备副标题（VID/PID 或 MAC，v2 的 `.dev-item small` 等价物）。 */
private fun deviceDescription(d: SerialDeviceInfo): String {
    val parts = mutableListOf<String>()
    val vendor = d.vendor
    if (!vendor.isNullOrEmpty()) parts.add(vendor)
    val vid = d.vendorId
    val pid = d.productId
    if (vid != null && pid != null) {
        parts.add("VID 0x%04X / PID 0x%04X".format(vid.toInt(), pid.toInt()))
    }
    val address = d.address
    if (address != null) parts.add(address)
    parts.add(d.kind.value)
    return parts.joinToString(" · ")
}

/** 打开系统蓝牙设置（v2 走原生插件的 `openSettings()`）。 */
private fun openBluetoothSettings(container: AppContainer) {
    runCatching {
        container.context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** 波特率档位（v2 `ConnectView.vue` 的 `BAUD_RATES`）。 */
private val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400, 250000)
