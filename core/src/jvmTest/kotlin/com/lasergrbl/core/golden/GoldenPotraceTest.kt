package com.lasergrbl.core.golden

import com.lasergrbl.core.vector.Polyline
import com.lasergrbl.core.vector.PotraceImage
import com.lasergrbl.core.vector.PotraceOptions
import com.lasergrbl.core.vector.Pt
import com.lasergrbl.core.vector.potraceTrace
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `potraceTrace` 对照 v2 的黄金样本 `golden/potrace.json`。
 *
 * 样本由 `tools/golden/generate.ts` 用**真实的 TypeScript 实现**跑一张合成 64×64 RGBA 位图
 * （白底 + 实心黑圆 + 黑矩形 + 黑色圆环）生成，共 4 组选项：
 * `default` / `tuned` / `inverted` / `coarse-flatten`。
 *
 * 比对内容：折线数量、总点数、每条折线点数、闭合标记，以及**每一个点的 x/y 坐标**。
 * 坐标用 `assertEquals(expected, actual, 0.0)` —— 必须逐位相同（JSON 里存的是 double 的
 * 最短可往返十进制表示，样本就是 TypeScript 的浮点结果本身，没有再做舍入）。
 */
class GoldenPotraceTest {

    /** 黄金样本 `image` 节点的最小实现：`data` 是 RGBA 缓冲，每元素 0..255。 */
    private class RawImage(
        override val data: IntArray,
        override val width: Int,
        override val height: Int
    ) : PotraceImage

    @Test
    fun matchesOracle() {
        val result = Golden.result("potrace")
        val imageNode = Golden.objectAt(result, "image")
        val image = RawImage(
            data = GoldenBitmap.decode(imageNode),
            width = Golden.int(imageNode, "width"),
            height = Golden.int(imageNode, "height")
        )

        val variants = Golden.objectsAt(result, "variants")
        assertEquals(4, variants.size, "样本变体数量")

        var totalPolylines = 0
        var totalPoints = 0

        for (variant in variants) {
            val name = Golden.string(variant, "name")
            val options = optionsOf(variant)
            val actual = potraceTrace(image, options)
            val expected = polylinesOf(variant)

            // 1) 汇总指标
            assertEquals(Golden.int(variant, "polylineCount"), actual.size, "变体 $name 的折线数量")
            assertEquals(
                Golden.int(variant, "totalPoints"),
                actual.sumOf { it.pts.size },
                "变体 $name 的总点数"
            )
            assertEquals(
                intList(variant, "pointsPerPolyline"),
                actual.map { it.pts.size },
                "变体 $name 的每条折线点数"
            )
            assertEquals(
                boolList(variant, "closedFlags"),
                actual.map { it.closed == true },
                "变体 $name 的闭合标记"
            )
            assertEquals(
                expected.size,
                actual.size,
                "变体 $name 的 polylines.length 与 polylineCount 不一致"
            )

            // 2) 逐点坐标（逐位一致）
            for (pi in expected.indices) {
                val exp = expected[pi]
                val act = actual[pi]
                assertEquals(exp.pts.size, act.pts.size, "变体 $name 第 $pi 条折线的点数")
                assertEquals(exp.closed, act.closed, "变体 $name 第 $pi 条折线的 closed")
                for (k in exp.pts.indices) {
                    assertSameDouble(
                        exp.pts[k].x,
                        act.pts[k].x,
                        "变体 $name 第 $pi 条折线第 $k 个点的 x 不一致"
                    )
                    assertSameDouble(
                        exp.pts[k].y,
                        act.pts[k].y,
                        "变体 $name 第 $pi 条折线第 $k 个点的 y 不一致"
                    )
                }
            }

            totalPolylines += actual.size
            totalPoints += actual.sumOf { it.pts.size }
        }

        // 样本元数据自检：case 声明的 entryCounts
        assertEquals(17, totalPolylines, "样本声明的折线总数")
        assertEquals(334, totalPoints, "样本声明的点总数")
    }

    /**
     * 坐标必须与 TypeScript 的浮点结果**逐位相同**。
     *
     * 用 `assertEquals(expected, actual, 0.0)` 比对（容差 0）；额外补一刀 `isFinite`：
     * kotlin.test 的容差版对 `NaN` 是直接放行的（`abs(x - NaN) > 0.0` 为 false），
     * 若不补这一刀，实现吐出 NaN 也会「通过」。
     */
    private fun assertSameDouble(expected: Double, actual: Double, message: String) {
        assertTrue(actual.isFinite(), "$message（实际值不是有限数：$actual）")
        assertEquals(expected, actual, 0.0, message)
    }

    /** 变体的 `options` 节点 → [PotraceOptions]；缺省字段用 Kotlin 侧的默认值。 */
    private fun optionsOf(variant: JsonObject): PotraceOptions {
        val node = Golden.objectAt(variant, "options")
        val defaults = PotraceOptions()
        return PotraceOptions(
            threshold = Golden.doubleOrNull(node, "threshold") ?: defaults.threshold,
            turdSize = Golden.doubleOrNull(node, "turdSize") ?: defaults.turdSize,
            alphaMax = Golden.doubleOrNull(node, "alphaMax") ?: defaults.alphaMax,
            optTolerance = Golden.doubleOrNull(node, "optTolerance") ?: defaults.optTolerance,
            curveOptimizing = boolOrNull(node, "curveOptimizing") ?: defaults.curveOptimizing,
            invert = boolOrNull(node, "invert") ?: defaults.invert,
            flattenTolerance = Golden.doubleOrNull(node, "flattenTolerance") ?: defaults.flattenTolerance
        )
    }

    /** 变体的 `polylines` 节点（`[{pts: [[x,y],...], closed: bool}, ...]`）。 */
    private fun polylinesOf(variant: JsonObject): List<Polyline> {
        val array = variant["polylines"]?.jsonArray ?: error("变体缺少 polylines")
        return array.map { element ->
            val node = element.asObject()
            val pts = (node["pts"]?.jsonArray ?: error("折线缺少 pts")).map { pt ->
                val xy = pt.jsonArray
                Pt(
                    xy[0].jsonPrimitive.content.toDouble(),
                    xy[1].jsonPrimitive.content.toDouble()
                )
            }
            Polyline(pts, node["closed"]?.jsonPrimitive?.content?.toBoolean() ?: false)
        }
    }

    private fun intList(node: JsonObject, key: String): List<Int> =
        (node[key]?.jsonArray ?: error("节点缺少数组字段 $key")).map { it.jsonPrimitive.content.toInt() }

    private fun boolList(node: JsonObject, key: String): List<Boolean> =
        (node[key]?.jsonArray ?: error("节点缺少数组字段 $key")).map { it.jsonPrimitive.content.toBoolean() }

    private fun boolOrNull(node: JsonObject, key: String): Boolean? {
        val element = node[key] ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.content.toBooleanStrictOrNull()
    }
}
