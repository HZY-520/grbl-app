package com.lasergrbl.android

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.catalog.components.LiquidSlider
import com.kyant.backdrop.catalog.components.LiquidToggle
import com.lasergrbl.glasskit.LiquidAlert
import com.lasergrbl.glasskit.LiquidAlertType
import com.lasergrbl.glasskit.LiquidBottomSheet
import com.lasergrbl.glasskit.LiquidDialog
import com.lasergrbl.glasskit.LiquidDisclosure
import com.lasergrbl.glasskit.LiquidIcons
import com.lasergrbl.glasskit.LiquidPlainSurface
import com.lasergrbl.glasskit.LiquidProgress
import com.lasergrbl.glasskit.LiquidRow
import com.lasergrbl.glasskit.LiquidScaffold
import com.lasergrbl.glasskit.LiquidSectionTitle
import com.lasergrbl.glasskit.LiquidSegmented
import com.lasergrbl.glasskit.LiquidSelect
import com.lasergrbl.glasskit.LiquidSheetOption
import com.lasergrbl.glasskit.LiquidSpinner
import com.lasergrbl.glasskit.LiquidSurface
import com.lasergrbl.glasskit.LiquidTextField
import com.lasergrbl.glasskit.LiquidToastHost
import com.lasergrbl.glasskit.rememberLiquidToastController
import com.lasergrbl.glasskit.theme.LiquidTheme
import com.lasergrbl.glasskit.theme.LiquidType
import kotlin.math.roundToInt

/**
 * Phase 1 组件画廊：一屏跑完「他的 5 个组件 + 我们补齐的全部组件」，逐项肉眼验收。
 * Phase 4 的 13 个页面就是拿这些积木拼出来的。
 */
