package com.lasergrbl.core.golden

import com.lasergrbl.core.vector.DEFAULT_IMAGE_VECTOR_OPTIONS
import com.lasergrbl.core.vector.ImageVectorOptions
import com.lasergrbl.core.vector.PotraceImage
import com.lasergrbl.core.vector.SmartVectorOptions
import com.lasergrbl.core.vector.TextSmartEngine
import com.lasergrbl.core.vector.VECTOR_TOOL_LABELS
import com.lasergrbl.core.vector.VectorDecision
import com.lasergrbl.core.vector.VectorMode
import com.lasergrbl.core.vector.VectorTool
import com.lasergrbl.core.vector.convertImageVector
import com.lasergrbl.core.vector.convertImageVectorSmart
import com.lasergrbl.core.vector.decideTextEngine
import com.lasergrbl.core.vector.hasNonAscii
import com.lasergrbl.core.vector.hasNonHersheyChar
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `convertImageVector` / `convertImageVectorSmart` / 字符判定 对照 v2 的黄金样本
 * `golden/image-vector.json`（33 条目）。
 *
 * 样本由 `tools/golden/generate.ts` 的 `caseImageVector()` 用**真实的 TypeScript 实现**生成，
 * 内嵌两张合成位图（palette-rle 编码）：
 *  - 128×128：黑圆（r=24）+ **半透明**黑矩形 + 黑圆环 + **全透明但 RGB 非零**的条带；
 *  - 40×40：3px 十字 + 3px 斜线 + 1px 短刺（同时被 `outline-threshold-90-of-bars` 与
 *    `smart-thin-lines` 使用）。
 *
 * 比对内容：
 *  - `toolLabels` 两条标签逐字；
 *  - `defaultOptions` 24 个字段逐条（同时校验 `DEFAULT_IMAGE_VECTOR_OPTIONS` 常量与
 *    data class 默认值完全相同）；
 *  - 8 组 variants：`lines` **逐行相等**（909 行）、`lineCount` / `pathCount`、
 *    `lengthMm` 按 `Double` **零容差**、`preview` 逐像素（399620 字节）与 sha256；
 *  - 3 组 smartVariants：同上（515 行 / 137472 字节）+ `decision` 逐字段（含内嵌 14 项分析）；
 *  - `hasNonAscii` 10 条 / `hasNonHersheyChar` 12 条 / `decideTextEngine` 12 条输入输出对照。
 *
 * ⚠️ 样本的 `smartVariants` 里**没有** `image` 节点，图片按其 `name` 从对应的 variants 条目取
 * （详见 [smartImages] 的说明）；该假设由「三组 smart 变体的 lines / preview 全部逐字节命中」
 * 反过来验证。样本里 3 组 smart 的 `decision.mode` 恰好都是 `Outline`，因此样本**无法区分**
 * 「用 decision.mode」与「用 options.tool」—— 这个盲区由 [smartCenterlineDecisionEqualsExplicitCenterline]
 * 补上（非黄金判据，见该用例 KDoc）。
 */
class GoldenImageVectorTest {

    /** 黄金样本 `image` / `preview` 节点的最小实现：`data` 是 RGBA 缓冲，每元素 0..255。 */
    private class RawImage(
        override val data: IntArray,
        override val width: Int,
        override val height: Int
    ) : PotraceImage

    /** 逐行比对过的行数（用于自检 `entryCounts.emittedLines`）。 */
    private var lineComparisons = 0
    private var smartLineComparisons = 0

    /** 逐字节比对过的 preview 字节数。 */
    private var previewByteComparisons = 0
    private var smartPreviewByteComparisons = 0

    /** 逐字段比对过的 decision 字段数。 */
    private var decisionFieldComparisons = 0

