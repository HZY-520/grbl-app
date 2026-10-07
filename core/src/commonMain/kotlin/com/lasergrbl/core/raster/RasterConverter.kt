package com.lasergrbl.core.raster

import com.lasergrbl.core.internal.jsNumberToString
import com.lasergrbl.core.vector.PotraceImage
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 光栅图像 → G 代码 —— 逐行移植自 v2 `src/core/raster/RasterConverter.ts`
 * （其本身移植自 LaserGRBL 的 `GrblFile.cs` 的 `LoadImageL2L` / `ImageLine2Line` /
 * `GetSegments` / `OptimizeLine2Line`，以及 `RasterConverter/ImageProcessor.cs` 的预处理流程）。
 *
 * 对应 Phase 4「图片转雕刻 → 光栅模式（逐行扫描 / 抖动）」。
 *
 * ### 唯一的平台依赖被抽成了入参
 * v2 的 `convertImageToGcode` 开头会调 `resizeImage(source, ...)`（浏览器 canvas 重采样），
 * 其余从 `testGrayScale` 到最终 `lines` 拼装**全是纯逻辑**。这里把重采样换成
 * [ResizeSampler] 入参（见 [Interpolation]），于是整条管线可以在 `:core` 里被黄金样本
 * 逐行钉死 —— 夹具 `raster-converter.json` 是**v2 真实代码跑出来的**（生成器只替换了
 * `document.createElement('canvas')` 这一层）。
 *
 * ### 几个必须照抄的怪癖（黄金样本都会钉住）
 *  * `res` 有 22000×22000 像素的总量上限（`maxRes = sqrt(maxSize / max(filesize, 0.0001))`）；
 *  * 灰度化时若原图**已经是灰度图**，会强制用 `SimpleAverage` 而不是用户选的公式；
 *  * `getColor` 的 `rv = floor((255 - r) * a / 255)`，`rv == 0` 直接返回 0（不再映射功率）；
 *  * 对角线方向的分段是按 `slice = x + y` 切，且 `D` 段的累计是 `cum.y -= pixLen`（不是 `+=`）；
 *  * 头/尾要先 `trim()` 判空、再按 `\n` 切分、再逐行 `trim()` 并跳过空行。
 */

/** 光栅走线方式（对应 v2 `RasterTool`）。 */
enum class RasterTool(val value: String) {
    Line2Line("Line2Line"),
    Dithering("Dithering");

    companion object {
        fun fromValue(value: String): RasterTool? = entries.firstOrNull { it.value == value }
    }
}

/** 扫描方向（对应 v2 `RasterDirection`）。 */
enum class RasterDirection(val value: String) {
    Horizontal("Horizontal"),
    Vertical("Vertical"),
    Diagonal("Diagonal");

    companion object {
        fun fromValue(value: String): RasterDirection? = entries.firstOrNull { it.value == value }
    }
}

/** 扫描方向的中文标签（逐字对齐 v2 `DIRECTION_LABELS`）。 */
val DIRECTION_LABELS: Map<RasterDirection, String> = linkedMapOf(
    RasterDirection.Horizontal to "水平（横向扫描）",
    RasterDirection.Vertical to "垂直（纵向扫描）",
    RasterDirection.Diagonal to "对角线（斜向扫描）"
)

/**
 * 光栅转换选项 —— 字段与默认值逐条对齐 v2 `DEFAULT_RASTER_OPTIONS`。
 */
data class RasterOptions(
    val tool: RasterTool = RasterTool.Line2Line,
    val direction: RasterDirection = RasterDirection.Horizontal,
    /** 分辨率，线/mm。 */
    val quality: Double = 3.0,
    /** 是否使用硬件 PWM（S 值渐变）。 */
    val pwm: Boolean = true,
    val markSpeed: Double = 1000.0,
    val minPower: Int = 0,
    val maxPower: Int = 1000,
    val laserOn: String = "M3",
    val laserOff: String = "M5",
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    // ---- 预处理 ----
    val formula: Formula = Formula.SimpleAverage,
    val red: Double = 100.0,
    val green: Double = 100.0,
    val blue: Double = 100.0,
    val brightness: Double = 100.0,
    val contrast: Double = 100.0,
    val whiteClip: Double = 5.0,
    val useThreshold: Boolean = false,
    val threshold: Double = 50.0,
    val dithering: DitheringMode = DitheringMode.FloydSteinberg,
    val interpolation: Interpolation = Interpolation.High,
    val unidirectional: Boolean = false,
    /** 是否禁用 G0 快速空移（禁用后用 G1 空移）。 */
    val disableFastSkip: Boolean = false,
    val header: String = "G90",
    val footer: String = "M5\nG0 X0 Y0"
)

