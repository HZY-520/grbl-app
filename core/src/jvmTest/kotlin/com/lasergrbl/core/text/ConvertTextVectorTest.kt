package com.lasergrbl.core.text

import com.lasergrbl.core.vector.DEFAULT_IMAGE_VECTOR_OPTIONS
import com.lasergrbl.core.vector.PotraceImage
import com.lasergrbl.core.vector.TextVectorOptions
import com.lasergrbl.core.vector.VectorTool
import com.lasergrbl.core.vector.convertImageVector
import com.lasergrbl.core.vector.convertTextVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `convertTextVector` 的**编排**测试（假渲染器，无需真实字体引擎）。
 *
 * 为什么没有黄金样本：v2 的 `renderTextToImage` 依赖浏览器 Canvas 的字体栈
 * （PingFang SC / Noto Sans CJK SC / …），`measureText` 的墨迹包围盒与 Android 平台
 * 必然不同，**文字转雕刻不可能逐像素对齐 v2**（见 `text/TextRaster.kt` 的说明）。
 * 因此这里用 [TextRasterizer] 的假实现钉住 v2 `ImageVector.ts:395-432` 里**纯编排**的部分：
 *
 *  1. 渲染选项逐个透传（text / sizeMm / bold / orientation / lineSpacing / fontFamily / renderSizePx）；
 *  2. 转换选项以 `DEFAULT_IMAGE_VECTOR_OPTIONS` 为底、被 [TextVectorOptions] 覆盖，
 *     且 `widthMm` / `heightMm` **取自渲染结果**（不是选项里的值）——
 *     断言「输出 == 直接用同一张图 + 同一批选项调 `convertImageVector`」；
 *  3. `widthMm <= 0 || heightMm <= 0` 的退化路径返回空结果，且**没有**走到
 *     `convertImageVector`（用「header 一行都不输出」+ 同一张图在正常尺寸下有输出做反证）。
 */
class ConvertTextVectorTest {

    /** 最小位图实现：`data` 是 RGBA 缓冲，每元素 0..255。 */
    private class RgbaImage(
        override val data: IntArray,
        override val width: Int,
        override val height: Int
    ) : PotraceImage

    /**
     * 假渲染器：记录调用次数与收到的选项，返回预先给定的图片与尺寸。
     *
     * `sizeMm` / `pxPerMm` 在本测试里只是"平台侧算出来的结果"，编排层不该再动它们。
     */
    private class FakeRasterizer(
        private val image: PotraceImage,
        private val pxPerMm: Double,
        private val widthMm: Double,
        private val heightMm: Double
    ) : TextRasterizer {
        var calls = 0
            private set
        var lastOptions: TextRenderOptions? = null
            private set

        override fun render(options: TextRenderOptions): TextRenderResult {
            calls++
            lastOptions = options
            return TextRenderResult(image = image, pxPerMm = pxPerMm, widthMm = widthMm, heightMm = heightMm)
        }
    }

    /** 16×16 白底 + 2px 粗黑十字（Potrace 能出轮廓，骨架化也能出结果）。 */
    private fun crossImage(): RgbaImage {
        val n = 16
        val data = IntArray(n * n * 4) { 255 }
        for (y in 0 until n) {
            for (x in 0 until n) {
                val ink = (x in 7..8 && y in 2..13) || (y in 7..8 && x in 2..13)
                if (ink) {
                    val i = (y * n + x) * 4
                    data[i] = 0
                    data[i + 1] = 0
                    data[i + 2] = 0
                }
            }
        }
        return RgbaImage(data, n, n)
    }

