package com.lasergrbl.core.golden

import com.lasergrbl.core.raster.DITHERING_LABELS
import com.lasergrbl.core.raster.DITHERING_MODES
import com.lasergrbl.core.raster.DitheringMode
import com.lasergrbl.core.raster.ditherImage
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 抖动算法对照 v2 的黄金样本：9 种模式 × 32×32 渐变图。
 *
 * 除逐像素比对 `#`/`.` 行之外，还比原始 RGBA 的 sha256，能抓住"行看着一样但误差传播不同"的情况。
 */
class GoldenDitheringTest {

    @Test
    fun modesAndLabelsMatchOracle() {
        val result = Golden.result("dithering")
        val modes = result["ditheringModes"]?.jsonArray?.map { it.toString().trim('"') }
            ?: error("样本缺少 ditheringModes")
        assertEquals(modes, DITHERING_MODES.map { it.value })

        val labels = Golden.objectAt(result, "ditheringLabels")
        for (mode in DITHERING_MODES) {
            assertEquals(
                Golden.string(labels, mode.value),
                DITHERING_LABELS[mode],
                "标签不一致：${mode.value}"
            )
        }
    }

    @Test
    fun allModesMatchOraclePixelForPixel() {
        val result = Golden.result("dithering")
        val input = Golden.objectAt(result, "input")
        val width = Golden.int(input, "width")
        val height = Golden.int(input, "height")
        val template = GoldenBitmap.decode(input)

        val modes = result["modes"]?.jsonArray ?: error("样本缺少 modes")
        assertTrue(modes.isNotEmpty())

        for (element in modes) {
            val node = element.asObject()
            val modeValue = Golden.string(node, "mode")
            val mode = DitheringMode.fromValue(modeValue) ?: error("未知抖动模式 $modeValue")
            val nowOverride = Golden.doubleOrNull(node, "dateNowOverride")?.toLong() ?: 0L

            val data = template.copyOf()
            ditherImage(data, width, height, mode, nowMillis = nowOverride)

            val expectedRows = Golden.strings(node, "outputRows")
            val actualRows = GoldenBitmap.toRows(data, width, height)

            assertEquals(height, expectedRows.size)
            assertEquals(expectedRows, actualRows, "模式 $modeValue 的像素行不一致")

            val expectedWhite = Golden.int(node, "whitePixels")
            val actualWhite = actualRows.sumOf { row -> row.count { it == '#' } }
            assertEquals(expectedWhite, actualWhite, "模式 $modeValue 的白点数")

            assertEquals(
                Golden.string(node, "outputRgbaSha256"),
                GoldenBitmap.sha256Rgba(data),
                "模式 $modeValue 的 RGBA sha256"
            )

            // 输出必须是纯 0/255（生成器也断言了这一点）
            for (i in data.indices step 4) {
                val v = data[i]
                assertTrue(v == 0 || v == 255, "模式 $modeValue 出现了非二值像素：$v")
            }
        }
    }
}
