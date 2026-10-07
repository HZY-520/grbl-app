package com.lasergrbl.core.golden

import com.lasergrbl.core.grbl.Element
import com.lasergrbl.core.grbl.formatDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `formatDecimal` / `Element` 对照 v2 的黄金样本。
 *
 * 样本里包含 24 个数字边界（`-0`、`1e-7`、`1e21`、`5e-324`、`MAX_VALUE`、`NaN`、`±Infinity` 等）——
 * 这正是 JS `String(v)` 与 Kotlin `Double.toString()` 分歧最大的地方。
 */
class GoldenFormatDecimalTest {

    @Test
    fun formatDecimalMatchesOracle() {
        val cases = Golden.array("format-decimal", "cases")
        assertEquals(24, cases.size, "样本用例数量变了，说明夹具被重新生成过")
        for (case in cases) {
            val node = case.asObject()
            val input = Golden.string(node, "input")
            val expected = Golden.string(node, "output")
            assertEquals(expected, formatDecimal(input.toDouble()), "formatDecimal($input)")
        }
    }

    @Test
    fun elementParseToStringMatchesOracle() {
        val cases = Golden.array("format-decimal", "elementToString")
        assertEquals(7, cases.size)
        for (case in cases) {
            val node = case.asObject()
            val input = Golden.string(node, "input")
            val expected = Golden.string(node, "elementToString")
            assertEquals(expected, Element.parse(input).toString(), "Element.parse(\"$input\").toString()")
        }
    }

    @Test
    fun elementEqualityFollowsV2() {
        val a = Element.parse("X10.5")
        val b = Element.parse("X10.5")
        val c = Element.parse("X10.4")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(false, a == c)
        // NaN 不等于自身（与 v2 的 `o.number === this.number` 一致）
        val nan = Element.parse("Y")
        assertEquals(false, nan.number == nan.number)
    }
}
