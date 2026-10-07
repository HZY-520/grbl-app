package com.lasergrbl.core.vector

import com.lasergrbl.core.internal.jsNumberToString
import com.lasergrbl.core.internal.jsToFixed
import com.lasergrbl.core.native.jsRound
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 笔画宽度分析 —— 「智能识别」判定线稿 / 实心图案
 * —— 逐行移植自 v2 `src/core/vector/StrokeAnalysis.ts`。
 *
 * 思路（纯几何统计，与分辨率无关）：
 *   1. 二值化 → 统计墨水（前景）面积 inkArea；
 *   2. Zhang-Suen 细化得到骨架 → 统计骨架总长 skeletonLength
 *      （四邻接按 1 计、对角邻接按 √2 计，只统计右下四个方向避免重复计数）；
 *   3. 平均笔画宽度 ≈ 墨水面积 / 骨架长度（同一条笔画：面积 ≈ 宽 × 长）；
 *   4. 归一化到图像短边：strokeWidthRatio = avgStrokeWidthPx / min(w, h)。
 *
 * ⚠️ 第 3 步只在骨架「真的收敛成 1 像素宽」时成立：粗图形（笔画宽 w）需要约 w/2
 * 次细化迭代，迭代不够时剩下的是一坨肉而不是骨架，面积 / 长度会恒等于 1 左右，
 * 会把实心块误判成线稿。所以这里按阈值反推迭代上限
 * （maxIter ≈ 阈值宽度 / 2 + 余量）：
 *   - 细线稿会在上限内收敛 → 面积 / 长度有效 → 按阈值判定；
 *   - 粗图形在细化过程中就用光迭代 → 判定为「笔画粗于阈值」→ 轮廓提取。
 * 这同时让分析保持廉价（细线稿几次迭代就结束，实心图不会跑成成百上千次）。
 *
 * 判定：收敛且宽度比例 ≤ 阈值（默认 2.5%）→ 线稿 → 中心线描线；否则 → 轮廓提取。
 * 兜底：无墨迹 / 墨水覆盖率过高（大片实心）/ 细化不收敛 / 骨架为空或退化
 *       → 一律回退轮廓提取。
 *
 * 与 centerlineTrace 共用同一份二值化与细化实现（见 Centerline.kt 的 skeletonize）。
 *
 * ⚠️ 必须原样复刻的 JS 语义（黄金样本 `golden/stroke-analysis.json` 钉住）：
 *  1. summary 里的数字走 JS 的格式化：`Math.round` → `jsRound`（并列向 +∞），
 *     数字转字符串 → `jsNumberToString`（`String(6)` 是 `"6"` 而不是 Kotlin 的 `"6.0"`），
 *     `toFixed(1)` → `jsToFixed`；
 *  2. 骨架扫描只覆盖 `1 .. h-2` / `1 .. w-2`（补边后的内圈），与 v2 的循环边界一致；
 *  3. `SQRT2` 是 `Math.SQRT2` 的字面值，逐位相同。
 */

/** 走线方式（与 ImageVector 的 VectorTool 同构，此处独立声明避免循环依赖）。 */
enum class VectorMode { Outline, Centerline }

/** `Math.SQRT2`（与 `sqrt(2.0)` 逐位相同，这里写字面值以免受平台 sqrt 实现影响）。 */
private const val SQRT2 = 1.4142135623730951

/** 细化迭代上限的硬上限：约能分辨 2 × 48 = 96 px 宽的笔画。 */
private const val MAX_THIN_ITERATIONS = 48

/** 笔画分析选项。 */
data class StrokeAnalysisOptions(
    /** 二值化阈值 0..255，默认 128（与 centerlineTrace 一致）。 */
    val threshold: Double = 128.0,
    /** 反相，默认 false。 */
    val invert: Boolean = false,
    /**
     * 笔画宽度阈值：占图像短边的百分比，默认 2.5 (%)。
     * 分析用的细化迭代上限由它反推（阈值宽 / 2 + 4），因此它会影响 converged 的判定。
     */
    val strokeWidthThresholdPct: Double = 2.5,
    /** 手动指定细化迭代上限（不传则按阈值反推）。 */
    val maxIterations: Double? = null
)

