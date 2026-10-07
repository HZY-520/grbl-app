package com.lasergrbl.core.golden

import com.lasergrbl.core.vector.SvgConvertOptions
import com.lasergrbl.core.vector.convertSvgToGcode
import com.lasergrbl.core.vector.parseLength
import com.lasergrbl.core.vector.parseViewBox
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `convertSvgToGcode` / `parseLength` / `parseViewBox` 对照 v2 的黄金样本 `golden/svg-to-gcode.json`。
 *
 * 样本由 `tools/golden/generate.ts` 跑**真实的 TypeScript 实现**生成：里面的 `svg` 是手写 SVG
 * （rect、带 transform 的 rect、circle、三次 + 二次/T 贝塞尔、mm/pt 长度的 line、polyline、
 * polygon、嵌套 `<g>`、`<defs>`、viewBox、`width="40mm"`），用 2 组选项转换。
 * 生成器用的是一个**极简 DOM shim**（`documentElement/children/tagName/getAttribute/querySelector`），
 * 并与 `@xmldom/xmldom` 交叉验证过 —— Kotlin 侧只需要镜像这个子集。
 *
 * 比对内容：
 *   * 变体：**每一行 G 代码逐字**比对（2 × 137 / 77 = 214 行），外加 `widthMm`/`heightMm`/
 *     `pathCount`/`pathLengthMm`；
 *   * `parseLength`：16 组输入输出（含 `null` 与空串）；
 *   * `parseViewBox`：7 组输入输出（无效输入必须是 `null`）；
 *   * `errorPaths`：3 个畸形输入必须**抛异常**，且异常消息与 TS 的 `Error.message` 逐字相同
 *     —— v2 的 `convertSvgToGcode` 是抛 `Error`，Kotlin 侧保持一致（抛 `IllegalStateException`）。
 *
 * 所有浮点字段都用 `assertEquals(expected, actual, 0.0)` —— 逐位相同。样本里存的是
 * TypeScript 浮点结果的最短可往返十进制表示，容差 0 就是移植正确性的最强判据。
 * 实测**不需要任何容差**：214 行 G 代码逐字相同，`pathLengthMm` 也是 `Double` 逐位相同 ——
 * 前提是 `Math.round` 走 `jsToFixed`、`Math.hypot` 走 V8 的两参快路径，
 * 详见 `SvgToGcode.kt` 里 `fmt` / `jsHypot` 的注释。
 */
class GoldenSvgToGcodeTest {

    @Test
    fun variantsMatchOracle() {
        val suite = Golden.load("svg-to-gcode")
        val result = Golden.result("svg-to-gcode")
        val entryCounts = Golden.objectAt(suite, "entryCounts")

        // 样本自检：消费到的 SVG 必须就是生成器记录的那份（防止夹具被换掉而测试静默通过）
        val svg = Golden.string(result, "svg")
        assertTrue(svg.contains("<svg"), "样本的 svg 字段不像 SVG 文本")
        assertTrue(svg.contains("viewBox"), "样本的 svg 字段应带 viewBox")

        val variants = Golden.objectsAt(result, "variants")
        assertEquals(Golden.int(entryCounts, "variants"), variants.size, "样本声明的变体数量")

        var emittedLines = 0
        var numericFields = 0
        for (variant in variants) {
            val name = Golden.string(variant, "name")
            val options = optionsOf(Golden.objectAt(variant, "options"))
            val actual = convertSvgToGcode(svg, options)

            // 逐行比对 G 代码
            val expectedLines = Golden.strings(variant, "lines")
            assertEquals(Golden.int(variant, "lineCount"), expectedLines.size, "变体 $name 的 lineCount")
            assertEquals(expectedLines.size, actual.lines.size, "变体 $name 的行数")
            for (i in expectedLines.indices) {
                assertEquals(expectedLines[i], actual.lines[i], "变体 $name 第 $i 行 G 代码")
            }
            emittedLines += expectedLines.size

            // 数值字段：容差 0（逐位相同）
            assertEquals(Golden.double(variant, "widthMm"), actual.widthMm, 0.0, "变体 $name 的 widthMm")
            assertEquals(Golden.double(variant, "heightMm"), actual.heightMm, 0.0, "变体 $name 的 heightMm")
            assertEquals(Golden.int(variant, "pathCount"), actual.pathCount, "变体 $name 的 pathCount")
            assertEquals(
                Golden.double(variant, "pathLengthMm"),
                actual.pathLengthMm,
                0.0,
                "变体 $name 的 pathLengthMm"
            )
            numericFields += 4
        }

        // 样本元数据自检（与生成器写下的 entryCounts 对齐）
        assertEquals(Golden.int(entryCounts, "emittedLines"), emittedLines, "entryCounts.emittedLines")
        assertEquals(
            Golden.int(entryCounts, "pathsFirstVariant"),
            Golden.int(variants[0], "pathCount"),
            "entryCounts.pathsFirstVariant"
        )

        println("[golden svg-to-gcode] 变体比对：$emittedLines 行 G 代码逐字 + $numericFields 个数值字段（容差 0）")
    }

