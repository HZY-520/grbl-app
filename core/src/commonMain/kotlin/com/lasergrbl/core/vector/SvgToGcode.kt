package com.lasergrbl.core.vector

import com.lasergrbl.core.internal.XmlElement
import com.lasergrbl.core.internal.jsToFixed
import com.lasergrbl.core.internal.jsTrim
import com.lasergrbl.core.internal.parseXml
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * SVG 矢量 → G 代码转换 —— 逐行移植自 v2 `src/core/vector/SvgToGcode.ts`。
 *
 * 与 C# 版本（LaserGRBL `SvgConverter/GCodeFromSVG.cs`）的区别（与 v2 一致）：
 *   * 只生成轮廓描边 G 代码，不做填充、不做光栅化；
 *   * 曲线/圆弧在**毫米坐标系**内按 tolerance 自适应离散为折线。
 *
 * 唯一的平台差异：v2 用浏览器 `DOMParser` 解析 SVG，Kotlin 侧走
 * [com.lasergrbl.core.internal.parseXml]（expect/actual 包住 `javax.xml.parsers`）。
 * 出错路径也照抄 v2：解析失败与根元素非 `<svg>` 都**抛异常**（不是返回失败结果），
 * 异常消息与 TS 逐字相同 —— 黄金样本 `svg-to-gcode.json` 的 `errorPaths` 就是这么钉的。
 */

/** SVG → G 代码转换选项（对应 TS `SvgConvertOptions`）。 */
data class SvgConvertOptions(
    /** 目标宽度（毫米），高度按比例自动计算。 */
    val targetWidthMm: Double,
    /** 目标高度（毫米），可选；若提供则以宽高拉伸。 */
    val targetHeightMm: Double? = null,
    /** 曲线离散精度（毫米），越小越平滑，默认 0.1。 */
    val tolerance: Double? = null,
    /** 雕刻进给速度 mm/min。 */
    val markSpeed: Double,
    /** 空移速度 mm/min。 */
    val travelSpeed: Double? = null,
    /** 激光开启指令，如 `M3` / `M4`。 */
    val laserOn: String,
    /** 激光关闭指令，如 `M5`。 */
    val laserOff: String,
    /** 是否使用 S 值 PWM 控制（true 时用 S 值开激光）。 */
    val pwm: Boolean,
    /** 最大功率 S 值，默认 1000。 */
    val maxPower: Double? = null,
    /** 起点偏移（毫米）。 */
    val offsetX: Double? = null,
    val offsetY: Double? = null,
    /** 自定义文件头（多行字符串），默认 `G90`。 */
    val header: String? = null,
    /** 自定义文件尾（多行字符串）。 */
    val footer: String? = null
)

/** SVG → G 代码转换结果（对应 TS `SvgConvertResult`）。 */
data class SvgConvertResult(
    /** 生成的 G 代码行（不含换行符）。 */
    val lines: List<String>,
    /** 实际使用的宽度/高度（毫米）。 */
    val widthMm: Double,
    val heightMm: Double,
    /** 路径数量。 */
    val pathCount: Int,
    /** 总路径长度（毫米）。 */
    val pathLengthMm: Double
)

/**
 * 仿射矩阵 `[a b c d e f]`，对应 SVG `matrix(a,b,c,d,e,f)`：
 * `x' = a*x + c*y + e`，`y' = b*x + d*y + f`。
 */
internal data class Matrix(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double
)

private val IDENTITY = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

/** 需要整体跳过的容器元素。 */
private val SKIP_TAGS = setOf(
    "defs", "clippath", "mask", "pattern", "symbol", "marker",
    "lineargradient", "radialgradient", "filter", "style", "title", "desc", "metadata"
)

/** 支持解析的图形元素。 */
private val SHAPE_TAGS = setOf("path", "rect", "circle", "ellipse", "line", "polyline", "polygon")

/** 贝塞尔递归最大细分次数（与 BezierTools.FlattenTo 一致）。 */
private const val MAX_SUBDIV = 20

/**
 * C# `"0.###"` 格式化（与 `RasterConverter.fmt` 一致）：
 *
 * ```ts
 * const r = Math.round(v * 1000) / 1000
 * return String(r)
 * ```
 *
 * ⚠️ 两个坑，都踩过：
 *   1. `String(r)` 是 JS 的最短可往返表示，所以尾零要去掉、整数不能带 `.0`；
 *   2. **不能直接用 Kotlin 的 `round(v * 1000)`**：`kotlin.math.round` 落到
 *      `Math.rint`，是「并列取偶」，而 JS 的 `Math.round` 是「并列朝 +∞」。
 *      样本里的 `34.0625` 就是反例：JS 得 `34063` → `"34.063"`，`rint` 得 `34062` → `"34.062"`。
 *
 * 正确做法与 `Paths.kt` 的 `fmt` 同源：用 [jsToFixed]（JS `toFixed` 的精确复刻，
 * 对 double 的**精确十进制值**四舍五入、并列朝 +∞）到 3 位小数，再按 `String(r)` 的语义
 * 去掉多余的尾零（与 `jsNumberToString` 的输出去尾零后相同）。
 */
