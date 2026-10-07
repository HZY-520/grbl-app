package com.lasergrbl.core.golden

import com.lasergrbl.core.raster.DIRECTION_LABELS
import com.lasergrbl.core.raster.DitheringMode
import com.lasergrbl.core.raster.Formula
import com.lasergrbl.core.raster.Interpolation
import com.lasergrbl.core.raster.RasterOptions
import com.lasergrbl.core.raster.RasterTool
import com.lasergrbl.core.raster.RasterDirection
import com.lasergrbl.core.raster.ResizeSampler
import com.lasergrbl.core.raster.convertImageToGcode
import com.lasergrbl.core.vector.PotraceImage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 光栅管线（`RasterConverter` + `ImageTransform` 预处理）对照 v2 的黄金样本。
 *
 * 夹具 `raster-converter.json` 的特殊之处：**它是 v2 真实代码跑出来的**。
 * 生成器只替换了 `document.createElement('canvas')` 这一层（一个确定性最近邻的画布 shim），
 * 之后的 `testGrayScale → grayScale → whitenize → dither/threshold → flipVertical →
 * getSegments → segToGCodeNumber → optimizeLine2Line → 头尾拼装` 全部是 v2 的原样执行。
 * 所以本测试是整条光栅管线的逐行判据，而不是"照着 TS 重写一遍再自证"。
 *
 * 重采样内核：夹具里记录的是**确定性最近邻**（定义见 `result.resample.definition`），
 * 不是浏览器的平滑滤波 —— [GoldenNearestResampler] 与它逐条对齐。
 * 真实浏览器内核的比对是另一件事，登记在 `docs/PHASE3-PLATFORM-MODULES.md` §3.1。
 */
class GoldenRasterConverterTest {

    /**
     * 与夹具 `result.resample.definition` 逐条对齐的最近邻重采样：
     * `sx = floor((dx + 0.5) * sw / dw)`、`sy = floor((dy + 0.5) * sh / dh)`，
     * 夹到源矩形内，RGBA 原样拷贝（不改 alpha、不与白底合成）。
     */
    private object GoldenNearestResampler : ResizeSampler {
        override fun sample(
            source: PotraceImage,
            sizeW: Int,
            sizeH: Int,
            interpolation: Interpolation,
            fillWhite: Boolean
        ): IntArray {
            val w = maxOf(1, sizeW)
            val h = maxOf(1, sizeH)
            val sw = source.width
            val sh = source.height
            val src = source.data
            val out = IntArray(w * h * 4)
            if (sw <= 0 || sh <= 0 || src.size < sw * sh * 4) return out
            for (y in 0 until h) {
                val sy = minOf(sh - 1, ((y + 0.5) * sh / h).toInt())
                for (x in 0 until w) {
                    val sx = minOf(sw - 1, ((x + 0.5) * sw / w).toInt())
                    val si = (sy * sw + sx) * 4
                    val di = (y * w + x) * 4
                    out[di] = src[si]
                    out[di + 1] = src[si + 1]
                    out[di + 2] = src[si + 2]
                    out[di + 3] = src[si + 3]
                }
            }
            return out
        }
    }