    @Test
    fun toolLabelsAndDefaultOptionsMatchOracle() {
        val result = Golden.result("image-vector")

        // ---- toolLabels：键集合 + 逐条文案 ----
        val labels = Golden.objectAt(result, "toolLabels")
        assertEquals(setOf("Outline", "Centerline"), labels.keys, "toolLabels 键集合")
        assertEquals("轮廓描线", Golden.string(labels, "Outline"), "toolLabels.Outline")
        assertEquals("中心线走线", Golden.string(labels, "Centerline"), "toolLabels.Centerline")
        assertEquals(
            mapOf(VectorTool.Outline to "轮廓描线", VectorTool.Centerline to "中心线走线"),
            VECTOR_TOOL_LABELS,
            "VECTOR_TOOL_LABELS 必须与样本逐字一致"
        )

        // ---- defaultOptions：24 个字段逐条 ----
        val defaults = Golden.objectAt(result, "defaultOptions")
        assertEquals(BASE_OPTION_KEYS, defaults.keys, "defaultOptions 字段集合（不应有 laserPower）")
        val d = DEFAULT_IMAGE_VECTOR_OPTIONS
        assertEquals(VectorTool.Outline, d.tool, "defaultOptions.tool")
        assertSameDouble(Golden.double(defaults, "threshold"), d.threshold, "defaultOptions.threshold")
        assertEquals(Golden.boolean(defaults, "invert"), d.invert, "defaultOptions.invert")
        assertSameDouble(Golden.double(defaults, "widthMm"), d.widthMm, "defaultOptions.widthMm")
        assertSameDouble(Golden.double(defaults, "heightMm"), d.heightMm, "defaultOptions.heightMm")
        assertSameDouble(Golden.double(defaults, "offsetX"), d.offsetX, "defaultOptions.offsetX")
        assertSameDouble(Golden.double(defaults, "offsetY"), d.offsetY, "defaultOptions.offsetY")
        assertSameDouble(Golden.double(defaults, "markSpeed"), d.markSpeed, "defaultOptions.markSpeed")
        assertSameDouble(Golden.double(defaults, "travelSpeed"), d.travelSpeed, "defaultOptions.travelSpeed")
        assertEquals(Golden.int(defaults, "minPower"), d.minPower, "defaultOptions.minPower")
        assertEquals(Golden.int(defaults, "maxPower"), d.maxPower, "defaultOptions.maxPower")
        assertEquals(Golden.boolean(defaults, "pwm"), d.pwm, "defaultOptions.pwm")
        assertEquals(Golden.string(defaults, "laserOn"), d.laserOn, "defaultOptions.laserOn")
        assertEquals(Golden.string(defaults, "laserOff"), d.laserOff, "defaultOptions.laserOff")
        assertEquals(Golden.string(defaults, "header"), d.header, "defaultOptions.header")
        assertEquals(Golden.string(defaults, "footer"), d.footer, "defaultOptions.footer")
        assertEquals(Golden.boolean(defaults, "optimize"), d.optimize, "defaultOptions.optimize")
        assertSameDouble(Golden.double(defaults, "turdSize"), d.turdSize, "defaultOptions.turdSize")
        assertSameDouble(Golden.double(defaults, "alphaMax"), d.alphaMax, "defaultOptions.alphaMax")
        assertSameDouble(Golden.double(defaults, "optTolerance"), d.optTolerance, "defaultOptions.optTolerance")
        assertEquals(
            Golden.boolean(defaults, "curveOptimizing"),
            d.curveOptimizing,
            "defaultOptions.curveOptimizing"
        )
        assertSameDouble(
            Golden.double(defaults, "flattenTolerance"),
            d.flattenTolerance,
            "defaultOptions.flattenTolerance"
        )
        assertSameDouble(Golden.double(defaults, "minBranchPx"), d.minBranchPx, "defaultOptions.minBranchPx")
        assertSameDouble(
            Golden.double(defaults, "simplifyTolerance"),
            d.simplifyTolerance,
            "defaultOptions.simplifyTolerance"
        )
        assertEquals(null, d.laserPower, "defaultOptions.laserPower 应为 null（样本里没有该键）")
        // 常量与 data class 默认值必须同源：否则「默认值」会有两个出处
        assertEquals(
            ImageVectorOptions(),
            DEFAULT_IMAGE_VECTOR_OPTIONS,
            "DEFAULT_IMAGE_VECTOR_OPTIONS 与 data class 默认值"
        )
    }

