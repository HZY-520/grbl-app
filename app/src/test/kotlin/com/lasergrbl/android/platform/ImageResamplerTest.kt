package com.lasergrbl.android.platform

import com.lasergrbl.core.vector.PotraceImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重采样接缝与位图接缝的单元测试（纯 JVM，不需要真机、不需要 Robolectric）。
 *
 * 覆盖范围**严格限定在可离线判定的部分**：
 *  * [NearestNeighborResampler]：逐像素精确断言（含放大、缩小、killAlpha 合成、退化输入）；
 *  * [TriangleResampler]：手算可验证的小图（4×4 放大到 8×8 的三个代表位置 + 2×2 缩到 1×1 的盒平均），
 *    以及"低质量 = 最近邻"、"退化输入不崩"这类结构性质；
 *  * [AndroidBitmapBridge] 的通道解包是 Android 类型（`Bitmap`），**离线测不了**，
 *    这里只测它的纯约定（RGBA 布局）由 `:core` 的黄金样本覆盖。
 *
 * ⚠️ 与真实浏览器 Canvas 的逐像素一致性**没有**在这里验证（也没有别的地方验证过）——
 * 这条差异登记在 `docs/PHASE3-PLATFORM-MODULES.md`，Phase 4 用无头浏览器做一次离线捕获收盘。
 */
class ImageResamplerTest {

    private class Img(override val data: IntArray, override val width: Int, override val height: Int) : PotraceImage