private fun fmt(v: Double): String {
    var s = jsToFixed(v, 3)
    if (s.contains('.')) {
        s = s.trimEnd('0')
        s = s.trimEnd('.')
    }
    if (s == "-0") s = "0"
    return s
}

/** 矩阵乘法：结果表示「先应用 m2，再应用 m1」。 */
private fun mul(m1: Matrix, m2: Matrix): Matrix = Matrix(
    a = m1.a * m2.a + m1.c * m2.b,
    b = m1.b * m2.a + m1.d * m2.b,
    c = m1.a * m2.c + m1.c * m2.d,
    d = m1.b * m2.c + m1.d * m2.d,
    e = m1.a * m2.e + m1.c * m2.f + m1.e,
    f = m1.b * m2.e + m1.d * m2.f + m1.f
)

/** 用矩阵变换点。 */
private fun apply(m: Matrix, x: Double, y: Double): Pt =
    Pt(x = m.a * x + m.c * y + m.e, y = m.b * x + m.d * y + m.f)

/**
 * 等价于 JS `Math.hypot(a, b)`（两参情形）。
 *
 * 实现已统一到 [com.lasergrbl.core.internal.jsHypot]（V8 `FastMathHypot` 快路径的逐行复刻）——
 * 这里保留一个私有别名只是为了不改动本文件里已有的调用点。
 */
private fun jsHypot(a: Double, b: Double): Double = com.lasergrbl.core.internal.jsHypot(a, b)

/** 从字符串中提取所有数字（支持逗号/空格分隔、科学计数法）。 */
private val NUMBER_RE = Regex("[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?")

private fun parseNumbers(str: String?): List<Double> {
    if (str.isNullOrEmpty()) return emptyList()
    return NUMBER_RE.findAll(str).map { it.value.toDouble() }.toList()
}

/**
 * 解析 SVG 长度（px/mm/cm/in/pt/pc/em），返回像素值。
 *
 * 注意与 TS 的 `string | null` 对齐：`null` 与 `""` 都返回 0。属性不存在时用 `""`，
 * 两者在 TS 里同样都走 `if (!str) return 0`，因此无需在 Kotlin 侧区分。
 */
fun parseLength(str: String?): Double {
    if (str.isNullOrEmpty()) return 0.0
    val m = LENGTH_RE.find(str) ?: return 0.0
    val v = m.groupValues[1].toDouble()
    val unit = m.groupValues[2].lowercase()
    return when (unit) {
        "", "px" -> v
        "mm" -> (v * 96) / 25.4
        "cm" -> (v * 96) / 2.54
        "in" -> v * 96
        "pt" -> (v * 96) / 72
        "pc" -> (v * 12 * 96) / 72
        "em" -> v * 16
        else -> v
    }
}

private val LENGTH_RE = Regex(
    "^\\s*([-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?)\\s*([a-zA-Z%]*)\\s*$"
)

/**
 * 解析 viewBox，返回 `[minX, minY, width, height]`；无效时返回 `null`。
 *
 * `!(n[2] > 0)` 就是要连 NaN 一起挡掉，所以这里写成 `!(w > 0.0)` 而不是 `w <= 0.0`。
 */
fun parseViewBox(str: String?): List<Double>? {
    val n = parseNumbers(str)
    if (n.size < 4) return null
    if (!(n[2] > 0.0) || !(n[3] > 0.0)) return null
    return listOf(n[0], n[1], n[2], n[3])
}