    @Test
    fun imageVectorVariantsMatchOracle() {
        val result = Golden.result("image-vector")
        val variants = Golden.objectsAt(result, "variants")
        assertEquals(8, variants.size, "variants 数量")

        var outlineCount = 0
        var centerlineCount = 0
        var degenerateCount = 0

        for (variant in variants) {
            val name = Golden.string(variant, "name")
            val optionsNode = Golden.objectAt(variant, "options")
            // 选项字段集合自检：既不能少读字段，也不能多出预期外的字段
            assertTrue(
                optionsNode.keys == BASE_OPTION_KEYS || optionsNode.keys == BASE_OPTION_KEYS + "laserPower",
                "$name.options 字段集合异常：${optionsNode.keys}"
            )
            val options = imageVectorOptionsOf(optionsNode)
            val image = rawImageOf(Golden.objectAt(variant, "image"), "$name.image")

            val actual = convertImageVector(image, options)
            assertResult(
                variant,
                actual.lines,
                actual.pathCount,
                actual.lengthMm,
                actual.preview,
                name,
                isSmart = false
            )

            if (options.tool == VectorTool.Centerline) centerlineCount++ else outlineCount++
            if (image.width <= 0 || image.height <= 0) {
                degenerateCount++
                // 退化输入：v2 返回 `new ImageData(1, 1)`，即 1×1 全透明黑（四个通道都是 0）
                assertEquals(0, actual.lines.size, "$name 空图不应有输出")
                assertEquals(0, actual.pathCount, "$name 空图 pathCount")
                assertSameDouble(0.0, actual.lengthMm, "$name 空图 lengthMm")
                assertEquals(4, actual.preview.size, "$name 空图 preview 长度")
                assertEquals(listOf(0, 0, 0, 0), actual.preview.toList(), "$name 空图 preview 全零")
            } else if (options.tool == VectorTool.Centerline) {
                // Centerline 分支必须真的产出内容，避免「两边都是空结果」式的假绿
                assertTrue(actual.lines.isNotEmpty(), "$name Centerline 结果不应为空")
            }
        }

        assertEquals(6, outlineCount, "Outline 变体数")
        assertEquals(2, centerlineCount, "Centerline 变体数")
        assertEquals(1, degenerateCount, "退化（0×0）变体数")

        // 样本元数据自检
        val counts = Golden.objectAt(Golden.load("image-vector"), "entryCounts")
        assertEquals(Golden.int(counts, "imageVectorVariants"), variants.size, "entryCounts.imageVectorVariants")
        assertEquals(Golden.int(counts, "emittedLines"), lineComparisons, "entryCounts.emittedLines")
        assertEquals(Golden.int(counts, "toolLabels"), labels().size, "entryCounts.toolLabels")
        assertEquals(909, lineComparisons, "逐行比对的总行数")
        assertEquals(399620, previewByteComparisons, "逐像素比对的总字节数（6×65536 + 6400 + 4）")
    }

