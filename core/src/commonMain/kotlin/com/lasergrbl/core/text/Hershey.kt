package com.lasergrbl.core.text

import com.lasergrbl.core.internal.jsNumberToString
import com.lasergrbl.core.internal.jsParseFloat
import com.lasergrbl.core.internal.jsTrim
import kotlin.math.floor

/**
 * Hershey 矢量字体文本 → G 代码
 *
 * 移植自 LaserGRBL 的 Hershey/Hershey.cs，核心对应 CreateString / MeasureString /
 * ApplyOffset 三个方法：
 *  - 字符片段（G0/G1/M3/M5 以及裸 X/Y 续接移动）会按比例缩放并平移到当前位置；
 *  - 相邻字符之间按 HERSHEY_SPACE_BETWEEN 累加间距；
 *  - 横向字体沿 +X 排列、换行到 +Y，纵向字体沿 +Y 排列、换行到 +X。
 *
 * 逐行对应 v2 `src/core/text/Hershey.ts`（该文件是唯一判据，黄金样本见
 * `core/src/commonTest/resources/golden/hershey.json`）。JS 语义上有三处必须显式复刻：
 *  1. `Math.round` 是「四舍五入、并列朝 +∞」（见 [jsMathRound]）；
 *  2. 数字转字符串是 `String(number)`（最短可往返表示），不是 `toFixed` / `String.format`，
 *     直接复用 `core.internal.jsNumberToString`；
 *  3. `.trim()` 是 JS trim（含 U+FEFF）、`replace(search, repl)` 只替换**第一处**且
 *     会展开替换串里的 `$` 模式（见 [jsReplaceFirst]）。
 */

/** 字体朝向；对应 v2 的 `type HersheyOrientation = 'horizontal' | 'vertical'`。 */
enum class HersheyOrientation {
    HORIZONTAL,
    VERTICAL
}

/** 文本 → G 代码的选项；字段与可选性逐条对应 v2 的 `HersheyOptions`（可选字段在此为 `null`）。 */
data class HersheyOptions(
    /** 文字内容，支持换行（CRLF 或 LF） */
    val text: String,
    /** 字体朝向 */
    val orientation: HersheyOrientation,
    /** 字符高度（毫米），对应原软件的字号 */
    val sizeMm: Double,
    /** 是否加粗（多次重复雕刻，微偏移） */
    val bold: Boolean,
    /** 行间距倍率，默认 1.5 */
    val lineSpacing: Double? = null,
    /** 起点 X（毫米），默认 0 */
    val offsetX: Double? = null,
    /** 起点 Y（毫米），默认 0 */
    val offsetY: Double? = null,
    /** 雕刻进给速度 mm/min */
    val markSpeed: Double,
    /** 激光开启指令，如 'M3' 或 'M4'；若为空则使用 S 值控制 */
    val laserOn: String,
    /** 激光关闭指令，如 'M5' */
    val laserOff: String,
    /** 是否使用 S 值（PWM） */
    val pwm: Boolean,
    /** 最大功率 S 值，默认 1000 */
    val maxPower: Double? = null
)

/** 结果：G 代码行 + 文本包围盒。 */
data class HersheyResult(
    /** 生成的 G 代码行（不含换行符） */
    val lines: List<String>,
    /** 文本包围盒（毫米），用于预览与居中，相对于起点 */
    val widthMm: Double,
    /** 文本包围盒（毫米），用于预览与居中，相对于起点 */
    val heightMm: Double
)

/**
 * 字体基准高度（字体单位）。
 * C# 的 CreateString 不做缩放，字体单位直接当作毫米使用，并把用于居中的
 * 方框尺寸固定为 2（横向 sizeY=2、纵向 sizeX=2）。实测字形高度两向均约 2.0，
 * 因此缩放因子统一取 sizeMm / 2.0。
 */
private const val BASE_HEIGHT_HORIZONTAL = 2.0
private const val BASE_HEIGHT_VERTICAL = 2.0

/** 加粗时附加雕刻的微偏移量（取字号的比例，单位毫米） */
private const val BOLD_OFFSET_RATIO = 0.03

/** 匹配字体片段中的 X/Y 坐标，等价于 C# 的正则 `[XY]-?\d+(\.\d+)?`（`\d` 在 JS/Java 里都是 ASCII 数字）。 */
private val COORD_REGEX = Regex("[XY]-?[0-9]+(?:\\.[0-9]+)?")

