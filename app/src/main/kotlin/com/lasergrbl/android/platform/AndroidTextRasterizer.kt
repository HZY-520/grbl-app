package com.lasergrbl.android.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.lasergrbl.core.text.TextOrientation
import com.lasergrbl.core.text.TextRasterizer
import com.lasergrbl.core.text.TextRenderOptions
import com.lasergrbl.core.text.TextRenderResult
import com.lasergrbl.core.vector.PotraceImage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * `TextRasterizer` 接缝的 Android 实现 —— 对应 v2 `ImageVector.renderTextToImage()`
 * 里的 `document.createElement('canvas')` + `ctx.fillText/strokeText` + `getImageData`。
 *
 * ### 与 v2 的逐条对应
 * | v2（浏览器 Canvas） | 这里（Android Canvas + Paint） |
 * | --- | --- |
 * | `ctx.font = '700 180px <family>'` | `Paint.textSize = renderSizePx`；粗体用 `Typeface.DEFAULT_BOLD` |
 * | `fillStyle='#ffffff'; fillRect(...)` 铺白底 | `canvas.drawColor(Color.WHITE)` |
 * | `fillStyle='#000000'; fillText(line, pad, baseline)` | `paint.color = BLACK; canvas.drawText(...)` |
 * | 粗体额外 `strokeText` + `lineWidth = max(1, size*0.03)` + `lineJoin='round'` | 同一个 `Paint` 切到 `STROKE`，`strokeWidth`/`strokeJoin` 对齐 |
 * | `measureText` 的 `actualBoundingBoxAscent/Descent` | `Paint.FontMetrics`（见下方取舍） |
 * | `getImageData(...)` | `Bitmap.getPixels(...)` 后解包成 RGBA |
 *
 * ### 两处**不可能与 v2 完全一致**的地方（必须显式记录，不要假装一致）
 * 1. **字体本身不同**：v2 的字体栈是
 *    `"PingFang SC", "Noto Sans CJK SC", "Source Han Sans SC", "Microsoft YaHei", sans-serif`
 *    （WebView 的可用字体），Android 平台上是 `Typeface.DEFAULT` / `DEFAULT_BOLD`。
 *    同一段中文的**墨迹包围盒必然不同** ⇒ "文字转雕刻"的像素级输出不可能与 v2 逐像素一致。
 *    3.0 能保证的是**算法与参数一致**（字号换算、行距、pad、竖排旋转、裁剪规则）。
 * 2. **度量口径不同**：浏览器的 `actualBoundingBoxAscent/Descent` 是**紧贴墨迹**的包围盒，
 *    而 `Paint.FontMetrics.ascent/descent` 是**字体度量行**（含内部行距）。
 *    这里用后者，并在 [AndroidTextRasterizer.measureLine] 里把"取不到墨迹度量"的兜底
 *    与 v2 的 `fallback * 0.8 / 0.2` 对齐。后果是行距会略大一点 —— 可接受，且已记录。
 */