/** 由单个变换函数名与参数构造矩阵。 */
private fun transformFromArgs(name: String, a: List<Double>): Matrix = when (name) {
    "translate" -> Matrix(1.0, 0.0, 0.0, 1.0, a.getOrNull(0) ?: 0.0, a.getOrNull(1) ?: 0.0)
    "scale" -> {
        val sx = if (a.isNotEmpty()) a[0] else 1.0
        val sy = if (a.size > 1) a[1] else sx
        Matrix(sx, 0.0, 0.0, sy, 0.0, 0.0)
    }

    "rotate" -> {
        val ang = ((a.getOrNull(0) ?: 0.0) * Math.PI) / 180
        val cosA = cos(ang)
        val sinA = sin(ang)
        val rot = Matrix(cosA, sinA, -sinA, cosA, 0.0, 0.0)
        if (a.size >= 3) {
            val cx = a[1]
            val cy = a[2]
            mul(
                mul(Matrix(1.0, 0.0, 0.0, 1.0, cx, cy), rot),
                Matrix(1.0, 0.0, 0.0, 1.0, -cx, -cy)
            )
        } else {
            rot
        }
    }

    "matrix" -> Matrix(
        a.getOrElse(0) { Double.NaN },
        a.getOrElse(1) { Double.NaN },
        a.getOrElse(2) { Double.NaN },
        a.getOrElse(3) { Double.NaN },
        a.getOrElse(4) { Double.NaN },
        a.getOrElse(5) { Double.NaN }
    )

    "skewX" -> {
        val t = tan(((a.getOrNull(0) ?: 0.0) * Math.PI) / 180)
        Matrix(1.0, 0.0, t, 1.0, 0.0, 0.0)
    }

    "skewY" -> {
        val t = tan(((a.getOrNull(0) ?: 0.0) * Math.PI) / 180)
        Matrix(1.0, t, 0.0, 1.0, 0.0, 0.0)
    }

    else -> IDENTITY
}

private val TRANSFORM_RE = Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)")

/**
 * 解析元素的 `transform` 属性（translate/scale/rotate/matrix/skewX/skewY）。
 * 按 SVG 规范：变换列表从左到右构造矩阵，作用于点时靠后的变换先生效。
 */
private fun parseTransform(str: String?): Matrix {
    if (str.isNullOrEmpty()) return IDENTITY
    var result = IDENTITY
    for (match in TRANSFORM_RE.findAll(str)) {
        val name = match.groupValues[1]
        val args = parseNumbers(match.groupValues[2])
        result = mul(result, transformFromArgs(name, args))
    }
    return result
}

/** 三角形面积（用于判断贝塞尔是否足够平坦）。 */
private fun triArea(a: Pt, b: Pt, c: Pt): Double =
    abs(a.x * b.y + b.x * c.y + c.x * a.y - a.y * b.x - b.y * c.x - c.y * a.x) / 2

private fun isFlat(p0: Pt, p1: Pt, p2: Pt, p3: Pt, tol: Double): Boolean =
    sqrt(triArea(p0, p1, p2)) < tol && sqrt(triArea(p1, p2, p3)) < tol

/** 递归自适应离散三次贝塞尔，追加除起点外的所有点。 */
private fun flattenCubicRec(
    p0: Pt,
    p1: Pt,
    p2: Pt,
    p3: Pt,
    tol: Double,
    depth: Int,
    out: MutableList<Pt>
) {
    if (depth >= MAX_SUBDIV || isFlat(p0, p1, p2, p3, tol)) {
        out.add(p3)
        return
    }
    // De Casteljau 在 t=0.5 处切分
    val p01 = Pt((p0.x + p1.x) / 2, (p0.y + p1.y) / 2)
    val p12 = Pt((p1.x + p2.x) / 2, (p1.y + p2.y) / 2)
    val p23 = Pt((p2.x + p3.x) / 2, (p2.y + p3.y) / 2)
    val p012 = Pt((p01.x + p12.x) / 2, (p01.y + p12.y) / 2)
    val p123 = Pt((p12.x + p23.x) / 2, (p12.y + p23.y) / 2)
    val mid = Pt((p012.x + p123.x) / 2, (p012.y + p123.y) / 2)
    flattenCubicRec(p0, p01, p012, mid, tol, depth + 1, out)
    flattenCubicRec(mid, p123, p23, p3, tol, depth + 1, out)
}

/** 有向夹角（与 C# `CalculateVectorAngle` 一致）。 */
private fun vectorAngle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
    val ta = atan2(uy, ux)
    val tb = atan2(vy, vx)
    if (tb >= ta) return tb - ta
    return Math.PI * 2 - (ta - tb)
}

/**
 * 椭圆弧端点参数化 → 拆成若干 ≤90° 的三次贝塞尔段，逐段回调。
 * 移植自 `GCodeFromSVG.calcArc`（源于 vvvv/SVG `SvgArcSegment.cs`）。
 */