    private fun sha256(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 从夹具的 `options` 节点构造 [RasterOptions]（字段名与 v2 一一对应）。 */
    private fun optionsOf(node: JsonObject): RasterOptions {
        fun double(name: String, fallback: Double): Double =
            node[name]?.jsonPrimitive?.content?.toDoubleOrNull() ?: fallback

        fun int(name: String, fallback: Int): Int = double(name, fallback.toDouble()).toInt()

        fun bool(name: String, fallback: Boolean): Boolean =
            node[name]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: fallback

        fun str(name: String, fallback: String): String = node[name]?.jsonPrimitive?.content ?: fallback

        return RasterOptions(
            tool = RasterTool.fromValue(str("tool", "Line2Line")) ?: RasterTool.Line2Line,
            direction = RasterDirection.fromValue(str("direction", "Horizontal")) ?: RasterDirection.Horizontal,
            quality = double("quality", 3.0),
            pwm = bool("pwm", true),
            markSpeed = double("markSpeed", 1000.0),
            minPower = int("minPower", 0),
            maxPower = int("maxPower", 1000),
            laserOn = str("laserOn", "M3"),
            laserOff = str("laserOff", "M5"),
            offsetX = double("offsetX", 0.0),
            offsetY = double("offsetY", 0.0),
            formula = Formula.fromValue(int("formula", 0)) ?: Formula.SimpleAverage,
            red = double("red", 100.0),
            green = double("green", 100.0),
            blue = double("blue", 100.0),
            brightness = double("brightness", 100.0),
            contrast = double("contrast", 100.0),
            whiteClip = double("whiteClip", 5.0),
            useThreshold = bool("useThreshold", false),
            threshold = double("threshold", 50.0),
            dithering = DitheringMode.fromValue(str("dithering", "FloydSteinberg")) ?: DitheringMode.FloydSteinberg,
            interpolation = Interpolation.fromValue(str("interpolation", "high")) ?: Interpolation.High,
            unidirectional = bool("unidirectional", false),
            disableFastSkip = bool("disableFastSkip", false),
            header = str("header", "G90"),
            footer = str("footer", "M5\nG0 X0 Y0")
        )
    }

    @Test
    fun directionLabelsMatchOracle() {
        val labels = Golden.objectAt(Golden.result("raster-converter"), "directionLabels")
        for (dir in RasterDirection.entries) {
            assertEquals(Golden.string(labels, dir.value), DIRECTION_LABELS[dir], "方向标签不一致：${dir.value}")
        }
    }

    @Test
    fun defaultOptionsMatchOracle() {
        val defaults = Golden.objectAt(Golden.result("raster-converter"), "defaultOptions")
        val expected = RasterOptions()
        assertEquals(Golden.string(defaults, "tool"), expected.tool.value)
        assertEquals(Golden.string(defaults, "direction"), expected.direction.value)
        assertEquals(Golden.doubleOrNull(defaults, "quality"), expected.quality)
        assertEquals(Golden.doubleOrNull(defaults, "markSpeed"), expected.markSpeed)
        assertEquals(Golden.int(defaults, "minPower"), expected.minPower)
        assertEquals(Golden.int(defaults, "maxPower"), expected.maxPower)
        assertEquals(Golden.string(defaults, "laserOn"), expected.laserOn)
        assertEquals(Golden.string(defaults, "laserOff"), expected.laserOff)
        assertEquals(Golden.int(defaults, "formula"), expected.formula.value)
        assertEquals(Golden.doubleOrNull(defaults, "red"), expected.red)
        assertEquals(Golden.doubleOrNull(defaults, "green"), expected.green)
        assertEquals(Golden.doubleOrNull(defaults, "blue"), expected.blue)
        assertEquals(Golden.doubleOrNull(defaults, "brightness"), expected.brightness)
        assertEquals(Golden.doubleOrNull(defaults, "contrast"), expected.contrast)
        assertEquals(Golden.doubleOrNull(defaults, "whiteClip"), expected.whiteClip)
        assertEquals(Golden.doubleOrNull(defaults, "threshold"), expected.threshold)
        assertEquals(Golden.string(defaults, "dithering"), expected.dithering.value)
        assertEquals(Golden.string(defaults, "interpolation"), expected.interpolation.value)
        assertEquals(Golden.string(defaults, "header"), expected.header)
        assertEquals(Golden.string(defaults, "footer"), expected.footer)
    }

    @Test
    fun everyVariantMatchesOracleLineForLine() {
        val result = Golden.result("raster-converter")
        val variants = result["variants"]?.jsonArray ?: error("样本缺少 variants")
        assertTrue(variants.size == 5, "样本变体数量变了（预期 5）：${variants.size}")

        for (element in variants) {
            val node = element.asObject()
            val name = Golden.string(node, "name")
            val optionsNode = Golden.objectAt(node, "options")
            val target = Golden.objectAt(node, "targetMm")
            val widthMm = Golden.doubleOrNull(target, "widthMm") ?: 0.0
            val heightMm = Golden.doubleOrNull(target, "heightMm") ?: 0.0

            // 夹具记录了"重采样后的源位图"（= v2 里 resizeImage 的输出），
            // 我们把它当作**输入**，让 :core 从预处理开始跑完整条管线。
            val resampled = Golden.objectAt(node, "resampled")
            val srcWidth = Golden.int(resampled, "width")
            val srcHeight = Golden.int(resampled, "height")
            val srcData = GoldenBitmap.decode(resampled)
            val source = object : PotraceImage {
                override val data: IntArray = srcData
                override val width: Int = srcWidth
                override val height: Int = srcHeight
            }

            val actual = convertImageToGcode(source, widthMm, heightMm, optionsOf(optionsNode), GoldenNearestResampler)

            assertEquals(Golden.int(node, "pixelWidth"), actual.pixelWidth, "$name: pixelWidth")
            assertEquals(Golden.int(node, "pixelHeight"), actual.pixelHeight, "$name: pixelHeight")
            assertEquals(Golden.doubleOrNull(node, "res"), actual.res, "$name: res")

            // 逐行（用整份输出拼接后的 sha256 钉住，另比首/尾各 12 行做定位）
            assertEquals(Golden.int(node, "lineCount"), actual.lines.size, "$name: 行数")
            assertEquals(
                Golden.string(node, "linesSha256"),
                sha256(actual.lines.joinToString("\n")),
                "$name: lines 的 sha256（拼接方式 = join(\"\\n\")）"
            )
            assertEquals(Golden.strings(node, "firstLines"), actual.lines.take(12), "$name: 前 12 行")
            assertEquals(Golden.strings(node, "lastLines"), actual.lines.takeLast(12), "$name: 后 12 行")

            // 预览图逐像素 + sha256（这是预处理链的判据）
            val previewNode = Golden.objectAt(node, "preview")
            assertEquals(Golden.int(previewNode, "width"), actual.pixelWidth, "$name: 预览宽")
            assertEquals(Golden.int(previewNode, "height"), actual.pixelHeight, "$name: 预览高")
            val expectedPreview = GoldenBitmap.decode(previewNode)
            assertEquals(
                expectedPreview.size,
                actual.preview.size,
                "$name: 预览数组长度（期望 ${expectedPreview.size}，实际 ${actual.preview.size}）"
            )
            var mismatch = -1
            for (i in expectedPreview.indices) {
                if (expectedPreview[i] != actual.preview[i]) {
                    mismatch = i
                    break
                }
            }
            assertEquals(
                -1,
                mismatch,
                if (mismatch < 0) "" else {
                    val px = mismatch / 4
                    "$name: 预览第 $mismatch 个元素不一致（像素 ${px % actual.pixelWidth},${px / actual.pixelWidth}）"
                }
            )
            assertEquals(GoldenBitmap.sha256Rgba(expectedPreview), GoldenBitmap.sha256Rgba(actual.preview), "$name: 预览 sha256")
        }
    }
}