@Composable
fun Phase1Gallery() {
    LiquidTheme(darkTheme = true) {
        val colors = LiquidTheme.colors
        val dimens = LiquidTheme.dimens

        var tab by remember { mutableIntStateOf(0) }
        var toggle by remember { mutableStateOf(true) }
        var slider by remember { mutableFloatStateOf(0.42f) }
        var name by remember { mutableStateOf("") }
        var widthMm by remember { mutableStateOf("60") }
        var power by remember { mutableStateOf("80") }
        var progress by remember { mutableFloatStateOf(0.62f) }

        var segmented by remember { mutableIntStateOf(0) }
        var baud by remember { mutableStateOf<String?>(null) }
        var showBaudSheet by remember { mutableStateOf(false) }
        var showResetDialog by remember { mutableStateOf(false) }
        val toast = rememberLiquidToastController()

        LiquidScaffold(
            title = "组件画廊",
            subtitle = "Phase 1 · 玻璃地基 + 全部补齐组件",
            actions = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(colors.success, CircleShape)
                    )
                    BasicText("未连接", style = LiquidType.caption.copy(color = colors.textDim))
                }
            },
            bottomBar = { backdrop ->
                Box(Modifier.padding(horizontal = dimens.screenPadding, vertical = 8.dp)) {
                    LiquidBottomTabs(
                        selectedTabIndex = { tab },
                        onTabSelected = { tab = it },
                        backdrop = backdrop,
                        tabsCount = 5
                    ) {
                        listOf("首页", "图案", "文件", "控制", "设置").forEachIndexed { index, label ->
                            LiquidBottomTab(onClick = { tab = index }) {
                                BasicText(
                                    label,
                                    style = LiquidType.caption.copy(
                                        color = if (tab == index) colors.text else colors.textDim
                                    )
                                )
                            }
                        }
                    }
                }
            },
            // 浮层必须走这个槽：同窗口 + 共用折射源，玻璃才是真折射
            overlay = { backdrop ->
                if (showBaudSheet) {
                    LiquidBottomSheet(
                        backdrop = backdrop,
                        onDismiss = { showBaudSheet = false },
                        title = "波特率"
                    ) {
                        listOf("115200", "250000", "500000", "921600").forEach { option ->
                            LiquidSheetOption(
                                label = option,
                                descriptor = if (option == "115200") "默认，最稳" else null,
                                selected = baud == option
                            ) {
                                baud = option
                                showBaudSheet = false
                                toast.show("波特率已设为 $option", LiquidAlertType.Success)
                            }
                        }
                    }
                }

                if (showResetDialog) {
                    LiquidDialog(
                        backdrop = backdrop,
                        onDismissRequest = { showResetDialog = false },
                        title = "软复位？",
                        message = "会清空当前任务与缓冲区，设备将重新回到 Idle。",
                        confirmText = "软复位",
                        destructive = true,
                        onConfirm = { toast.show("已发送软复位（0x18）", LiquidAlertType.Warning) }
                    )
                }

                LiquidToastHost(controller = toast, backdrop = backdrop)
            }
        ) { backdrop ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = dimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(dimens.gap)
            ) {
                // ── 1. 玻璃卡片 + 嵌套玻璃 + 进度 ────────────────────────────
                LiquidSurface(backdrop = backdrop) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LiquidSectionTitle("当前坐标")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(backdrop, "工作坐标 X", "0", Modifier.weight(1f))
                            StatTile(backdrop, "工作坐标 Y", "0", Modifier.weight(1f))
                            StatTile(backdrop, "工作坐标 Z", "0", Modifier.weight(1f))
                        }
                        LiquidProgress(value = progress, showLabel = true)
                    }
                }

                // ── 2. 他的按钮 ──────────────────────────────────────────────
                LiquidSurface(backdrop = backdrop) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LiquidSectionTitle("快捷操作（他的 LiquidButton）")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.weight(1f)) {
                                LiquidButton({}, backdrop, Modifier.fillMaxWidth()) {
                                    BasicText("回原点 \$H", style = LiquidType.body.copy(color = colors.text))
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                LiquidButton({}, backdrop, Modifier.fillMaxWidth()) {
                                    BasicText("解锁 \$X", style = LiquidType.body.copy(color = colors.text))
                                }
                            }
                        }
                        LiquidButton({ showResetDialog = true }, backdrop, Modifier.fillMaxWidth(), tint = colors.accent) {
                            BasicText("软复位（打开玻璃对话框）", style = LiquidType.body.copy(color = Color.White))
                        }
                    }
                }

                // ── 3. 输入框 ────────────────────────────────────────────────
                LiquidSurface(backdrop = backdrop) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LiquidSectionTitle("输入（LiquidTextField）")
                        LiquidTextField(
                            value = name,
                            onValueChange = { name = it },
                            backdrop = backdrop,
                            placeholder = "文件名",
                            imeAction = ImeAction.Next
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                LiquidTextField(
                                    value = widthMm,
                                    onValueChange = { widthMm = it },
                                    backdrop = backdrop,
                                    keyboardType = KeyboardType.Number,
                                    suffix = "mm"
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                LiquidTextField(
                                    value = power,
                                    onValueChange = { power = it },
                                    backdrop = backdrop,
                                    keyboardType = KeyboardType.Number,
                                    suffix = "%"
                                )
                            }
                        }
                        BasicText(
                            "失焦才提交；非法值会回弹（v2 GlassInput 契约）",
                            style = LiquidType.caption.copy(color = colors.textFaint)
                        )
                    }
                }

                // ── 4. 下拉（触发器 + 玻璃弹层）与分段 ───────────────────────
                LiquidSurface(backdrop = backdrop) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LiquidSectionTitle("选择（LiquidSelect + 玻璃底部弹层）")
                        LiquidSelect(
                            label = baud,
                            backdrop = backdrop,
                            placeholder = "选择波特率",
                            onClick = { showBaudSheet = true }
                        )
                        LiquidSectionTitle("分段（LiquidSegmented）")
                        LiquidSegmented(
                            options = listOf(0, 1, 2),
                            selected = segmented,
                            onSelect = { segmented = it },
                            label = { listOf("轮廓", "中心线", "智能")[it] },
                            backdrop = backdrop
                        )
                    }
                }

                // ── 5. 他的开关与滑块 ────────────────────────────────────────
                LiquidSurface(backdrop = backdrop) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LiquidSectionTitle("参数")
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                BasicText("高度按比例自动", style = LiquidType.body.copy(color = colors.text))
                                BasicText("保持图片宽高比", style = LiquidType.caption.copy(color = colors.textDim))
                            }
                            LiquidToggle(
                                selected = { toggle },
                                onSelect = { toggle = it },
                                backdrop = backdrop
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                BasicText("激光功率", style = LiquidType.label.copy(color = colors.textDim))
                                BasicText(
                                    "${(slider * 100f).roundToInt()}%",
                                    style = LiquidType.label.copy(color = colors.text)
                                )
                            }
                            LiquidSlider(
                                value = { slider },
                                onValueChange = { slider = it },
                                valueRange = 0f..1f,
                                visibilityThreshold = 0.001f,
                                backdrop = backdrop,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // ── 6. 列表行 + 折叠 ────────────────────────────────────────
                LiquidSurface(backdrop = backdrop) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiquidSectionTitle("列表（LiquidRow）")
                        LiquidRow(
                            title = "串口终端",
                            description = "查看通讯日志、发送原始命令",
                            backdrop = backdrop,
                            leading = { GalleryIcon("terminal") },
                            showChevron = true,
                            onClick = { toast.show("点了「串口终端」") }
                        )
                        LiquidRow(
                            title = "后台保活",
                            description = "雕刻期间常驻通知",
                            backdrop = backdrop,
                            leading = { GalleryIcon("power") },
                            trailing = {
                                LiquidToggle(
                                    selected = { toggle },
                                    onSelect = { toggle = it },
                                    backdrop = backdrop
                                )
                            }
                        )
                        LiquidDisclosure(
                            title = "高级选项",
                            description = "点开看折叠动画",
                            backdrop = backdrop
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BasicText("线程模式：Buffered", style = LiquidType.label.copy(color = colors.textDim))
                                BasicText("自动缓冲区：127 字节", style = LiquidType.label.copy(color = colors.textDim))
                            }
                        }
                    }
                }

                // ── 7. 反馈 ─────────────────────────────────────────────────
                LiquidSurface(backdrop = backdrop) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LiquidSectionTitle("反馈")
                        LiquidAlert(
                            title = "未连接设备",
                            message = "请先在「连接设备」页选择串口",
                            type = LiquidAlertType.Warning
                        )
                        LiquidAlert(title = "参数已下发", type = LiquidAlertType.Success)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LiquidSpinner()
                            BasicText("正在读取机器参数…", style = LiquidType.label.copy(color = colors.textDim))
                            Spacer(Modifier.weight(1f))
                            LiquidButton(
                                { progress = (progress + 0.1f) % 1.1f },
                                backdrop
                            ) {
                                BasicText("推进度", style = LiquidType.label.copy(color = colors.text))
                            }
                        }
                        LiquidButton({ toast.show("这是一条轻提示 toast") }, backdrop, Modifier.fillMaxWidth()) {
                            BasicText("弹一条 Toast", style = LiquidType.label.copy(color = colors.text))
                        }
                    }
                }

                // ── 8. 图标 ────────────────────────────────────────────────
                LiquidSurface(backdrop = backdrop) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LiquidSectionTitle("图标（v2 icons.ts 逐字搬运 · 42 个）")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf("home", "grid", "image", "text", "vector", "file", "move", "settings")
                                .forEach { GalleryIcon(it) }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf("terminal", "usb", "bluetooth", "play", "pause", "stop", "flame", "speed")
                                .forEach { GalleryIcon(it) }
                        }
                    }
                }

                // ── 9. 渲染能力 ─────────────────────────────────────────────
                LiquidSurface(backdrop = backdrop, surfaceColor = colors.fill1) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LiquidSectionTitle("渲染能力")
                        BasicText(
                            "Android ${Build.VERSION.SDK_INT} · ${capabilityText()}",
                            style = LiquidType.label.copy(color = colors.textDim)
                        )
                        BasicText(
                            "API 33+ 完整折射 · API 31–32 只有模糊 · API <31 纯色卡片",
                            style = LiquidType.caption.copy(color = colors.textFaint)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun GalleryIcon(name: String) {
    val colors = LiquidTheme.colors
    Image(
        imageVector = LiquidIcons.get(name),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        colorFilter = ColorFilter.tint(colors.textDim)
    )
}

@Composable
private fun StatTile(
    backdrop: com.kyant.backdrop.Backdrop,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = LiquidTheme.colors
    LiquidPlainSurface(
        backdrop = backdrop,
        modifier = modifier,
        contentPadding = PaddingValues(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(label, style = LiquidType.caption.copy(color = colors.textDim))
            BasicText(value, style = LiquidType.stat.copy(color = colors.text))
        }
    }
}

private fun capabilityText(): String = when {
    com.kyant.backdrop.isRuntimeShaderSupported() -> "完整折射（RuntimeShader）"
    com.kyant.backdrop.isRenderEffectSupported() -> "部分效果（RenderEffect）"
    else -> "纯色降级"
}
