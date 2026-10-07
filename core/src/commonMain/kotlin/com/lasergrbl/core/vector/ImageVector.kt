package com.lasergrbl.core.vector

import com.lasergrbl.core.text.DEFAULT_TEXT_FONT
import com.lasergrbl.core.text.TextOrientation
import com.lasergrbl.core.text.TextRasterizer
import com.lasergrbl.core.text.TextRenderOptions

/**
 * 位图 → 线性走线 G 代码（图片转线性 / 文字雕刻的公共核心）—— 逐行移植自 v2 `src/core/vector/ImageVector.ts`。
 *
 * 与光栅（Line2Line / 抖动）不同，这里生成的是「真正沿图形走线」的路径：
 *  - Outline（轮廓描线）：Potrace 矢量化，沿图形内外轮廓走线，适合实心图案与汉字外轮廓；
 *  - Centerline（中心线走线）：Zhang-Suen 骨架化，沿笔画中心单线走线，最省材料与时间，
 *    适合线稿、汉字笔画、签名等（对应原项目 Centerline 模式）。
 *
 * 移植边界（重要）：
 *  - 已移植：`VectorTool` / `VECTOR_TOOL_LABELS` / `ImageVectorOptions` /
 *    `DEFAULT_IMAGE_VECTOR_OPTIONS` / `buildPreview` / `convertImageVector` / `hasNonAscii`。
 *  - **未移植**：`renderTextToImage` / `convertTextVector`（以及 `TextRenderOptions` /
 *    `TextRenderResult` / `TextVectorOptions` / `TextVectorResult` / `DEFAULT_TEXT_FONT` /
 *    `measureLine` / `rotate90CW` / `inkBox` / `cropImageData`）—— 它们依赖
 *    `document.createElement('canvas')`、`CanvasRenderingContext2D.measureText`、
 *    `getImageData` 等浏览器光栅化能力，`commonMain` 里无法离线复刻；
 *    这部分由 `:app` 侧的接缝（Android Canvas 渲染 → RGBA 位图）承担，渲染出位图后
 *    直接调用本文件的 [convertImageVector] 即可。
 *
 * 浮点一致性：所有算式都保持 v2 的运算顺序与类型（`Uint8ClampedArray` 的取值是 0..255 的整数，
 * 用 [Double] 参与乘除），以便黄金样本里的 lines / preview 逐字节对齐。
 */

/** 走线方式（v2：`export type VectorTool = 'Outline' | 'Centerline'`）。 */
enum class VectorTool { Outline, Centerline }

/** 走线方式的中文标签（逐字对齐 v2 `VECTOR_TOOL_LABELS`）。 */
val VECTOR_TOOL_LABELS: Map<VectorTool, String> = mapOf(
    VectorTool.Outline to "轮廓描线",
    VectorTool.Centerline to "中心线走线"
)

/**
 * 位图线性转换选项（v2：`interface ImageVectorOptions`）。
 *
 * 数值字段用 [Double] 承载 v2 的 `number`（`threshold` 参与 `threshold * 3` 的浮点比较），
 * 整数语义的字段（功率）用 [Int]，与 [PolylineGcodeOptions] 对齐。
 */
data class ImageVectorOptions(
    /** 走线方式，默认 [VectorTool.Outline]。 */
    val tool: VectorTool = VectorTool.Outline,
    /** 二值化阈值 0..255，默认 128。 */
    val threshold: Double = 128.0,
    /** 反相（深底浅图时使用）。 */
    val invert: Boolean = false,
    /** 目标宽度 (mm)。 */
    val widthMm: Double = 50.0,
    /** 目标高度 (mm)。 */
    val heightMm: Double = 50.0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val markSpeed: Double = 1000.0,
    /** 空移速度，0 表示使用 G0 快速空移（不输出 F）。 */
    val travelSpeed: Double = 3000.0,
    val minPower: Int = 0,
    val maxPower: Int = 1000,
    /** 走线时使用的 S 值，缺省（null）用 [maxPower]。 */
    val laserPower: Int? = null,
    val laserOn: String = "M4",
    val laserOff: String = "M5",
    val pwm: Boolean = true,
    val header: String = "G90",
    val footer: String = "M5\nG0 X0 Y0",
    /** 是否做最近邻排序优化，默认 true。 */
    val optimize: Boolean = true,
    // ---- Outline 专用 ----
    val turdSize: Double = 2.0,
    val alphaMax: Double = 1.0,
    val optTolerance: Double = 0.2,
    val curveOptimizing: Boolean = true,
    val flattenTolerance: Double = 0.2,
    // ---- Centerline 专用 ----
    val minBranchPx: Double = 6.0,
    val simplifyTolerance: Double = 1.2
)