/** 笔画分析结果。 */
data class StrokeAnalysis(
    val width: Int,
    val height: Int,
    /** 图像短边（像素），用于归一化笔画宽度。 */
    val shortSide: Int,
    /** 墨水（前景）像素数。 */
    val inkArea: Int,
    /** 墨水覆盖率 0..1。 */
    val inkRatio: Double,
    /** 骨架像素数。 */
    val skeletonPixels: Int,
    /** 骨架总长度（像素，对角连接按 √2 计）。 */
    val skeletonLength: Double,
    /** 端点数量（8 邻域度 == 1）。 */
    val endpoints: Int,
    /** 分支点数量（8 邻域度 >= 3；对角阶梯也会产生，仅供参考）。 */
    val junctions: Int,
    /** 本次分析使用的细化迭代上限。 */
    val iterations: Double,
    /** 骨架是否收敛（false = 笔画比迭代上限对应的宽度还粗，宽度估算无效）。 */
    val converged: Boolean,
    /** 平均笔画宽度（像素）= 墨水面积 / 骨架长度（仅 converged 时有效）。 */
    val avgStrokeWidthPx: Double,
    /** 平均笔画宽度 / 图像短边，线稿通常 < 0.03（仅 converged 时有效）。 */
    val strokeWidthRatio: Double,
    /** 骨架是否为空或退化（无法据其判断笔画宽度）。 */
    val degenerate: Boolean
)

/** 空结果。 */
private fun emptyAnalysis(
    width: Int,
    height: Int,
    inkArea: Int = 0,
    iterations: Double = 0.0,
    converged: Boolean = true
): StrokeAnalysis = StrokeAnalysis(
    width = width,
    height = height,
    shortSide = max(1, min(width, height)),
    inkArea = inkArea,
    inkRatio = if (width > 0 && height > 0) inkArea.toDouble() / (width * height) else 0.0,
    skeletonPixels = 0,
    skeletonLength = 0.0,
    endpoints = 0,
    junctions = 0,
    iterations = iterations,
    converged = converged,
    avgStrokeWidthPx = 0.0,
    strokeWidthRatio = 0.0,
    degenerate = true
)

/**
 * 统计墨水面积与骨架长度，估算平均笔画宽度。
 * 输入位图坐标系与 centerlineTrace 一致（原点左上、y 向下）。
 */
fun analyzeStrokes(image: PotraceImage, options: StrokeAnalysisOptions = StrokeAnalysisOptions()): StrokeAnalysis {
    if (image.width <= 0 || image.height <= 0) return emptyAnalysis(0, 0)
    if (image.width < 3 || image.height < 3) return emptyAnalysis(image.width, image.height)

    val shortSide = max(1, min(image.width, image.height))
    val thresholdPct = options.strokeWidthThresholdPct
    // 阈值宽度（像素）的一半 + 余量：够细线稿收敛，又让粗图形尽早出局
    val maxIterations = options.maxIterations
        ?: max(6.0, min(MAX_THIN_ITERATIONS.toDouble(), ceil(((thresholdPct / 100.0) * shortSide) / 2.0) + 4.0))

    val sk = skeletonize(
        image,
        CenterlineOptions(
            threshold = options.threshold,
            invert = options.invert,
            maxIterations = maxIterations
        )
    )
    val data = sk.data
    val w = sk.width
    val h = sk.height
    val inkArea = sk.inkArea
    val converged = sk.converged
    val inkRatio = inkArea.toDouble() / (image.width * image.height)
    if (inkArea == 0 || w < 3 || h < 3) {
        return emptyAnalysis(image.width, image.height, inkArea, maxIterations, true)
    }

    val deg = skeletonDegree(data, w, h)

    var skeletonPixels = 0
    var endpoints = 0
    var junctions = 0
    // 只统计「右 / 下 / 右下 / 左下」四个方向，每条连接只数一次
    var step4 = 0
    var step8 = 0
    for (y in 1 until h - 1) {
        val row = y * w
        for (x in 1 until w - 1) {
            val i = row + x
            if (data[i] == 0) continue
            skeletonPixels++
            val d = deg[i]
            if (d == 1) endpoints++ else if (d >= 3) junctions++
            if (data[i + 1] != 0) step4++
            if (data[i + w] != 0) step4++
            if (data[i + w + 1] != 0) step8++
            if (data[i + w - 1] != 0) step8++
        }
    }

    val skeletonLength = step4 + step8 * SQRT2
    val degenerate = skeletonPixels == 0 || skeletonLength < 1e-6
    val usable = converged && !degenerate
    val avgStrokeWidthPx = if (usable) inkArea / skeletonLength else 0.0

    return StrokeAnalysis(
        width = image.width,
        height = image.height,
        shortSide = shortSide,
        inkArea = inkArea,
        inkRatio = inkRatio,
        skeletonPixels = skeletonPixels,
        skeletonLength = skeletonLength,
        endpoints = endpoints,
        junctions = junctions,
        iterations = maxIterations,
        converged = converged,
        avgStrokeWidthPx = avgStrokeWidthPx,
        strokeWidthRatio = if (usable) avgStrokeWidthPx / shortSide else 0.0,
        degenerate = degenerate
    )
}