/** 文本分行，对应 v2 的 `/\r?\n/`（Kotlin 的 `split(Regex)` 同样保留末尾空串）。 */
private val LINE_BREAK_REGEX = Regex("\\r?\\n")

/**
 * 等价于 JS `Math.round`，并且**不做 Int/Long 截断**（坐标可以任意大）：
 * 取最接近的整数；恰好居中时取较大者（朝 +∞）。`Math.round(-2.5) === -2`。
 *
 * 为什么不用 `kotlin.math.round`（HALF_UP 于 .5 远离零）或 `Double.roundToInt()`
 * （.5 语义对，但超出 Int 范围会被截断成 MAX_VALUE，v2 不会）：
 * 这里用 `floor` 分解实现，对任意 double（含 NaN / ±Infinity）都与 JS 一致，
 * 且不受浮点加法 `x + 0.5` 的舍入影响（例如 0.49999999999999994 在 JS 里是 0）。
 */
private fun jsMathRound(value: Double): Double {
    val f = floor(value)
    val diff = value - f
    return if (diff < 0.5) f else f + 1.0
}

/** 与 RasterConverter 一致的数字格式化：保留 3 位小数并去掉多余的 0。 */
private fun fmt(v: Double): String {
    val r = jsMathRound(v * 1000) / 1000
    return jsNumberToString(r)
}

/**
 * 等价于 JS `String.prototype.replace(search, replacement)`（两者都是字符串字面量）：
 *  * 只替换**第一处**匹配 —— Kotlin 的 `String.replace(old, new)` 默认替换全部；
 *  * replacement 里的 `$` 按 JS GetSubstitution 展开：`$$` → `$`、`$&` → 匹配文本、
 *    `` $` `` → 匹配前的前缀、`$'` → 匹配后的后缀；字符串模式没有捕获组，
 *    因此 `$0` / `$1` / `$x` 保持字面量。
 *  `M3` / `M5` 里没有 `$`，但 `laserOn` / `laserOff` 是用户输入，必须与 v2 完全一致。
 */
private fun jsReplaceFirst(source: String, search: String, replacement: String): String {
    val index = source.indexOf(search)
    if (index < 0) return source
    val prefix = source.substring(0, index)
    val suffix = source.substring(index + search.length)
    val sb = StringBuilder(prefix)
    var i = 0
    while (i < replacement.length) {
        val c = replacement[i]
        if (c != '$' || i == replacement.length - 1) {
            sb.append(c)
            i++
            continue
        }
        when (replacement[i + 1]) {
            '$' -> {
                sb.append('$')
                i += 2
            }

            '&' -> {
                sb.append(search)
                i += 2
            }

            '`' -> {
                sb.append(prefix)
                i += 2
            }

            '\'' -> {
                sb.append(suffix)
                i += 2
            }

            else -> {
                sb.append(c)
                i++
            }
        }
    }
    sb.append(suffix)
    return sb.toString()
}

/** 单个字形经缩放与平移后的结果 */
private class GlyphPlacement(
    /** 已缩放、平移的 G 代码片段 */
    val parts: List<String>,
    /** 字形横向尺寸（毫米） */
    val widthMm: Double,
    /** 字形纵向尺寸（毫米） */
    val heightMm: Double
)

/**
 * 对应 C# 的 ApplyOffset：把片段中所有 X/Y 乘以 scale 再平移，
 * 并按原始字体单位统计字形尺寸（尺寸统计发生在平移之前）。
 */
private fun placeGlyph(
    parts: List<String>,
    xOffsetMm: Double,
    yOffsetMm: Double,
    scale: Double
): GlyphPlacement {
    var maxX = 0.0
    var minX = 0.0
    var maxY = 0.0
    var minY = 0.0

    val out = parts.map { part ->
        COORD_REGEX.replace(part) { matched ->
            val text = matched.value
            val axis = text[0]
            val raw = jsParseFloat(text.substring(1))
            if (axis == 'X') {
                if (raw > maxX) maxX = raw
                if (raw < minX) minX = raw
                "X${fmt(raw * scale + xOffsetMm)}"
            } else {
                if (raw > maxY) maxY = raw
                if (raw < minY) minY = raw
                "Y${fmt(raw * scale + yOffsetMm)}"
            }
        }
    }

    return GlyphPlacement(out, (maxX - minX) * scale, (maxY - minY) * scale)
}