/**
 * v2 的 `DEFAULT_IMAGE_VECTOR_OPTIONS`。
 *
 * 与 [ImageVectorOptions] 的默认值逐字段相同；保留这个常量是为了让「默认值」有单一出处，
 * 黄金样本 `result.defaultOptions` 会逐字段比对它。
 */
val DEFAULT_IMAGE_VECTOR_OPTIONS: ImageVectorOptions = ImageVectorOptions()

/**
 * 位图线性转换结果（v2：`interface ImageVectorResult`）。
 *
 * ⚠️ [preview] 是 `IntArray`，data class 自动生成的 `equals` 对数组走**引用相等**，
 * 不要直接比较两个 [ImageVectorResult]；测试里逐像素比对（`preview.toList()` 或按索引）。
 */
data class ImageVectorResult(
    val lines: List<String>,
    val pathCount: Int,
    val lengthMm: Double,
    /** 二值化预览（处理分辨率，RGBA，长度 `width*height*4`，黑=出光区域）。 */
    val preview: IntArray
)

/**
 * 二值化预览：白底黑图。
 *
 * ⚠️ 这里与 [potraceTrace] 内部 `binarize` 的判定**不同**，不要合并：
 *  - 本函数先把像素按 alpha 合成到白底（`r*a + 255*(1-a)`）再比较，于是「半透明黑」与
 *    「全透明但 RGB 非零」都会按合成结果判定，**没有 `alpha < 128` 的特殊分支**；
 *  - Potrace 的 `binarize` 是 `alpha < 128 → 背景`、否则直接比 `r+g+b < threshold*3`。
 *  v2 就是这么写的（预览是给界面看的），黄金样本 `image-vector.json` 里 8 组 `preview`
 *  逐像素钉住了本函数的语义。
 *
 * 输出为 0/255 灰度、alpha 恒为 255（对齐 `Uint8ClampedArray` 的写入值）。
 */
private fun buildPreview(image: PotraceImage, threshold: Double, invert: Boolean): IntArray {
    val w = image.width
    val h = image.height
    val src = image.data
    val out = IntArray(w * h * 4)
    val n = w * h
    for (i in 0 until n) {
        val a = src[4 * i + 3] / 255.0
        // 合成到白底后再比较，避免透明区域被判成前景
        val r = src[4 * i] * a + 255 * (1 - a)
        val g = src[4 * i + 1] * a + 255 * (1 - a)
        val b = src[4 * i + 2] * a + 255 * (1 - a)
        var black = r + g + b < threshold * 3
        if (invert) black = !black
        val v = if (black) 0 else 255
        out[4 * i] = v
        out[4 * i + 1] = v
        out[4 * i + 2] = v
        out[4 * i + 3] = 255
    }
    return out
}

/**
 * 位图 → 线性 G 代码。
 * 输入位图坐标系为图像坐标系（原点左上、y 向下），输出 G 代码坐标系 y 向上。
 *
 * 逐行对齐 v2：
 *  1. `width <= 0 || height <= 0` 直接返回空结果（`preview` 是 1×1 的**全零** RGBA ——
 *     v2 用 `new ImageData(1, 1)`，四个通道都是 0，不是白底）；
 *  2. Centerline 走 `centerlineTrace`（`closed = false`，不带 `maxIterations`）；
 *     其余走 `potraceTrace`；
 *  3. `sc = widthMm / image.width`（注意分母是**像素宽**，与高度无关）；
 *  4. 交给 `polylinesToGcode` 时 `offsetY = offsetY + heightMm`、`flipY = true`、
 *     `travelCommand = 'G0'`、`travelSpeed <= 0` 视为不输出 F。
 */