    @Test
    fun smartVariantsMatchOracle() {
        val result = Golden.result("image-vector")
        val smart = Golden.objectsAt(result, "smartVariants")
        assertEquals(3, smart.size, "smartVariants 数量")

        val images = smartImages(result)
        var fallbackCount = 0

        for (variant in smart) {
            val name = Golden.string(variant, "name")
            val optionsNode = Golden.objectAt(variant, "options")
            // 样本里 smart 变体的 options = `{ ...DEFAULT_IMAGE_VECTOR_OPTIONS }`（即带 tool 的 24 个字段），
            // 判定参数只有 strict 那组显式给出 —— 缺省时对应 v2 的 `undefined`，
            // 由 decideVectorMode 内部的 `?? 2.5` / `?? 0.5` / 派生值兜底（见 smartVectorOptionsOf）。
            val expectedExtras = when (name) {
                "smart-centerline-image-strict" -> SMART_EXTRA_KEYS
                "smart-outline-image-default", "smart-thin-lines" -> emptySet()
                else -> error("样本里出现未预期的 smart 变体：$name")
            }
            assertEquals(expectedExtras, optionsNode.keys - BASE_OPTION_KEYS, "$name.options 判定参数字段")
            assertEquals(
                BASE_OPTION_KEYS,
                optionsNode.keys intersect BASE_OPTION_KEYS,
                "$name.options 基础字段集合"
            )
            val options = smartVectorOptionsOf(optionsNode)
            val image = images[name] ?: error("样本 smartVariants 里没有可对应的图片：$name")

            val actual = convertImageVectorSmart(image, options)
            assertResult(
                variant,
                actual.lines,
                actual.pathCount,
                actual.lengthMm,
                actual.preview,
                name,
                isSmart = true
            )
            assertDecision(Golden.objectAt(variant, "decision"), actual.decision, "$name.decision")
            if (actual.decision.fallback) fallbackCount++

            // 与显式选择的一致性（v2 注释承诺：两种方式的数值结果与显式选择时完全一致）
            val explicit = convertImageVector(
                image,
                options.toImageVectorOptions(vectorToolOfMode(actual.decision.mode))
            )
            assertEquals(explicit.lines, actual.lines, "$name 智能 vs 显式 lines")
            assertEquals(explicit.pathCount, actual.pathCount, "$name 智能 vs 显式 pathCount")
            assertSameDouble(explicit.lengthMm, actual.lengthMm, "$name 智能 vs 显式 lengthMm")
            assertTrue(explicit.preview.contentEquals(actual.preview), "$name 智能 vs 显式 preview")
        }

        // 样本里 3 组判定：image-default（细化不收敛）与 centerline-strict（墨水覆盖超 0.2）
        // 是兜底；thin-lines 虽然也判 Outline，但那是「实心 / 粗笔画」的正常判定（fallback=false）。
        assertEquals(2, fallbackCount, "样本里 2 组 smart 判定走了兜底规则")

        val counts = Golden.objectAt(Golden.load("image-vector"), "entryCounts")
        assertEquals(Golden.int(counts, "smartVariants"), smart.size, "entryCounts.smartVariants")
        assertEquals(Golden.int(counts, "smartEmittedLines"), smartLineComparisons, "entryCounts.smartEmittedLines")
        assertEquals(515, smartLineComparisons, "smart 逐行比对的总行数")
        assertEquals(137472, smartPreviewByteComparisons, "smart 逐像素比对的总字节数（2×65536 + 6400）")
        assertEquals(3 * DECISION_FIELDS, decisionFieldComparisons, "decision 逐字段比对总数")
    }

    @Test
    fun charClassificationMatchesOracle() {
        val result = Golden.result("image-vector")

        val nonAsciiRows = Golden.objectsAt(result, "hasNonAscii")
        assertEquals(10, nonAsciiRows.size, "hasNonAscii 条目数")
        for (row in nonAsciiRows) {
            val input = Golden.string(row, "input")
            assertEquals(
                Golden.boolean(row, "output"),
                hasNonAscii(input),
                "hasNonAscii(${escape(input)})"
            )
        }

        val hersheyRows = Golden.objectsAt(result, "hasNonHersheyChar")
        assertEquals(12, hersheyRows.size, "hasNonHersheyChar 条目数")
        for (row in hersheyRows) {
            val input = Golden.string(row, "input")
            assertEquals(
                Golden.boolean(row, "output"),
                hasNonHersheyChar(input),
                "hasNonHersheyChar(${escape(input)})"
            )
        }

        val engineRows = Golden.objectsAt(result, "decideTextEngine")
        assertEquals(12, engineRows.size, "decideTextEngine 条目数")
        for (row in engineRows) {
            val input = Golden.string(row, "input")
            val output = Golden.objectAt(row, "output")
            val actual = decideTextEngine(input)
            assertEquals(
                Golden.string(output, "engine"),
                if (actual.engine == TextSmartEngine.Centerline) "Centerline" else "Hershey",
                "decideTextEngine(${escape(input)}).engine"
            )
            assertEquals(
                Golden.string(output, "reason"),
                actual.reason,
                "decideTextEngine(${escape(input)}).reason"
            )
        }

        val counts = Golden.objectAt(Golden.load("image-vector"), "entryCounts")
        assertEquals(Golden.int(counts, "hasNonAsciiCases"), nonAsciiRows.size, "entryCounts.hasNonAsciiCases")
        assertEquals(
            Golden.int(counts, "decideTextEngineCases"),
            engineRows.size,
            "entryCounts.decideTextEngineCases"
        )
        // entryCount = variants + smartVariants + hasNonAscii + decideTextEngine
        assertEquals(
            Golden.int(Golden.load("image-vector"), "entryCount"),
            nonAsciiRows.size + engineRows.size + 8 + 3,
            "entryCount"
        )
        assertEquals(33, Golden.int(Golden.load("image-vector"), "entryCount"), "样本条目总数")
    }