/** 把文本转成 G 代码行 */
fun textToGcode(options: HersheyOptions): HersheyResult {
    val horizontal = options.orientation != HersheyOrientation.VERTICAL
    val font = if (horizontal) HERSHEY_HORIZONTAL else HERSHEY_VERTICAL
    val baseHeight = if (horizontal) BASE_HEIGHT_HORIZONTAL else BASE_HEIGHT_VERTICAL
    val scale = if (options.sizeMm > 0) options.sizeMm / baseHeight else 0.0
    val spaceMm = HERSHEY_SPACE_BETWEEN * scale

    val lineSpacing = options.lineSpacing ?: 1.5
    val lineStepMm = options.sizeMm * lineSpacing
    val originX = options.offsetX ?: 0.0
    val originY = options.offsetY ?: 0.0

    val power = options.maxPower ?: 1000.0
    val laserOnRaw = jsTrim(options.laserOn)
    val laserOff = jsTrim(options.laserOff).ifEmpty { "M5" }
    // 开启指令：未指定时用 S 值控制；启用 PWM 时把 S 值跟在开启指令之后
    val onToken = if (laserOnRaw.isNotEmpty()) {
        if (options.pwm) "$laserOnRaw S${jsNumberToString(power)}" else laserOnRaw
    } else {
        "S${jsNumberToString(power)}"
    }

    // 加粗：除原位置外，再沿 +X、+Y 各做一次微偏移雕刻
    val boldDx = options.sizeMm * BOLD_OFFSET_RATIO
    val boldOffsets: List<Pair<Double, Double>> = if (options.bold) {
        listOf(0.0 to 0.0, boldDx to 0.0, 0.0 to boldDx)
    } else {
        listOf(0.0 to 0.0)
    }

    val lines = mutableListOf<String>()
    // 与 C# 一致：先设置功率与进给、保持激光关闭
    lines.add(
        "$laserOff S${jsNumberToString(power)} F${jsNumberToString(options.markSpeed)}"
    )

    val textLines = options.text.split(LINE_BREAK_REGEX)
    var flowMm = 0.0

    for (li in textLines.indices) {
        // v2 用 Array.from(str) 按码点切分，这里按 UTF-16 码元遍历；两者对
        // 「只处理码 32..126」的过滤结果完全相同（非 ASCII / 代理对都会被跳过）。
        val chars = textLines[li]
        var oX = if (horizontal) originX else originX + li * lineStepMm
        var oY = if (horizontal) originY + li * lineStepMm else originY
        var lineFlowMm = 0.0
        var rendered = 0

        for (ch in chars) {
            val code = ch.code
            if (code < 32 || code > 126) continue
            // v2 的 `if (!glyph) continue`：空数组在 JS 里是真值，只有越界才会跳过
            val glyph = font.getOrNull(code - 32) ?: continue

            val placed = placeGlyph(glyph, oX, oY, scale)

            // 行内尺寸统计（对应 MeasureString：字符尺寸 + 字符间 spbwl）
            if (rendered > 0) lineFlowMm += spaceMm
            lineFlowMm += if (horizontal) placed.widthMm else placed.heightMm
            rendered++

            // 每个加粗 pass 单独输出一遍字形（含激光开/关）
            for ((bx, by) in boldOffsets) {
                val pass =
                    if (bx == 0.0 && by == 0.0) placed else placeGlyph(glyph, oX + bx, oY + by, scale)
                for (part in pass.parts) {
                    lines.add(jsReplaceFirst(jsReplaceFirst(part, "M3", onToken), "M5", laserOff))
                }
                lines.add(laserOff)
            }

            // 前进：横向累加字符宽度，纵向累加字符高度（C# 的 oX/oY += maxX/maxY + spbwl）
            if (horizontal) oX += placed.widthMm + spaceMm
            else oY += placed.heightMm + spaceMm
        }

        if (lineFlowMm > flowMm) flowMm = lineFlowMm
    }

    val crossMm = (textLines.size - 1) * lineStepMm + options.sizeMm

    return if (horizontal) {
        HersheyResult(lines, widthMm = flowMm, heightMm = crossMm)
    } else {
        HersheyResult(lines, widthMm = crossMm, heightMm = flowMm)
    }
}
