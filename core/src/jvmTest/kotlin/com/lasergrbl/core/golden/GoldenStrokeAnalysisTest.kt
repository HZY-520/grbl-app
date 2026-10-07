package com.lasergrbl.core.golden

import com.lasergrbl.core.vector.PotraceImage
import com.lasergrbl.core.vector.StrokeAnalysis
import com.lasergrbl.core.vector.StrokeAnalysisOptions
import com.lasergrbl.core.vector.VectorDecision
import com.lasergrbl.core.vector.VectorDecisionOptions
import com.lasergrbl.core.vector.VectorMode
import com.lasergrbl.core.vector.analyzeStrokes
import com.lasergrbl.core.vector.decideVectorMode
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `analyzeStrokes` / `decideVectorMode` 对照 v2 的黄金样本 `golden/stroke-analysis.json`。
 *
 * 样本由 `tools/golden/generate.ts` 用**真实的 TypeScript 实现**跑 5 张合成 64×64 RGBA 位图
 * （1px 网格 / 4px 粗条 / r=22 实心圆 / 60.9% 密集墨迹 / 单个像素），每张跑两组选项：
 *   * 默认：`analyzeStrokes(img, {})` 与 `decideVectorMode(img, {})`；
 *   * 严格：`analyzeStrokes(img, { strokeWidthThresholdPct: 6, maxIterations: 12 })` 与
 *     `decideVectorMode(img, { strokeWidthThresholdPct: 6, maxInkRatio: 0.2, maxIterations: 12 })`
 *     （选项硬编码自生成器，样本里没有 options 节点）。
 *
 * 比对内容：分析结果的**全部 14 个字段**（含 inkRatio / skeletonLength / avgStrokeWidthPx /
 * strokeWidthRatio / iterations 的逐位相等），以及判定结果的 mode / fallback / thresholdPct /
 * summary 字符串与内嵌的同一份分析明细。
 */
class GoldenStrokeAnalysisTest {

    /** 黄金样本 `image` 节点的最小实现：`data` 是 RGBA 缓冲，每元素 0..255。 */
    private class RawImage(
        override val data: IntArray,
        override val width: Int,
        override val height: Int
    ) : PotraceImage

    /** 单个样本比对的字段数（14 个分析字段 × 2 组分析 + 2 组判定）。 */
    var comparedFields = 0
        private set

    @Test
    fun matchesOracle() {
        val result = Golden.result("stroke-analysis")
        val samples = Golden.objectsAt(result, "samples")
        assertEquals(5, samples.size, "样本数量")

        var analyses = 0
        var decisions = 0
        var centerlineDecisions = 0
        var outlineDecisions = 0
        var defaultFallbacks = 0

        for (sample in samples) {
            val label = Golden.string(sample, "label")
            val imageNode = Golden.objectAt(sample, "image")
            val image = RawImage(
                data = GoldenBitmap.decode(imageNode),
                width = Golden.int(imageNode, "width"),
                height = Golden.int(imageNode, "height")
            )
            assertEquals(
                Golden.string(imageNode, "sha256"),
                GoldenBitmap.sha256Rgba(image.data),
                "$label 的位图解码结果与样本记录的 sha256 不一致"
            )

            // 默认选项
            val aDefault = analyzeStrokes(image, StrokeAnalysisOptions())
            assertAnalysis(Golden.objectAt(sample, "analyzeDefault"), aDefault, "$label analyzeDefault")
            analyses++

            val dDefault = decideVectorMode(image, VectorDecisionOptions())
            assertDecision(Golden.objectAt(sample, "decideDefault"), dDefault, "$label decideDefault")
            decisions++
            if (dDefault.mode == VectorMode.Centerline) centerlineDecisions++ else outlineDecisions++
            if (dDefault.fallback) defaultFallbacks++

            // 严格选项（对应生成器 caseStrokeAnalysis 里的 aStrict / dStrict）
            val aStrict = analyzeStrokes(
                image,
                StrokeAnalysisOptions(strokeWidthThresholdPct = 6.0, maxIterations = 12.0)
            )
            assertAnalysis(
                Golden.objectAt(sample, "analyzeThreshold6Iter12"),
                aStrict,
                "$label analyzeThreshold6Iter12"
            )
            analyses++

            val dStrict = decideVectorMode(
                image,
                VectorDecisionOptions(
                    strokeWidthThresholdPct = 6.0,
                    maxIterations = 12.0,
                    maxInkRatio = 0.2
                )
            )
            assertDecision(
                Golden.objectAt(sample, "decideStrictThreshold6Iter12maxInkRatio0p2"),
                dStrict,
                "$label decideStrictThreshold6Iter12maxInkRatio0p2"
            )
            decisions++
        }

        // 样本元数据自检：case 声明的 entryCounts
        val entryCounts = Golden.objectAt(Golden.load("stroke-analysis"), "entryCounts")
        assertEquals(Golden.int(entryCounts, "samples"), samples.size, "entryCounts.samples")
        assertEquals(Golden.int(entryCounts, "analyses"), analyses, "entryCounts.analyses")
        assertEquals(Golden.int(entryCounts, "decisions"), decisions, "entryCounts.decisions")
        assertEquals(
            Golden.int(entryCounts, "centerlineDecisions"),
            centerlineDecisions,
            "entryCounts.centerlineDecisions"
        )
        assertEquals(Golden.int(entryCounts, "outlineDecisions"), outlineDecisions, "entryCounts.outlineDecisions")
        assertEquals(Golden.int(entryCounts, "defaultFallbacks"), defaultFallbacks, "entryCounts.defaultFallbacks")

        assertEquals(10, analyses, "分析次数")
        assertEquals(10, decisions, "判定次数")
        // 每个样本 64 个字段（2 组分析 × 14 + 2 组判定 × (4 + 内嵌分析 14)）
        assertEquals(5 * 64, comparedFields, "逐字段比对的总字段数")
    }