/** 转换结果。 */
data class RasterResult(
    val lines: List<String>,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val res: Double,
    /** 预处理后的预览图（RGBA，长度 `pixelWidth*pixelHeight*4`）。 */
    val preview: IntArray
)

/** 段类型（`X`/`Y` 直线段、`D` 对角段、`V`/`H` 分隔符）。 */
private enum class SegKind { X, Y, D, VSep, HSep }

private class Segment(val kind: SegKind, val color: Int, /** 像素长度，负值表示反向。 */ val pixLen: Int)

private class L2LConf(
    val res: Double,
    val oX: Double,
    val oY: Double,
    val markSpeed: Double,
    val minPower: Int,
    val maxPower: Int,
    val lOn: String,
    val lOff: String,
    val pwm: Boolean,
    val dir: RasterDirection,
    val skipcmd: String
)

private fun isSeparator(s: Segment): Boolean = s.kind == SegKind.VSep || s.kind == SegKind.HSep

private fun segFast(s: Segment, c: L2LConf): Boolean = if (c.pwm) s.color == 0 else s.color <= 125

/**
 * v2 的 `fmt`（`Math.round(v * 1000) / 1000` 之后走 `String(v)`）。
 *
 * ⚠️ 这里的 `Math.round` 是 **JS 语义**（四舍五入、并列朝 +∞），而 v2 后续并没有再调
 * `toFixed`，而是直接 `String(rounded)`（最短往返表示）。所以这一步**不是** `jsToFixed`，
 * 必须用 JS 的 `Math.round` 语义 + `jsNumberToString`。
 */
private fun jsRound3(v: Double): Double = floor(v * 1000.0 + 0.5) / 1000.0

private fun fmt3(v: Double): String {
    if (!v.isFinite()) return jsNumberToString(0.0)
    // `Math.round(v*1000)/1000`：注意 -0 在 JS 里会被 String(-0) 输出成 "0"
    val r = jsRound3(v)
    return if (r == 0.0) "0" else jsNumberToString(r)
}

private fun formatNumber(number: Double, offset: Double, c: L2LConf): String =
    fmt3(jsRound3((number / c.res + offset) * 1000.0) / 1000.0)

private fun formatLaserPower(color: Int): String = "S$color"

private class Cum(var x: Double = 0.0, var y: Double = 0.0)

private fun segToGCodeNumber(s: Segment, cum: Cum, c: L2LConf): String {
    return when (s.kind) {
        SegKind.X -> {
            cum.x += s.pixLen
            val x = formatNumber(cum.x, c.oX, c)
            if (c.pwm) "X$x ${formatLaserPower(s.color)}" else "X$x ${if (segFast(s, c)) c.lOff else c.lOn}"
        }

        SegKind.Y -> {
            cum.y += s.pixLen
            val y = formatNumber(cum.y, c.oY, c)
            if (c.pwm) "Y$y ${formatLaserPower(s.color)}" else "Y$y ${if (segFast(s, c)) c.lOff else c.lOn}"
        }

        SegKind.D -> {
            cum.x += s.pixLen
            // ⚠️ 对角段的 y 是**减去** pixLen（与 X/Y 不同），v2 原样如此
            cum.y -= s.pixLen
            val x = formatNumber(cum.x, c.oX, c)
            val y = formatNumber(cum.y, c.oY, c)
            if (c.pwm) "X$x Y$y ${formatLaserPower(s.color)}" else "X$x Y$y ${if (segFast(s, c)) c.lOff else c.lOn}"
        }

        SegKind.VSep -> {
            cum.y += s.pixLen
            "Y${formatNumber(cum.y, c.oY, c)}"
        }

        SegKind.HSep -> {
            cum.x += s.pixLen
            "X${formatNumber(cum.x, c.oX, c)}"
        }
    }
}

/** 与 LaserGRBL `GetColor` 完全一致。 */
private fun getColor(px: IntArray, x: Int, y: Int, width: Int, c: L2LConf): Int {
    val i = (y * width + x) * 4
    val r = px[i]
    val a = px[i + 3]
    val rv = floor(((255 - r) * a) / 255.0).toInt()
    if (rv == 0) return 0
    if (c.pwm) return floor((rv * (c.maxPower - c.minPower)) / 255.0).toInt() + c.minPower
    return rv
}

private class Buf(var v: Int)