fun convertImageVector(
    image: PotraceImage,
    o: ImageVectorOptions = ImageVectorOptions()
): ImageVectorResult {
    if (image.width <= 0 || image.height <= 0) {
        // v2：new ImageData(1, 1) —— 1×1 全透明黑（4 个 0）
        return ImageVectorResult(emptyList(), 0, 0.0, IntArray(4))
    }

    val polys: List<Polyline> = if (o.tool == VectorTool.Centerline) {
        centerlineTrace(
            image,
            CenterlineOptions(
                threshold = o.threshold,
                invert = o.invert,
                closed = true,
                minBranchPx = o.minBranchPx,
                simplifyTolerance = o.simplifyTolerance
            )
        )
    } else {
        potraceTrace(
            image,
            PotraceOptions(
                threshold = o.threshold,
                turdSize = o.turdSize,
                alphaMax = o.alphaMax,
                optTolerance = o.optTolerance,
                curveOptimizing = o.curveOptimizing,
                invert = o.invert,
                flattenTolerance = o.flattenTolerance
            )
        )
    }

    val sc = o.widthMm / image.width
    val g = polylinesToGcode(
        polys,
        PolylineGcodeOptions(
            pixelSizeMm = sc,
            offsetX = o.offsetX,
            // 图像 y 向下，G 代码 y 向上：翻转后整体上移一个高度，使输出落在 [0, heightMm]
            offsetY = o.offsetY + o.heightMm,
            markSpeed = o.markSpeed,
            travelSpeed = if (o.travelSpeed > 0) o.travelSpeed else null,
            minPower = o.minPower,
            maxPower = o.maxPower,
            laserPower = o.laserPower,
            laserOn = o.laserOn,
            laserOff = o.laserOff,
            pwm = o.pwm,
            flipY = true,
            header = o.header,
            footer = o.footer,
            optimize = o.optimize,
            travelCommand = "G0"
        )
    )

    return ImageVectorResult(
        lines = g.lines,
        pathCount = g.pathCount,
        lengthMm = g.lengthMm,
        preview = buildPreview(image, o.threshold, o.invert)
    )
}

/**
 * 是否包含非 ASCII 字符（中文等需走位图渲染）—— 等价 v2 的 `/[^\x20-\x7E]/.test(text)`。
 *
 * 实现说明（为什么可以逐 `Char` 判定）：
 *  - JS 正则不带 `u` 标志时按 **UTF-16 code unit** 匹配，区间 `\x20-\x7E` 之外即命中；
 *  - Kotlin 的 `Char` 同样是 UTF-16 code unit，代理对（如 emoji、部分生僻字）会拆成两个
 *    都大于 0x7E 的单元，两者都会命中 —— 与 JS 结论一致；
 *  - 反过来，任何命中 JS 的 code unit 也必然被本循环命中（`< 0x20` 或 `> 0x7E` 一一对应）。
 *  空串不含任何字符 → false。
 */
fun hasNonAscii(text: String): Boolean {
    for (c in text) {
        val code = c.code
        if (code < 0x20 || code > 0x7E) return true
    }
    return false
}

// ---------------------------------------------------------------------------
// 文字 → 位图 → 线性走线（支持中文）
// ---------------------------------------------------------------------------

/**
 * 文本渲染 + 线性转换的合并选项
 * （v2：`interface TextVectorOptions extends TextRenderOptions`，本类把两者拍平）。
 *
 * ⚠️ 与 v2 一样**没有** `widthMm` / `heightMm`：目标尺寸由渲染结果的墨迹包围盒决定
 * （`r.widthMm` / `r.heightMm`），不接受调用方指定。
 *
 * 默认值取自 [DEFAULT_IMAGE_VECTOR_OPTIONS]（v2 在 `convertTextVector` 里就是
 * `{ ...DEFAULT_IMAGE_VECTOR_OPTIONS, ... }`），这里放在 data class 上便于 `:app` 直接构造。
 */
data class TextVectorOptions(
    // ---- 来自 v2 TextRenderOptions ----
    val text: String,
    /** 单字高度 (mm)。 */
    val sizeMm: Double,
    val bold: Boolean,
    val orientation: TextOrientation,
    /** 行距倍率，默认 1.5。 */
    val lineSpacing: Double = 1.5,
    /** 字体族（v2 是 CSS 字体栈；平台侧可忽略或映射到 Typeface）。 */
    val fontFamily: String = DEFAULT_TEXT_FONT,
    /** 渲染时的像素字高（越大越精细），默认 180。 */
    val renderSizePx: Int = 180,
    // ---- 来自 v2 与 ImageVectorOptions 的公共部分 ----
    val threshold: Double = 128.0,
    val invert: Boolean = false,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val markSpeed: Double = 1000.0,
    /** 空移速度，0 表示使用 G0 快速空移。 */
    val travelSpeed: Double = 3000.0,
    val minPower: Int = 0,
    val maxPower: Int = 1000,
    /** 走线时使用的 S 值，缺省（null）用 [maxPower]。 */
    val laserPower: Int? = null,
    val laserOn: String = "M4",
    val laserOff: String = "M5",
    val pwm: Boolean = true,
    val header: String = "G90",
    val footer: String = "M5\nG0 X0 Y0",
    val optimize: Boolean = true,
    val tool: VectorTool = VectorTool.Outline,
    // ---- Outline 专用 ----
    val turdSize: Double = 2.0,
    val alphaMax: Double = 1.0,
    val optTolerance: Double = 0.2,
    val curveOptimizing: Boolean = true,
    val flattenTolerance: Double = 0.2,
    // ---- Centerline 专用 ----
    val minBranchPx: Double = 6.0,
    val simplifyTolerance: Double = 1.2
) {
    /** 取渲染层选项（v2 把同一个 `o` 直接当 `TextRenderOptions` 传给 `renderTextToImage`）。 */
    fun toTextRenderOptions(): TextRenderOptions = TextRenderOptions(
        text = text,
        sizeMm = sizeMm,
        bold = bold,
        orientation = orientation,
        lineSpacing = lineSpacing,
        fontFamily = fontFamily,
        renderSizePx = renderSizePx
    )
}

