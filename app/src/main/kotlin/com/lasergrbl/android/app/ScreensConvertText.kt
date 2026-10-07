package com.lasergrbl.android.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.android.platform.AndroidBitmapBridge
import com.lasergrbl.android.platform.AndroidTextRasterizer
import com.lasergrbl.core.gcode.GcodeFileData
import com.lasergrbl.core.grbl.FitMode
import com.lasergrbl.core.grbl.checkGcodeWithinTravel
import com.lasergrbl.core.grbl.fitSizeToTravel
import com.lasergrbl.core.text.DEFAULT_TEXT_FONT
import com.lasergrbl.core.text.HersheyOptions
import com.lasergrbl.core.text.HersheyOrientation
import com.lasergrbl.core.text.TextOrientation
import com.lasergrbl.core.text.textToGcode
import com.lasergrbl.core.vector.PotraceImage
import com.lasergrbl.core.vector.TextVectorOptions
import com.lasergrbl.core.vector.TextVectorResult
import com.lasergrbl.core.vector.VECTOR_TOOL_LABELS
import com.lasergrbl.core.vector.VectorTool
import com.lasergrbl.core.vector.convertTextVector
import com.lasergrbl.core.vector.decideTextEngine
import com.lasergrbl.core.vector.hasNonAscii
import com.lasergrbl.glasskit.LiquidAlert
import com.lasergrbl.glasskit.LiquidAlertType
import com.lasergrbl.glasskit.LiquidRow
import com.lasergrbl.glasskit.LiquidSpinner
import com.lasergrbl.glasskit.LiquidTextField
import com.lasergrbl.glasskit.theme.LiquidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文字转雕刻 —— 对应 v2 `src/ui/views/TextConvertView.vue`（442 行）。
 *
 * ### v2 的控件清单与去向（§3：输入 11、开关 4、行 4、警告 3）
 *
 * | v2 区块 | 控件 | 3.0 实现 |
 * | --- | --- | --- |
 * | 文字内容 | 多行输入（`rows=3`）、placeholder、Hershey 不支持中文的**警告条** | `LiquidTextField` + `LiquidAlert(Warning)`，文案逐字保留 |
 * | 生成方式 | 4 个引擎按钮（Hershey / 轮廓描线 / 中心线走线 / 智能）、`isVector` 说明、Auto 的 `reason` | `ConvertChipRow` + `decideTextEngine()`（`:core`，黄金样本钉过文案） |
 * | 排版 | 横向 / 纵向排版按钮 | `ConvertChipRow` |
 * | 排版 | 字体下拉（4 个预设） | `ConvertInlineSelect`（**弹层 → 行内清单**） |
 * | 排版 | 字号 / 字高、行距倍率、起点 X、起点 Y | 数值输入（失焦提交、非法回退） |
 * | 排版 | 加粗开关 | 实现 |
 * | 矢量参数 | 二值化阈值、去斑面积（Outline）/ 去毛刺 + 简化容差（Centerline）、反相、路径排序优化 | 实现 |
 * | 参数 | 雕刻速度、激光功率 S、硬件 PWM | 实现 |
 * | 警告 | 行程适配提示 + 超出行程提示 | `LiquidAlert(Warning)` |
 * | 结果 | 智能判定说明、栅格化预览、尺寸 / 走线段数 / 长度、路径预览、行数 | 实现 |
 *
 * ### 有意差异（逐条记录）
 * 1. **未提供"保存文件" / "去雕刻"按钮**：生成结果通过
 *    `container.grbl.loadGcodeLines(name, lines)` 交给状态层，与 v2 的 `loadGcodeLines` 同语义；
 *    落盘与"去雕刻"由 `/file` 屏承担（那里有 `GcodeFileStore` 的"另存"，底部标签栏也有"首页"）。
 * 2. **字体下拉不影响渲染**：v2 的 `fontFamily` 是 CSS 字体栈；`AndroidTextRasterizer` 用
 *    `Typeface.DEFAULT` / `DEFAULT_BOLD`（`:app` 只保证算法与参数一致，WebView 字体栈在
 *    Android 上不可得 —— 见 `docs/PHASE3-PLATFORM-MODULES.md` §5 与 `AndroidTextRasterizer` 的 KDoc）。
 *    这里**保留同一份 4 个预设与中文标签**，并显示一行说明；选择不会改变输出。
 * 3. **`renderSizePx` 使用 `:core` 的默认值 180**：v2 的 `convertTextVector` 把 `TextRenderOptions`
 *    原样传给 `renderTextToImage`（`o` 里没有 `renderSizePx`，于是走默认 180）；3.0 一致。
 * 4. **转换放 `Dispatchers.Default`**（v2 在主线程同步跑）。
 * 5. **下拉变行内清单**：同图片屏 —— 屏幕拿不到 `LiquidScaffold` 的 `overlay` 槽。
 *
 * ### 无法离线验证的条目
 *  * 中文/英文文字的真实栅格化结果（需要设备上的 Android `Canvas` + 系统字体）；
 *  * 字体预设对渲染的影响（**已知无影响**，见差异 2）；
 *  * 垂直排版的旋转换算在真机上的观感（算法在 `AndroidTextRasterizer`，已由单测/审计覆盖接线，但未做像素级比对）；
 *  * Hershey 路径的 G 代码正确性（`:core` 已有黄金样本，本屏只做接线）。
 */