    /**
     * 辅助一致性用例（**非黄金样本判据**，样本盲区补充）。
     *
     * 黄金样本里 3 组 `convertImageVectorSmart` 的判定恰好都是 `Outline`，因此样本无法区分
     * 「把 `decision.mode` 传给 `convertImageVector`」与「沿用 options 里的 tool」。
     * 本用例用 40×40 细线图 + `strokeWidthThresholdPct = 20` 逼出 **Centerline** 判定：
     *  - 先断言判定确实落在 Centerline（否则本用例无意义，直接红）；
     *  - 再断言智能结果与「显式 tool = Centerline」逐字段一致 —— 这正是 v2 注释承诺的不变式
     *    （`交给原有转换函数，两种方式的数值结果与显式选择时完全一致`）。
     * Centerline 走线本身的正确性由黄金样本的 `centerline-default` / `centerline-tuned` 钉住；
     * 这里只补「判定结果被用于选择分支」这一条。
     */
    @Test
    fun smartCenterlineDecisionEqualsExplicitCenterline() {
        val result = Golden.result("image-vector")
        val thin = smartImages(result)["smart-thin-lines"] ?: error("样本缺少 smart-thin-lines")

        val options = SmartVectorOptions(strokeWidthThresholdPct = 20.0)
        val smart = convertImageVectorSmart(thin, options)
        assertEquals(
            VectorMode.Centerline,
            smart.decision.mode,
            "该配置必须落到 Centerline 分支，否则本用例退化（判定：${smart.decision.summary}）"
        )
        assertTrue(smart.lines.isNotEmpty(), "Centerline 结果不应为空")

        val explicit = convertImageVector(thin, options.toImageVectorOptions(VectorTool.Centerline))
        assertEquals(explicit.lines, smart.lines, "智能 Centerline 与显式 Centerline 的 lines")
        assertEquals(explicit.pathCount, smart.pathCount, "pathCount")
        assertSameDouble(explicit.lengthMm, smart.lengthMm, "lengthMm")
        assertTrue(explicit.preview.contentEquals(smart.preview), "preview")
    }

    // -----------------------------------------------------------------------
    // 辅助
    // -----------------------------------------------------------------------

    /** 比对普通结果 / 智能结果的公共字段。 */
    private fun assertResult(
        node: JsonObject,
        lines: List<String>,
        pathCount: Int,
        lengthMm: Double,
        preview: IntArray,
        where: String,
        isSmart: Boolean
    ) {
        val expectedLines = Golden.strings(node, "lines")
        assertEquals(Golden.int(node, "lineCount"), lines.size, "$where.lineCount")
        assertEquals(expectedLines.size, lines.size, "$where.lines 行数")
        for (i in expectedLines.indices) {
            assertEquals(expectedLines[i], lines[i], "$where.lines[$i]")
            if (isSmart) smartLineComparisons++ else lineComparisons++
        }
        assertEquals(Golden.int(node, "pathCount"), pathCount, "$where.pathCount")
        assertSameDouble(Golden.double(node, "lengthMm"), lengthMm, "$where.lengthMm")
        assertPreview(Golden.objectAt(node, "preview"), preview, where, isSmart)
    }