private fun extractSegment(
    px: IntArray,
    width: Int,
    x: Int,
    y: Int,
    reverse: Boolean,
    len: Buf,
    prevCol: Buf,
    out: MutableList<Segment>,
    c: L2LConf
) {
    len.v++
    val col = getColor(px, x, y, width, c)
    if (prevCol.v == -1) prevCol.v = col
    if (prevCol.v != col) {
        val kind = when (c.dir) {
            RasterDirection.Horizontal -> SegKind.X
            RasterDirection.Vertical -> SegKind.Y
            RasterDirection.Diagonal -> SegKind.D
        }
        out.add(Segment(kind, prevCol.v, if (reverse) -len.v else len.v))
        len.v = 0
    }
    prevCol.v = col
}

private fun isEven(v: Int): Boolean = v % 2 == 0

private fun getSegments(px: IntArray, width: Int, height: Int, c: L2LConf, uni: Boolean): List<Segment> {
    val rv = ArrayList<Segment>()
    if (c.dir == RasterDirection.Horizontal || c.dir == RasterDirection.Vertical) {
        val h = c.dir == RasterDirection.Horizontal
        val outer = if (h) height else width
        for (i in 0 until outer) {
            val d = uni || isEven(i)
            val len = Buf(-1)
            val prevCol = Buf(-1)
            val inner = if (h) width else height
            var j = if (d) 0 else inner - 1
            while (if (d) j < inner else j >= 0) {
                val x = if (h) j else i
                val y = if (h) i else j
                extractSegment(px, width, x, y, !d, len, prevCol, rv, c)
                j = if (d) j + 1 else j - 1
            }
            rv.add(Segment(if (h) SegKind.X else SegKind.Y, prevCol.v, if (!d) -(len.v + 1) else len.v + 1))
            if (uni) {
                if (h) rv.add(Segment(SegKind.X, 0, -width)) else rv.add(Segment(SegKind.Y, 0, -height))
            }
            if (i < outer - 1) {
                rv.add(if (h) Segment(SegKind.VSep, 0, 1) else Segment(SegKind.HSep, 0, 1))
            }
        }
    } else {
        // 对角线：按 x + y = slice 逐条扫描
        rv.add(Segment(SegKind.VSep, 0, 1))
        val w = width
        val h = height
        var slice = 0
        while (slice < w + h - 1) {
            val d = uni || isEven(slice)
            val len = Buf(-1)
            val prevCol = Buf(-1)
            val z1 = if (slice < h) 0 else slice - h + 1
            val z2 = if (slice < w) 0 else slice - w + 1
            var j = if (d) z1 else slice - z2
            while (if (d) j <= slice - z2 else j >= z1) {
                extractSegment(px, width, j, slice - j, !d, len, prevCol, rv, c)
                j = if (d) j + 1 else j - 1
            }
            rv.add(Segment(SegKind.D, prevCol.v, if (!d) -(len.v + 1) else len.v + 1))
            if (uni) {
                val slen = slice - z1 - z2 + 1
                rv.add(Segment(SegKind.D, 0, -slen))
            }
            if (slice < min(w, h) - 1) {
                rv.add(if (d && !uni) Segment(SegKind.HSep, 0, 1) else Segment(SegKind.VSep, 0, 1))
            } else if (slice >= maxOf(w, h) - 1) {
                rv.add(if (d && !uni) Segment(SegKind.VSep, 0, 1) else Segment(SegKind.HSep, 0, 1))
            } else {
                rv.add(if (w > h) Segment(SegKind.HSep, 0, 1) else Segment(SegKind.VSep, 0, 1))
            }
            slice++
        }
    }
    return rv
}

/** 把命令解析为 X/Y/S/M 值（对应 v2 `parseCmd` / `BuildHelper`）。 */
private class ParsedCmd(
    val x: Double?,
    val y: Double?,
    val s: Double?,
    val m3: Boolean,
    val m5: Boolean
) {
    val isMovement: Boolean get() = x != null || y != null
}

private fun parseCmd(line: String): ParsedCmd {
    val upper = line.uppercase()
    fun get(letter: Char): Double? {
        // 注意用非捕获组：v2 的 `new RegExp(`${letter}(-?[0-9.]+)`)` 取的是 m[1]，
        // Kotlin 侧对应"第 1 个捕获组"，所以内部括号必须是 `(?:...)`，否则组号会错位。
        val m = Regex("${Regex.escape(letter.toString())}((?:-?[0-9.]+))").find(upper) ?: return null
        return m.groupValues[1].toDoubleOrNull()
    }
    // v2: /(^|\s)S(-?[0-9.]+)/.test(` ${upper}`)
    // 同样用非捕获组，保证 groupValues[1] 是数字本身
    val padded = " $upper"
    val sMatches = Regex("(?:^|\\s)S((?:-?[0-9.]+))").containsMatchIn(padded)
    return ParsedCmd(
        x = get('X'),
        y = get('Y'),
        s = if (sMatches) get('S') else null,
        m3 = upper.contains("M3") || upper.contains("M4"),
        m5 = upper.contains("M5")
    )
}

