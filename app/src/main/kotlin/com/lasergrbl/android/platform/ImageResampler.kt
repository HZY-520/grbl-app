package com.lasergrbl.android.platform

import com.lasergrbl.core.vector.PotraceImage

/**
 * `:app` 侧的重采样接缝 —— 对应 v2 `src/core/raster/ImageTransform.ts` 的 `resizeImage()`。
 *
 * ### 为什么它必须放在平台层
 * v2 的 `resizeImage` 用 `document.createElement('canvas')` + `ctx.drawImage(...)`，
 * **重采样内核由浏览器实现**（`imageSmoothingQuality = 'high'`）。Node 里没有等价物，
 * 所以 `tools/golden/generate.ts` **刻意不捕获**这条路径（见该文件的 excludes 注释）；
 * 想在 `:core` 里"逐像素复刻"浏览器内核是不现实的，因此做成可注入接缝：
 *
 *  * `:core` 的算法只依赖"重采样后的 RGBA"，不关心它怎么来的；
 *  * `:app` 侧提供真机实现（默认 [TriangleResampler]）；
 *  * 单测可以注入 [NearestNeighborResampler] 这类精确可预期的实现。
 *
 * ### 与 v2 的三处对应关系
 * 1. `killAlpha = true`（图片转线性路径）→ 先合成到白底再重采样。实现上等价于
 *    `v2 resizeImage(..., killAlpha=true)` 的"画布先 fillRect 白底、再 drawImage"。
 * 2. `killAlpha = false`（光栅路径）→ 保留 alpha 通道（后续由 `threshold()` 负责合成）。
 * 3. 尺寸夹取：v2 是 `Math.max(1, sizeW/sizeH)`，调用方若传 0 必须得到 1×1 而不是崩溃。
 */
interface ImageResampler {

    /** 重采样质量（对应 v2 的 `interpolation: 'high' | 'low'`）。 */
    enum class Interpolation { High, Low }

    /**
     * 把 [source] 重采样到 `sizeW × sizeH`（两者都至少为 1），返回 RGBA（长度 `sizeW*sizeH*4`）。
     *
     * @param killAlpha 是否先合成到白底（透明像素变成白色，alpha 置 255）
     */
    fun resample(
        source: PotraceImage,
        sizeW: Int,
        sizeH: Int,
        interpolation: Interpolation,
        killAlpha: Boolean
    ): IntArray
}

/**
 * 最近邻重采样 —— **精确、可预期、无浮点争议**，用于单元测试与"低质量"分支的兜底。
 *
 * 放大时按像素中心取最近源像素；缩小时按目标像素中心反查源坐标。
 */
object NearestNeighborResampler : ImageResampler {

    override fun resample(
        source: PotraceImage,
        sizeW: Int,
        sizeH: Int,
        interpolation: ImageResampler.Interpolation,
        killAlpha: Boolean
    ): IntArray {
        val w = maxOf(sizeW, 1)
        val h = maxOf(sizeH, 1)
        val out = IntArray(w * h * 4)
        val src = source.data
        val sw = source.width
        val sh = source.height
        if (sw <= 0 || sh <= 0 || src.size < sw * sh * 4) {
            // 退化输入：整幅填白（与 v2 的"空白画布"一致）
            for (i in 0 until w * h) fillWhite(out, i)
            return out
        }
        for (y in 0 until h) {
            val sy = ((y + 0.5) * sh / h).toInt().coerceIn(0, sh - 1)
            for (x in 0 until w) {
                val sx = ((x + 0.5) * sw / w).toInt().coerceIn(0, sw - 1)
                copyPixel(src, (sy * sw + sx) * 4, out, (y * w + x) * 4, killAlpha)
            }
        }
        return out
    }

    private fun copyPixel(src: IntArray, si: Int, dst: IntArray, di: Int, killAlpha: Boolean) {
        val a = src[si + 3]
        if (!killAlpha || a == 255) {
            dst[di] = src[si]
            dst[di + 1] = src[si + 1]
            dst[di + 2] = src[si + 2]
            dst[di + 3] = a
            return
        }
        val inv = 255 - a
        dst[di] = (src[si] * a + 255 * inv + 127) / 255
        dst[di + 1] = (src[si + 1] * a + 255 * inv + 127) / 255
        dst[di + 2] = (src[si + 2] * a + 255 * inv + 127) / 255
        dst[di + 3] = 255
    }

    private fun fillWhite(out: IntArray, pixel: Int) {
        val base = pixel * 4
        out[base] = 255
        out[base + 1] = 255
        out[base + 2] = 255
        out[base + 3] = 255
    }
}

/**
 * 三角（bilinear）重采样 —— 默认实现，目标是**尽量贴近浏览器 `imageSmoothingQuality='high'`**。
 *
 * 做法：对每个目标像素，在源图上做一次**带支撑缩放的三角滤波积分**
 * （`support = filterScale`，缩小时核随比例变宽即标准 box 近似）。这正是浏览器
 * "high" 质量的常规实现方式，因此：
 *
 *  * **缩小时**（本项目的实际场景：1600 px 上限、光栅分辨率换算）结果与 Canvas 高度接近；
 *  * **放大时** 退化为标准双线性插值（与浏览器 `low`/`high` 在放大上差别极小）。
 *
 * ⚠️ **诚实标注**：这里没有与真实浏览器做过逐像素比对 ——
 * `tools/golden/generate.ts` 不含 `resizeImage`，Node 也没有 Canvas。
 * 这条差异已登记在 `docs/PHASE3-PLATFORM-MODULES.md`，并在 Phase 4 用一次
 * "无头浏览器抓 Canvas 输出"的离线捕获来收盘（不需要真机）。
 */