    /** 智能结果额外比对：`decision` 的 5 个字段 + 内嵌分析的 14 个字段。 */
    private fun assertDecision(node: JsonObject, actual: VectorDecision, where: String) {
        assertEquals(
            setOf("mode", "fallback", "analysis", "thresholdPct", "summary"),
            node.keys,
            "$where 字段集合"
        )
        assertEquals(Golden.string(node, "mode"), vectorModeName(actual.mode), "$where.mode")
        assertEquals(Golden.boolean(node, "fallback"), actual.fallback, "$where.fallback")
        assertSameDouble(Golden.double(node, "thresholdPct"), actual.thresholdPct, "$where.thresholdPct")
        assertEquals(Golden.string(node, "summary"), actual.summary, "$where.summary")
        decisionFieldComparisons += 4

        val a = Golden.objectAt(node, "analysis")
        assertEquals(ANALYSIS_FIELDS, a.keys, "$where.analysis 字段集合")
        val act = actual.analysis
        assertEquals(Golden.int(a, "width"), act.width, "$where.analysis.width")
        assertEquals(Golden.int(a, "height"), act.height, "$where.analysis.height")
        assertEquals(Golden.int(a, "shortSide"), act.shortSide, "$where.analysis.shortSide")
        assertEquals(Golden.int(a, "inkArea"), act.inkArea, "$where.analysis.inkArea")
        assertSameDouble(Golden.double(a, "inkRatio"), act.inkRatio, "$where.analysis.inkRatio")
        assertEquals(Golden.int(a, "skeletonPixels"), act.skeletonPixels, "$where.analysis.skeletonPixels")
        assertSameDouble(
            Golden.double(a, "skeletonLength"),
            act.skeletonLength,
            "$where.analysis.skeletonLength"
        )
        assertEquals(Golden.int(a, "endpoints"), act.endpoints, "$where.analysis.endpoints")
        assertEquals(Golden.int(a, "junctions"), act.junctions, "$where.analysis.junctions")
        assertSameDouble(Golden.double(a, "iterations"), act.iterations, "$where.analysis.iterations")
        assertEquals(Golden.boolean(a, "converged"), act.converged, "$where.analysis.converged")
        assertSameDouble(
            Golden.double(a, "avgStrokeWidthPx"),
            act.avgStrokeWidthPx,
            "$where.analysis.avgStrokeWidthPx"
        )
        assertSameDouble(
            Golden.double(a, "strokeWidthRatio"),
            act.strokeWidthRatio,
            "$where.analysis.strokeWidthRatio"
        )
        assertEquals(Golden.boolean(a, "degenerate"), act.degenerate, "$where.analysis.degenerate")
        decisionFieldComparisons += 14
    }

