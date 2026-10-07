package com.lasergrbl.core.vector

import com.lasergrbl.core.internal.jsHypot
import com.lasergrbl.core.internal.jsToFixed
import kotlin.math.abs

/**
 * 矢量路径通用类型与工具 —— 逐行移植自 v2 `src/core/vector/Paths.ts`。
 *
 * 坐标系约定：像素坐标，原点在图像左上角，y 轴向下（与 ImageData 一致）。
 */

/** 二维点（像素坐标）。 */
data class Pt(val x: Double, val y: Double)

/** 折线：由若干点构成；`closed = true` 表示首尾闭合。 */
data class Polyline(val pts: List<Pt>, val closed: Boolean? = null)

/**
 * 最近邻贪心排序，减少空移距离；[start] 为起点（像素坐标）。
 * 同时允许对折线做反向接入（选择更近的一端）。返回新数组，不修改入参。
 *
 * ⚠️ 比较用严格小于：**距离并列时保持原顺序且不反向**（与 v2 一致）。
 */
fun optimizeOrder(paths: List<Polyline>, start: Pt? = null): List<Polyline> {
    val valid = mutableListOf<Polyline>()
    val empties = mutableListOf<Polyline>()
    for (p in paths) {
        val copy = Polyline(p.pts.map { Pt(it.x, it.y) }, p.closed)
        if (copy.pts.isNotEmpty()) valid.add(copy) else empties.add(copy)
    }

    val out = mutableListOf<Polyline>()
    var cur: Pt = when {
        start != null -> Pt(start.x, start.y)
        valid.isNotEmpty() -> Pt(valid[0].pts[0].x, valid[0].pts[0].y)
        else -> Pt(0.0, 0.0)
    }

    while (valid.isNotEmpty()) {
        var bestI = -1
        var bestD = Double.POSITIVE_INFINITY
        var bestRev = false
        for (i in valid.indices) {
            val pts = valid[i].pts
            val head = pts[0]
            val tail = pts[pts.size - 1]
            val d0 = jsHypot(head.x - cur.x, head.y - cur.y)
            if (d0 < bestD) {
                bestD = d0
                bestI = i
                bestRev = false
            }
            val d1 = jsHypot(tail.x - cur.x, tail.y - cur.y)
            if (d1 < bestD) {
                bestD = d1
                bestI = i
                bestRev = true
            }
        }
        if (bestI < 0) break
        var pick = valid.removeAt(bestI)
        if (bestRev && pick.pts.size > 1) pick = pick.copy(pts = pick.pts.reversed())
        out.add(pick)
        val last = pick.pts[pick.pts.size - 1]
        cur = Pt(last.x, last.y)
    }

    out.addAll(empties)
    return out
}

/** 点到线段的垂距（a、b 相同时退化为点距）。 */
private fun perpendicularDistance(p: Pt, a: Pt, b: Pt): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = jsHypot(dx, dy)
    if (len < 1e-12) return jsHypot(p.x - a.x, p.y - a.y)
    return abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
}

/** Douglas-Peucker 简化，[tol] 为像素容差（迭代实现，避免深递归）。 */
fun simplifyPath(pts: List<Pt>, tol: Double): List<Pt> {
    val n = pts.size
    if (n <= 2) return pts.toList()
    val keep = BooleanArray(n)
    keep[0] = true
    keep[n - 1] = true
    val stack = mutableListOf<Int>()
    stack.add(0)
    stack.add(n - 1)
    while (stack.size >= 2) {
        val e = stack.removeAt(stack.size - 1)
        val s = stack.removeAt(stack.size - 1)
        if (e <= s + 1) continue
        val a = pts[s]
        val b = pts[e]
        var maxD = -1.0
        var idx = -1
        for (i in (s + 1) until e) {
            val d = perpendicularDistance(pts[i], a, b)
            if (d > maxD) {
                maxD = d
                idx = i
            }
        }
        if (maxD > tol && idx > 0) {
            keep[idx] = true
            stack.add(s)
            stack.add(idx)
            stack.add(idx)
            stack.add(e)
        }
    }
    val out = mutableListOf<Pt>()
    for (i in 0 until n) if (keep[i]) out.add(pts[i])
    return out
}

