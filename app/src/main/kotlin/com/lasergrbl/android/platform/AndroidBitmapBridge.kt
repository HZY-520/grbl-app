package com.lasergrbl.android.platform

import android.graphics.Bitmap
import com.lasergrbl.core.vector.PotraceImage

/**
 * `:app` 侧的**位图接缝**：把 Android 的 [Bitmap] / 文件 / 内容 URI 翻译成 `:core` 认识的
 * RGBA `IntArray`（长度 `w*h*4`、每元素 0..255）。
 *
 * 为什么要有这一层：`:core` 是纯 Kotlin（KMP），不持有 Android 依赖；而
 * `Bitmap` / `BitmapFactory` / `ContentResolver` 都只在 Android 上存在。
 * `:core` 的算法（灰度化、抖动、Potrace、骨架化、路径生成）全部只依赖
 * `PotraceImage` 这个三元组，所以平台差异被压缩到"取像素"和"写像素"两件事上。
 *
 * ⚠️ **通道顺序**：`:core` 约定 `data[i*4 + 0/1/2/3] = R/G/B/A`，每通道 0..255。
 * Android 的 `Bitmap.getPixels` 返回的是**打包 ARGB_8888 Int**（`0xAARRGGBB`），
 * 所以这里必须解包，不能直接把 Int 当通道用（v2 的 `ImageData.data` 是
 * `Uint8ClampedArray`，即"每像素 4 个数组元素"，与 `:core` 的约定一致）。
 */
object AndroidBitmapBridge {

    /** 解码结果：像素 + 尺寸（尺寸单独带着，因为空图时 `data` 是空数组）。 */
    data class RgbaImage(
        override val data: IntArray,
        override val width: Int,
        override val height: Int
    ) : PotraceImage

    /**
     * 从 [Bitmap] 取 RGBA。
     *
     * @param killAlpha 为 true 时把像素**合成到白底**（等价 v2 `resizeImage(..., killAlpha=true)`
     *   在画布上先填白再绘制）：`c' = round(c * a / 255 + 255 * (1 - a / 255))`，alpha 置 255。
     *   用于"图片转线性"路径 —— 那里必须先消灭透明，否则透明区域会被 Potrace 当成背景、
     *   而被 `buildPreview` 当成白底，两边判定不一致。
     */
    fun read(bitmap: Bitmap, killAlpha: Boolean = false): RgbaImage {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return RgbaImage(IntArray(0), maxOf(w, 0), maxOf(h, 0))
        val packed = IntArray(w * h)
        bitmap.getPixels(packed, 0, w, 0, 0, w, h)
        val out = IntArray(w * h * 4)
        for (i in packed.indices) {
            val p = packed[i]
            val a = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val base = i * 4
            if (killAlpha && a != 255) {
                val inv = 255 - a
                out[base] = (r * a + 255 * inv + 127) / 255
                out[base + 1] = (g * a + 255 * inv + 127) / 255
                out[base + 2] = (b * a + 255 * inv + 127) / 255
                out[base + 3] = 255
            } else {
                out[base] = r
                out[base + 1] = g
                out[base + 2] = b
                out[base + 3] = a
            }
        }
        return RgbaImage(out, w, h)
    }

    /** 把 RGBA 写成 [Bitmap]（`ARGB_8888`），用于预览。 */
    fun write(image: PotraceImage): Bitmap {
        val w = maxOf(image.width, 1)
        val h = maxOf(image.height, 1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val packed = IntArray(w * h)
        val src = image.data
        val known = src.size >= w * h * 4
        for (i in packed.indices) {
            if (!known) {
                // 尺寸与像素不匹配（例如 0×0 的空图 + 1×1 输出）：整幅填白，预览不崩
                packed[i] = 0xFFFFFFFF.toInt()
                continue
            }
            val base = i * 4
            val r = src[base] and 0xFF
            val g = src[base + 1] and 0xFF
            val b = src[base + 2] and 0xFF
            val a = src[base + 3] and 0xFF
            packed[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(packed, 0, w, 0, 0, w, h)
        return bitmap
    }
}
