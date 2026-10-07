package com.lasergrbl.core.golden

import com.lasergrbl.core.vector.Polyline
import com.lasergrbl.core.vector.PolylineGcodeOptions
import com.lasergrbl.core.vector.Pt
import com.lasergrbl.core.vector.polylinesToGcode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `polylinesToGcode` 对照 v2 的黄金样本。
 *
 * 样本里的 `polylinesIn` 是 **potrace 默认输出**（正方形/矩形/圆环的 4 条折线），
 * 因此它与各变体的 `potraceInput` 结果一一对应，可以逐行比对。
 *
 * ⚠️ 样本另有 `handAuthoredInput`（5 条手工折线：重复端点的闭合方框、亚阈值重复点、
 * 单点、开放三点、空路径），但**样本没有保存那 5 条折线的坐标**，只存了输出。
 * 因此这里只比对 potraceInput；手工输入那条留待需要时重新生成夹具再补。
 */
class GoldenPolylineGcodeTest {

    @Test
    fun potraceInputMatchesOracle() {
        val result = Golden.result("polylines-to-gcode")
        val polylines = result["polylinesIn"]?.jsonArray?.map { element ->
            val node = element.asObject()
            val pts = node["pts"]?.jsonArray?.map { pt ->
                val xy = pt.jsonArray
                Pt(xy[0].jsonPrimitive.content.toDouble(), xy[1].jsonPrimitive.content.toDouble())
            } ?: error("折线缺少 pts")
            Polyline(pts, Golden.boolean(node, "closed"))
        } ?: error("样本缺少 polylinesIn")

        assertEquals(4, polylines.size)

        val variants = result["variants"]?.jsonArray ?: error("样本缺少 variants")
        assertEquals(2, variants.size)

        for (variant in variants) {
            val node = variant.asObject()
            val name = Golden.string(node, "name")
            val options = optionsOf(node["options"]!!.asObject())
            val expected = Golden.objectAt(node, "potraceInput")

            val actual = polylinesToGcode(polylines, options)
            val expectedLines = Golden.strings(expected, "lines")

            assertEquals(Golden.int(expected, "pathCount"), actual.pathCount, "变体 $name 的 pathCount")
            assertEquals(Golden.double(expected, "lengthMm"), actual.lengthMm, 1e-9, "变体 $name 的 lengthMm")
            assertEquals(expectedLines.size, actual.lines.size, "变体 $name 的行数")
            expectedLines.forEachIndexed { index, line ->
                assertEquals(line, actual.lines[index], "变体 $name 第 $index 行")
            }
        }
    }

    private fun optionsOf(node: kotlinx.serialization.json.JsonObject) = PolylineGcodeOptions(
        pixelSizeMm = Golden.double(node, "pixelSizeMm"),
        offsetX = Golden.double(node, "offsetX"),
        offsetY = Golden.double(node, "offsetY"),
        markSpeed = Golden.double(node, "markSpeed"),
        travelSpeed = Golden.doubleOrNull(node, "travelSpeed"),
        minPower = Golden.int(node, "minPower"),
        maxPower = Golden.int(node, "maxPower"),
        laserPower = Golden.intOrNull(node, "laserPower"),
        laserOn = Golden.string(node, "laserOn"),
        laserOff = Golden.string(node, "laserOff"),
        pwm = Golden.boolean(node, "pwm"),
        flipY = node["flipY"]?.jsonPrimitive?.content?.toBoolean() ?: false,
        header = Golden.stringOrNull(node, "header"),
        footer = Golden.stringOrNull(node, "footer"),
        decimals = Golden.intOrNull(node, "decimals"),
        optimize = node["optimize"]?.jsonPrimitive?.content?.toBoolean(),
        travelCommand = Golden.stringOrNull(node, "travelCommand")
    )
}