@Composable
fun TextConvertScreen(container: AppContainer, backdrop: Backdrop, screen: Screen.ConvertText) {
    val colors = LiquidTheme.colors
    val scope = rememberCoroutineScope()
    val settings = container.grbl.settings

    var text by remember { mutableStateOf(screen.initialText ?: "激光雕刻") }
    var engineIndex by remember { mutableIntStateOf(0) }
    val engine = TEXT_ENGINE_ORDER[engineIndex]

    var sizeMm by remember { mutableStateOf(20.0) }
    var orientation by remember { mutableStateOf(TextOrientation.Horizontal) }
    var bold by remember { mutableStateOf(false) }
    var lineSpacing by remember { mutableStateOf(1.5) }
    var offsetX by remember { mutableStateOf(0.0) }
    var offsetY by remember { mutableStateOf(0.0) }
    var fontFamily by remember { mutableStateOf(DEFAULT_TEXT_FONT) }

    var threshold by remember { mutableStateOf(50.0) }
    var invert by remember { mutableStateOf(false) }
    var optimize by remember { mutableStateOf(true) }
    var turdSize by remember { mutableStateOf(2.0) }
    var minBranchPx by remember { mutableStateOf(6.0) }
    var simplifyTolerance by remember { mutableStateOf(1.2) }

    var markSpeed by remember { mutableStateOf(convertSettingNum(settings, "Mark Speed", 1000.0)) }
    var maxPower by remember { mutableStateOf(convertSettingNum(settings, "Max Power", 1000.0)) }
    var minPower by remember { mutableStateOf(convertSettingNum(settings, "Min Power", 0.0)) }
    var pwm by remember { mutableStateOf(convertSettingBool(settings, "Support Hardware PWM", true)) }
    var laserOn by remember { mutableStateOf(convertSettingString(settings, "Laser On Command", "M4")) }
    var laserOff by remember { mutableStateOf(convertSettingString(settings, "Laser Off Command", "M5")) }
    var header by remember { mutableStateOf(convertSettingString(settings, "Header", "G90")) }
    var footer by remember { mutableStateOf(convertSettingString(settings, "Footer", "M5\nG0 X0 Y0")) }

    var busy by remember { mutableStateOf(false) }
    var warn by remember { mutableStateOf("") }
    var previewUrl by remember { mutableStateOf<Bitmap?>(null) }
    var extraInfo by remember { mutableStateOf("") }
    var generated by remember { mutableStateOf<GcodeFileData?>(null) }

    // 智能模式的实时判定（v2 的 computed）
    val decision = decideTextEngine(text)
    /** 实际使用的引擎（v2 `effectiveEngine`）。 */
    val effectiveEngine: TextEngine = if (engine == TextEngine.Auto) {
        if (decision.engine == com.lasergrbl.core.vector.TextSmartEngine.Hershey) TextEngine.Hershey
        else TextEngine.Centerline
    } else {
        engine
    }
    val isVector = effectiveEngine != TextEngine.Hershey
    /** Hershey 单线字体无法渲染非 ASCII（中文）字符（v2 `cjkBlocked`）。 */
    val cjkBlocked = engine == TextEngine.Hershey && hasNonAscii(text)
    val canGenerate = text.trim().isNotEmpty() && sizeMm > 0 && !cjkBlocked

    /** 生成路径（v2 `generate()`），整段 CPU 密集工作放 `Dispatchers.Default`。 */
    fun generate() {
        if (!canGenerate) {
            if (cjkBlocked) {
                container.toasts.show("Hershey 单线字体不支持中文，请切换到轮廓描线或中心线走线", LiquidAlertType.Error)
            } else {
                container.toasts.show("请输入文字内容", LiquidAlertType.Error)
            }
            return
        }
        if (busy) return
        busy = true
        warn = ""
        // 把界面状态拍成不可变参数，交给文件级纯函数
        val params = TextConvertParams(
            text = text,
            isVector = isVector,
            effectiveEngine = effectiveEngine,
            sizeMm = sizeMm,
            orientation = orientation,
            bold = bold,
            lineSpacing = lineSpacing,
            offsetX = offsetX,
            offsetY = offsetY,
            fontFamily = fontFamily,
            threshold = threshold,
            invert = invert,
            optimize = optimize,
            turdSize = turdSize,
            minBranchPx = minBranchPx,
            simplifyTolerance = simplifyTolerance,
            markSpeed = markSpeed,
            minPower = minPower,
            maxPower = maxPower,
            laserOn = laserOn,
            laserOff = laserOff,
            pwm = pwm,
            header = header,
            footer = footer
        )
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.Default) { buildTextOutcome(params) }
                if (outcome == null) {
                    // v2：`convertTextVector` 返回空结果时 toast「文字为空，无法生成」并中止
                    container.toasts.show("文字为空，无法生成", LiquidAlertType.Error)
                    return@launch
                }
                warn = outcome.warn
                extraInfo = outcome.extra
                previewUrl = outcome.preview
                container.grbl.loadGcodeLines(outcome.name, outcome.lines)
                generated = container.grbl.file
                container.toasts.show("已生成 ${outcome.lines.size} 行 G 代码")
            } catch (e: Throwable) {
                container.toasts.show("生成失败：${e.message ?: e.toString()}", LiquidAlertType.Error)
            } finally {
                busy = false
            }
        }
    }

    GlassScreenBody {
        // ================= 文字内容 =================
        GlassCard(backdrop) {
            Column(Modifier.fillMaxWidth()) {
                GlassSectionHeader("文字内容")
                Spacer(Modifier.height(8.dp))
                LiquidTextField(
                    value = text,
                    onValueChange = { text = it },
                    backdrop = backdrop,
                    placeholder = "输入要雕刻的文字，支持中文与换行",
                    singleLine = false,
                    minLines = 3
                )
                if (cjkBlocked) {
                    Spacer(Modifier.height(8.dp))
                    LiquidAlert(
                        title = "当前为 Hershey 单线字体，无法渲染中文，请切换到「轮廓描线」或「中心线走线」。",
                        type = LiquidAlertType.Warning
                    )
                }
            }
        }

        // ================= 生成方式 =================
        GlassCard(backdrop) {
            Column(Modifier.fillMaxWidth()) {
                GlassSectionHeader("生成方式")
                Spacer(Modifier.height(8.dp))
                ConvertChipRow(
                    backdrop = backdrop,
                    options = TEXT_ENGINE_ORDER.map { TEXT_ENGINE_LABELS.getValue(it) },
                    selectedIndex = engineIndex,
                    onSelect = { engineIndex = it }
                )
                Spacer(Modifier.height(8.dp))
                ConvertHint(
                    if (isVector) "栅格化后沿轮廓 / 笔画走线，支持中文等任意字符。"
                    else "内置矢量单线字体，仅支持英文、数字与常见符号。"
                )
                if (engine == TextEngine.Auto) {
                    Spacer(Modifier.height(6.dp))
                    ConvertHint(decision.reason)
                }
            }
        }

        // ================= 排版 =================
        GlassCard(backdrop) {
            Column(Modifier.fillMaxWidth()) {
                GlassSectionHeader("排版")
                Spacer(Modifier.height(8.dp))
                ConvertHint("字体朝向")
                Spacer(Modifier.height(6.dp))
                ConvertChipRow(
                    backdrop = backdrop,
                    options = listOf("横向排版", "纵向排版"),
                    selectedIndex = if (orientation == TextOrientation.Horizontal) 0 else 1,
                    onSelect = {
                        orientation = if (it == 0) TextOrientation.Horizontal else TextOrientation.Vertical
                    }
                )

                if (isVector) {
                    Spacer(Modifier.height(8.dp))
                    ConvertInlineSelect(
                        label = "字体",
                        title = "字体",
                        value = FONT_PRESETS.firstOrNull { it.first == fontFamily }?.second
                            ?: FONT_PRESETS[0].second,
                        options = FONT_PRESETS.map { it.second },
                        onSelect = { fontFamily = FONT_PRESETS[it].first }
                    )
                    ConvertHint("Android 平台使用系统默认字体渲染，与浏览器字体栈必然有差异；此项仅作记录。")
                }

                Spacer(Modifier.height(10.dp))
                ConvertNumGrid(
                    listOf(
                        ConvertNumSpec("字号 / 字高 (mm)", convertFmt(sizeMm)) { sizeMm = it },
                        ConvertNumSpec("行距倍率", convertFmt(lineSpacing)) { lineSpacing = it },
                        ConvertNumSpec("起点 X (mm)", convertFmt(offsetX)) { offsetX = it },
                        ConvertNumSpec("起点 Y (mm)", convertFmt(offsetY)) { offsetY = it }
                    )
                )
                Spacer(Modifier.height(6.dp))
                LiquidRow(
                    title = "加粗",
                    description = "笔画更粗",
                    backdrop = backdrop,
                    trailing = { GlassSwitch(backdrop, bold, onCheckedChange = { bold = it }) }
                )
            }
        }

        // ================= 矢量参数 =================
        if (isVector) {
            GlassCard(backdrop) {
                Column(Modifier.fillMaxWidth()) {
                    GlassSectionHeader(
                        if (effectiveEngine == TextEngine.Centerline) "中心线参数" else "轮廓描线参数"
                    )
                    if (engine == TextEngine.Auto && effectiveEngine == TextEngine.Centerline) {
                        Spacer(Modifier.height(6.dp))
                        ConvertHint(
                            "智能模式判定为含中文等非 ASCII 字符：Hershey 无该字符字形，改用中心线（骨架化）走线。"
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ConvertNumGrid(
                        if (effectiveEngine == TextEngine.Outline) {
                            listOf(
                                ConvertNumSpec("二值化阈值 (%)", convertFmt(threshold)) { threshold = it },
                                ConvertNumSpec("去斑面积 (像素)", convertFmt(turdSize)) { turdSize = it }
                            )
                        } else {
                            listOf(
                                ConvertNumSpec("二值化阈值 (%)", convertFmt(threshold)) { threshold = it },
                                ConvertNumSpec("去毛刺长度 (像素)", convertFmt(minBranchPx)) { minBranchPx = it },
                                ConvertNumSpec("简化容差 (像素)", convertFmt(simplifyTolerance)) {
                                    simplifyTolerance = it
                                }
                            )
                        }
                    )
                    LiquidRow(
                        title = "反相",
                        description = "深底浅字时启用",
                        backdrop = backdrop,
                        trailing = { GlassSwitch(backdrop, invert, onCheckedChange = { invert = it }) }
                    )
                    LiquidRow(
                        title = "路径排序优化",
                        description = "最近邻排序，缩短空移距离",
                        backdrop = backdrop,
                        trailing = { GlassSwitch(backdrop, optimize, onCheckedChange = { optimize = it }) }
                    )
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
                        ConvertNumSpec("激光功率 S", convertFmt(maxPower)) { maxPower = it }
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
            // v2 是 `:loading="busy" :disabled="!canGenerate"`
            loading = busy,
            enabled = canGenerate
        ) {
            GlassIcon(name = "layers", size = 17.dp, tint = colors.text)
            Spacer(Modifier.size(6.dp))
            GlassText("生成雕刻路径", color = colors.text, fontSize = 14.sp)
        }

        // ================= 结果 =================
        val file = generated
        if (file != null) {
            GlassCard(backdrop) {
                Column(Modifier.fillMaxWidth()) {
                    if (engine == TextEngine.Auto) {
                        LiquidAlert(title = decision.reason, type = LiquidAlertType.Info)
                        Spacer(Modifier.height(8.dp))
                    }
                    val bmp = previewUrl
                    if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                        GlassText("栅格化预览", color = colors.text, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.fill1)
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "栅格化预览",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (extraInfo.isNotEmpty()) {
                        ConvertHint(extraInfo)
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassText("路径预览", color = colors.text, fontSize = 14.sp)
                        GlassText("${file.stats.totalLines} 行", color = colors.textDim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
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

/** v2 的 `Engine`：Hershey 单线 + 两种线性 + 智能。 */
private enum class TextEngine(val key: String) {
    Hershey("Hershey"),
    Outline("Outline"),
    Centerline("Centerline"),
    Auto("Auto")
}

/** v2 `ENGINES` 的顺序，逐一对齐。 */
private val TEXT_ENGINE_ORDER = listOf(
    TextEngine.Hershey,
    TextEngine.Outline,
    TextEngine.Centerline,
    TextEngine.Auto
)

/** v2 `ENGINE_LABELS`（逐字，后两项取 `VECTOR_TOOL_LABELS`）。 */
private val TEXT_ENGINE_LABELS: Map<TextEngine, String> = mapOf(
    TextEngine.Hershey to "Hershey 单线",
    TextEngine.Outline to VECTOR_TOOL_LABELS.getValue(VectorTool.Outline),
    TextEngine.Centerline to VECTOR_TOOL_LABELS.getValue(VectorTool.Centerline),
    TextEngine.Auto to "智能"
)

/** v2 `FONT_PRESETS`（4 个预设，中文标签逐字保留；见屏幕 KDoc 的差异 2）。 */
private val FONT_PRESETS: List<Pair<String, String>> = listOf(
    DEFAULT_TEXT_FONT to "系统默认（支持中文）",
    "\"Noto Sans CJK SC\", \"Source Han Sans SC\", sans-serif" to "思源黑体 / Noto Sans CJK",
    "\"Noto Serif CJK SC\", \"Source Han Serif SC\", serif" to "思源宋体 / Noto Serif CJK",
    "\"Microsoft YaHei\", \"PingFang SC\", sans-serif" to "微软雅黑 / 苹方"
)

/** 一次生成的结果（编排与 UI 状态分离，便于整体放进 `Dispatchers.Default`）。 */
private class TextOutcome(
    val lines: List<String>,
    val name: String,
    val warn: String,
    val extra: String,
    val preview: Bitmap?
)

/** 生成的输入快照（把屏幕里的一堆 `var` 拍成不可变参数）。 */
private class TextConvertParams(
    val text: String,
    val isVector: Boolean,
    val effectiveEngine: TextEngine,
    val sizeMm: Double,
    val orientation: TextOrientation,
    val bold: Boolean,
    val lineSpacing: Double,
    val offsetX: Double,
    val offsetY: Double,
    val fontFamily: String,
    val threshold: Double,
    val invert: Boolean,
    val optimize: Boolean,
    val turdSize: Double,
    val minBranchPx: Double,
    val simplifyTolerance: Double,
    val markSpeed: Double,
    val minPower: Double,
    val maxPower: Double,
    val laserOn: String,
    val laserOff: String,
    val pwm: Boolean,
    val header: String,
    val footer: String
)

/**
 * 组装位图矢量化选项 —— 逐字段对齐 v2 `convertTextVector({ ... })` 的入参
 * （含 `alphaMax = 1`、`optTolerance = 0.2`、`curveOptimizing = true`、`flattenTolerance = 0.2`
 * 这些 v2 在本屏写死的值）。
 */
private fun textVectorOptions(p: TextConvertParams, size: Double): TextVectorOptions = TextVectorOptions(
    text = p.text,
    sizeMm = size,
    bold = p.bold,
    orientation = p.orientation,
    lineSpacing = p.lineSpacing,
    fontFamily = p.fontFamily,
    threshold = Math.round(p.threshold / 100.0 * 255.0).toDouble(),
    invert = p.invert,
    offsetX = p.offsetX,
    offsetY = p.offsetY,
    markSpeed = p.markSpeed,
    travelSpeed = 3000.0,
    minPower = p.minPower.toInt(),
    maxPower = p.maxPower.toInt(),
    laserPower = p.maxPower.toInt(),
    laserOn = p.laserOn,
    laserOff = p.laserOff,
    pwm = p.pwm,
    header = p.header,
    footer = p.footer,
    optimize = p.optimize,
    tool = if (p.effectiveEngine == TextEngine.Centerline) VectorTool.Centerline else VectorTool.Outline,
    turdSize = p.turdSize,
    alphaMax = 1.0,
    optTolerance = 0.2,
    curveOptimizing = true,
    flattenTolerance = 0.2,
    minBranchPx = p.minBranchPx,
    simplifyTolerance = p.simplifyTolerance
)

/**
 * 文字生成编排 —— 逐行对应 v2 `generate()`：
 *  * Hershey 分支：`textToGcode(HersheyOptions(...))`，然后 `checkGcodeWithinTravel` 收警告；
 *  * 矢量分支（`generateVector()`）：
 *      1. 先 `convertTextVector(sizeMm)` **测量**墨迹尺寸（v2 的 `renderTextToImage` 探针）；
 *      2. `fitSizeToTravel(measure.widthMm, measure.heightMm, mode = 'Fit')`；
 *      3. `scale = fit.widthMm / measure.widthMm`，`effectiveSizeMm = max(0.5, round2(sizeMm * scale))`；
 *      4. 用 `effectiveSizeMm` 重新 `convertTextVector`；
 *      5. `checkGcodeWithinTravel` 收警告；统计文案的格式与 v2 逐字一致。
 *
 * 返回 null 表示"文字为空，无法生成"（`widthMm <= 0 || heightMm <= 0`，与 v2 一致）。
 */
private fun buildTextOutcome(p: TextConvertParams): TextOutcome? {
    if (!p.isVector) {
        // Hershey 单线矢量（仅 ASCII）
        val res = textToGcode(
            HersheyOptions(
                text = p.text,
                orientation = if (p.orientation == TextOrientation.Horizontal) {
                    HersheyOrientation.HORIZONTAL
                } else {
                    HersheyOrientation.VERTICAL
                },
                sizeMm = p.sizeMm,
                bold = p.bold,
                lineSpacing = p.lineSpacing,
                offsetX = p.offsetX,
                offsetY = p.offsetY,
                markSpeed = p.markSpeed,
                laserOn = p.laserOn,
                laserOff = p.laserOff,
                pwm = p.pwm,
                maxPower = p.maxPower
            )
        )
        val warning = appendWarn("", checkGcodeWithinTravel(res.lines).message)
        return TextOutcome(
            lines = res.lines,
            name = textFileName(p.text),
            warn = warning,
            extra = "",
            preview = null
        )
    }

    // 先按设备行程等比适配，再把缩放进 sizeMm 重算（v2 `generateVector()`）
    val rasterizer = AndroidTextRasterizer()
    val probe = convertTextVector(textVectorOptions(p, p.sizeMm), rasterizer)
    if (probe.widthMm <= 0 || probe.heightMm <= 0) return null
    var warning = ""
    val fit = fitSizeToTravel(probe.widthMm, probe.heightMm, mode = FitMode.Fit)
    warning = appendWarn(warning, fit.message)
    val scale = if (probe.widthMm > 0) fit.widthMm / probe.widthMm else 1.0
    val effectiveSizeMm = maxOf(0.5, round2Text(p.sizeMm * scale))
    val options = textVectorOptions(p, effectiveSizeMm)
    val res: TextVectorResult = convertTextVector(options, rasterizer)
    warning = appendWarn(warning, checkGcodeWithinTravel(res.lines).message)
    return TextOutcome(
        lines = res.lines,
        name = textFileName(p.text),
        warn = warning,
        extra = "尺寸 ${convertFmt(res.widthMm, 1)} × ${convertFmt(res.heightMm, 1)} mm · " +
            "走线 ${res.pathCount} 段 · 路径长度 ${convertFmt(res.lengthMm, 1)} mm",
        preview = runCatching {
            // 结果里只有像素数组，尺寸从"同一份渲染选项"再算一次标题宽度（纯计算，代价低）
            val r = rasterizer.render(options.toTextRenderOptions())
            AndroidBitmapBridge.write(PreviewImageText(res.preview, r.image.width, r.image.height))
        }.getOrNull()
    )
}

/** 供 `AndroidBitmapBridge.write` 复用的 RGBA 视图（`:core` 的结果不带宽高）。 */
private class PreviewImageText(
    override val data: IntArray,
    override val width: Int,
    override val height: Int
) : PotraceImage

/** v2：`文字-${首行前 12 字符 || 'text'}.gcode`。 */
private fun textFileName(text: String): String {
    val head = text.trim().split('\n').firstOrNull()?.take(12).orEmpty()
    return "文字-${head.ifEmpty { "text" }}.gcode"
}

/** 保留 2 位小数（v2 `Math.round(v * 100) / 100`）。 */
private fun round2Text(v: Double): Double = Math.round(v * 100.0) / 100.0