private fun arcToCubics(
    sx: Double,
    sy: Double,
    rx0: Double,
    ry0: Double,
    angleDeg: Double,
    large: Double,
    sweep: Double,
    ex: Double,
    ey: Double,
    emit: (Pt, Pt, Pt, Pt) -> Unit
) {
    val phi = (angleDeg * Math.PI) / 180
    val sinPhi = sin(phi)
    val cosPhi = cos(phi)
    val x1dash = (cosPhi * (sx - ex)) / 2 + (sinPhi * (sy - ey)) / 2
    val y1dash = (-sinPhi * (sx - ex)) / 2 + (cosPhi * (sy - ey)) / 2

    val numerator =
        rx0 * rx0 * ry0 * ry0 - rx0 * rx0 * y1dash * y1dash - ry0 * ry0 * x1dash * x1dash

    var rx = rx0
    var ry = ry0
    val root: Double
    if (numerator < 0) {
        val s = sqrt(1 - numerator / (rx0 * rx0 * ry0 * ry0))
        rx *= s
        ry *= s
        root = 0.0
    } else {
        val sign = if ((large == 1.0 && sweep == 1.0) || (large == 0.0 && sweep == 0.0)) -1.0 else 1.0
        root = sign * sqrt(numerator / (rx0 * rx0 * y1dash * y1dash + ry0 * ry0 * x1dash * x1dash))
    }

    val cxdash = (root * rx * y1dash) / ry
    val cydash = (-root * ry * x1dash) / rx
    val cx = cosPhi * cxdash - sinPhi * cydash + (sx + ex) / 2
    val cy = sinPhi * cxdash + cosPhi * cydash + (sy + ey) / 2

    var theta1 = vectorAngle(1.0, 0.0, (x1dash - cxdash) / rx, (y1dash - cydash) / ry)
    var dtheta = vectorAngle(
        (x1dash - cxdash) / rx,
        (y1dash - cydash) / ry,
        (-x1dash - cxdash) / rx,
        (-y1dash - cydash) / ry
    )
    if (sweep == 0.0 && dtheta > 0) dtheta -= 2 * Math.PI
    else if (sweep == 1.0 && dtheta < 0) dtheta += 2 * Math.PI

    val segments = max(1.0, ceil(abs(dtheta / (Math.PI / 2)))).toInt()
    val delta = dtheta / segments
    val t = ((8.0 / 3) * sin(delta / 4) * sin(delta / 4)) / sin(delta / 2)

    var startX = sx
    var startY = sy
    for (i in 0 until segments) {
        val cosT1 = cos(theta1)
        val sinT1 = sin(theta1)
        val theta2 = theta1 + delta
        val cosT2 = cos(theta2)
        val sinT2 = sin(theta2)

        val endpointX = cosPhi * rx * cosT2 - sinPhi * ry * sinT2 + cx
        val endpointY = sinPhi * rx * cosT2 + cosPhi * ry * sinT2 + cy

        val dx1 = t * (-cosPhi * rx * sinT1 - sinPhi * ry * cosT1)
        val dy1 = t * (-sinPhi * rx * sinT1 + cosPhi * ry * cosT1)
        val dxe = t * (cosPhi * rx * sinT2 + sinPhi * ry * cosT2)
        val dye = t * (sinPhi * rx * sinT2 - cosPhi * ry * cosT2)

        emit(
            Pt(startX, startY),
            Pt(startX + dx1, startY + dy1),
            Pt(endpointX + dxe, endpointY + dye),
            Pt(endpointX, endpointY)
        )

        theta1 = theta2
        startX = endpointX
        startY = endpointY
    }
}

/** 路径命令分组。 */
private class CmdGroup(val cmd: String) {
    val nums = mutableListOf<Double>()
}

private val PATH_TOKEN_RE = Regex(
    "([MmLlHhVvCcSsQqTtAaZz])|([-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?)"
)

/** 把 path 的 `d` 拆成「命令 + 数字列表」。 */
private fun tokenizePath(d: String): List<CmdGroup> {
    val groups = mutableListOf<CmdGroup>()
    var cur: CmdGroup? = null
    for (match in PATH_TOKEN_RE.findAll(d)) {
        val cmd = match.groupValues[1]
        val num = match.groupValues[2]
        if (cmd.isNotEmpty()) {
            cur = CmdGroup(cmd)
            groups.add(cur)
        } else if (num.isNotEmpty() && cur != null) {
            cur.nums.add(num.toDouble())
        }
    }
    return groups
}

/**
 * 解析 path 的 `d` 属性，输出毫米坐标系下的折线子路径。
 * 支持 `M/m L/l H/h V/v C/c S/s Q/q T/t A/a Z/z`。
 */
