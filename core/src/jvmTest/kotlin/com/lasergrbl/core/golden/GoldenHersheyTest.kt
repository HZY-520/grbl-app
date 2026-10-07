package com.lasergrbl.core.golden

import com.lasergrbl.core.text.HERSHEY_HORIZONTAL
import com.lasergrbl.core.text.HERSHEY_HORIZONTAL_SHA256
import com.lasergrbl.core.text.HERSHEY_SPACE_BETWEEN
import com.lasergrbl.core.text.HERSHEY_VERTICAL
import com.lasergrbl.core.text.HERSHEY_VERTICAL_SHA256
import com.lasergrbl.core.text.HersheyOptions
import com.lasergrbl.core.text.HersheyOrientation
import com.lasergrbl.core.text.textToGcode
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hershey 单线字体对照 v2 的黄金样本（`golden/hershey.json`）。
 *
 * 判据分两层：
 *  1. 字体表本身：字形数 + `sha256(JSON.stringify(表))` —— 与夹具生成器
 *     `tools/golden/generate.ts` 的 `caseHershey()` 用同一套定义，
 *     因此能证明 2568 个片段逐字节一致（这里在 Kotlin 侧重建成同样的 JSON 文本再哈希）；
 *  2. 5 个变体的输出：**逐行**比对 G 代码、行数，以及包围盒两个 double。
 *
 * 包围盒用容差 `0.0`（即逐位相等）：夹具里的 `12.059999999999999` 这类值说明它存的是
 * JS 侧算出的那个 double 本身，而不是四舍五入过的结果 —— 只要加减乘除的顺序与 v2 一致，
 * IEEE 754 下 JVM 与 V8 的结果就应当逐位相同。实测通过。
 */
class GoldenHersheyTest {

    @Test
    fun fontTableMatchesOracle() {
        val fontTable = Golden.objectAt(Golden.result("hershey"), "fontTable")

        assertEquals(
            Golden.double(fontTable, "spaceBetween"),
            HERSHEY_SPACE_BETWEEN,
            0.0,
            "HERSHEY_SPACE_BETWEEN"
        )
        assertEquals(Golden.int(fontTable, "horizontalCount"), HERSHEY_HORIZONTAL.size, "横向字形数")
        assertEquals(Golden.int(fontTable, "verticalCount"), HERSHEY_VERTICAL.size, "纵向字形数")
        assertEquals(95, HERSHEY_HORIZONTAL.size, "横向字形数应为 ASCII 32..126")
        assertEquals(95, HERSHEY_VERTICAL.size, "纵向字形数应为 ASCII 32..126")

        // 生成器嵌进 Kotlin 的摘要（源自 tools/port/gen-hershey.mjs），先自检一次
        assertEquals(
            Golden.string(fontTable, "horizontalSha256"),
            HERSHEY_HORIZONTAL_SHA256,
            "横向表的 sha256 常量与夹具不一致（表被改过？）"
        )
        assertEquals(
            Golden.string(fontTable, "verticalSha256"),
            HERSHEY_VERTICAL_SHA256,
            "纵向表的 sha256 常量与夹具不一致（表被改过？）"
        )

        // 关键判据：在 Kotlin 侧重建成 JSON.stringify 的文本，再比对 sha256
        val horizontalJson = jsJsonStringify(HERSHEY_HORIZONTAL)
        val verticalJson = jsJsonStringify(HERSHEY_VERTICAL)
        assertEquals(
            Golden.string(fontTable, "horizontalSha256"),
            sha256Hex(horizontalJson),
            "sha256(JSON.stringify(HERSHEY_HORIZONTAL))"
        )
        assertEquals(
            Golden.string(fontTable, "verticalSha256"),
            sha256Hex(verticalJson),
            "sha256(JSON.stringify(HERSHEY_VERTICAL))"
        )

        // 夹具里的表是纯 ASCII，重建时不应该出现任何转义（否则上面的等价性要靠转义规则撑）
        assertEquals(
            0,
            jsonEscapeCount(horizontalJson) + jsonEscapeCount(verticalJson),
            "字体表里出现了需要转义的字符，JSON 重建不再逐字节等价"
        )
    }