    /** 预览逐像素比对 + sha256（样本记录的摘要必须同时等于「解码值」与「实现输出」）。 */
    private fun assertPreview(node: JsonObject, actual: IntArray, where: String, isSmart: Boolean) {
        val w = Golden.int(node, "width")
        val h = Golden.int(node, "height")
        assertEquals(w * h * 4, actual.size, "$where.preview 长度")
        val expected = GoldenBitmap.decode(node)
        assertEquals(w * h * 4, expected.size, "$where.preview 解码长度")
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                val pixel = i / 4
                assertEquals(
                    expected[i],
                    actual[i],
                    "$where.preview 第 $pixel 像素（x=${pixel % w}, y=${pixel / w}）通道 ${i % 4}"
                )
            }
            if (isSmart) smartPreviewByteComparisons++ else previewByteComparisons++
        }
        val sha = Golden.string(node, "sha256")
        assertEquals(sha, GoldenBitmap.sha256Rgba(expected), "$where.preview 样本 sha256 自检")
        assertEquals(sha, GoldenBitmap.sha256Rgba(actual), "$where.preview sha256")
    }

    /** 解码 `image` 节点并校验 sha256（顺带验证 palette-rle 没有被解错）。 */
    private fun rawImageOf(node: JsonObject, where: String): RawImage {
        val image = RawImage(
            data = GoldenBitmap.decode(node),
            width = Golden.int(node, "width"),
            height = Golden.int(node, "height")
        )
        assertEquals(
            Golden.string(node, "sha256"),
            GoldenBitmap.sha256Rgba(image.data),
            "$where 解码结果与样本记录的 sha256 不一致"
        )
        return image
    }

    /**
     * 智能变体 → 位图。
     *
     * 样本的 `smartVariants` 里没有 `image` 节点（见 `caseImageVector()` 的写法），按生成器
     * 用的同一张图对应：
     *  - `smart-outline-image-default` / `smart-centerline-image-strict` → `imageVectorSampleImage()`
     *    （与 `outline-default` 同一张，128×128）；
     *  - `smart-thin-lines` → `centerlineSampleImage()`（与 `outline-threshold-90-of-bars` 同一张，40×40）。
     * 该假设不是靠命名猜的：只有图片正确，3 组 smart 的 lines / preview 才可能逐字节命中。
     */
    private fun smartImages(result: JsonObject): Map<String, RawImage> {
        val variants = Golden.objectsAt(result, "variants")
        val byName = variants.associateBy { Golden.string(it, "name") }
        fun imageOf(variantName: String): RawImage {
            val node = byName[variantName] ?: error("样本缺少变体 $variantName")
            return rawImageOf(Golden.objectAt(node, "image"), "$variantName.image")
        }
        return mapOf(
            "smart-outline-image-default" to imageOf("outline-default"),
            "smart-centerline-image-strict" to imageOf("outline-default"),
            "smart-thin-lines" to imageOf("outline-threshold-90-of-bars")
        )
    }

    private fun labels(): Map<String, String> {
        val node = Golden.objectAt(Golden.result("image-vector"), "toolLabels")
        return node.keys.associateWith { Golden.string(node, it) }
    }

    private fun imageVectorOptionsOf(node: JsonObject): ImageVectorOptions = ImageVectorOptions(
        tool = vectorTool(Golden.string(node, "tool")),
        threshold = Golden.double(node, "threshold"),
        invert = Golden.boolean(node, "invert"),
        widthMm = Golden.double(node, "widthMm"),
        heightMm = Golden.double(node, "heightMm"),
        offsetX = Golden.double(node, "offsetX"),
        offsetY = Golden.double(node, "offsetY"),
        markSpeed = Golden.double(node, "markSpeed"),
        travelSpeed = Golden.double(node, "travelSpeed"),
        minPower = Golden.int(node, "minPower"),
        maxPower = Golden.int(node, "maxPower"),
        laserPower = Golden.intOrNull(node, "laserPower"),
        laserOn = Golden.string(node, "laserOn"),
        laserOff = Golden.string(node, "laserOff"),
        pwm = Golden.boolean(node, "pwm"),
        header = Golden.string(node, "header"),
        footer = Golden.string(node, "footer"),
        optimize = Golden.boolean(node, "optimize"),
        turdSize = Golden.double(node, "turdSize"),
        alphaMax = Golden.double(node, "alphaMax"),
        optTolerance = Golden.double(node, "optTolerance"),
        curveOptimizing = Golden.boolean(node, "curveOptimizing"),
        flattenTolerance = Golden.double(node, "flattenTolerance"),
        minBranchPx = Golden.double(node, "minBranchPx"),
        simplifyTolerance = Golden.double(node, "simplifyTolerance")
    )

    /**
     * 智能选项：样本里那份 options 就是 `DEFAULT_IMAGE_VECTOR_OPTIONS`（+ 可选的 3 个判定参数），
     * 缺省字段按 v2 的 `undefined` 语义落回 [SmartVectorOptions] 的默认值
     * （`strokeWidthThresholdPct` 2.5 / `maxInkRatio` 0.5 / `maxIterations` 由阈值反推）。
     */
    private fun smartVectorOptionsOf(node: JsonObject): SmartVectorOptions = SmartVectorOptions(
        threshold = Golden.double(node, "threshold"),
        invert = Golden.boolean(node, "invert"),
        widthMm = Golden.double(node, "widthMm"),
        heightMm = Golden.double(node, "heightMm"),
        offsetX = Golden.double(node, "offsetX"),
        offsetY = Golden.double(node, "offsetY"),
        markSpeed = Golden.double(node, "markSpeed"),
        travelSpeed = Golden.double(node, "travelSpeed"),
        minPower = Golden.int(node, "minPower"),
        maxPower = Golden.int(node, "maxPower"),
        laserPower = Golden.intOrNull(node, "laserPower"),
        laserOn = Golden.string(node, "laserOn"),
        laserOff = Golden.string(node, "laserOff"),
        pwm = Golden.boolean(node, "pwm"),
        header = Golden.string(node, "header"),
        footer = Golden.string(node, "footer"),
        optimize = Golden.boolean(node, "optimize"),
        turdSize = Golden.double(node, "turdSize"),
        alphaMax = Golden.double(node, "alphaMax"),
        optTolerance = Golden.double(node, "optTolerance"),
        curveOptimizing = Golden.boolean(node, "curveOptimizing"),
        flattenTolerance = Golden.double(node, "flattenTolerance"),
        minBranchPx = Golden.double(node, "minBranchPx"),
        simplifyTolerance = Golden.double(node, "simplifyTolerance"),
        strokeWidthThresholdPct = Golden.doubleOrNull(node, "strokeWidthThresholdPct") ?: 2.5,
        maxInkRatio = Golden.doubleOrNull(node, "maxInkRatio") ?: 0.5,
        maxIterations = Golden.doubleOrNull(node, "maxIterations")
    )

    private fun vectorTool(name: String): VectorTool = when (name) {
        "Outline" -> VectorTool.Outline
        "Centerline" -> VectorTool.Centerline
        else -> error("未知走线方式：$name")
    }

    private fun vectorModeName(mode: VectorMode): String = when (mode) {
        VectorMode.Outline -> "Outline"
        VectorMode.Centerline -> "Centerline"
    }

    /** [VectorMode]（判定用的枚举）→ [VectorTool]（走线方式枚举）。 */
    private fun vectorToolOfMode(mode: VectorMode): VectorTool = when (mode) {
        VectorMode.Outline -> VectorTool.Outline
        VectorMode.Centerline -> VectorTool.Centerline
    }

    /** 控制字符可视化（失败信息里要能看出是 `\n` 还是 `\t`）。 */
    private fun escape(text: String): String =
        "\"" + text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    /** 数值必须与 TypeScript 的浮点结果**逐位相同**（容差 0，且不允许 NaN / Infinity）。 */
    private fun assertSameDouble(expected: Double, actual: Double, message: String) {
        assertTrue(actual.isFinite(), "$message（实际值不是有限数：$actual）")
        assertEquals(expected, actual, 0.0, message)
    }

    private companion object {
        /** `DEFAULT_IMAGE_VECTOR_OPTIONS` / 变体 options 的 24 个字段（`laserPower` 单独可选）。 */
        val BASE_OPTION_KEYS = setOf(
            "tool", "threshold", "invert", "widthMm", "heightMm", "offsetX", "offsetY",
            "markSpeed", "travelSpeed", "minPower", "maxPower", "pwm", "laserOn", "laserOff",
            "header", "footer", "optimize", "turdSize", "alphaMax", "optTolerance",
            "curveOptimizing", "flattenTolerance", "minBranchPx", "simplifyTolerance"
        )

        /** 智能变体在基础字段之外可能出现的 3 个判定参数（缺省 = v2 的 `undefined`）。 */
        val SMART_EXTRA_KEYS = setOf("strokeWidthThresholdPct", "maxInkRatio", "maxIterations")

        val ANALYSIS_FIELDS = setOf(
            "width", "height", "shortSide", "inkArea", "inkRatio", "skeletonPixels", "skeletonLength",
            "endpoints", "junctions", "iterations", "converged", "avgStrokeWidthPx",
            "strokeWidthRatio", "degenerate"
        )

        /** `decision` 的字段数：mode / fallback / thresholdPct / summary + 14 项分析。 */
        const val DECISION_FIELDS = 18
    }
}