    @Test
    fun rendersThenConvertsAndForwardsEveryOption() {
        val image = crossImage()
        val rasterizer = FakeRasterizer(image, pxPerMm = 2.0, widthMm = 8.0, heightMm = 6.0)
        val options = TextVectorOptions(
            text = "iGRBL 中文",
            sizeMm = 4.0,
            bold = true,
            orientation = TextOrientation.Vertical,
            lineSpacing = 1.2,
            fontFamily = "Fake Font",
            renderSizePx = 96,
            threshold = 100.0,
            invert = false,
            offsetX = 3.0,
            offsetY = 5.0,
            markSpeed = 500.0,
            travelSpeed = 0.0,
            minPower = 0,
            maxPower = 255,
            laserPower = 200,
            laserOn = "M3",
            laserOff = "M5",
            pwm = false,
            header = "G90\n; text",
            footer = "",
            optimize = false,
            tool = VectorTool.Centerline,
            turdSize = 0.0,
            alphaMax = 0.8,
            optTolerance = 0.05,
            curveOptimizing = false,
            flattenTolerance = 0.5,
            minBranchPx = 2.0,
            simplifyTolerance = 0.5
        )

        val result = convertTextVector(options, rasterizer)

        // 1) 渲染选项逐个透传（v2：同一个 o 直接当 TextRenderOptions）
        assertEquals(1, rasterizer.calls, "render 调用次数")
        val rendered = rasterizer.lastOptions ?: error("假渲染器没有收到选项")
        assertEquals("iGRBL 中文", rendered.text, "render.text")
        assertEquals(4.0, rendered.sizeMm, 0.0, "render.sizeMm")
        assertEquals(true, rendered.bold, "render.bold")
        assertEquals(TextOrientation.Vertical, rendered.orientation, "render.orientation")
        assertEquals(1.2, rendered.lineSpacing, 0.0, "render.lineSpacing")
        assertEquals("Fake Font", rendered.fontFamily, "render.fontFamily")
        assertEquals(96, rendered.renderSizePx, "render.renderSizePx")

        // 2) 转换结果 == 直接用同一张图 + 同一批选项调 convertImageVector
        //    （widthMm / heightMm 必须来自渲染结果 8.0 / 6.0，而不是 DEFAULT 的 50 / 50）
        val expected = convertImageVector(
            image,
            DEFAULT_IMAGE_VECTOR_OPTIONS.copy(
                tool = options.tool,
                threshold = options.threshold,
                invert = options.invert,
                widthMm = 8.0,
                heightMm = 6.0,
                offsetX = options.offsetX,
                offsetY = options.offsetY,
                markSpeed = options.markSpeed,
                travelSpeed = options.travelSpeed,
                minPower = options.minPower,
                maxPower = options.maxPower,
                laserPower = options.laserPower,
                laserOn = options.laserOn,
                laserOff = options.laserOff,
                pwm = options.pwm,
                header = options.header,
                footer = options.footer,
                optimize = options.optimize,
                turdSize = options.turdSize,
                alphaMax = options.alphaMax,
                optTolerance = options.optTolerance,
                curveOptimizing = options.curveOptimizing,
                flattenTolerance = options.flattenTolerance,
                minBranchPx = options.minBranchPx,
                simplifyTolerance = options.simplifyTolerance
            )
        )
        assertEquals(expected.lines, result.lines, "lines")
        assertEquals(expected.pathCount, result.pathCount, "pathCount")
        assertEquals(expected.lengthMm, result.lengthMm, 0.0, "lengthMm")
        assertTrue(expected.preview.contentEquals(result.preview), "preview")
        assertEquals(8.0, result.widthMm, 0.0, "widthMm 必须来自渲染结果")
        assertEquals(6.0, result.heightMm, 0.0, "heightMm 必须来自渲染结果")

        // 该图在 Centerline 下确有输出（避免「两边都是空结果」式的假绿）
        assertTrue(result.lines.isNotEmpty(), "Centerline 结果不应为空")
        assertTrue(result.lines.contains("; text"), "header 的第二个非空行应被输出（逐行沿用 v2）")
        assertTrue(result.lines.any { it.startsWith("M3") }, "pwm=false 时只输出 laserOn 指令")
        assertTrue(result.lines.none { it.startsWith("M3 S") }, "pwm=false 不应带 S 值")
    }

    @Test
    fun scalesLineLengthWithRenderedWidth() {
        val image = crossImage()
        val options = TextVectorOptions(text = "A", sizeMm = 1.0, bold = false, orientation = TextOrientation.Horizontal)

        val big = convertTextVector(options, FakeRasterizer(image, pxPerMm = 1.0, widthMm = 8.0, heightMm = 8.0))
        val small = convertTextVector(options, FakeRasterizer(image, pxPerMm = 1.0, widthMm = 4.0, heightMm = 4.0))

        assertTrue(big.lengthMm > 0.0, "正常尺寸下应有走线长度")
        assertEquals(big.pathCount, small.pathCount, "缩放不应改变路径条数")
        // sc = widthMm / image.width：8/16 = 0.5 与 4/16 = 0.25，长度必须逐位成半（2 的幂缩放是精确的）
        assertEquals(big.lengthMm, small.lengthMm * 2.0, 0.0, "lengthMm 应随 widthMm 等比变化")
    }

    @Test
    fun degenerateSizeReturnsEmptyWithoutConverting() {
        val image = crossImage()
        val options = TextVectorOptions(text = " ", sizeMm = 5.0, bold = false, orientation = TextOrientation.Horizontal)

        // 反证基准：同一张图在正常尺寸下**有**输出（说明下面这些空结果只能来自提前返回）
        val control = convertTextVector(options, FakeRasterizer(image, pxPerMm = 1.0, widthMm = 8.0, heightMm = 8.0))
        assertTrue(control.lines.isNotEmpty(), "基准：正常尺寸下应有输出")

        // v2：r.widthMm <= 0 || r.heightMm <= 0 → 空结果（1×1 全零 preview），且不调用 convertImageVector
        val degenerateSizes = listOf(
            Triple(0.0, 0.0, "全零（无墨迹，例如仅空格）"),
            Triple(8.0, 0.0, "高度为 0"),
            Triple(0.0, 6.0, "宽度为 0"),
            Triple(-3.0, 4.0, "宽度为负")
        )
        for ((widthMm, heightMm, label) in degenerateSizes) {
            val rasterizer = FakeRasterizer(image, pxPerMm = 1.0, widthMm = widthMm, heightMm = heightMm)
            val result = convertTextVector(options, rasterizer)
            assertEquals(1, rasterizer.calls, "$label：仍应先渲染一次")
            assertEquals(0, result.pathCount, "$label：pathCount")
            assertEquals(0.0, result.lengthMm, 0.0, "$label：lengthMm")
            assertEquals(0.0, result.widthMm, 0.0, "$label：widthMm")
            assertEquals(0.0, result.heightMm, 0.0, "$label：heightMm")
            // 若误调了 convertImageVector，header（默认 "G90"）与 footer 至少会各出一行
            assertTrue(result.lines.isEmpty(), "$label：lines 必须为空（否则说明没有提前返回）")
            assertEquals(4, result.preview.size, "$label：preview 应为 1×1")
            assertEquals(listOf(0, 0, 0, 0), result.preview.toList(), "$label：preview 应为全零")
        }
    }
}