    @Test
    fun variantsMatchOracle() {
        val variants = Golden.array("hershey", "variants")
        assertEquals(5, variants.size, "样本用例数量变了，说明夹具被重新生成过")

        var comparedLines = 0
        for (variant in variants) {
            val node = variant.asObject()
            val name = Golden.string(node, "name")
            val actual = textToGcode(optionsOf(Golden.objectAt(node, "options")))
            val expectedLines = Golden.strings(node, "lines")

            assertEquals(
                Golden.int(node, "lineCount"),
                actual.lines.size,
                "变体 $name 的 lineCount"
            )
            assertEquals(expectedLines.size, actual.lines.size, "变体 $name 的行数")
            expectedLines.forEachIndexed { index, line ->
                assertEquals(line, actual.lines[index], "变体 $name 第 $index 行")
            }
            comparedLines += expectedLines.size

            // 包围盒：容差 0.0（逐位相等）；夹具存的就是 JS 侧算出的 double 原值
            assertEquals(
                Golden.double(node, "widthMm"),
                actual.widthMm,
                0.0,
                "变体 $name 的 widthMm"
            )
            assertEquals(
                Golden.double(node, "heightMm"),
                actual.heightMm,
                0.0,
                "变体 $name 的 heightMm"
            )
        }

        // 与 manifest 里记录的 gcodeLines 总数交叉验证
        val entryCounts = Golden.objectAt(Golden.load("hershey"), "entryCounts")
        assertEquals(Golden.int(entryCounts, "gcodeLines"), comparedLines, "比对的 G 代码总行数")
        assertEquals(846, comparedLines, "样本记录的 G 代码总行数变了")
    }

    private fun optionsOf(node: JsonObject) = HersheyOptions(
        text = Golden.string(node, "text"),
        orientation = when (val orientation = Golden.string(node, "orientation")) {
            "horizontal" -> HersheyOrientation.HORIZONTAL
            "vertical" -> HersheyOrientation.VERTICAL
            else -> error("未知的 orientation: $orientation")
        },
        sizeMm = Golden.double(node, "sizeMm"),
        bold = Golden.boolean(node, "bold"),
        lineSpacing = Golden.doubleOrNull(node, "lineSpacing"),
        offsetX = Golden.doubleOrNull(node, "offsetX"),
        offsetY = Golden.doubleOrNull(node, "offsetY"),
        markSpeed = Golden.double(node, "markSpeed"),
        laserOn = Golden.string(node, "laserOn"),
        laserOff = Golden.string(node, "laserOff"),
        pwm = Golden.boolean(node, "pwm"),
        maxPower = Golden.doubleOrNull(node, "maxPower")
    )

    /** `sha256` 的十六进制小写形式（对 UTF-8 字节，与 Node 的 `createHash().update(str)` 一致）。 */
    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    /**
     * 等价于 JS `JSON.stringify(table)`（`string[][]`）：无空格、`"` 包裹每个元素。
     * 逐条复刻 JSON.stringify 的字符串转义规则（含控制字符与孤立代理项）。
     */
    private fun jsJsonStringify(table: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append('[')
        table.forEachIndexed { gi, glyph ->
            if (gi > 0) sb.append(',')
            sb.append('[')
            glyph.forEachIndexed { si, part ->
                if (si > 0) sb.append(',')
                appendJsonString(sb, part)
            }
            sb.append(']')
        }
        sb.append(']')
        return sb.toString()
    }

    private fun appendJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' || (ch in '\uD800'..'\uDFFF') -> sb.append("\\u").append(
                    ch.code.toString(16).padStart(4, '0')
                )

                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }

    /** 统计重建文本里的转义序列个数（`\"` / `\\` / `\uXXXX` 等）。 */
    private fun jsonEscapeCount(json: String): Int {
        var count = 0
        var i = 0
        while (i < json.length) {
            if (json[i] == '\\') {
                count++
                i++
            }
            i++
        }
        return count
    }
}