private fun processPath(d: String, m: Matrix, tol: Double, out: MutableList<List<Pt>>) {
    val groups = tokenizePath(d)
    if (groups.isEmpty()) return

    var cur = Pt(0.0, 0.0)
    var subStart = Pt(0.0, 0.0)
    var currentPts = mutableListOf<Pt>()
    var prevCubicCtrl: Pt? = null
    var prevQuadCtrl: Pt? = null
    var lastCmd = ""
    var firstCmd = true

    // ⚠️ TS 里 `emitCubic` 闭包捕获的是 `currentPts` **变量**（M/Z 会整体替换数组），
    // 所以这里必须让局部函数读外层的 `var currentPts`，不能传引用。
    fun pushPt(p: Pt) {
        currentPts.add(p)
    }

    // 若当前没有活动子路径，则以当前点作为子路径起点
    fun ensureSub() {
        if (currentPts.isEmpty()) {
            currentPts.add(apply(m, cur.x, cur.y))
            subStart = cur
        }
    }

    fun finish() {
        if (currentPts.size >= 2) out.add(currentPts)
        currentPts = mutableListOf()
    }

    // 用户坐标 → 毫米后离散三次贝塞尔
    fun emitCubic(p0: Pt, p1: Pt, p2: Pt, p3: Pt) {
        flattenCubicRec(
            apply(m, p0.x, p0.y),
            apply(m, p1.x, p1.y),
            apply(m, p2.x, p2.y),
            apply(m, p3.x, p3.y),
            tol,
            0,
            currentPts
        )
    }

    // 二次贝塞尔升阶为三次
    fun emitQuad(p0: Pt, c: Pt, p1: Pt) {
        val c1 = Pt(p0.x + (2.0 / 3) * (c.x - p0.x), p0.y + (2.0 / 3) * (c.y - p0.y))
        val c2 = Pt(p1.x + (2.0 / 3) * (c.x - p1.x), p1.y + (2.0 / 3) * (c.y - p1.y))
        emitCubic(p0, c1, c2, p1)
    }

    for (g in groups) {
        val cmd = g.cmd
        val upper = cmd.uppercase()
        val rel = cmd != upper
        val nums = g.nums
        lastCmd = upper

        when (upper) {
            "M" -> {
                var i = 0
                while (i + 1 < nums.size) {
                    var px = nums[i]
                    var py = nums[i + 1]
                    // 路径首个相对 moveto 按绝对坐标处理（SVG 规范）
                    if (rel && !(firstCmd && i == 0)) {
                        px += cur.x
                        py += cur.y
                    }
                    if (i == 0) {
                        finish()
                        cur = Pt(px, py)
                        subStart = cur
                        currentPts = mutableListOf(apply(m, px, py))
                    } else {
                        cur = Pt(px, py)
                        pushPt(apply(m, px, py))
                    }
                    i += 2
                }
                prevCubicCtrl = null
                prevQuadCtrl = null
            }

            "L" -> {
                var i = 0
                while (i + 1 < nums.size) {
                    val px = if (rel) cur.x + nums[i] else nums[i]
                    val py = if (rel) cur.y + nums[i + 1] else nums[i + 1]
                    ensureSub()
                    cur = Pt(px, py)
                    pushPt(apply(m, px, py))
                    i += 2
                }
                prevCubicCtrl = null
                prevQuadCtrl = null
            }

            "H" -> {
                for (n in nums) {
                    val px = if (rel) cur.x + n else n
                    ensureSub()
                    cur = Pt(px, cur.y)
                    pushPt(apply(m, cur.x, cur.y))
                }
                prevCubicCtrl = null
                prevQuadCtrl = null
            }

            "V" -> {
                for (n in nums) {
                    val py = if (rel) cur.y + n else n
                    ensureSub()
                    cur = Pt(cur.x, py)
                    pushPt(apply(m, cur.x, cur.y))
                }
                prevCubicCtrl = null
                prevQuadCtrl = null
            }

            "C" -> {
                var i = 0
                while (i + 5 < nums.size) {
                    val c1 = Pt(
                        if (rel) cur.x + nums[i] else nums[i],
                        if (rel) cur.y + nums[i + 1] else nums[i + 1]
                    )
                    val c2 = Pt(
                        if (rel) cur.x + nums[i + 2] else nums[i + 2],
                        if (rel) cur.y + nums[i + 3] else nums[i + 3]
                    )
                    val p = Pt(
                        if (rel) cur.x + nums[i + 4] else nums[i + 4],
                        if (rel) cur.y + nums[i + 5] else nums[i + 5]
                    )
                    ensureSub()
                    emitCubic(cur, c1, c2, p)
                    prevCubicCtrl = c2
                    prevQuadCtrl = null
                    cur = p
                    i += 6
                }
            }

            "S" -> {
                var i = 0
                while (i + 3 < nums.size) {
                    val c2 = Pt(
                        if (rel) cur.x + nums[i] else nums[i],
                        if (rel) cur.y + nums[i + 1] else nums[i + 1]
                    )
                    val p = Pt(
                        if (rel) cur.x + nums[i + 2] else nums[i + 2],
                        if (rel) cur.y + nums[i + 3] else nums[i + 3]
                    )
                    val useMirror = (lastCmd == "C" || lastCmd == "S") && prevCubicCtrl != null
                    val c1 = if (useMirror) {
                        Pt(2 * cur.x - prevCubicCtrl.x, 2 * cur.y - prevCubicCtrl.y)
                    } else {
                        Pt(cur.x, cur.y)
                    }
                    ensureSub()
                    emitCubic(cur, c1, c2, p)
                    prevCubicCtrl = c2
                    prevQuadCtrl = null
                    cur = p
                    i += 4
                }
            }

            "Q" -> {
                var i = 0
                while (i + 3 < nums.size) {
                    val c = Pt(
                        if (rel) cur.x + nums[i] else nums[i],
                        if (rel) cur.y + nums[i + 1] else nums[i + 1]
                    )
                    val p = Pt(
                        if (rel) cur.x + nums[i + 2] else nums[i + 2],
                        if (rel) cur.y + nums[i + 3] else nums[i + 3]
                    )
                    ensureSub()
                    emitQuad(cur, c, p)
                    prevQuadCtrl = c
                    prevCubicCtrl = null
                    cur = p
                    i += 4
                }
            }

            "T" -> {
                var i = 0
                while (i + 1 < nums.size) {
                    val p = Pt(
                        if (rel) cur.x + nums[i] else nums[i],
                        if (rel) cur.y + nums[i + 1] else nums[i + 1]
                    )
                    val useMirror = (lastCmd == "Q" || lastCmd == "T") && prevQuadCtrl != null
                    val c = if (useMirror) {
                        Pt(2 * cur.x - prevQuadCtrl.x, 2 * cur.y - prevQuadCtrl.y)
                    } else {
                        Pt(cur.x, cur.y)
                    }
                    ensureSub()
                    emitQuad(cur, c, p)
                    prevQuadCtrl = c
                    prevCubicCtrl = null
                    cur = p
                    i += 2
                }
            }

            "A" -> {
                var i = 0
                while (i + 6 < nums.size) {
                    val rx = nums[i]
                    val ry = nums[i + 1]
                    val rot = nums[i + 2]
                    val large = nums[i + 3]
                    val sweep = nums[i + 4]
                    val p = Pt(
                        if (rel) cur.x + nums[i + 5] else nums[i + 5],
                        if (rel) cur.y + nums[i + 6] else nums[i + 6]
                    )
                    ensureSub()
                    if (rx > 0 && ry > 0) {
                        arcToCubics(cur.x, cur.y, rx, ry, rot, large, sweep, p.x, p.y) { a, b, c, d ->
                            emitCubic(a, b, c, d)
                        }
                    } else {
                        // 半径为 0：退化为直线
                        pushPt(apply(m, p.x, p.y))
                    }
                    prevCubicCtrl = null
                    prevQuadCtrl = null
                    cur = p
                    i += 7
                }
            }

            "Z" -> {
                if (currentPts.isNotEmpty()) {
                    val startMm = apply(m, subStart.x, subStart.y)
                    val last = currentPts[currentPts.size - 1]
                    if (jsHypot(startMm.x - last.x, startMm.y - last.y) > 1e-6) pushPt(startMm)
                }
                finish()
                cur = Pt(subStart.x, subStart.y)
                prevCubicCtrl = null
                prevQuadCtrl = null
            }

            else -> Unit
        }
        firstCmd = false
    }
    finish()
}

