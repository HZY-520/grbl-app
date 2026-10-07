package com.lasergrbl.android.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
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
import com.lasergrbl.core.gcode.GcodeFileData
import com.lasergrbl.core.grbl.FitMode
import com.lasergrbl.core.grbl.checkGcodeWithinTravel
import com.lasergrbl.core.grbl.fitSizeToTravel
import com.lasergrbl.core.raster.DIRECTION_LABELS
import com.lasergrbl.core.raster.DITHERING_LABELS
import com.lasergrbl.core.raster.DITHERING_MODES
import com.lasergrbl.core.raster.DitheringMode
import com.lasergrbl.core.raster.FORMULA_LABELS
import com.lasergrbl.core.raster.Formula
import com.lasergrbl.core.raster.Interpolation
import com.lasergrbl.core.raster.RasterDirection
import com.lasergrbl.core.raster.RasterOptions
import com.lasergrbl.core.raster.RasterTool
import com.lasergrbl.core.raster.convertImageToGcode
import com.lasergrbl.core.vector.DEFAULT_IMAGE_VECTOR_OPTIONS
import com.lasergrbl.core.vector.ImageVectorOptions
import com.lasergrbl.core.vector.PotraceImage
import com.lasergrbl.core.vector.SmartVectorOptions
import com.lasergrbl.core.vector.VECTOR_TOOL_LABELS
import com.lasergrbl.core.vector.VectorTool
import com.lasergrbl.core.vector.convertImageVector
import com.lasergrbl.core.vector.convertImageVectorSmart
import com.lasergrbl.glasskit.LiquidAlert
import com.lasergrbl.glasskit.LiquidAlertType
import com.lasergrbl.glasskit.LiquidDisclosure
import com.lasergrbl.glasskit.LiquidRow
import com.lasergrbl.glasskit.LiquidSpinner
import com.lasergrbl.glasskit.theme.LiquidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片转雕刻 —— 对应 v2 `src/ui/views/ImageConvertView.vue`（685 行）。
 *
 * ### v2 的控件清单与去向（`docs/PHASE4-SCREEN-CONVENTIONS.md` §3：输入 17、开关 9、行 9、
 * 滑块 5、下拉 3、警告 2、折叠 1）
 *
 * | v2 区块 | 控件 | 3.0 实现 |
 * | --- | --- | --- |
 * | 图片 | 点击选择 / 更换、缩略图、`宽 × 高 px`、"支持 JPG / PNG / BMP / GIF / WEBP" | 卡片 + 缩略图 + `rememberFilePicker()` |
 * | 尺寸 | 宽度、高度（自动时禁用）、`高度按比例自动` 开关、行程适配说明 | 全部实现 |
 * | 转换方式 | 5 个方式按钮、`isVector` 说明、Auto 的判定说明 | `ConvertChipRow`；两段中文逐字保留 |
 * | 转换方式 | 扫描方向下拉（3 项） | `ConvertInlineSelect`（**弹层 → 行内清单**，见该函数说明） |
 * | 转换方式 | 分辨率 (线/mm) / 二值化阈值 (%)、起始偏移 X/Y | 数值输入（失焦提交、非法回退） |
 * | 转换方式 | 反相开关 | 实现 |
 * | 抖动算法 | 抖动模式下拉（9 项） | `ConvertInlineSelect` |
 * | 线性参数 | Outline：去斑面积 / 圆角阈值；Centerline：去毛刺 / 简化容差 | 实现 |
 * | 线性参数 | Auto：笔画宽度阈值滑块 + 三段说明 + 两组参数 | 实现 |
 * | 线性参数 | `轮廓曲线优化`（Centerline 禁用）、`路径排序优化` | 实现 |
 * | 雕刻参数 | 雕刻速度 / 最大功率 / 最小功率 + 硬件 PWM + 单向雕刻 + 禁用 G0 快速空移 | 实现（后两者仅光栅显示） |
 * | 进阶预处理（折叠） | 灰度公式下拉 + 亮度 / 对比度 / 白色裁剪滑块 + 启用阈值化 + 阈值 + 高质量插值 | `LiquidDisclosure`，全部实现 |
 * | 警告 | 行程适配提示 + 超出行程提示 | `LiquidAlert(Warning)`，文案由 `:core` 产出 |
 * | 结果 | 智能判定、二值化预览 + 像素信息、走线统计、路径预览 | 实现 |
 *
 * ### 有意差异（逐条记录；不许把"编译通过"当成"功能可用"）
 * 1. **未提供"保存文件" / "去雕刻"按钮**：生成结果通过
 *    `container.grbl.loadGcodeLines(name, lines)` 交给状态层（与 v2 的 `loadGcodeLines` 同一语义），
 *    因此**预览、运行、保存共用同一份**；落盘与"去雕刻"由 `/file` 屏承担
 *    （那里有 `GcodeFileStore` 的"另存"，底部标签栏也有"首页"）。本屏不自建文件选择器，
 *    避免与 `/file` 屏的保存路径产生两份 gcode。
 * 2. **下拉变行内清单**：v2 的 `GlassSelect` 点开是底部弹层，而 3.0 的弹层必须由 `AppShell`
 *    放进 `LiquidScaffold` 的 `overlay` 槽，屏幕拿不到 —— 详见 [ConvertInlineSelect]。
 * 3. **转换放 `Dispatchers.Default`**：v2 在主线程同步跑（大图直接卡死界面）；
 *    这里协程 + `withContext(Dispatchers.Default)`，生成期间按钮走 `loading` 态。
 * 4. **原图预览按 4096 px 长边降采样**：避免真机 OOM（线性模式的处理分辨率另有 1600 px 上限，与 v2 相同）。
 * 5. **线性模式的重采样**走 `container.resampler`（`:app` 的 `TriangleResampler`）：
 *    与 v2 的浏览器 canvas 重采样**不可能逐像素一致**（Phase 3 已记录该差异）。
 * 6. **`Laser On Command` 沿用设置里的值（默认 `M4`）**：v2 的本屏是从
 *    `AppSettings.get('Laser On Command', 'M4')` 取值（不是 core 的 `M3`），3.0 一致。
 *
 * ### 无法离线验证的条目
 *  * 真实图片解码（需要设备上的 `ContentResolver` + `BitmapFactory`）与缩略图显示；
 *  * `container.resampler` 与 v2 canvas 重采样的像素一致性（黄金样本不含重采样路径）；
 *  * 大图在真机上的峰值内存与生成耗时（含 1600 px 线性上限、22000×22000 光栅上限）；
 *  * 智能模式 `decision.summary` 的中文文案在真实图片上的分支覆盖率；
 *  * 所有 `:core` 转换算法本身已由黄金样本钉住，本屏只做接线，算法正确性不在本屏验证范围。
 */
