package com.lasergrbl.core.vector

/**
 * 智能模式（Auto）—— 自动在「轮廓提取」与「中心线描线」之间选择
 * —— 逐行移植自 v2 `src/core/vector/SmartVector.ts`。
 *
 * 页面上的「智能 / Auto」选项调用这里的入口：
 *  - 图片 / SVG：对光栅化结果做笔画宽度启发式判定（见 [decideVectorMode]）；
 *  - 文字：纯 ASCII 用 Hershey 单线字体（本就是「写字」效果，且矢量输出最干净），
 *    含中文等非 ASCII 字符时 Hershey 没有字形，改用中心线（骨架化）。
 *
 * 本文件与 v2 一样是纯逻辑，无 DOM 依赖。
 */

/**
 * 图片智能转换选项：在 [ImageVectorOptions] 上去掉 `tool`（由智能判定决定），并带上判定参数。
 *
 * ⚠️ [maxIterations] 默认是 `null`，与 v2 的 `maxIterations?: number`（未传即 `undefined`）
 * 语义一致：`undefined` 会一路传到 [analyzeStrokes]，由 `maxIterations ?? 派生值` 兜底
 * （v2 SmartVector.ts 的注释写「默认 60」，但代码是原样透传 `undefined`，注释与实现不一致）。
 * 黄金样本 `image-vector.json` 钉住了这一点：`smart-outline-image-default`
 * （128×128、`strokeWidthThresholdPct = 2.5`）的 `analysis.iterations` 是**派生值 6**
 * （`max(6, min(48, ceil((2.5/100*128)/2) + 4))`），而不是 60 —— 若把默认写死成 60，
 * 该用例会立刻变红。故这里保留可空语义。
 */
data class SmartVectorOptions(
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
    val simplifyTolerance: Double = 1.2,
    // ---- 智能判定专用 ----
    /** 笔画宽度阈值：占图像短边的百分比，默认 2.5 (%)。 */
    val strokeWidthThresholdPct: Double = 2.5,
    /** 墨水覆盖率上限，默认 0.5。 */
    val maxInkRatio: Double = 0.5,
    /** 骨架化迭代上限；`null`（默认）表示按笔画宽度阈值反推，见本类 KDoc。 */
    val maxIterations: Double? = null
) {
    /**
     * 转成显式走线方式的 [ImageVectorOptions]。
     *
     * 对应 v2 的 `{ ...o, tool: decision.mode }`：既然判定已经把两种方式的数值结果统一，
     * 显式选择与智能选择必须产出完全相同的 G 代码（黄金样本的 3 组 smartVariants 钉住了这点）。
     */
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
        optTolerance = optTolerance,
        curveOptimizing = curveOptimizing,
        flattenTolerance = flattenTolerance,
        minBranchPx = minBranchPx,
        simplifyTolerance = simplifyTolerance
    )
}

/**
 * 图片智能转换结果：普通结果 + 判定明细。
 *
 * ⚠️ [preview] 是 `IntArray`，data class 的 `equals` 对数组走引用相等，比较时请逐像素。
 */
data class SmartVectorResult(
    val lines: List<String>,
    val pathCount: Int,
    val lengthMm: Double,
    /** 二值化预览（RGBA，长度 `width*height*4`）。 */
    val preview: IntArray,
    /** 智能判定明细（含展示文案）。 */
    val decision: VectorDecision
)

/** [VectorMode] → [VectorTool]（两个枚举同构但独立声明，这里做显式映射）。 */
private fun vectorToolOf(mode: VectorMode): VectorTool = when (mode) {
    VectorMode.Outline -> VectorTool.Outline
    VectorMode.Centerline -> VectorTool.Centerline
}

/**
 * 位图 → 线性 G 代码（智能选择轮廓 / 中心线）。
 * 判定用位图与出图用位图必须是同一张（同一分辨率），否则面积 / 骨架长度之比无意义。
 *
 * 逐行对齐 v2：先 [decideVectorMode]（只透传 threshold / invert / 三个判定参数），
 * 再把 `tool = decision.mode` 交给 [convertImageVector] —— 两种方式的数值结果与显式选择时完全一致。
 */
fun convertImageVectorSmart(
    image: PotraceImage,
    o: SmartVectorOptions = SmartVectorOptions()
): SmartVectorResult {
    val decision = decideVectorMode(
        image,
        VectorDecisionOptions(
            threshold = o.threshold,
            invert = o.invert,
            strokeWidthThresholdPct = o.strokeWidthThresholdPct,
            maxIterations = o.maxIterations,
            maxInkRatio = o.maxInkRatio
        )
    )
    val res = convertImageVector(image, o.toImageVectorOptions(vectorToolOf(decision.mode)))
    return SmartVectorResult(
        lines = res.lines,
        pathCount = res.pathCount,
        lengthMm = res.lengthMm,
        preview = res.preview,
        decision = decision
    )
}

// ---------------------------------------------------------------------------
// 文字智能识别
// ---------------------------------------------------------------------------

/** 文字可用的引擎（智能模式只会选这两种）。 */
enum class TextSmartEngine { Hershey, Centerline }

/** 文字智能识别结果。 */
data class TextEngineDecision(
    val engine: TextSmartEngine,
    /** 界面展示用的一行中文说明。 */
    val reason: String
)

/**
 * 是否含有 Hershey 无法渲染的字符（换行 / 制表符不算：Hershey 支持多行排版）。
 *
 * v2：`hasNonAscii(text.replace(/[\r\n\t]/g, ' '))` —— 先做**逐字符**归一化
 * （`\r`、`\n`、`\t` → 空格），再判非 ASCII。这一步是必须的：`\n`(0x0A) / `\t`(0x09)
 * 都小于 0x20，直接判会把多行英文误判成「含非 ASCII」。
 */
fun hasNonHersheyChar(text: String): Boolean {
    val normalized = StringBuilder(text.length)
    for (c in text) {
        normalized.append(if (c == '\r' || c == '\n' || c == '\t') ' ' else c)
    }
    return hasNonAscii(normalized.toString())
}

/**
 * 文字智能识别：
 *  - 纯 ASCII（英文 / 数字 / 符号，可含换行）→ Hershey 单线字体；
 *  - 含中文等非 ASCII 字符 → 中心线（骨架化）。
 * 两条 reason 文案与 v2 逐字一致（黄金样本 `decideTextEngine` 表逐条比对）。
 */
fun decideTextEngine(text: String): TextEngineDecision {
    if (hasNonHersheyChar(text)) {
        return TextEngineDecision(
            engine = TextSmartEngine.Centerline,
            reason = "智能识别：含中文等非 ASCII 字符 → 采用中心线走线（骨架化），Hershey 无该字符字形"
        )
    }
    return TextEngineDecision(
        engine = TextSmartEngine.Hershey,
        reason = "智能识别：纯 ASCII → 采用 Hershey 单线字体（单线笔画，最贴切「写字」效果）"
    )
}