/** 点到线段的垂直距离（用于共线点合并）。 */
private fun perpendicularDistance(a: Pt, b: Pt, c: Pt): Double {
    val dx = c.x - a.x
    val dy = c.y - a.y
    val len = jsHypot(dx, dy)
    if (len < 1e-9) return jsHypot(b.x - a.x, b.y - a.y)
    return abs((b.x - a.x) * dy - (b.y - a.y) * dx) / len
}

/** 合并共线中间点，压缩 G 代码量。 */
private fun simplify(points: List<Pt>): List<Pt> {
    if (points.size <= 2) return points.toList()
    val out = mutableListOf(points[0])
    for (i in 1 until points.size - 1) {
        val a = out[out.size - 1]
        val b = points[i]
        val c = points[i + 1]
        if (perpendicularDistance(a, b, c) > 0.001) out.add(b)
    }
    out.add(points[points.size - 1])
    return out
}

/** 读取元素数值属性（支持单位）。 */
private fun numAttr(el: XmlElement, name: String): Double = parseLength(el.getAttribute(name))

/** 解析 `points` 属性为坐标点。 */
private fun parsePointsAttr(str: String?): List<Pt> {
    val n = parseNumbers(str)
    val pts = mutableListOf<Pt>()
    var i = 0
    while (i + 1 < n.size) {
        pts.add(Pt(n[i], n[i + 1]))
        i += 2
    }
    return pts
}