    /** 生成 `w×h` 的 RGBA 图，颜色由 [paint] 决定。 */
    private fun image(w: Int, h: Int, paint: (x: Int, y: Int) -> IntArray): Img {
        val data = IntArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgba = paint(x, y)
                val base = (y * w + x) * 4
                data[base] = rgba[0]
                data[base + 1] = rgba[1]
                data[base + 2] = rgba[2]
                data[base + 3] = rgba[3]
            }
        }
        return Img(data, w, h)
    }

    private fun pixel(img: IntArray, width: Int, x: Int, y: Int): IntArray {
        val base = (y * width + x) * 4
        return intArrayOf(img[base], img[base + 1], img[base + 2], img[base + 3])
    }

    // ===================== 最近邻 =====================

    @Test
    fun nearestNeighborUpscaleRepeatsSourcePixels() {
        val src = image(2, 2) { x, y ->
            if (x == 0 && y == 0) intArrayOf(10, 0, 0, 255) else intArrayOf(200, 0, 0, 255)
        }

        val out = NearestNeighborResampler.resample(src, 4, 4, ImageResampler.Interpolation.High, false)

        // 2×2 → 4×4：每个源像素覆盖 2×2 目标块
        assertArrayEquals(intArrayOf(10, 0, 0, 255), pixel(out, 4, 0, 0))
        assertArrayEquals(intArrayOf(10, 0, 0, 255), pixel(out, 4, 1, 0))
        assertArrayEquals(intArrayOf(200, 0, 0, 255), pixel(out, 4, 2, 0))
        assertArrayEquals(intArrayOf(200, 0, 0, 255), pixel(out, 4, 3, 0))
    }

    @Test
    fun nearestNeighborDownscalePicksPixelCenters() {
        // 4×4，颜色按列编号 0..3（灰度）
        val src = image(4, 4) { x, _ -> intArrayOf(x * 50, x * 50, x * 50, 255) }

        val out = NearestNeighborResampler.resample(src, 2, 1, ImageResampler.Interpolation.High, false)

        // 目标 x=0 的中心 0.5*4/2 = 1.0 → 源列 1；x=1 的中心 1.5*4/2 = 3.0 → 源列 3
        assertEquals(50, pixel(out, 2, 0, 0)[0])
        assertEquals(150, pixel(out, 2, 1, 0)[0])
    }

    @Test
    fun nearestNeighborKillAlphaCompositesOntoWhite() {
        val src = image(1, 1) { _, _ -> intArrayOf(0, 0, 0, 128) }

        val kept = NearestNeighborResampler.resample(src, 1, 1, ImageResampler.Interpolation.High, false)
        assertArrayEquals(intArrayOf(0, 0, 0, 128), pixel(kept, 1, 0, 0))

        val killed = NearestNeighborResampler.resample(src, 1, 1, ImageResampler.Interpolation.High, true)
        // 0*128/255 + 255*127/255 = 127（四舍五入）
        assertArrayEquals(intArrayOf(127, 127, 127, 255), pixel(killed, 1, 0, 0))
    }

    @Test
    fun fullyTransparentPixelBecomesWhiteWhenKillAlpha() {
        val src = image(1, 1) { _, _ -> intArrayOf(10, 20, 30, 0) }
        val killed = NearestNeighborResampler.resample(src, 1, 1, ImageResampler.Interpolation.High, true)
        assertArrayEquals(intArrayOf(255, 255, 255, 255), pixel(killed, 1, 0, 0))
    }

    @Test
    fun zeroSizedRequestIsClampedToOnePixel() {
        val src = image(3, 3) { _, _ -> intArrayOf(1, 2, 3, 255) }
        val out = NearestNeighborResampler.resample(src, 0, 0, ImageResampler.Interpolation.High, false)
        assertEquals(4, out.size)
    }

    @Test
    fun degenerateSourceFillsWhite() {
        val empty = Img(IntArray(0), 0, 0)
        val out = NearestNeighborResampler.resample(empty, 2, 2, ImageResampler.Interpolation.High, false)
        assertEquals(16, out.size)
        for (i in 0 until 4) assertArrayEquals(intArrayOf(255, 255, 255, 255), pixel(out, 2, i % 2, i / 2))
    }

    // ===================== 三角滤波 =====================

    @Test
    fun triangleUpscaleInterpolatesExpectedCorners() {
        // 2×2 → 4×4：四角必须是原色（放大时三角核在边界只覆盖到一个源像素）
        val src = image(2, 2) { x, y ->
            when {
                x == 0 && y == 0 -> intArrayOf(0, 0, 0, 255)
                x == 1 && y == 0 -> intArrayOf(50, 50, 50, 255)
                x == 0 && y == 1 -> intArrayOf(100, 100, 100, 255)
                else -> intArrayOf(150, 150, 150, 255)
            }
        }

        val out = TriangleResampler.resample(src, 4, 4, ImageResampler.Interpolation.High, false)

        assertArrayEquals(intArrayOf(0, 0, 0, 255), pixel(out, 4, 0, 0))
        assertArrayEquals(intArrayOf(50, 50, 50, 255), pixel(out, 4, 3, 0))
        assertArrayEquals(intArrayOf(100, 100, 100, 255), pixel(out, 4, 0, 3))
        assertArrayEquals(intArrayOf(150, 150, 150, 255), pixel(out, 4, 3, 3))
    }

    /**
     * 放大时的**加权**必须真的发生（不是最近邻复制）。
     *
     * 本实现的坐标映射是标准的"像素中心"约定：目标的第 x 个像素覆盖源区间
     * `[x*scale, (x+1)*scale)`，其中 `scale = 源宽 / 目标宽`。以 2×2 → 4×4（`scale = 0.5`）为例，
     * 目标 x=2 覆盖源区间 `[1.0, 1.5)`，中心 **1.25**：
     *  * 源列 0（中心 0.5）距离 0.75 → 三角权重 `1 - 0.75 = 0.25`
     *  * 源列 1（中心 1.5）距离 0.25 → 三角权重 `1 - 0.25 = 0.75`
     * ⇒ `0.25*0 + 0.75*255 = 191.25` → 四舍五入 **191**。
     * （最近邻在这里会给出 255，所以这条断言能区分两种实现。）
     */
    @Test
    fun triangleUpscaleInteriorIsWeightedAverage() {
        val src = image(2, 2) { x, _ -> if (x == 0) intArrayOf(0, 0, 0, 255) else intArrayOf(255, 255, 255, 255) }
        val out = TriangleResampler.resample(src, 4, 4, ImageResampler.Interpolation.High, false)
        assertEquals(191, pixel(out, 4, 2, 0)[0])
    }

    @Test
    fun triangleDownscaleAveragesTheWholeSource() {
        // 2×2 → 1×1：四像素等权 → (0+255+0+255)/4 = 127.5 → 128（四舍五入）
        val src = image(2, 2) { x, y ->
            if ((x + y) % 2 == 0) intArrayOf(0, 0, 0, 255) else intArrayOf(255, 255, 255, 255)
        }
        val out = TriangleResampler.resample(src, 1, 1, ImageResampler.Interpolation.High, false)
        assertEquals(128, pixel(out, 1, 0, 0)[0])
    }

    @Test
    fun lowQualityFallsBackToNearestNeighbor() {
        val src = image(4, 4) { x, y -> intArrayOf(x * 40, y * 40, 0, 255) }

        val low = TriangleResampler.resample(src, 2, 2, ImageResampler.Interpolation.Low, false)
        val nearest = NearestNeighborResampler.resample(src, 2, 2, ImageResampler.Interpolation.Low, false)

        assertArrayEquals(nearest, low)
    }

    @Test
    fun triangleKeepsAlphaWhenNotKillingIt() {
        val src = image(2, 2) { _, _ -> intArrayOf(0, 0, 0, 128) }
        val out = TriangleResampler.resample(src, 2, 2, ImageResampler.Interpolation.High, false)
        for (i in 0 until 4) assertEquals(128, pixel(out, 2, i % 2, i / 2)[3])
    }

    @Test
    fun triangleKillAlphaCompositesAndSetsOpaque() {
        val src = image(2, 2) { _, _ -> intArrayOf(0, 0, 0, 128) }
        val out = TriangleResampler.resample(src, 2, 2, ImageResampler.Interpolation.High, true)
        for (i in 0 until 4) {
            val px = pixel(out, 2, i % 2, i / 2)
            assertEquals(127, px[0]) // 0*128/255 + 255*127/255 = 127
            assertEquals(255, px[3])
        }
    }

    @Test
    fun triangleDegenerateSourceDoesNotCrash() {
        val empty = Img(IntArray(0), 0, 0)
        val out = TriangleResampler.resample(empty, 3, 3, ImageResampler.Interpolation.High, false)
        assertEquals(36, out.size)
        assertTrue(out.all { it == 255 })
    }

    @Test
    fun triangleOutputSizeMatchesRequest() {
        val src = image(7, 5) { x, y -> intArrayOf(x * 10, y * 10, 0, 255) }
        val out = TriangleResampler.resample(src, 13, 11, ImageResampler.Interpolation.High, false)
        assertEquals(13 * 11 * 4, out.size)
    }
}