@Composable
fun ImageConvertScreen(container: AppContainer, backdrop: Backdrop, screen: Screen.ConvertImage) {
    val colors = LiquidTheme.colors
    val scope = rememberCoroutineScope()
    val picker = rememberFilePicker()
    val settings = container.grbl.settings

    // ---- 源图与尺寸 ----
    var source by remember { mutableStateOf<PotraceImage?>(null) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var imgName by remember { mutableStateOf("") }
    var imgW by remember { mutableIntStateOf(0) }
    var imgH by remember { mutableIntStateOf(0) }

    var widthMm by remember { mutableStateOf(50.0) }
    var heightMm by remember { mutableStateOf(50.0) }
    var autoHeight by remember { mutableStateOf(true) }

    // ---- 转换方式与参数（默认值 = v2 的 DEFAULT_RASTER_OPTIONS + AppSettings）----
    var toolIndex by remember { mutableIntStateOf(0) }
    val tool = TOOL_ORDER[toolIndex]

    var direction by remember { mutableStateOf(RasterDirection.Horizontal) }
    var quality by remember { mutableStateOf(3.0) }
    var dithering by remember { mutableStateOf(DitheringMode.FloydSteinberg) }
    var offsetX by remember { mutableStateOf(0.0) }
    var offsetY by remember { mutableStateOf(0.0) }

    var vecThreshold by remember { mutableStateOf(50.0) }
    var vecInvert by remember { mutableStateOf(false) }
    var vecTurdSize by remember { mutableStateOf(DEFAULT_IMAGE_VECTOR_OPTIONS.turdSize) }
    var vecAlphaMax by remember { mutableStateOf(DEFAULT_IMAGE_VECTOR_OPTIONS.alphaMax) }
    var vecCurveOptimizing by remember { mutableStateOf(DEFAULT_IMAGE_VECTOR_OPTIONS.curveOptimizing) }
    var vecMinBranch by remember { mutableStateOf(DEFAULT_IMAGE_VECTOR_OPTIONS.minBranchPx) }
    var vecSimplify by remember { mutableStateOf(DEFAULT_IMAGE_VECTOR_OPTIONS.simplifyTolerance) }
    var vecStrokeWidthPct by remember { mutableStateOf(2.5f) }
    var vecOptimize by remember { mutableStateOf(true) }

    var markSpeed by remember { mutableStateOf(convertSettingNum(settings, "Mark Speed", 1000.0)) }
    var minPower by remember { mutableStateOf(convertSettingNum(settings, "Min Power", 0.0)) }
    var maxPower by remember { mutableStateOf(convertSettingNum(settings, "Max Power", 1000.0)) }
    var laserOn by remember { mutableStateOf(convertSettingString(settings, "Laser On Command", "M4")) }
    var laserOff by remember { mutableStateOf(convertSettingString(settings, "Laser Off Command", "M5")) }
    var pwm by remember { mutableStateOf(convertSettingBool(settings, "Support Hardware PWM", true)) }
    var unidirectional by remember { mutableStateOf(convertSettingBool(settings, "Unidirectional Engraving", false)) }
    var disableFastSkip by remember { mutableStateOf(convertSettingBool(settings, "Disable G0 fast skip", false)) }
    var header by remember { mutableStateOf(convertSettingString(settings, "Header", "G90")) }
    var footer by remember { mutableStateOf(convertSettingString(settings, "Footer", "M5\nG0 X0 Y0")) }

    var formula by remember { mutableStateOf(Formula.SimpleAverage) }
    var brightness by remember { mutableStateOf(100f) }
    var contrast by remember { mutableStateOf(100f) }
    var whiteClip by remember { mutableStateOf(5f) }
    var useThreshold by remember { mutableStateOf(false) }
    var rasterThreshold by remember { mutableStateOf(50f) }
    var highQuality by remember { mutableStateOf(true) }

    // ---- 结果 ----
    var busy by remember { mutableStateOf(false) }
    var warn by remember { mutableStateOf("") }
    var decisionText by remember { mutableStateOf("") }
    var pixelInfo by remember { mutableStateOf("") }
    var extraInfo by remember { mutableStateOf("") }
    var processedPreview by remember { mutableStateOf<Bitmap?>(null) }
    var generated by remember { mutableStateOf<GcodeFileData?>(null) }

    val isVector = tool != ConvertTool.Line2Line && tool != ConvertTool.Dithering

    /** v2 `effectiveHeight`：自动时按原始宽高比换算，保留 2 位小数、下限 1。 */
    fun effectiveHeight(): Double =
        if (autoHeight) {
            maxOf(1.0, round2(widthMm * (if (imgW > 0) imgH.toDouble() / imgW else 1.0)))
        } else {
            heightMm
        }

    /** v2 `pick()` 的成功分支（此处抽出便于复用初始 URI 分支）。 */
    fun acceptImage(name: String, w: Int, h: Int, rgba: PotraceImage, thumb: Bitmap) {
        imgName = name
        imgW = w
        imgH = h
        source = rgba
        thumbnail = thumb
        if (autoHeight && w > 0) heightMm = round2(widthMm * h.toDouble() / w)
        decisionText = ""
        pixelInfo = ""
        extraInfo = ""
        processedPreview = null
        generated = null
        warn = ""
    }

    /** 选择图片（v2 `pick()`）。 */
    fun pick() {
        scope.launch {
            val picked = picker.pick(IMAGE_MIME)
            if (picked == null) {
                // 用户取消时静默返回；打不开 / 超限时才提示（v2 在取消时同样不提示）
                return@launch
            }
            val bmp = picked.bitmap
            if (bmp == null) {
                container.toasts.show(
                    if (picked.looksLikeSvg) "请选择图片文件" else "图片加载失败：无法解码该文件",
                    LiquidAlertType.Error
                )
                return@launch
            }
            // killAlpha = true：与 v2 `loadImage` 后先铺白底一致（透明像素合成到白）
            val rgba = AndroidBitmapBridge.read(bmp, killAlpha = true)
            acceptImage(picked.name, bmp.width, bmp.height, rgba, makeThumbnail(bmp))
            container.toasts.show("已导入 ${picked.name}（${bmp.width}×${bmp.height}）")
        }
    }

    /** 生成路径（v2 `generate()`）。整段 CPU 密集工作在 `Dispatchers.Default`。 */
    fun generate() {
        val src = source
        if (src == null || imgW <= 0 || imgH <= 0) {
            container.toasts.show("请先导入图片", LiquidAlertType.Error)
            return
        }
        if (busy) return
        busy = true
        warn = ""
        // 把界面状态拍成不可变参数，交给文件级纯函数（便于放进 Dispatchers.Default 并保持可读）
        val params = ImageConvertParams(
            source = src,
            sourceName = imgName,
            srcW = imgW,
            srcH = imgH,
            widthMm = widthMm,
            heightMm = effectiveHeight(),
            tool = tool,
            direction = direction,
            quality = quality,
            dithering = dithering,
            offsetX = offsetX,
            offsetY = offsetY,
            vecThreshold = vecThreshold,
            vecInvert = vecInvert,
            vecTurdSize = vecTurdSize,
            vecAlphaMax = vecAlphaMax,
            vecCurveOptimizing = vecCurveOptimizing,
            vecMinBranch = vecMinBranch,
            vecSimplify = vecSimplify,
            vecStrokeWidthPct = vecStrokeWidthPct.toDouble(),
            vecOptimize = vecOptimize,
            markSpeed = markSpeed,
            minPower = minPower,
            maxPower = maxPower,
            laserOn = laserOn,
            laserOff = laserOff,
            pwm = pwm,
            unidirectional = unidirectional,
            disableFastSkip = disableFastSkip,
            header = header,
            footer = footer,
            formula = formula,
            brightness = brightness.toDouble(),
            contrast = contrast.toDouble(),
            whiteClip = whiteClip.toDouble(),
            useThreshold = useThreshold,
            rasterThreshold = rasterThreshold.toDouble(),
            highQuality = highQuality
        )
        scope.launch {
            try {
                val pair = withContext(Dispatchers.Default) {
                    convertImageWithParams(params, container.resampler)
                }
                val (outcome, warning) = pair
                decisionText = outcome.decision
                pixelInfo = outcome.pixel
                extraInfo = outcome.extra
                warn = warning
                processedPreview = runCatching {
                    // 预览尺寸从生成结果带出来（光栅用 pixelWidth/Height，线性用处理分辨率）
                    AndroidBitmapBridge.write(PreviewImage(outcome.preview, outcome.previewW, outcome.previewH))
                }.getOrNull()
                // 结果交给状态层：预览 / 运行 / 保存共用同一份（v2 的 loadGcodeLines 语义）
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

    // 从其它屏跳转时可能带着已选 URI（`Screen.ConvertImage.sourceUri`）
    LaunchedEffect(screen.sourceUri) {
        val uri = screen.sourceUri ?: return@LaunchedEffect
        if (source != null) return@LaunchedEffect
        val picked = runCatching { readPickedFile(container.context, Uri.parse(uri)) }.getOrNull()
            ?: return@LaunchedEffect
        val bmp = picked.bitmap ?: return@LaunchedEffect
        val rgba = AndroidBitmapBridge.read(bmp, killAlpha = true)
        acceptImage(picked.name, bmp.width, bmp.height, rgba, makeThumbnail(bmp))
    }

    GlassScreenBody {
        // ================= 图片 =================
        GlassCard(backdrop) {
            Column(Modifier.fillMaxWidth()) {
                GlassSectionHeader("图片")
                Spacer(Modifier.height(8.dp))
                if (source == null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { pick() }
                            .padding(vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        GlassIcon(name = "image", size = 30.dp, tint = colors.accent)
                        Spacer(Modifier.height(8.dp))
                        GlassText("点击选择图片", color = colors.accent, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        GlassText("支持 JPG / PNG / BMP / GIF / WEBP", color = colors.textDim, fontSize = 12.sp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val thumb = thumbnail
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = "原图",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                            )
                        }
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            GlassText(imgName, color = colors.text, fontSize = 13.5.sp, maxLines = 1)
                            GlassText("$imgW × $imgH px", color = colors.textDim, fontSize = 12.sp)
                        }
                        GlassIconButton(
                            backdrop = backdrop,
                            icon = "image",
                            label = "更换",
                            size = GlassButtonSize.Small,
                            plain = true,
                            onClick = { pick() }
                        )
                    }
                }
            }
        }

        // ================= 尺寸 =================
        GlassCard(backdrop) {
            Column(Modifier.fillMaxWidth()) {
                GlassSectionHeader("尺寸")
                Spacer(Modifier.height(8.dp))
                ConvertNumGrid(
                    listOf(
                        ConvertNumSpec("宽度 (mm)", convertFmt(widthMm)) { widthMm = it },
                        ConvertNumSpec(
                            label = "高度 (mm)",
                            value = convertFmt(if (autoHeight) effectiveHeight() else heightMm),
                            enabled = !autoHeight
                        ) { heightMm = it }
                    )
                )
                LiquidRow(
                    title = "高度按比例自动",
                    description = "保持图片宽高比",
                    backdrop = backdrop,
                    trailing = { GlassSwitch(backdrop, autoHeight, onCheckedChange = { autoHeight = it }) }
                )
                Spacer(Modifier.height(6.dp))
                ConvertHint("生成时会自动按设备行程（可在设置 / 初始化向导中修改）等比适配。")
            }
        }

        // ================= 转换方式 =================
        GlassCard(backdrop) {
            Column(Modifier.fillMaxWidth()) {
                GlassSectionHeader("转换方式")
                Spacer(Modifier.height(8.dp))
                ConvertChipRow(
                    backdrop = backdrop,
                    options = TOOL_ORDER.map { TOOL_LABELS.getValue(it) },
                    selectedIndex = toolIndex,
                    onSelect = { toolIndex = it }
                )
                Spacer(Modifier.height(8.dp))
                ConvertHint(
                    if (isVector) "沿图形轮廓 / 笔画走线，不是水平轮询。"
                    else "逐行扫描出光，适合照片 / 渐变色块。"
                )
                if (tool == ConvertTool.Auto) {
                    Spacer(Modifier.height(6.dp))
                    ConvertHint(
                        "自动判断：平均笔画宽度 ≈ 墨水面积 / 骨架长度，占图像短边不超过阈值（默认 2.5%）" +
                            "判为线稿 → 中心线描线；否则判为实心图案 → 轮廓提取。"
                    )
                }

                if (!isVector) {
                    Spacer(Modifier.height(8.dp))
                    ConvertInlineSelect(
                        label = "扫描方向",
                        title = "扫描方向",
                        value = DIRECTION_LABELS.getValue(direction),
                        options = DIRECTION_ORDER.map { DIRECTION_LABELS.getValue(it) },
                        onSelect = { direction = DIRECTION_ORDER[it] }
                    )
                }

                Spacer(Modifier.height(8.dp))
                if (!isVector) {
                    ConvertNumGrid(
                        listOf(
                            ConvertNumSpec("分辨率 (线/mm)", convertFmt(quality)) { quality = it }
                        )
                    )
                    // v2 把 X / Y 并排放在同一个 num-item 里：这里用一行两列还原
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConvertNumField(
                            label = "起始偏移 X (mm)",
                            value = convertFmt(offsetX),
                            onValue = { offsetX = it.toDoubleOrNull() ?: offsetX },
                            modifier = Modifier.weight(1f)
                        )
                        ConvertNumField(
                            label = "起始偏移 Y (mm)",
                            value = convertFmt(offsetY),
                            onValue = { offsetY = it.toDoubleOrNull() ?: offsetY },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    ConvertNumGrid(
                        listOf(
                            ConvertNumSpec("二值化阈值 (%)", convertFmt(vecThreshold)) { vecThreshold = it }
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConvertNumField(
                            label = "起始偏移 X (mm)",
                            value = convertFmt(offsetX),
                            onValue = { offsetX = it.toDoubleOrNull() ?: offsetX },
                            modifier = Modifier.weight(1f)
                        )
                        ConvertNumField(
                            label = "起始偏移 Y (mm)",
                            value = convertFmt(offsetY),
                            onValue = { offsetY = it.toDoubleOrNull() ?: offsetY },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (isVector) {
                    LiquidRow(
                        title = "反相",
                        description = "深底浅图时启用",
                        backdrop = backdrop,
                        trailing = { GlassSwitch(backdrop, vecInvert, onCheckedChange = { vecInvert = it }) }
                    )
                }
            }
        }

        // ================= 抖动算法（仅抖动点阵）=================
        if (tool == ConvertTool.Dithering) {
            GlassCard(backdrop) {
                Column(Modifier.fillMaxWidth()) {
                    GlassSectionHeader("抖动算法")
                    Spacer(Modifier.height(8.dp))
                    ConvertInlineSelect(
                        label = "抖动算法",
                        value = DITHERING_LABELS.getValue(dithering),
                        options = DITHERING_MODES.map { DITHERING_LABELS.getValue(it) },
                        onSelect = { dithering = DITHERING_MODES[it] }
                    )
                }
            }
        }

        // ================= 线性参数 =================
        if (isVector) {
            GlassCard(backdrop) {
                Column(Modifier.fillMaxWidth()) {
                    GlassSectionHeader(
                        when (tool) {
                            ConvertTool.Outline -> "轮廓描线参数"
                            ConvertTool.Auto -> "智能识别参数"
                            else -> "中心线参数"
                        }
                    )
                    Spacer(Modifier.height(8.dp))

                    when (tool) {
                        ConvertTool.Outline -> ConvertNumGrid(
                            listOf(
                                ConvertNumSpec("去斑面积 (像素)", convertFmt(vecTurdSize)) { vecTurdSize = it },
                                ConvertNumSpec("圆角阈值", convertFmt(vecAlphaMax)) { vecAlphaMax = it }
                            )
                        )

                        ConvertTool.Centerline -> ConvertNumGrid(
                            listOf(
                                ConvertNumSpec("去毛刺长度 (像素)", convertFmt(vecMinBranch)) { vecMinBranch = it },
                                ConvertNumSpec("简化容差 (像素)", convertFmt(vecSimplify)) { vecSimplify = it }
                            )
                        )

                        else -> {
                            GlassSliderRow(
                                backdrop = backdrop,
                                title = "笔画宽度阈值（占图像短边 %）",
                                value = vecStrokeWidthPct,
                                onValueChange = { vecStrokeWidthPct = it },
                                valueRange = 0.5f..10f,
                                suffix = "%",
                                decimals = 1
                            )
                            Spacer(Modifier.height(6.dp))
                            ConvertHint(
                                "平均笔画宽度 ≈ 墨水面积 / 骨架长度，再除以图像短边：小于等于该阈值 → 中心线走线；" +
                                    "大于 → 轮廓描线。"
                            )
                            ConvertHint("兜底：骨架为空或退化、墨水覆盖率超过 50% 时，一律回退轮廓描线。")
                            Spacer(Modifier.height(8.dp))
                            ConvertHint("判定为线稿时使用下面的中心线参数：")
                            Spacer(Modifier.height(6.dp))
                            ConvertNumGrid(
                                listOf(
                                    ConvertNumSpec("去毛刺长度 (像素)", convertFmt(vecMinBranch)) { vecMinBranch = it },
                                    ConvertNumSpec("简化容差 (像素)", convertFmt(vecSimplify)) { vecSimplify = it }
                                )
                            )
                            Spacer(Modifier.height(8.dp))
                            ConvertHint("判定为实心图案时使用下面的轮廓参数：")
                            Spacer(Modifier.height(6.dp))
                            ConvertNumGrid(
                                listOf(
                                    ConvertNumSpec("去斑面积 (像素)", convertFmt(vecTurdSize)) { vecTurdSize = it },
                                    ConvertNumSpec("圆角阈值", convertFmt(vecAlphaMax)) { vecAlphaMax = it }
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    LiquidRow(
                        title = "轮廓曲线优化",
                        description = "把相邻曲线合并，减少节点数（仅轮廓描线）",
                        backdrop = backdrop,
                        trailing = {
                            GlassSwitch(
                                backdrop = backdrop,
                                checked = vecCurveOptimizing,
                                enabled = tool != ConvertTool.Centerline,
                                onCheckedChange = { vecCurveOptimizing = it }
                            )
                        }
                    )
                    LiquidRow(
                        title = "路径排序优化",
                        description = "最近邻排序，缩短空移距离",
                        backdrop = backdrop,
                        trailing = { GlassSwitch(backdrop, vecOptimize, onCheckedChange = { vecOptimize = it }) }
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
                        ConvertNumSpec("最大功率 S", convertFmt(maxPower)) { maxPower = it },
                        ConvertNumSpec("最小功率 S", convertFmt(minPower)) { minPower = it }
                    )
                )
                LiquidRow(
                    title = "硬件 PWM",
                    description = "使用 S 值渐变控制激光功率",
                    backdrop = backdrop,
                    trailing = { GlassSwitch(backdrop, pwm, onCheckedChange = { pwm = it }) }
                )
                if (!isVector) {
                    LiquidRow(
                        title = "单向雕刻",
                        description = "仅单向出光，质量更高",
                        backdrop = backdrop,
                        trailing = { GlassSwitch(backdrop, unidirectional, onCheckedChange = { unidirectional = it }) }
                    )
                    LiquidRow(
                        title = "禁用 G0 快速空移",
                        description = "空移使用 G1 进给",
                        backdrop = backdrop,
                        trailing = { GlassSwitch(backdrop, disableFastSkip, onCheckedChange = { disableFastSkip = it }) }
                    )
                }
            }
        }

        // ================= 进阶预处理（仅光栅；v2 的 GlassCollapse）=================
        if (!isVector) {
            GlassCard(backdrop) {
                LiquidDisclosure(title = "进阶：图像预处理", backdrop = backdrop) {
                    Column(Modifier.fillMaxWidth()) {
                        ConvertInlineSelect(
                            label = "灰度公式",
                            title = "灰度公式",
                            value = FORMULA_LABELS.getValue(formula),
                            options = Formula.entries.map { FORMULA_LABELS.getValue(it) },
                            onSelect = { formula = Formula.entries[it] }
                        )
                        Spacer(Modifier.height(8.dp))
                        GlassSliderRow(
                            backdrop = backdrop,
                            title = "亮度",
                            value = brightness,
                            onValueChange = { brightness = it },
                            valueRange = 0f..200f,
                            steps = 39
                        )
                        Spacer(Modifier.height(10.dp))
                        GlassSliderRow(
                            backdrop = backdrop,
                            title = "对比度",
                            value = contrast,
                            onValueChange = { contrast = it },
                            valueRange = 0f..200f,
                            steps = 39
                        )
                        Spacer(Modifier.height(10.dp))
                        GlassSliderRow(
                            backdrop = backdrop,
                            title = "白色裁剪",
                            value = whiteClip,
                            onValueChange = { whiteClip = it },
                            valueRange = 0f..100f,
                            steps = 99
                        )
                        if (tool == ConvertTool.Line2Line) {
                            Spacer(Modifier.height(6.dp))
                            LiquidRow(
                                title = "启用阈值化",
                                description = "按阈值二值化，适合线稿",
                                backdrop = backdrop,
                                trailing = { GlassSwitch(backdrop, useThreshold, onCheckedChange = { useThreshold = it }) }
                            )
                            Spacer(Modifier.height(6.dp))
                            GlassSliderRow(
                                backdrop = backdrop,
                                title = "阈值",
                                value = rasterThreshold,
                                onValueChange = { rasterThreshold = it },
                                valueRange = 1f..99f,
                                steps = 97
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LiquidRow(
                            title = "高质量插值",
                            description = "缩放时使用平滑插值",
                            backdrop = backdrop,
                            trailing = { GlassSwitch(backdrop, highQuality, onCheckedChange = { highQuality = it }) }
                        )
                    }
                }
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
            // v2 是 `:loading="busy" :disabled="!canGenerate"`；busy 期间 GlassButton 自带 spinner 且不接受点击
            loading = busy,
            enabled = source != null && widthMm > 0 && effectiveHeight() > 0
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
                    if (decisionText.isNotEmpty()) {
                        LiquidAlert(title = decisionText, type = LiquidAlertType.Info)
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassText("二值化预览", color = colors.text, fontSize = 14.sp)
                        GlassText(pixelInfo, color = colors.textDim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.fill1)
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = processedPreview
                        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "预处理预览",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                            )
                        } else {
                            LiquidSpinner(size = 20.dp, color = colors.textDim)
                        }
                    }
                    if (extraInfo.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        ConvertHint(extraInfo)
                    }
                    Spacer(Modifier.height(10.dp))
                    GlassText("路径预览", color = colors.text, fontSize = 14.sp)
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

/** v2 的 `ImageTool`：光栅两种 + 线性两种 + 智能。 */
private enum class ConvertTool(val key: String) {
    Line2Line("Line2Line"),
    Dithering("Dithering"),
    Outline("Outline"),
    Centerline("Centerline"),
    Auto("Auto")
}

/** v2 `TOOL_OPTIONS` 的顺序，逐一对齐。 */
private val TOOL_ORDER = listOf(
    ConvertTool.Line2Line,
    ConvertTool.Dithering,
    ConvertTool.Outline,
    ConvertTool.Centerline,
    ConvertTool.Auto
)

/** v2 `TOOL_LABELS`（逐字；后两项取 `VECTOR_TOOL_LABELS`）。 */
private val TOOL_LABELS: Map<ConvertTool, String> = mapOf(
    ConvertTool.Line2Line to "线条扫描",
    ConvertTool.Dithering to "抖动点阵",
    ConvertTool.Outline to VECTOR_TOOL_LABELS.getValue(VectorTool.Outline),
    ConvertTool.Centerline to VECTOR_TOOL_LABELS.getValue(VectorTool.Centerline),
    ConvertTool.Auto to "智能"
)

/** 扫描方向的显示顺序（v2 `Object.keys(DIRECTION_LABELS)`）。 */
private val DIRECTION_ORDER = listOf(
    RasterDirection.Horizontal,
    RasterDirection.Vertical,
    RasterDirection.Diagonal
)

/** 一次生成的中间结果（编排与 UI 状态分离，便于整体放进 `Dispatchers.Default`）。 */
private class ImageOutcome(
    val lines: List<String>,
    val preview: IntArray,
    val previewW: Int,
    val previewH: Int,
    val pixel: String,
    val extra: String,
    val decision: String,
    val name: String
)

/** 线性模式的公共选项（从 v2 的 `base` 对象逐字段抄来）。 */
private class VectorCommon(
    val threshold: Double,
    val invert: Boolean,
    val widthMm: Double,
    val heightMm: Double,
    val offsetX: Double,
    val offsetY: Double,
    val markSpeed: Double,
    val travelSpeed: Double,
    val minPower: Int,
    val maxPower: Int,
    val laserPower: Int,
    val laserOn: String,
    val laserOff: String,
    val pwm: Boolean,
    val header: String,
    val footer: String,
    val optimize: Boolean,
    val turdSize: Double,
    val alphaMax: Double,
    val curveOptimizing: Boolean,
    val minBranchPx: Double,
    val simplifyTolerance: Double
) {
    fun toImageVectorOptions(tool: VectorTool): ImageVectorOptions = ImageVectorOptions(
        tool = tool,
        threshold = threshold,
        invert = invert,
        widthMm = widthMm,
        heightMm = heightMm,
        offsetX = offsetX,
        offsetY = offsetY,
        markSpeed = markSpeed,
        travelSpeed = travelSpeed,
        minPower = minPower,
        maxPower = maxPower,
        laserPower = laserPower,
        laserOn = laserOn,
        laserOff = laserOff,
        pwm = pwm,
        header = header,
        footer = footer,
        optimize = optimize,
        turdSize = turdSize,
        alphaMax = alphaMax,
        curveOptimizing = curveOptimizing,
        minBranchPx = minBranchPx,
        simplifyTolerance = simplifyTolerance
    )

    fun toSmartOptions(strokeWidthPct: Double): SmartVectorOptions = SmartVectorOptions(
        threshold = threshold,
        invert = invert,
        widthMm = widthMm,
        heightMm = heightMm,
        offsetX = offsetX,
        offsetY = offsetY,
        markSpeed = markSpeed,
        travelSpeed = travelSpeed,
        minPower = minPower,
        maxPower = maxPower,
        laserPower = laserPower,
        laserOn = laserOn,
        laserOff = laserOff,
        pwm = pwm,
        header = header,
        footer = footer,
        optimize = optimize,
        turdSize = turdSize,
        alphaMax = alphaMax,
        curveOptimizing = curveOptimizing,
        minBranchPx = minBranchPx,
        simplifyTolerance = simplifyTolerance,
        strokeWidthThresholdPct = strokeWidthPct
    )
}

/**
 * 线性模式的处理分辨率：v2 `generateVector` 的 `maxDim = 1600`，
 * `s = min(1, maxDim / max(srcW, srcH))`，`pw/ph` 至少 1。
 */
private fun vectorProcessSize(srcW: Int, srcH: Int): Pair<Int, Int> {
    val s = Math.min(1.0, MAX_VECTOR_EDGE.toDouble() / maxOf(srcW, srcH).toDouble())
    return Pair(maxOf(1, Math.round(srcW * s).toInt()), maxOf(1, Math.round(srcH * s).toInt()))
}

/** v2 `ImageConvertView.vue:166` 的处理分辨率上限（像素）。 */
private const val MAX_VECTOR_EDGE = 1600

/** 去掉最后一个扩展名（与 v2 生成文件名时的处理一致，缺扩展名时回退 `image`）。 */
private fun baseName(name: String): String {
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    return base.ifEmpty { "image" }
}

/**
 * 供 [AndroidBitmapBridge.write] 复用的 RGBA 视图（只做尺寸标注，不复制像素）。
 *
 * `:core` 的结果里只带像素数组（`ImageVectorResult` / `RasterResult` 都不含宽高），
 * 而预览需要知道宽高才能还原成 `Bitmap`，所以在这里包一层。
 */
private class PreviewImage(
    override val data: IntArray,
    override val width: Int,
    override val height: Int
) : PotraceImage

/**
 * 生成的输入快照 —— 把屏幕里的一堆 `var` 拍成不可变参数，让转换函数保持纯粹、
 * 可以整体放进 `Dispatchers.Default`，也让"哪一步用了哪个参数"一目了然。
 */
private class ImageConvertParams(
    val source: PotraceImage,
    val sourceName: String,
    val srcW: Int,
    val srcH: Int,
    val widthMm: Double,
    val heightMm: Double,
    val tool: ConvertTool,
    val direction: RasterDirection,
    val quality: Double,
    val dithering: DitheringMode,
    val offsetX: Double,
    val offsetY: Double,
    val vecThreshold: Double,
    val vecInvert: Boolean,
    val vecTurdSize: Double,
    val vecAlphaMax: Double,
    val vecCurveOptimizing: Boolean,
    val vecMinBranch: Double,
    val vecSimplify: Double,
    val vecStrokeWidthPct: Double,
    val vecOptimize: Boolean,
    val markSpeed: Double,
    val minPower: Double,
    val maxPower: Double,
    val laserOn: String,
    val laserOff: String,
    val pwm: Boolean,
    val unidirectional: Boolean,
    val disableFastSkip: Boolean,
    val header: String,
    val footer: String,
    val formula: Formula,
    val brightness: Double,
    val contrast: Double,
    val whiteClip: Double,
    val useThreshold: Boolean,
    val rasterThreshold: Double,
    val highQuality: Boolean
)

/**
 * 图片转换的编排 —— 逐行对应 v2 `generate()`：
 *  1. `adaptSize()`：`fitSizeToTravel(width, height, mode = 'Fit')`，并把 `message` 收进警告；
 *  2. 光栅：`convertImageToGcode(source, w, h, opts, resampler)`；
 *     线性：先把处理分辨率钳到 1600 px（`ImageConvertView.vue:166`）再重采样，
 *     然后按 `Outline / Centerline / Auto` 分派到 `convertImageVector*`；
 *  3. `checkGcodeWithinTravel(lines)` 的 `message` 追加到警告；
 *  4. 文件名：光栅 `${base}.gcode`，Outline/Centerline `${base}-${tool}.gcode`，智能 `${base}-Auto.gcode`。
 *
 * 返回 `(结果, 警告)`；警告文案全部来自 `:core`（中文），本函数不自行拼接新文案。
 */
private fun convertImageWithParams(
    p: ImageConvertParams,
    resampler: com.lasergrbl.core.raster.ResizeSampler
): Pair<ImageOutcome, String> {
    val fit = fitSizeToTravel(p.widthMm, p.heightMm, mode = FitMode.Fit)
    var warning = fit.message.orEmpty()
    val w = fit.widthMm
    val h = fit.heightMm

    // ---- 光栅（逐行扫描 / 抖动）----
    if (p.tool == ConvertTool.Line2Line || p.tool == ConvertTool.Dithering) {
        val opts = RasterOptions(
            tool = if (p.tool == ConvertTool.Dithering) RasterTool.Dithering else RasterTool.Line2Line,
            direction = p.direction,
            quality = p.quality,
            pwm = p.pwm,
            markSpeed = p.markSpeed,
            minPower = p.minPower.toInt(),
            maxPower = p.maxPower.toInt(),
            laserOn = p.laserOn,
            laserOff = p.laserOff,
            offsetX = p.offsetX,
            offsetY = p.offsetY,
            formula = p.formula,
            brightness = p.brightness,
            contrast = p.contrast,
            whiteClip = p.whiteClip,
            useThreshold = p.useThreshold,
            threshold = p.rasterThreshold,
            dithering = p.dithering,
            interpolation = if (p.highQuality) Interpolation.High else Interpolation.Low,
            unidirectional = p.unidirectional,
            disableFastSkip = p.disableFastSkip,
            header = p.header,
            footer = p.footer
        )
        val r = convertImageToGcode(p.source, w, h, opts, resampler)
        warning = appendWarn(warning, checkGcodeWithinTravel(r.lines).message)
        return ImageOutcome(
            lines = r.lines,
            preview = r.preview,
            previewW = r.pixelWidth,
            previewH = r.pixelHeight,
            pixel = "${r.pixelWidth} × ${r.pixelHeight} px · ${convertFmt(r.res, 2)} 线/mm",
            extra = "",
            decision = "",
            name = "${baseName(p.sourceName)}.gcode"
        ) to warning
    }

    // ---- 线性（轮廓 / 中心线 / 智能）----
    val (pw, ph) = vectorProcessSize(p.srcW, p.srcH)
    val processed = resampler.sample(
        source = p.source,
        sizeW = pw,
        sizeH = ph,
        interpolation = Interpolation.High,
        fillWhite = true
    )
    val image = PreviewImage(processed, pw, ph)
    val common = VectorCommon(
        threshold = Math.round(p.vecThreshold / 100.0 * 255.0).toDouble(),
        invert = p.vecInvert,
        widthMm = w,
        heightMm = h,
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
        optimize = p.vecOptimize,
        turdSize = p.vecTurdSize,
        alphaMax = p.vecAlphaMax,
        curveOptimizing = p.vecCurveOptimizing,
        minBranchPx = p.vecMinBranch,
        simplifyTolerance = p.vecSimplify
    )
    if (p.tool == ConvertTool.Auto) {
        // 智能：判定用位图与出图用位图是同一张（与 v2 一致）
        val smart = convertImageVectorSmart(image, common.toSmartOptions(p.vecStrokeWidthPct))
        warning = appendWarn(warning, checkGcodeWithinTravel(smart.lines).message)
        return ImageOutcome(
            lines = smart.lines,
            preview = smart.preview,
            previewW = pw,
            previewH = ph,
            pixel = "$pw × $ph px · 二值化 ${convertFmt(p.vecThreshold, 0)}%",
            extra = "走线 ${smart.pathCount} 段 · 路径长度 ${convertFmt(smart.lengthMm, 1)} mm",
            decision = smart.decision.summary,
            name = "${baseName(p.sourceName)}-Auto.gcode"
        ) to warning
    }
    val vectorTool = if (p.tool == ConvertTool.Centerline) VectorTool.Centerline else VectorTool.Outline
    val res = convertImageVector(image, common.toImageVectorOptions(vectorTool))
    warning = appendWarn(warning, checkGcodeWithinTravel(res.lines).message)
    return ImageOutcome(
        lines = res.lines,
        preview = res.preview,
        previewW = pw,
        previewH = ph,
        pixel = "$pw × $ph px · 二值化 ${convertFmt(p.vecThreshold, 0)}%",
        extra = "走线 ${res.pathCount} 段 · 路径长度 ${convertFmt(res.lengthMm, 1)} mm",
        decision = "",
        name = "${baseName(p.sourceName)}-${p.tool.key}.gcode"
    ) to warning
}

/** 保留 2 位小数（v2 `Math.round(v * 100) / 100`）。 */
private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
