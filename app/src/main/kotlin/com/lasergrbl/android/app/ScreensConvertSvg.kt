package com.lasergrbl.android.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.core.gcode.GcodeFileData
import com.lasergrbl.core.grbl.FitMode
import com.lasergrbl.core.grbl.checkGcodeWithinTravel
import com.lasergrbl.core.grbl.fitSizeToTravel
import com.lasergrbl.core.vector.SvgConvertOptions
import com.lasergrbl.core.vector.SvgConvertResult
import com.lasergrbl.core.vector.convertSvgToGcode
import com.lasergrbl.glasskit.LiquidAlert
import com.lasergrbl.glasskit.LiquidAlertType
import com.lasergrbl.glasskit.LiquidRow
import com.lasergrbl.glasskit.LiquidSpinner
import com.lasergrbl.glasskit.theme.LiquidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SVG 转雕刻 —— 对应 v2 `src/ui/views/SvgConvertView.vue`（508 行）。
 *
 * ### v2 的控件清单与去向（§3：输入 11、开关 3、行 3、滑块 2）
 *
 * | v2 区块 | 控件 | 3.0 实现 |
 * | --- | --- | --- |
 * | SVG 文件 | 点击选择（`支持 path、rect、circle、ellipse、line、polygon 等图形`）、文件名行 + 更换 | 卡片 + `rememberFilePicker()` |
 * | 尺寸与精度 | 目标宽度、目标高度（自动时禁用、placeholder「自动」）、`高度按比例自动` 开关、曲线精度 | 数值输入（失焦提交、非法回退） |
 * | 走线方式 | 3 个方式按钮（轮廓提取 / 中心线描线 / 智能）+ 每个方式的中文提示 | `ConvertChipRow` + 三句提示**逐字保留** |
 * | 描线参数 | 二值化阈值、去毛刺长度、简化容差、阈值/去毛刺/简化容差三段说明 | 数值输入 + 三句中文说明逐字保留 |
 * | 描线参数 | 光栅化分辨率滑块（256–2048，步长 64）+ 说明 | `GlassSliderRow`（步长用 `steps` 还原） |
 * | 描线参数 | 反相开关 | 实现 |
 * | 智能判定 | 笔画宽度阈值滑块（0.5–10 %，步长 0.1）+ 两段说明 | `GlassSliderRow` + 两段说明逐字保留 |
 * | 参数 | 雕刻速度、空移速度（0=快速）、激光功率 S、起点 X/Y、硬件 PWM | 实现 |
 * | 警告 | `适配行程` 提示 + `超出行程` 提示 | `LiquidAlert(Warning)` |
 * | 结果 | 路径条数 / 长度、智能判定文案、光栅化预览、路径预览 | 实现 |
 *
 * ### ⚠️ 中心线 / 智能模式的**能力缺口**（必须显式记录，不许假装可用）
 * v2 的 `convertSvgVector()`（`src/core/vector/SvgVector.ts`）做的是
 * **「SVG → 位图 → 骨架化 / 智能判定 → 走线」**：它需要一个能把 SVG 画成位图的平台能力
 * （浏览器 `Image` + `canvas.drawImage`，即 v2 的 `rasterizeSvg`）。
 *
 * 3.0 现状：
 *  * `:core` 里**没有** `SvgVector.kt`（`grep convertSvgVector` 无匹配），
 *    也没有骨架化入口的 SVG 变体；`convertSvgToGcode` 只做路径几何解析；
 *  * `:app` 里**没有** SVG 位图化实现（`grep -i svg` 只命中路由与注释），
 *    `AndroidBitmapBridge` 只能读 `Bitmap`，不能读 SVG 源码；
 *  * 平台依赖也说明这一步无法在 `:core` 里离线复刻（同 `resizeImage` / `toDataURL` 的处境）。
 *
 * 因此本屏：
 *  1. **完整实现「轮廓提取」**（`convertSvgToGcode`，与 v2 逐字节同源，含 `tolerance`、
 *     行程自适应重算、超行程校验、`pathCount` / `pathLengthMm` 统计）；
 *  2. 中心线 / 智能的**按钮与参数面板保留**（用户能看到 3.0 的界面契约），
 *     但点「生成」时会给出**明确的中文提示**「当前版本暂不支持…」并拒绝生成，
 *     **不会**静默退回轮廓提取冒充成功；
 *  3. 面板顶部显示一条 `LiquidAlert(Warning)` 说明缺口与原因。
 *
 * 补齐方式（交给 Lead 决策）：在 `:core` 移植 `SvgVector.kt`（需要 `rasterizeSvg` 接缝）
 * 并在 `:app` 实现 SVG 位图化（例如用平台 `WebView` 或引入 SVG 解析库 —— 都要新依赖，
 * 超出本任务"不引入新依赖"的约束）。
 *
 * ### 其它有意差异
 * 1. **未提供"保存文件" / "去雕刻"按钮**：生成结果通过
 *    `container.grbl.loadGcodeLines(name, lines)` 交给状态层，与 v2 的 `loadGcodeLines` 同语义；
 *    落盘与"去雕刻"由 `/file` 屏承担（那里有 `GcodeFileStore` 的"另存"，底部标签栏也有"首页"）。
 * 2. **空移速度的默认值取 3000**：v2 从 `AppSettings.get('Travel Speed', ...)` 读取，
 *    而 `:core` 的 `DEFAULT_SETTINGS` 里没有这个键（默认 0 = 快速）；3.0 用 `convertSettingNum`
 *    读取，取不到时回退 3000。差异已记录（设置页若新增该键会自动生效）。
 * 3. **转换放 `Dispatchers.Default`**（v2 在主线程同步跑）。
 * 4. `header` 用 `AppSettings.get('Header', 'G90')`、`footer` 用 `'M5'`（与 v2 本屏逐字一致）。
 *
 * ### 无法离线验证的条目
 *  * 真实 SVG 文件的导入与解析（需要设备上的 `ContentResolver`；`:core` 的 `parseXml`
 *    在 JVM 侧有实现，已有黄金样本覆盖，但"从 URI 读文件"这条链路只能在设备上验证）；
 *  * 大 SVG（上万条路径）在真机上的解析耗时与内存；
 *  * 中心线 / 智能模式 —— **已知不可用**，不是"未验证"而是"未实现"。
 */