object TriangleResampler : ImageResampler {

    override fun resample(
        source: PotraceImage,
        sizeW: Int,
        sizeH: Int,
        interpolation: ImageResampler.Interpolation,
        killAlpha: Boolean
    ): IntArray {
        val w = maxOf(sizeW, 1)
        val h = maxOf(sizeH, 1)
        val src = source.data
        val sw = source.width
        val sh = source.height
        if (sw <= 0 || sh <= 0 || src.size < sw * sh * 4) {
            return NearestNeighborResampler.resample(source, w, h, interpolation, killAlpha)
        }
        // 低质量走最近邻：与 v2 把 imageSmoothingEnabled 关掉的效果一致（无插值）
        if (interpolation == ImageResampler.Interpolation.Low) {
            return NearestNeighborResampler.resample(source, w, h, interpolation, killAlpha)
        }

        val out = IntArray(w * h * 4)
        val scaleX = sw.toDouble() / w
        val scaleY = sh.toDouble() / h
        // 支持的半径：放大时固定 1（双线性），缩小时按缩放比变宽（盒式近似）。
        // 坐标约定是标准"像素中心"：目标的第 x 个像素覆盖源区间 [x*scale, (x+1)*scale)，
        // 中心取 (x + 0.5) * scale；权重按"源像素中心 (sx + 0.5) 到该中心的距离"算三角核。
        val supportX = maxOf(1.0, scaleX)
        val supportY = maxOf(1.0, scaleY)

        val xStart = IntArray(w)
        val xEnd = IntArray(w)
        val xWeights = Array(w) { DoubleArray(0) }
        for (x in 0 until w) {
            val center = (x + 0.5) * scaleX
            val lo = maxOf(0, kotlin.math.floor(center - supportX).toInt())
            val hi = minOf(sw - 1, kotlin.math.ceil(center + supportX).toInt())
            xStart[x] = lo
            xEnd[x] = hi
            val weights = DoubleArray(hi - lo + 1)
            var sum = 0.0
            for (sx in lo..hi) {
                val distance = kotlin.math.abs((sx + 0.5) - center) / supportX
                val weight = if (distance < 1.0) 1.0 - distance else 0.0
                weights[sx - lo] = weight
                sum += weight
            }
            if (sum <= 0.0) {
                // 极端缩放：退化为最近邻权重，避免除零
                weights.fill(0.0)
                val nearest = ((x + 0.5) * scaleX).toInt().coerceIn(0, sw - 1) - lo
                weights[nearest.coerceIn(0, weights.size - 1)] = 1.0
                sum = 1.0
            }
            for (i in weights.indices) weights[i] /= sum
            xWeights[x] = weights
        }

        for (y in 0 until h) {
            val centerY = (y + 0.5) * scaleY
            val yLo = maxOf(0, kotlin.math.floor(centerY - supportY).toInt())
            val yHi = minOf(sh - 1, kotlin.math.ceil(centerY + supportY).toInt())
            for (x in 0 until w) {
                var accR = 0.0
                var accG = 0.0
                var accB = 0.0
                var accA = 0.0
                var weightSum = 0.0
                for (sy in yLo..yHi) {
                    val dy = kotlin.math.abs((sy + 0.5) - centerY) / supportY
                    val wy = if (dy < 1.0) 1.0 - dy else 0.0
                    if (wy <= 0.0) continue
                    val rowBase = sy * sw
                    val weights = xWeights[x]
                    for (k in weights.indices) {
                        val wx = weights[k]
                        if (wx <= 0.0) continue
                        val si = (rowBase + xStart[x] + k) * 4
                        val weight = wx * wy
                        accR += src[si] * weight
                        accG += src[si + 1] * weight
                        accB += src[si + 2] * weight
                        accA += src[si + 3] * weight
                        weightSum += weight
                    }
                }
                val di = (y * w + x) * 4
                if (weightSum <= 0.0) {
                    out[di] = 255
                    out[di + 1] = 255
                    out[di + 2] = 255
                    out[di + 3] = 255
                    continue
                }
                val a = clampByte(accA / weightSum)
                if (killAlpha && a != 255) {
                    // 按通道在**非预乘**空间合成到白底（与 v2 的画布语义一致）
                    val inv = 255 - a
                    out[di] = clampByte((accR / weightSum) * a / 255.0 + 255.0 * inv / 255.0)
                    out[di + 1] = clampByte((accG / weightSum) * a / 255.0 + 255.0 * inv / 255.0)
                    out[di + 2] = clampByte((accB / weightSum) * a / 255.0 + 255.0 * inv / 255.0)
                    out[di + 3] = 255
                } else {
                    out[di] = clampByte(accR / weightSum)
                    out[di + 1] = clampByte(accG / weightSum)
                    out[di + 2] = clampByte(accB / weightSum)
                    out[di + 3] = a
                }
            }
        }
        return out
    }

    /** 四舍五入到最近整数并夹到 0..255（`Uint8ClampedArray` 的语义）。 */
    private fun clampByte(value: Double): Int = when {
        value.isNaN() -> 0
        value <= 0.0 -> 0
        value >= 255.0 -> 255
        else -> kotlin.math.round(value).toInt().coerceIn(0, 255)
    }
}
