package com.lasergrbl.core.golden

import com.lasergrbl.core.vector.CenterlineOptions
import com.lasergrbl.core.vector.Polyline
import com.lasergrbl.core.vector.PotraceImage
import com.lasergrbl.core.vector.Pt
import com.lasergrbl.core.vector.centerlineTrace
import com.lasergrbl.core.vector.skeletonize
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `centerlineTrace` / `skeletonize` 对照 v2 的黄金样本 `golden/centerline.json`。
 *
 * 样本由 `tools/golden/generate.ts` 用**真实的 TypeScript 实现**跑一张合成 40×40 RGBA 位图
 * （白底 + 3px 十字 + 3px 斜线 + 1px 毛刺）生成，共 2 组走线选项
 * （`default` / `closed-minBranch2-tol0.5`）与 2 组骨架选项（`default` / `maxIterations 2`）。
 *
 * 比对内容：
 *   * 变体：折线数量、总点数、每条折线的点数、闭合标记，以及**每一个点的 x/y 坐标**；
 *   * 骨架：补边后的宽高、墨水面积、收敛标志、骨架像素数、**逐行骨架位图**（42×42 的 `0`/`1`），
 *     以及骨架字节的 sha256。
 *
 * 坐标与所有浮点字段都用 `assertEquals(expected, actual, 0.0)` —— 必须逐位相同
 * （JSON 里存的是 double 的最短可往返十进制表示，样本就是 TypeScript 的浮点结果本身）。
 */
class GoldenCenterlineTest {

    /** 黄金样本 `image` 节点的最小实现：`data` 是 RGBA 缓冲，每元素 0..255。 */
    private class RawImage(
        override val data: IntArray,
        override val width: Int,
        override val height: Int
    ) : PotraceImage

    @Test
    fun matchesOracle() {
        val result = Golden.result("centerline")
        val imageNode = Golden.objectAt(result, "image")
        val image = RawImage(
            data = GoldenBitmap.decode(imageNode),
            width = Golden.int(imageNode, "width"),
            height = Golden.int(imageNode, "height")
        )
        // 位图解码自检：展开后的 RGBA 与生成器记录的 sha256 一致
        assertEquals(
            Golden.string(imageNode, "sha256"),
            GoldenBitmap.sha256Rgba(image.data),
            "样本位图解码结果与样本记录的 sha256 不一致"
        )

        val entryCounts = Golden.objectAt(Golden.load("centerline"), "entryCounts")
        var polylineSum = 0
        var pointSum = 0

        // ---- 1) centerlineTrace ----
        val variants = Golden.objectsAt(result, "variants")
        assertEquals(2, variants.size, "样本变体数量")
        for (variant in variants) {
            val name = Golden.string(variant, "name")
            val options = optionsOf(Golden.objectAt(variant, "options"))
            val actual = centerlineTrace(image, options)
            val expected = polylinesOf(variant)

            assertEquals(Golden.int(variant, "polylineCount"), actual.size, "变体 $name 的折线数量")
            assertEquals(
                Golden.int(variant, "totalPoints"),
                actual.sumOf { it.pts.size },
                "变体 $name 的总点数"
            )
            assertEquals(
                boolList(variant, "closedFlags"),
                actual.map { it.closed == true },
                "变体 $name 的闭合标记"
            )
            assertEquals(expected.size, actual.size, "变体 $name 的 polylines.length 与 polylineCount 不一致")

            for (pi in expected.indices) {
                val exp = expected[pi]
                val act = actual[pi]
                assertEquals(exp.pts.size, act.pts.size, "变体 $name 第 $pi 条折线的点数")
                assertEquals(exp.closed, act.closed, "变体 $name 第 $pi 条折线的 closed")
                for (k in exp.pts.indices) {
                    assertSameDouble(exp.pts[k].x, act.pts[k].x, "变体 $name 第 $pi 条折线第 $k 个点的 x")
                    assertSameDouble(exp.pts[k].y, act.pts[k].y, "变体 $name 第 $pi 条折线第 $k 个点的 y")
                }
            }

            polylineSum += actual.size
            pointSum += actual.sumOf { it.pts.size }
        }

        // 样本元数据自检：case 声明的 entryCounts（顺序与 variants 一致）
        val defaultVariant = variants[0]
        val closedVariant = variants[1]
        assertEquals(Golden.int(entryCounts, "defaultPolylines"), polylineCountOf(defaultVariant), "entryCounts.defaultPolylines")
        assertEquals(Golden.int(entryCounts, "closedPolylines"), polylineCountOf(closedVariant), "entryCounts.closedPolylines")
        assertEquals(Golden.int(entryCounts, "defaultPoints"), pointCountOf(defaultVariant), "entryCounts.defaultPoints")
        assertEquals(Golden.int(entryCounts, "closedPoints"), pointCountOf(closedVariant), "entryCounts.closedPoints")
        assertEquals(Golden.int(entryCounts, "defaultPolylines") + Golden.int(entryCounts, "closedPolylines"), polylineSum)
        assertEquals(Golden.int(entryCounts, "defaultPoints") + Golden.int(entryCounts, "closedPoints"), pointSum)
        assertEquals(Golden.int(Golden.load("centerline"), "entryCount"), polylineSum, "样本声明的 entryCount")

        // ---- 2) skeletonize ----
        val skeletons = Golden.objectsAt(result, "skeletons")
        assertEquals(2, skeletons.size, "骨架样本数量")
        for (skeleton in skeletons) {
            val name = Golden.string(skeleton, "name")
            val options = optionsOf(Golden.objectAt(skeleton, "options"))
            val actual = skeletonize(image, options)

            val width = Golden.int(skeleton, "width")
            val height = Golden.int(skeleton, "height")
            assertEquals(width, actual.width, "骨架 $name 的宽度")
            assertEquals(height, actual.height, "骨架 $name 的高度")
            assertEquals(Golden.int(skeleton, "inkArea"), actual.inkArea, "骨架 $name 的墨水面积")
            assertEquals(Golden.boolean(skeleton, "converged"), actual.converged, "骨架 $name 的收敛标志")
            assertEquals(
                Golden.int(skeleton, "skeletonPixels"),
                actual.data.count { it != 0 },
                "骨架 $name 的骨架像素数"
            )

            // 逐行比对骨架位图（42 行 × 42 列）
            val expectedRows = Golden.strings(skeleton, "rows")
            assertEquals(height, expectedRows.size, "骨架 $name 的行数")
            val actualRows = binaryRows(actual.data, width, height)
            for (y in expectedRows.indices) {
                assertEquals(expectedRows[y], actualRows[y], "骨架 $name 第 $y 行不一致")
            }

            // 骨架字节的 sha256（与生成器的 rawSha256 对齐，逐字节钉死）
            assertEquals(
                Golden.string(skeleton, "rawSha256"),
                GoldenBitmap.sha256Rgba(actual.data),
                "骨架 $name 的 rawSha256 不一致"
            )
        }
    }