class AndroidTextRasterizer(
    /** 渲染倍率：真机高分辨率屏上可以用 >1 换取更精细的像素（v2 固定 1×）。 */
    private val densityScale: Float = 1f
) : TextRasterizer {

    override fun render(options: TextRenderOptions): TextRenderResult {
        val renderSizePx = max(32, (options.renderSizePx * densityScale).roundToInt())
        val lines = options.text.split(Regex("\\r?\\n"))

        // ---- 第一遍：测量（等价 v2 的 probe canvas）----
        val bold = options.bold
        val probePaint = buildPaint(renderSizePx, bold)
        val metrics = lines.map { measureLine(probePaint, it, renderSizePx) }
        val maxInk = max(renderSizePx.toDouble(), metrics.maxOfOrNull { it.ascent + it.descent } ?: 0.0)
        val lineStep = maxInk * max(1.0, options.lineSpacing)
        val maxWidth = max(renderSizePx.toDouble(), metrics.maxOfOrNull { it.width } ?: 0.0)
        val pad = ceil(renderSizePx * 0.25).toInt()

        val canvasWidth = max(1, ceil(maxWidth + pad * 2.0).toInt())
        val canvasHeight = max(1, ceil(lineStep * (lines.size - 1) + maxInk + pad * 2.0).toInt())

        // ---- 第二遍：真正绘制 ----
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = buildPaint(renderSizePx, bold)
        for (i in lines.indices) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val m = metrics[i]
            // v2：textBaseline='alphabetic' ⇒ baseline = pad + i*lineStep + ascent
            val baseline = pad + i * lineStep + m.ascent
            if (bold) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.0, renderSizePx * 0.03).toFloat()
                paint.strokeJoin = Paint.Join.ROUND
                canvas.drawText(line, pad.toFloat(), baseline.toFloat(), paint)
            }
            paint.style = Paint.Style.FILL
            canvas.drawText(line, pad.toFloat(), baseline.toFloat(), paint)
        }

        var image = AndroidBitmapBridge.read(bitmap)
        if (options.orientation == TextOrientation.Vertical) image = rotate90CW(image)

        val box = inkBox(image) ?: return EMPTY_RESULT
        val cropped = crop(image, box)

        // 以"单字墨迹高度"对齐 sizeMm，保证字号直观（v2 的 scaleDenom 分支）
        val scaleDenom = if (options.orientation == TextOrientation.Vertical) maxWidth else maxInk
        val pxPerMm = if (scaleDenom > 0) scaleDenom / options.sizeMm else 1.0

        return TextRenderResult(
            image = cropped,
            pxPerMm = pxPerMm,
            widthMm = cropped.width / pxPerMm,
            heightMm = cropped.height / pxPerMm
        )
    }

    private fun buildPaint(sizePx: Int, bold: Boolean): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx.toFloat()
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        color = Color.BLACK
        textAlign = Paint.Align.LEFT
        textSkewX = 0f
    }

    /** 一行文本的度量。取不到时按 v2 的兜底：`ascent = fallback*0.8`、`descent = fallback*0.2`。 */
    private fun measureLine(paint: Paint, line: String, fallback: Int): LineMetrics {
        val width = paint.measureText(line).toDouble()
        val fm = paint.fontMetrics
        var ascent = (-fm.ascent).toDouble()
        var descent = fm.descent.toDouble()
        if (!ascent.isFinite() || !descent.isFinite() || (ascent == 0.0 && descent == 0.0)) {
            ascent = fallback * 0.8
            descent = fallback * 0.2
        }
        return LineMetrics(ascent, descent, width)
    }

    private data class LineMetrics(val ascent: Double, val descent: Double, val width: Double)

    /** 垂直翻转 90°（顺时针）：水平文本 → 自上而下的竖排。逐行对齐 v2 `rotate90CW`。 */
    private fun rotate90CW(src: AndroidBitmapBridge.RgbaImage): AndroidBitmapBridge.RgbaImage {
        val w = src.width
        val h = src.height
        val out = IntArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val si = (y * w + x) * 4
                val nx = h - 1 - y
                val ny = x
                val di = (ny * h + nx) * 4
                out[di] = src.data[si]
                out[di + 1] = src.data[si + 1]
                out[di + 2] = src.data[si + 2]
                out[di + 3] = src.data[si + 3]
            }
        }
        return AndroidBitmapBridge.RgbaImage(out, h, w)
    }

    /** 墨迹包围盒：跳过透明像素与"接近白"的像素（v2 `inkBox` 的判据）。 */
    private fun inkBox(img: PotraceImage): IntArray? {
        val w = img.width
        val h = img.height
        val data = img.data
        if (w <= 0 || h <= 0 || data.size < w * h * 4) return null
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                if (data[i + 3] < 128) continue
                if (data[i] > 200 && data[i + 1] > 200 && data[i + 2] > 200) continue
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (x1 < 0) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    /** 按包围盒裁剪（含 1 px 边距），逐行对齐 v2 `cropImageData`。 */
    private fun crop(img: PotraceImage, box: IntArray): AndroidBitmapBridge.RgbaImage {
        val pad = 1
        val x0 = max(0, box[0] - pad)
        val y0 = max(0, box[1] - pad)
        val x1 = minOf(img.width - 1, box[2] + pad)
        val y1 = minOf(img.height - 1, box[3] + pad)
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val out = IntArray(w * h * 4)
        for (y in 0 until h) {
            val srcStart = ((y0 + y) * img.width + x0) * 4
            System.arraycopy(img.data, srcStart, out, y * w * 4, w * 4)
        }
        return AndroidBitmapBridge.RgbaImage(out, w, h)
    }

    private companion object {
        /** v2 无墨迹时返回 1×1 空图、`pxPerMm = 1`、宽高为 0。 */
        val EMPTY_RESULT = TextRenderResult(
            image = AndroidBitmapBridge.RgbaImage(IntArray(4) { 255 }, 1, 1),
            pxPerMm = 1.0,
            widthMm = 0.0,
            heightMm = 0.0
        )
    }
}