/**
 * 文本 → 线性走线结果（v2：`interface TextVectorResult extends ImageVectorResult`，
 * 本类把字段拍平，多带文本实际尺寸）。
 *
 * ⚠️ [preview] 是 `IntArray`，比较时请逐像素。
 */
data class TextVectorResult(
    val lines: List<String>,
    val pathCount: Int,
    val lengthMm: Double,
    /** 二值化预览（RGBA，长度 `width*height*4`）。 */
    val preview: IntArray,
    /** 文本实际尺寸 (mm)。 */
    val widthMm: Double,
    /** 文本实际尺寸 (mm)。 */
    val heightMm: Double
)

/**
 * 文本 → 线性走线 G 代码（支持中文等任意字符）。
 *
 * 逐行对齐 v2 `convertTextVector`（`ImageVector.ts:395-432`）：
 *  1. 先让注入的 [rasterizer] 把文本渲染成位图（这一步在平台侧，见 `text/TextRaster.kt`）；
 *  2. `r.widthMm <= 0 || r.heightMm <= 0` → 空结果（`lines` 空、`pathCount` 0、`lengthMm` 0、
 *     `preview` 1×1 全零），**不调用** [convertImageVector]；
 *  3. 否则以 `r.image`（渲染结果**原样使用，不做任何重采样**）调用 [convertImageVector]，
 *     选项为 `DEFAULT_IMAGE_VECTOR_OPTIONS` 起、用本函数的 [TextVectorOptions] 逐字段覆盖，
 *     其中 `widthMm` / `heightMm` 取自渲染结果（`sizeMm` 只影响平台侧的 `pxPerMm`）。
 *
 * [rasterizer] 作为参数注入（不用全局单例）：JVM 单测可以塞一个假渲染器，
 * 无需真实字体引擎即可验证编排；真实实现在 `:app`（Android `Canvas`）。
 */
fun convertTextVector(options: TextVectorOptions, rasterizer: TextRasterizer): TextVectorResult {
    val r = rasterizer.render(options.toTextRenderOptions())
    if (r.widthMm <= 0 || r.heightMm <= 0) {
        // 无墨迹（例如仅空格）：平台侧返回 1×1 空图 + 宽高 0，这里直接返回空结果
        return TextVectorResult(emptyList(), 0, 0.0, IntArray(4), widthMm = 0.0, heightMm = 0.0)
    }
    val res = convertImageVector(
        r.image,
        // v2：{ ...DEFAULT_IMAGE_VECTOR_OPTIONS, ...覆盖 } —— 用 copy 保证「没写到的字段沿用默认」
        DEFAULT_IMAGE_VECTOR_OPTIONS.copy(
            tool = options.tool,
            threshold = options.threshold,
            invert = options.invert,
            widthMm = r.widthMm,
            heightMm = r.heightMm,
            offsetX = options.offsetX,
            offsetY = options.offsetY,
            markSpeed = options.markSpeed,
            travelSpeed = options.travelSpeed,
            minPower = options.minPower,
            maxPower = options.maxPower,
            laserPower = options.laserPower,
            laserOn = options.laserOn,
            laserOff = options.laserOff,
            pwm = options.pwm,
            header = options.header,
            footer = options.footer,
            optimize = options.optimize,
            turdSize = options.turdSize,
            alphaMax = options.alphaMax,
            optTolerance = options.optTolerance,
            curveOptimizing = options.curveOptimizing,
            flattenTolerance = options.flattenTolerance,
            minBranchPx = options.minBranchPx,
            simplifyTolerance = options.simplifyTolerance
        )
    )
    return TextVectorResult(
        lines = res.lines,
        pathCount = res.pathCount,
        lengthMm = res.lengthMm,
        preview = res.preview,
        widthMm = r.widthMm,
        heightMm = r.heightMm
    )
}