    /** 坐标必须与 TypeScript 的浮点结果**逐位相同**（容差 0，且不允许 NaN）。 */
    private fun assertSameDouble(expected: Double, actual: Double, message: String) {
        assertTrue(actual.isFinite(), "$message（实际值不是有限数：$actual）")
        assertEquals(expected, actual, 0.0, message)
    }

    /** 变体的 `options` 节点 → [CenterlineOptions]；缺省字段用 Kotlin 侧的默认值。 */
    private fun optionsOf(node: JsonObject): CenterlineOptions {
        val defaults = CenterlineOptions()
        return CenterlineOptions(
            threshold = Golden.doubleOrNull(node, "threshold") ?: defaults.threshold,
            invert = Golden.booleanOrNull(node, "invert") ?: defaults.invert,
            closed = Golden.booleanOrNull(node, "closed") ?: defaults.closed,
            minBranchPx = Golden.doubleOrNull(node, "minBranchPx") ?: defaults.minBranchPx,
            simplifyTolerance = Golden.doubleOrNull(node, "simplifyTolerance") ?: defaults.simplifyTolerance,
            maxIterations = Golden.doubleOrNull(node, "maxIterations")
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

    private fun polylineCountOf(variant: JsonObject): Int = Golden.int(variant, "polylineCount")

    private fun pointCountOf(variant: JsonObject): Int = Golden.int(variant, "totalPoints")

    /** 骨架位图 → `0`/`1` 行（与生成器的 `binaryRows` 同构：1 = 骨架）。 */
    private fun binaryRows(data: IntArray, w: Int, h: Int): List<String> {
        val rows = ArrayList<String>(h)
        for (y in 0 until h) {
            val sb = StringBuilder(w)
            for (x in 0 until w) sb.append(if (data[y * w + x] != 0) '1' else '0')
            rows.add(sb.toString())
        }
        return rows
    }

    private fun boolList(node: JsonObject, key: String): List<Boolean> =
        (node[key]?.jsonArray ?: error("节点缺少数组字段 $key")).map { it.jsonPrimitive.content.toBoolean() }
}