/** 把基础图形元素转成等价的 path `d` 字符串。 */
private fun shapeToPathData(el: XmlElement, tag: String): String? = when (tag) {
    "path" -> el.getAttribute("d")

    "rect" -> {
        val x = numAttr(el, "x")
        val y = numAttr(el, "y")
        val w = numAttr(el, "width")
        val h = numAttr(el, "height")
        if (!(w > 0) || !(h > 0)) {
            null
        } else {
            var rx = numAttr(el, "rx")
            var ry = numAttr(el, "ry")
            if (rx <= 0 && ry > 0) rx = ry
            if (ry <= 0 && rx > 0) ry = rx
            rx = min(rx, w / 2)
            ry = min(ry, h / 2)
            if (rx > 0 && ry > 0) {
                "M ${x + rx} $y H ${x + w - rx} " +
                    "A $rx $ry 0 0 1 ${x + w} ${y + ry} " +
                    "V ${y + h - ry} A $rx $ry 0 0 1 ${x + w - rx} ${y + h} " +
                    "H ${x + rx} A $rx $ry 0 0 1 $x ${y + h - ry} " +
                    "V ${y + ry} A $rx $ry 0 0 1 ${x + rx} $y Z"
            } else {
                "M $x $y H ${x + w} V ${y + h} H $x Z"
            }
        }
    }

    "circle" -> {
        val cx = numAttr(el, "cx")
        val cy = numAttr(el, "cy")
        val r = numAttr(el, "r")
        if (!(r > 0)) null
        else "M ${cx - r} $cy A $r $r 0 1 1 ${cx + r} $cy A $r $r 0 1 1 ${cx - r} $cy Z"
    }

    "ellipse" -> {
        val cx = numAttr(el, "cx")
        val cy = numAttr(el, "cy")
        val rx = numAttr(el, "rx")
        val ry = numAttr(el, "ry")
        if (!(rx > 0) || !(ry > 0)) null
        else "M ${cx - rx} $cy A $rx $ry 0 1 1 ${cx + rx} $cy A $rx $ry 0 1 1 ${cx - rx} $cy Z"
    }

    "line" -> "M ${numAttr(el, "x1")} ${numAttr(el, "y1")} L ${numAttr(el, "x2")} ${numAttr(el, "y2")}"

    "polyline", "polygon" -> {
        val pts = parsePointsAttr(el.getAttribute("points"))
        if (pts.size < 2) {
            null
        } else {
            val sb = StringBuilder("M ${pts[0].x} ${pts[0].y}")
            for (i in 1 until pts.size) sb.append(" L ${pts[i].x} ${pts[i].y}")
            if (tag == "polygon") sb.append(" Z")
            sb.toString()
        }
    }

    else -> null
}

/** 递归遍历 DOM，累积父级变换并处理图形元素。 */
private fun walk(el: XmlElement, parentMatrix: Matrix, out: MutableList<List<Pt>>, tol: Double) {
    val children = el.children
    for (i in children.indices) {
        val child = children[i]
        val tag = child.tagName.lowercase()
        if (tag in SKIP_TAGS) continue

        // 元素自身变换叠加在父级之上
        val m = mul(parentMatrix, parseTransform(child.getAttribute("transform")))

        if (tag in SHAPE_TAGS) {
            val d = shapeToPathData(child, tag)
            if (d != null && d.isNotEmpty()) processPath(d, m, tol, out)
        } else {
            // 容器（g / a / 嵌套 svg 等）：继续遍历
            walk(child, m, out, tol)
        }
    }
}

/**
 * 把 SVG 文本转成 G 代码。
 *
 * @throws IllegalStateException 解析失败或缺少 `<svg>` 根元素时（与 v2 抛 `Error` 等价）。
 */