/** 智能判定选项。 */
data class VectorDecisionOptions(
    /** 二值化阈值 0..255，默认 128。 */
    val threshold: Double = 128.0,
    /** 反相，默认 false。 */
    val invert: Boolean = false,
    /** 笔画宽度阈值：占图像短边的百分比，小于等于它判为线稿，默认 2.5 (%)。 */
    val strokeWidthThresholdPct: Double = 2.5,
    /** 手动指定细化迭代上限（不传则按阈值反推）。 */
    val maxIterations: Double? = null,
    /** 墨水覆盖率上限（超过视为大片实心），默认 0.5。 */
    val maxInkRatio: Double = 0.5
)

/** 智能判定结果。 */
data class VectorDecision(
    /** 判定采用的方式。 */
    val mode: VectorMode,
    /** 是否走了兜底规则（无墨迹 / 墨水覆盖过高 / 细化不收敛 / 骨架退化）。 */
    val fallback: Boolean,
    /** 统计明细。 */
    val analysis: StrokeAnalysis,
    /** 本次使用的笔画宽度阈值（占短边百分比）。 */
    val thresholdPct: Double,
    /** 界面展示用的一行中文说明（含判定依据与关键数字）。 */
    val summary: String
)

/** 百分比格式化（最多 2 位小数）。 */
private fun pct(v: Double): String {
    // v2：const r = Math.round(v * 100) / 100 —— 注意除法在浮点上做，且数字转字符串是 JS 规则
    val r = jsRound(v * 100) / 100.0
    return jsNumberToString(r) + "%"
}

/**
 * 智能判定：线稿 → 中心线描线；实心 / 粗笔画图案 → 轮廓提取。
 * 判定依据可复现，summary 里带上关键数字，便于界面上向用户解释。
 */
fun decideVectorMode(image: PotraceImage, options: VectorDecisionOptions = VectorDecisionOptions()): VectorDecision {
    val thresholdPct = options.strokeWidthThresholdPct
    val maxInkRatio = options.maxInkRatio
    // v2：analyzeStrokes(image, { ...options, strokeWidthThresholdPct: thresholdPct })
    val a = analyzeStrokes(
        image,
        StrokeAnalysisOptions(
            threshold = options.threshold,
            invert = options.invert,
            strokeWidthThresholdPct = thresholdPct,
            maxIterations = options.maxIterations
        )
    )
    val stats = "骨架 ${jsRound(a.skeletonLength)} px，端点 ${a.endpoints}，分支 ${a.junctions}"

    if (a.inkArea <= 0) {
        return VectorDecision(
            mode = VectorMode.Outline,
            fallback = true,
            analysis = a,
            thresholdPct = thresholdPct,
            summary = "智能识别：未检测到墨迹 → 采用轮廓提取（请检查二值化阈值或反相设置）"
        )
    }

    if (a.inkRatio > maxInkRatio) {
        return VectorDecision(
            mode = VectorMode.Outline,
            fallback = true,
            analysis = a,
            thresholdPct = thresholdPct,
            summary = "智能识别：墨水覆盖 ${pct(a.inkRatio * 100)} 超过上限 ${pct(maxInkRatio * 100)}（以实心块为主）→ 采用轮廓提取"
        )
    }

    if (!a.converged) {
        return VectorDecision(
            mode = VectorMode.Outline,
            fallback = true,
            analysis = a,
            thresholdPct = thresholdPct,
            summary = "智能识别：笔画粗于阈值 → 采用轮廓提取（细化 ${jsNumberToString(a.iterations)} 次仍未收敛，" +
                "笔画宽于约 ${jsNumberToString(a.iterations * 2)} px，已超过短边的 ${pct(thresholdPct)} = " +
                "${jsRound((thresholdPct / 100.0) * a.shortSide)} px）"
        )
    }

    if (a.degenerate) {
        return VectorDecision(
            mode = VectorMode.Outline,
            fallback = true,
            analysis = a,
            thresholdPct = thresholdPct,
            summary = "智能识别：骨架为空或退化（墨水覆盖 ${pct(a.inkRatio * 100)}）→ 回退轮廓提取"
        )
    }

    val widthPct = a.strokeWidthRatio * 100
    val detail = "平均笔画宽 ≈ ${jsToFixed(a.avgStrokeWidthPx, 1)} px，占图像短边 ${pct(widthPct)}（$stats）"

    if (widthPct <= thresholdPct) {
        return VectorDecision(
            mode = VectorMode.Centerline,
            fallback = false,
            analysis = a,
            thresholdPct = thresholdPct,
            summary = "智能识别：线稿 → 采用中心线描线（$detail，阈值 ${pct(thresholdPct)}）"
        )
    }

    return VectorDecision(
        mode = VectorMode.Outline,
        fallback = false,
        analysis = a,
        thresholdPct = thresholdPct,
        summary = "智能识别：实心 / 粗笔画图案 → 采用轮廓提取（$detail，超过阈值 ${pct(thresholdPct)}）"
    )
}