    @Test
    fun parsersAndErrorPathsMatchOracle() {
        val suite = Golden.load("svg-to-gcode")
        val result = Golden.result("svg-to-gcode")
        val entryCounts = Golden.objectAt(suite, "entryCounts")

        // ---- parseLength：16 组 ----
        val lengths = Golden.objectsAt(result, "parseLength")
        assertEquals(Golden.int(entryCounts, "parseLengthCases"), lengths.size, "样本声明的 parseLength 组数")
        for (case in lengths) {
            val input = Golden.stringOrNull(case, "input")
            // 样本里 input 为 null 与 "" 在 TS 里都命中 `if (!str) return 0`
            val actual = parseLength(input)
            assertEquals(Golden.double(case, "output"), actual, 0.0, "parseLength(${jsonOf(input)})")
        }

        // ---- parseViewBox：7 组 ----
        val viewBoxes = Golden.objectsAt(result, "parseViewBox")
        assertEquals(Golden.int(entryCounts, "parseViewBoxCases"), viewBoxes.size, "样本声明的 parseViewBox 组数")
        var viewBoxValues = 0
        for (case in viewBoxes) {
            val input = Golden.stringOrNull(case, "input")
            val expected = case["output"]
            val actual = parseViewBox(input)
            if (expected == null || expected is JsonNull) {
                assertNull(actual, "parseViewBox(${jsonOf(input)}) 应为 null")
            } else {
                val numbers = expected.jsonArray.map { it.jsonPrimitive.content.toDouble() }
                val parsed = assertNotNull(actual, "parseViewBox(${jsonOf(input)}) 不应为 null")
                assertEquals(numbers.size, parsed.size, "parseViewBox(${jsonOf(input)}) 的元素个数")
                for (i in numbers.indices) {
                    assertEquals(numbers[i], parsed[i], 0.0, "parseViewBox(${jsonOf(input)}) 第 $i 个值")
                    viewBoxValues++
                }
            }
        }

        // ---- errorPaths：3 个畸形输入必须抛出同样的消息 ----
        val variants = Golden.objectsAt(result, "variants")
        val defaultOptions = optionsOf(Golden.objectAt(variants[0], "options"))
        val errorPaths = Golden.objectsAt(result, "errorPaths")
        assertEquals(Golden.int(entryCounts, "errorPaths"), errorPaths.size, "样本声明的 errorPaths 组数")
        for (case in errorPaths) {
            val name = Golden.string(case, "name")
            assertTrue(Golden.boolean(case, "threw"), "样本里 $name 应当抛异常")
            val expectedMessage = Golden.string(case, "errorMessage")

            val svgInput = BAD_INPUTS[name] ?: error("测试缺少畸形输入 $name 的定义")
            val thrown = runCatching { convertSvgToGcode(svgInput, defaultOptions) }.exceptionOrNull()
            val error = assertNotNull(thrown, "$name 应当抛异常，但调用成功了")
            assertEquals(expectedMessage, error.message, "$name 的异常消息")
        }

        println(
            "[golden svg-to-gcode] 解析器比对：${lengths.size} 组 parseLength + " +
                "${viewBoxes.size} 组 parseViewBox（$viewBoxValues 个值）+ ${errorPaths.size} 条错误路径"
        )
    }

    /** 与生成器 `badInputs` 逐字一致的畸形输入。 */
    private companion object {
        val BAD_INPUTS = mapOf(
            "malformed-unterminated-tag" to "<svg viewBox=\"0 0 10 10\"><rect</svg>",
            "non-svg-root" to "<html><body>hi</body></html>",
            "empty-string" to ""
        )
    }

    /** 变体的 `options` 节点 → [SvgConvertOptions]（缺省字段留给 Kotlin 侧的默认值）。 */
    private fun optionsOf(node: JsonObject): SvgConvertOptions = SvgConvertOptions(
        targetWidthMm = Golden.double(node, "targetWidthMm"),
        targetHeightMm = Golden.doubleOrNull(node, "targetHeightMm"),
        tolerance = Golden.doubleOrNull(node, "tolerance"),
        markSpeed = Golden.double(node, "markSpeed"),
        travelSpeed = Golden.doubleOrNull(node, "travelSpeed"),
        laserOn = Golden.string(node, "laserOn"),
        laserOff = Golden.string(node, "laserOff"),
        pwm = Golden.boolean(node, "pwm"),
        maxPower = Golden.doubleOrNull(node, "maxPower"),
        offsetX = Golden.doubleOrNull(node, "offsetX"),
        offsetY = Golden.doubleOrNull(node, "offsetY"),
        header = Golden.stringOrNull(node, "header"),
        footer = Golden.stringOrNull(node, "footer")
    )

    /** 只在失败消息里用：把可空输入渲染成可读文本。 */
    private fun jsonOf(value: String?): String = if (value == null) "null" else JsonPrimitive(value).toString()
}