/** 对应 `OptimizeLine2Line`：合并连续的激光关闭空移。 */
private fun optimizeLine2Line(temp: List<String>, c: L2LConf): List<String> {
    val rv = ArrayList<String>()
    var curX = c.oX
    var curY = c.oY
    var cumulate = false
    for (line in temp) {
        val p = parseCmd(line)
        val oldCumulate = cumulate
        if (c.pwm) {
            if (p.s != null) cumulate = p.s == 0.0
        } else {
            if (p.m5) cumulate = true else if (p.m3) cumulate = false
        }

        if (oldCumulate && !cumulate) {
            rv.add(
                if (c.pwm) "${c.skipcmd} X${fmt3(curX)} Y${fmt3(curY)} S0"
                else "${c.skipcmd} X${fmt3(curX)} Y${fmt3(curY)} ${c.lOff}"
            )
        }

        if (p.isMovement) {
            if (p.x != null) curX = p.x
            if (p.y != null) curY = p.y
        }

        if (!p.isMovement || !cumulate) rv.add(line)
    }
    return rv
}

/**
 * 主入口：位图 → 光栅 G 代码。
 *
 * @param source 源位图（RGBA，`:core` 的 [PotraceImage] 三元组）
 * @param targetMmW 目标宽 (mm) @param targetMmH 目标高 (mm)
 * @param resampler 重采样接缝（v2 的 `resizeImage`，由平台提供；单测传最近邻）
 */
fun convertImageToGcode(
    source: PotraceImage,
    targetMmW: Double,
    targetMmH: Double,
    options: RasterOptions,
    resampler: ResizeSampler
): RasterResult {
    val maxSize = 22000.0 * 22000.0
    val filesize = targetMmW * targetMmH
    val maxRes = sqrt(maxSize / maxOf(filesize, 0.0001))
    val res = min(maxRes, options.quality)
    val pixelW = maxOf(1, Math.round(targetMmW * res).toInt())
    val pixelH = maxOf(1, Math.round(targetMmH * res).toInt())

    val conf = L2LConf(
        res = res,
        oX = options.offsetX,
        oY = options.offsetY,
        markSpeed = options.markSpeed,
        minPower = options.minPower,
        maxPower = options.maxPower,
        lOn = options.laserOn,
        lOff = options.laserOff,
        pwm = options.pwm,
        dir = options.direction,
        skipcmd = if (options.disableFastSkip) "G1" else "G0"
    )

    // ---- 预处理 ----
    val resized = resampler.sample(source, pixelW, pixelH, options.interpolation, fillWhite = false)
    val isGray = testGrayScale(resized)
    grayScale(
        resized,
        options.red,
        options.green,
        options.blue,
        -(100.0 - options.brightness) / 100.0,
        options.contrast / 100.0,
        if (isGray) Formula.SimpleAverage else options.formula
    )
    whitenize(resized, options.whiteClip)
    if (options.tool == RasterTool.Dithering) {
        dither(resized, pixelW, pixelH, options.dithering)
    } else {
        threshold(resized, options.threshold / 100.0, options.useThreshold)
    }

    val flipped = flipVertical(resized, pixelW, pixelH)

    // ---- 生成 G 代码 ----
    val segments = getSegments(flipped, pixelW, pixelH, conf, options.unidirectional)
    val temp = ArrayList<String>()
    val cum = Cum()
    var fast = true

    for (seg in segments) {
        val changeGMode = fast != segFast(seg, conf)
        if (isSeparator(seg) && !fast) {
            temp.add(if (conf.pwm) "S0" else conf.lOff)
        }
        fast = segFast(seg, conf)
        val number = segToGCodeNumber(seg, cum, conf)
        temp.add(if (changeGMode) "${if (fast) conf.skipcmd else "G1"} $number" else number)
    }

    val optimized = optimizeLine2Line(temp, conf)

    val lines = ArrayList<String>()
    if (options.header.trim().isNotEmpty()) {
        for (l in options.header.split('\n')) if (l.trim().isNotEmpty()) lines.add(l.trim())
    }
    lines.add("${conf.skipcmd} X${fmt3(conf.oX)} Y${fmt3(conf.oY)} F${fmt3(conf.markSpeed)}")
    if (conf.pwm) lines.add("${conf.lOn} S0") else lines.add("${conf.lOff} S${conf.maxPower}")

    lines.addAll(optimized)

    lines.add(conf.lOff)
    if (options.footer.trim().isNotEmpty()) {
        for (l in options.footer.split('\n')) if (l.trim().isNotEmpty()) lines.add(l.trim())
    }

    return RasterResult(lines, pixelW, pixelH, res, resized)
}