    /** 逐字段比对分析结果：7 个整数 + 5 个浮点（逐位）+ 2 个布尔。 */
    private fun assertAnalysis(node: JsonObject, actual: StrokeAnalysis, where: String) {
        assertEquals(Golden.int(node, "width"), actual.width, "$where.width")
        assertEquals(Golden.int(node, "height"), actual.height, "$where.height")
        assertEquals(Golden.int(node, "shortSide"), actual.shortSide, "$where.shortSide")
        assertEquals(Golden.int(node, "inkArea"), actual.inkArea, "$where.inkArea")
        assertSameDouble(Golden.double(node, "inkRatio"), actual.inkRatio, "$where.inkRatio")
        assertEquals(Golden.int(node, "skeletonPixels"), actual.skeletonPixels, "$where.skeletonPixels")
        assertSameDouble(Golden.double(node, "skeletonLength"), actual.skeletonLength, "$where.skeletonLength")
        assertEquals(Golden.int(node, "endpoints"), actual.endpoints, "$where.endpoints")
        assertEquals(Golden.int(node, "junctions"), actual.junctions, "$where.junctions")
        assertSameDouble(Golden.double(node, "iterations"), actual.iterations, "$where.iterations")
        assertEquals(Golden.boolean(node, "converged"), actual.converged, "$where.converged")
        assertSameDouble(
            Golden.double(node, "avgStrokeWidthPx"),
            actual.avgStrokeWidthPx,
            "$where.avgStrokeWidthPx"
        )
        assertSameDouble(
            Golden.double(node, "strokeWidthRatio"),
            actual.strokeWidthRatio,
            "$where.strokeWidthRatio"
        )
        assertEquals(Golden.boolean(node, "degenerate"), actual.degenerate, "$where.degenerate")
        comparedFields += 14
    }

    /** 逐字段比对判定结果（含内嵌的分析明细与 summary 全文）。 */
    private fun assertDecision(node: JsonObject, actual: VectorDecision, where: String) {
        assertEquals(
            Golden.string(node, "mode"),
            if (actual.mode == VectorMode.Centerline) "Centerline" else "Outline",
            "$where.mode"
        )
        assertEquals(Golden.boolean(node, "fallback"), actual.fallback, "$where.fallback")
        assertSameDouble(Golden.double(node, "thresholdPct"), actual.thresholdPct, "$where.thresholdPct")
        assertEquals(Golden.string(node, "summary"), actual.summary, "$where.summary")
        assertAnalysis(Golden.objectAt(node, "analysis"), actual.analysis, "$where.analysis")
        comparedFields += 4
    }

    /** 数值必须与 TypeScript 的浮点结果**逐位相同**（容差 0，且不允许 NaN）。 */
    private fun assertSameDouble(expected: Double, actual: Double, message: String) {
        assertTrue(actual.isFinite(), "$message（实际值不是有限数：$actual）")
        assertEquals(expected, actual, 0.0, message)
    }
}