/** 折线 → G 代码选项。 */
data class PolylineGcodeOptions(
    /** 每个像素对应的毫米数。 */
    val pixelSizeMm: Double,
    val offsetX: Double,
    val offsetY: Double,
    val markSpeed: Double,
    val travelSpeed: Double? = null,
    val minPower: Int,
    val maxPower: Int,
    /** 绘制线条时的 S 值（0..maxPower），默认 maxPower。 */
    val laserPower: Int? = null,
    /** 激光开启/关闭指令，如 `M3`/`M4` 与 `M5`。 */
    val laserOn: String,
    val laserOff: String,
    /** 是否用 S 值调制功率；false 时只用开/关指令。 */
    val pwm: Boolean,
    /** 图像 y 轴向下、G 代码 y 轴向上；true 时翻转。 */
    val flipY: Boolean = false,
    val header: String? = null,
    val footer: String? = null,
    /** 小数位数，默认 3。 */
    val decimals: Int? = null,
    /** 是否对路径做最近邻排序，默认 true。 */
    val optimize: Boolean? = null,
    /** 空移指令，默认 `G0`。 */
    val travelCommand: String? = null
)

data class PolylineGcodeResult(
    val lines: List<String>,
    val pathCount: Int,
    val lengthMm: Double
)

/** 数字格式化：最多 [decimals] 位小数，去掉多余的尾零（对齐 C# `"0.###"`）。 */
internal fun fmt(v0: Double, decimals: Int): String {
    val v = if (v0.isFinite()) v0 else 0.0
    var s = jsToFixed(v, decimals)
    if (s.contains('.')) {
        s = s.trimEnd('0')
        s = s.trimEnd('.')
    }
    if (s == "-0") s = "0"
    return s
}

/** 把折线集合输出为 G 代码（像素坐标 → 毫米），含 header/footer、G0 空移、G1 雕刻、激光开/关。 */
fun polylinesToGcode(paths: List<Polyline>, o: PolylineGcodeOptions): PolylineGcodeResult {
    val decimals = o.decimals ?: 3
    val optimize = o.optimize ?: true
    val travelCommand = o.travelCommand ?: "G0"
    val flipY = o.flipY
    val laserPower = o.laserPower ?: o.maxPower
    val sc = o.pixelSizeMm

    val lines = mutableListOf<String>()
    val header = o.header
    if (header != null && header.isNotEmpty()) {
        for (l in header.split('\n')) if (l.trim().isNotEmpty()) lines.add(l.trim())
    }

    val ordered = if (optimize) optimizeOrder(paths) else paths

    fun toMm(p: Pt): Pt = Pt(
        x = p.x * sc + o.offsetX,
        y = (if (flipY) -p.y else p.y) * sc + o.offsetY
    )

    var lengthMm = 0.0
    var pathCount = 0
    var curFeed: Double? = null

    for (poly in ordered) {
        if (poly.pts.size < 2) continue
        // 毫米坐标 + 去除连续重复点，避免输出零长度 G1 指令
        val mm = mutableListOf<Pt>()
        for (p in poly.pts) {
            val q = toMm(p)
            val last = mm.lastOrNull()
            if (last != null && abs(last.x - q.x) < 1e-6 && abs(last.y - q.y) < 1e-6) continue
            mm.add(q)
        }
        if (mm.size < 2) continue
        pathCount++

        for (i in 1 until mm.size) {
            lengthMm += jsHypot(mm[i].x - mm[i - 1].x, mm[i].y - mm[i - 1].y)
        }

        // 空移到起点
        var travel = "$travelCommand X${fmt(mm[0].x, decimals)} Y${fmt(mm[0].y, decimals)}"
        val ts = o.travelSpeed
        if (ts != null && ts != 0.0 && curFeed != ts) {
            travel += " F${fmt(ts, decimals)}"
            curFeed = ts
        }
        lines.add(travel)

        // 开激光（v2 是 `Math.round(laserPower)`，而 laserPower 已是整数）
        lines.add(if (o.pwm) "${o.laserOn} S$laserPower" else o.laserOn)

        // 雕刻移动
        for (i in 1 until mm.size) {
            var move = "G1 X${fmt(mm[i].x, decimals)} Y${fmt(mm[i].y, decimals)}"
            if (curFeed != o.markSpeed) {
                move += " F${fmt(o.markSpeed, decimals)}"
                curFeed = o.markSpeed
            }
            lines.add(move)
        }

        // 关激光
        lines.add(o.laserOff)
    }

    val footer = o.footer
    if (footer != null && footer.isNotEmpty()) {
        for (l in footer.split('\n')) if (l.trim().isNotEmpty()) lines.add(l.trim())
    }

    return PolylineGcodeResult(lines, pathCount, lengthMm)
}