fun convertSvgToGcode(svgText: String, options: SvgConvertOptions): SvgConvertResult {
    val doc = parseXml(svgText)
    if (doc == null || doc.querySelector("parsererror") != null) {
        throw IllegalStateException("SVG 解析失败：文件格式无效")
    }

    val root = doc.documentElement
    if (root == null || root.tagName.lowercase() != "svg") {
        throw IllegalStateException("SVG 解析失败：缺少 <svg> 根元素")
    }

    val tolerance = options.tolerance ?: 0.1
    val maxPower = options.maxPower ?: 1000.0
    val offsetX = options.offsetX ?: 0.0
    val offsetY = options.offsetY ?: 0.0
    val travelSpeed = options.travelSpeed ?: 0.0
    val markSpeed = options.markSpeed

    // ---- 内容尺寸：优先 viewBox，其次 width/height ----
    val vb = parseViewBox(root.getAttribute("viewBox"))
    var minX = 0.0
    var minY = 0.0
    var contentW: Double
    var contentH: Double
    if (vb != null) {
        minX = vb[0]
        minY = vb[1]
        contentW = vb[2]
        contentH = vb[3]
    } else {
        contentW = parseLength(root.getAttribute("width"))
        contentH = parseLength(root.getAttribute("height"))
        if (contentW <= 0 && contentH > 0) contentW = contentH
        if (contentH <= 0 && contentW > 0) contentH = contentW
    }
    if (!(contentW > 0)) contentW = 1.0
    if (!(contentH > 0)) contentH = 1.0

    // ---- 缩放 + Y 轴翻转（SVG Y 向下，机床 Y 向上）----
    val scaleX = options.targetWidthMm / contentW
    val heightMm = options.targetHeightMm ?: (options.targetWidthMm * contentH) / contentW
    val scaleY = heightMm / contentH

    // 用户坐标 → 毫米：全局矩阵，含 viewBox 偏移、缩放、Y 翻转与用户偏移
    val globalMatrix = Matrix(
        a = scaleX,
        b = 0.0,
        c = 0.0,
        d = -scaleY,
        e = offsetX - minX * scaleX,
        f = offsetY + (contentH + minY) * scaleY
    )

    val subpaths = mutableListOf<List<Pt>>()
    walk(root, globalMatrix, subpaths, tolerance)
    val realSubpaths = subpaths.filter { it.size >= 2 }

    // ---- 统计总路径长度 ----
    var pathLengthMm = 0.0
    for (sp in realSubpaths) {
        for (i in 1 until sp.size) {
            pathLengthMm += jsHypot(sp[i].x - sp[i - 1].x, sp[i].y - sp[i - 1].y)
        }
    }

    // ---- 生成 G 代码 ----
    val lines = mutableListOf<String>()

    // 文件头（默认 G90，与 RasterConverter 默认 header 一致）
    val header = if (!options.header.isNullOrEmpty() && jsTrim(options.header).isNotEmpty()) {
        options.header
    } else {
        "G90"
    }
    for (l in header.split('\n')) if (jsTrim(l).isNotEmpty()) lines.add(jsTrim(l))

    var feedV: Double? = null
    val travelCmd = if (travelSpeed > 0) "G1" else "G0"

    fun travelTo(p: Pt): String {
        var s = "$travelCmd X${fmt(p.x)} Y${fmt(p.y)}"
        if (travelSpeed > 0 && feedV != travelSpeed) {
            s += " F${fmt(travelSpeed)}"
            feedV = travelSpeed
        }
        return s
    }

    fun cutTo(p: Pt): String {
        var s = "G1 X${fmt(p.x)} Y${fmt(p.y)}"
        if (feedV != markSpeed) {
            s += " F${fmt(markSpeed)}"
            feedV = markSpeed
        }
        return s
    }

    // 初始定位到起点并设置速度，随后确保激光关闭
    lines.add("$travelCmd X${fmt(offsetX)} Y${fmt(offsetY)} F${fmt(markSpeed)}")
    feedV = markSpeed
    lines.add(if (options.pwm) "${options.laserOn} S0" else options.laserOff)

    for (raw in realSubpaths) {
        val sp = simplify(raw)
        if (sp.size < 2) continue
        lines.add(travelTo(sp[0]))
        lines.add(if (options.pwm) "${options.laserOn} S${fmt(maxPower)}" else options.laserOn)
        for (i in 1 until sp.size) lines.add(cutTo(sp[i]))
        lines.add(options.laserOff)
    }

    // 文件尾
    val footer = options.footer
    if (!footer.isNullOrEmpty() && jsTrim(footer).isNotEmpty()) {
        for (l in footer.split('\n')) if (jsTrim(l).isNotEmpty()) lines.add(jsTrim(l))
    }

    return SvgConvertResult(
        lines = lines,
        widthMm = options.targetWidthMm,
        heightMm = heightMm,
        pathCount = realSubpaths.size,
        pathLengthMm = pathLengthMm
    )
}