@Composable
fun SvgConvertScreen(container: AppContainer, backdrop: Backdrop, screen: Screen.ConvertSvg) {
    val colors = LiquidTheme.colors
    val scope = rememberCoroutineScope()
    val picker = rememberFilePicker()
    val settings = container.grbl.settings

    var svgName by remember { mutableStateOf("") }
    var svgText by remember { mutableStateOf("") }
    var modeIndex by remember { mutableStateOf(0) }
    val mode = SVG_MODE_ORDER[modeIndex]

    var widthMm by remember { mutableStateOf(60.0) }
    var heightMm by remember { mutableStateOf(0.0) }
    var autoHeight by remember { mutableStateOf(true) }
    var tolerance by remember { mutableStateOf(0.1) }

    var markSpeed by remember { mutableStateOf(convertSettingNum(settings, "Mark Speed", 1000.0)) }
    var travelSpeed by remember { mutableStateOf(convertSettingNum(settings, "Travel Speed", 3000.0)) }
    var maxPower by remember { mutableStateOf(convertSettingNum(settings, "Max Power", 1000.0)) }
    var pwm by remember { mutableStateOf(convertSettingBool(settings, "Support Hardware PWM", true)) }
    var laserOn by remember { mutableStateOf(convertSettingString(settings, "Laser On Command", "M4")) }
    var laserOff by remember { mutableStateOf(convertSettingString(settings, "Laser Off Command", "M5")) }
    var offsetX by remember { mutableStateOf(0.0) }
    var offsetY by remember { mutableStateOf(0.0) }

    // 描线（中心线 / 智能）专用参数：界面上保留，但生成时不可用（见 KDoc）
    var threshold by remember { mutableStateOf(50.0) }
    var minBranchPx by remember { mutableStateOf(6.0) }
    var simplifyTolerance by remember { mutableStateOf(1.2) }
    var rasterPixels by remember { mutableStateOf(1024f) }
    var invert by remember { mutableStateOf(false) }
    var strokeWidthPct by remember { mutableStateOf(2.5f) }

    var busy by remember { mutableStateOf(false) }
    var warn by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<SvgConvertResult?>(null) }
    var generated by remember { mutableStateOf<GcodeFileData?>(null) }

    val canGenerate = svgText.isNotEmpty() && widthMm > 0

    /** v2 `pick()` 的成功分支（抽出来供初始 URI 复用）。 */
    fun acceptSvg(name: String, text: String) {
        svgName = name
        svgText = text
        generated = null
        info = null
        warn = ""
    }

    /** 导入（v2 `pick()`）：只接受 SVG 源码。 */
    fun pick() {
        scope.launch {
            val picked = picker.pick(ANY_MIME) ?: return@launch
            val text = picked.text.orEmpty()
            val isSvg = picked.looksLikeSvg || text.contains("<svg")
            if (!isSvg) {
                container.toasts.show("请选择 SVG 文件", LiquidAlertType.Error)
                return@launch
            }
            acceptSvg(picked.name, text)
            container.toasts.show("已导入 ${picked.name}")
        }
    }

    /** 组装转换参数（尺寸可变，便于行程自适应后重算）。 */
    fun buildOptions(w: Double, h: Double?): SvgConvertOptions = SvgConvertOptions(
        targetWidthMm = w,
        targetHeightMm = h,
        tolerance = tolerance,
        markSpeed = markSpeed,
        travelSpeed = travelSpeed,
        laserOn = laserOn,
        laserOff = laserOff,
        pwm = pwm,
        maxPower = maxPower,
        offsetX = offsetX,
        offsetY = offsetY,
        header = convertSettingString(settings, "Header", "G90"),
        footer = convertSettingString(settings, "Footer", "M5")
    )

    /** 生成路径（v2 `generate()`）；解析与离散是 CPU 密集的，放 `Dispatchers.Default`。 */
    fun generate() {
        if (!canGenerate) {
            container.toasts.show("请先导入 SVG 文件", LiquidAlertType.Error)
            return
        }
        if (mode != SvgMode.Outline) {
            // 诚实拒绝：3.0 没有 SVG 位图化 / 骨架化能力（见文件 KDoc）
            container.toasts.show(
                "当前版本暂不支持「${SVG_MODE_LABELS.getValue(mode)}」：缺少 SVG 位图化与骨架化能力，请先用「轮廓提取」",
                LiquidAlertType.Error
            )
            return
        }
        if (busy) return
        busy = true
        warn = ""
        scope.launch {
            try {
                val pair = withContext(Dispatchers.Default) {
                    var warning = ""
                    val h = if (autoHeight) null else heightMm
                    var res = convertSvgToGcode(svgText, buildOptions(widthMm, h))
                    // 超出设备行程时等比缩小后重算（v2 的两遍转换）
                    val fit = fitSizeToTravel(res.widthMm, res.heightMm, mode = FitMode.Fit)
                    if (fit.adjusted) {
                        warning = appendWarn(warning, fit.message)
                        res = convertSvgToGcode(
                            svgText,
                            buildOptions(fit.widthMm, if (autoHeight) null else fit.heightMm)
                        )
                    }
                    warning = appendWarn(warning, checkGcodeWithinTravel(res.lines).message)
                    res to warning
                }
                val (res, warning) = pair
                info = res
                warn = warning
                val base = svgName.removeSuffix(".svg").removeSuffix(".SVG").ifEmpty { "vector" }
                container.grbl.loadGcodeLines("$base.gcode", res.lines)
                generated = container.grbl.file
                container.toasts.show("已生成 ${res.pathCount} 条路径 / ${res.lines.size} 行")
            } catch (e: Throwable) {
                container.toasts.show("生成失败：${e.message ?: e.toString()}", LiquidAlertType.Error)
            } finally {
                busy = false
            }
        }
    }

    // 从其它屏跳转时可能带着已选 URI（`Screen.ConvertSvg.sourceUri`）
    LaunchedEffect(screen.sourceUri) {
        val uri = screen.sourceUri ?: return@LaunchedEffect
        if (svgText.isNotEmpty()) return@LaunchedEffect
        val picked = runCatching { readPickedFile(container.context, Uri.parse(uri)) }.getOrNull()
            ?: return@LaunchedEffect
        val text = picked.text.orEmpty()
        if (text.contains("<svg") || picked.looksLikeSvg) acceptSvg(picked.name, text)
    }

    GlassScreenBody {
        // ================= SVG 文件 =================
        GlassCard(backdrop) {
            Column(Modifier.fillMaxWidth()) {
                GlassSectionHeader("SVG 文件")
                Spacer(Modifier.height(8.dp))
                if (svgName.isEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { pick() }
                            .padding(vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        GlassIcon(name = "vector", size = 30.dp, tint = colors.accent)
                        Spacer(Modifier.height(8.dp))
                        GlassText("点击选择 SVG 文件", color = colors.accent, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        GlassText(
                            "支持 path、rect、circle、ellipse、line、polygon 等图形",
                            color = colors.textDim,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.fill1)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassIcon(name = "file", size = 22.dp, tint = colors.accent)
                        GlassText(
                            svgName,
                            color = colors.text,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        GlassIconButton(
                            backdrop = backdrop,
                            icon = "vector",
                            label = "更换",
                            size = GlassButtonSize.Small,
                            plain = true,
                            onClick = { pick() }
                        )
                    }
                }
            }
        }

        // ================= 尺寸与精度 =================
        GlassCard(backdrop) {
            Column(Modifier.fillMaxWidth()) {
                GlassSectionHeader("尺寸与精度")
                Spacer(Modifier.height(8.dp))
                ConvertNumGrid(
                    listOf(
                        ConvertNumSpec("目标宽度 (mm)", convertFmt(widthMm)) { widthMm = it },
                        ConvertNumSpec(
                            label = "目标高度 (mm)",
                            value = if (autoHeight) "" else convertFmt(heightMm),
                            enabled = !autoHeight,
                            placeholder = "自动"
                        ) { heightMm = it }
                    )
                )
                LiquidRow(
                    title = "高度按比例自动",
                    description = "保持原始宽高比",
                    backdrop = backdrop,
                    trailing = { GlassSwitch(backdrop, autoHeight, onCheckedChange = { autoHeight = it }) }
                )
                Spacer(Modifier.height(8.dp))
                ConvertNumGrid(
                    listOf(
                        ConvertNumSpec("曲线精度 (mm，越小越平滑)", convertFmt(tolerance)) { tolerance = it }
                    )
                )
            }
        }

        // ================= 走线方式 =================
        GlassCard(backdrop) {
            Column(Modifier.fillMaxWidth()) {
                GlassSectionHeader("走线方式")
                Spacer(Modifier.height(8.dp))
                ConvertChipRow(
                    backdrop = backdrop,
                    options = SVG_MODE_ORDER.map { SVG_MODE_LABELS.getValue(it) },
                    selectedIndex = modeIndex,
                    onSelect = {
                        modeIndex = it
                        // 切换方式时清掉上一次的判定 / 光栅化展示，避免误导（v2 `setMode`）
                        info = null
                        generated = null
                        warn = ""
                    }
                )
                Spacer(Modifier.height(8.dp))
                ConvertHint(SVG_MODE_HINTS.getValue(mode))

                if (mode != SvgMode.Outline) {
                    Spacer(Modifier.height(8.dp))
                    LiquidAlert(
                        title = "当前版本暂不支持该走线方式（缺少 SVG 位图化与骨架化能力），请先使用「轮廓提取」。",
                        type = LiquidAlertType.Warning
                    )
                }
            }
        }

        // ================= 描线参数（仅中心线 / 智能，界面保留）=================
        if (mode != SvgMode.Outline) {
            GlassCard(backdrop) {
                Column(Modifier.fillMaxWidth()) {
                    GlassSectionHeader(
                        if (mode == SvgMode.Auto) "描线参数（智能判定为线稿时生效）" else "中心线参数"
                    )
                    Spacer(Modifier.height(8.dp))
                    ConvertNumGrid(
                        listOf(
                            ConvertNumSpec("二值化阈值 (%)", convertFmt(threshold)) { threshold = it },
                            ConvertNumSpec("去毛刺长度 (像素)", convertFmt(minBranchPx)) { minBranchPx = it },
                            ConvertNumSpec("简化容差 (像素)", convertFmt(simplifyTolerance)) {
                                simplifyTolerance = it
                            }
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    ConvertHint("阈值：光栅化后按灰度二值化的分界（50% ≈ 128/255），线条偏灰时可调高。")
                    ConvertHint("去毛刺：丢弃短于该像素长度的骨架分支，消除边缘毛刺，越大越干净。")
                    ConvertHint("简化容差：Douglas-Peucker 容差（像素），越大节点越少、线条越硬。")

                    Spacer(Modifier.height(10.dp))
                    GlassSliderRow(
                        backdrop = backdrop,
                        title = "光栅化分辨率（长边像素）",
                        value = rasterPixels,
                        onValueChange = { rasterPixels = it },
                        valueRange = 256f..2048f,
                        steps = 27
                    )
                    ConvertHint("先把 SVG 画成位图再取骨架：太小细线容易断裂，太大生成较慢（默认 1024）。")

                    LiquidRow(
                        title = "反相",
                        description = "深底浅色线条时启用",
                        backdrop = backdrop,
                        trailing = { GlassSwitch(backdrop, invert, onCheckedChange = { invert = it }) }
                    )
                }
            }
        }

        // ================= 智能判定（仅智能）=================
        if (mode == SvgMode.Auto) {
            GlassCard(backdrop) {
                Column(Modifier.fillMaxWidth()) {
                    GlassSectionHeader("智能判定")
                    Spacer(Modifier.height(8.dp))
                    GlassSliderRow(
                        backdrop = backdrop,
                        title = "笔画宽度阈值（占图像短边 %）",
                        value = strokeWidthPct,
                        onValueChange = { strokeWidthPct = it },
                        valueRange = 0.5f..10f,
                        suffix = "%",
                        decimals = 1
                    )
                    Spacer(Modifier.height(6.dp))
                    ConvertHint(
                        "平均笔画宽度 ≈ 墨水面积 / 骨架长度，再除以图像短边得到百分比：小于等于该阈值判为线稿 → " +
                            "中心线描线；大于则判为实心 / 粗笔画图案 → 轮廓提取。"
                    )
                    ConvertHint("兜底：骨架为空或退化、墨水覆盖率超过 50% 时，一律回退轮廓提取。")
                }
            }
        }

        // ================= 雕刻参数 =================
        GlassCard(backdrop) {
            Column(Modifier.fillMaxWidth()) {
                GlassSectionHeader("雕刻参数")
                Spacer(Modifier.height(8.dp))
                ConvertNumGrid(
                    listOf(
                        ConvertNumSpec("雕刻速度 (mm/min)", convertFmt(markSpeed)) { markSpeed = it },
                        ConvertNumSpec("空移速度 (0=快速)", convertFmt(travelSpeed)) { travelSpeed = it },
                        ConvertNumSpec("激光功率 S", convertFmt(maxPower)) { maxPower = it },
                        ConvertNumSpec("起点 X (mm)", convertFmt(offsetX)) { offsetX = it },
                        ConvertNumSpec("起点 Y (mm)", convertFmt(offsetY)) { offsetY = it }
                    )
                )
                LiquidRow(
                    title = "硬件 PWM",
                    description = "使用 S 值控制激光功率",
                    backdrop = backdrop,
                    trailing = { GlassSwitch(backdrop, pwm, onCheckedChange = { pwm = it }) }
                )
            }
        }

        // ================= 警告 + 生成 =================
        if (warn.isNotEmpty()) {
            LiquidAlert(title = warn, type = LiquidAlertType.Warning)
        }

        GlassButton(
            backdrop = backdrop,
            onClick = { generate() },
            block = true,
            accent = true,
            // v2 是 `:loading="busy" :disabled="!canGenerate"`；
            // 中心线 / 智能模式下按钮仍可点 —— 由 generate() 给出"暂不支持"的明确提示
            loading = busy,
            enabled = canGenerate
        ) {
            GlassIcon(name = "layers", size = 17.dp, tint = colors.text)
            Spacer(Modifier.size(6.dp))
            GlassText("生成雕刻路径", color = colors.text, fontSize = 14.sp)
        }

        // ================= 结果 =================
        val file = generated
        val result = info
        if (file != null && result != null) {
            GlassCard(backdrop) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassText("路径预览", color = colors.text, fontSize = 14.sp)
                        GlassText(
                            "${result.pathCount} 条 · ${convertFmt(result.pathLengthMm, 1)} mm",
                            color = colors.textDim,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // 轮廓提取模式没有光栅化预览（v2 同样为空）
                    ConvertHint(
                        "尺寸 ${convertFmt(result.widthMm, 1)} × ${convertFmt(result.heightMm, 1)} mm · " +
                            "${result.lines.size} 行 G 代码"
                    )
                    Spacer(Modifier.height(8.dp))
                    GcodePreview(preview = file.preview, bbox = file.stats.bbox, height = 220.dp)
                    Spacer(Modifier.height(8.dp))
                    ConvertHint("结果已交给状态层（预览 / 运行共用同一份）；保存到\"雕刻文件\"请在文件页操作。")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 本屏私有辅助
// ---------------------------------------------------------------------------

/** v2 的 `SvgVectorMode`（`src/core/vector/SvgVector.ts` 的 `'Outline' | 'Centerline' | 'Auto'`）。 */
private enum class SvgMode(val key: String) {
    Outline("Outline"),
    Centerline("Centerline"),
    Auto("Auto")
}

/** v2 `MODE_OPTIONS` 的顺序。 */
private val SVG_MODE_ORDER = listOf(SvgMode.Outline, SvgMode.Centerline, SvgMode.Auto)

/** 走线方式的中文标签（逐字对齐 v2 `SVG_MODE_LABELS`）。 */
private val SVG_MODE_LABELS: Map<SvgMode, String> = mapOf(
    SvgMode.Outline to "轮廓提取",
    SvgMode.Centerline to "中心线描线",
    SvgMode.Auto to "智能"
)

/** 走线方式的中文提示（逐字对齐 v2 `MODE_HINTS`，含全角括号与斜杠）。 */
private val SVG_MODE_HINTS: Map<SvgMode, String> = mapOf(
    SvgMode.Outline to
        "解析 SVG 几何，沿图形内外轮廓走线；适合实心图案、字母轮廓。已转成填充路径的线稿会描出每条线的外框（双边）。",
    SvgMode.Centerline to
        "先光栅化再取骨架，沿笔画中心单线走线；适合签名 / 线稿 / 单线图形，省时间省材料。",
    SvgMode.Auto to
        "先按「平均笔画宽度 ≈ 墨水面积 / 骨架长度」判断是否线稿：细线稿 → 中心线描线；实心 / 粗笔画 → 轮廓提取。"
)
