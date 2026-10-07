package com.lasergrbl.core.vector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Potrace 位图轮廓矢量化（轮廓/描边模式）—— 逐行移植自 v2 `src/core/vector/Potrace.ts`
 * （其本身忠实移植自 LaserGRBL 的 CsPotrace，即 Peter Selinger / Wolfgang Nagl 的 C# 版 Potrace）。
 *
 * 完整实现了原始管线：
 *   1. 二值化（按 R+G+B 均值与阈值比较，alpha < 128 视为背景）
 *   2. 位图 → 路径分解（findNext / findPath / xorPath，XOR 分解，minority 转向策略）
 *   3. calcSums（前缀和）→ calcLon（最优直线段）→ bestPolygon（最优多边形）
 *   4. adjustVertices（顶点调整 / 二次型最小化）
 *   5. smooth（平滑与拐角分析）→ optiCurve（曲线优化）
 *   6. 曲线 → 折线：贝塞尔按 flattenTolerance 自适应离散
 *
 * 与 C# 差异：无全局静态状态（每次调用独立）；不再输出 Curve 对象，直接输出折线。
 * 输出的像素坐标为「像素角坐标」，y 轴向下，与 C# 版本一致。
 *
 * ⚠️ 必须原样复刻的 JS 语义（黄金样本 `golden/potrace.json` 全部钉住）：
 *  1. `tdiv` 是 `Math.trunc`（**朝零**截断），Kotlin 用整数除法 / `Double.toInt()` 实现，**不能**用 `floorDiv`；
 *  2. `calcLon` 里 TS 把 C# 的 `(int)(a / -b)` 写成了 `j = a / -b`（**没有取整**），
 *     于是 `pivk[i] = mod(k1 + j, n)` 是「先在 Double 上取模、再按 Int32Array 赋值朝零截断」，
 *     所以这里必须用 Double 版 `mod`（`mod(a: Double, n: Int)`），否则 pivk 会差 1；
 *  3. `Int32Array` / `Int8Array` 的写入是 ToInt32 / ToInt8（朝零截断 + 回绕），
 *     本移植用 `IntArray` 承载（写入值域很小，回绕不会发生）；
 *  4. `Float64Array` 一律用 `DoubleArray`；`Math.sqrt` 是 IEEE-754 正确舍入，与 Kotlin `sqrt` 逐位一致；
 *     `COS179 = Math.cos((179 * PI) / 180)` 与 JVM `cos` 实测逐位相同（0xBFEFFEC097F5AF8A）。
 */

/**
 * 输入位图（RGBA，ImageData 布局）。
 *
 * v2 的 `PotraceImage` 是 `{ data: Uint8ClampedArray, width, height }`，`data` 是 RGBA 字节缓冲
 * （长度 `width * height * 4`，顺序 R,G,B,A）。Kotlin 用 `IntArray` 承载同一份数据：
 * **每个元素 0..255**，未做任何预乘/夹取，与 `Uint8ClampedArray` 的取值一致。
 */
interface PotraceImage {
    val data: IntArray
    val width: Int
    val height: Int
}

/**
 * Potrace 选项。
 *
 * v2 的字段全部可选（`??` 兜底），这里的默认值就是那些兜底值，语义完全一致。
 * 数值字段对应 v2 的 `number`（Double），因为管线内部按浮点比较/运算（如 `area > turdsize`）。
 */
data class PotraceOptions(
    /** 二值化阈值 0..255，默认 128（原项目 Treshold 是 0..1 的 0.45）。 */
    val threshold: Double = 128.0,
    /** 去除小于该像素面积的斑点，默认 2（原 turdsize）。 */
    val turdSize: Double = 2.0,
    /** 曲线圆角阈值，默认 1.0（原 alphamax）。 */
    val alphaMax: Double = 1.0,
    /** 曲线优化容差，默认 0.2（原 opttolerance）。 */
    val optTolerance: Double = 0.2,
    /** 是否做曲线优化，默认 true（原 curveoptimizing）。 */
    val curveOptimizing: Boolean = true,
    /** 反相（白底黑线时用），默认 false。 */
    val invert: Boolean = false,
    /** 贝塞尔离散容差（像素），默认 0.2。 */
    val flattenTolerance: Double = 0.2
)

// ---------------------------------------------------------------------------
// 常量（与 C# 一致）
// ---------------------------------------------------------------------------
private const val POTRACE_CORNER = 1
private const val POTRACE_CURVETO = 2

/** `Math.cos((179 * Math.PI) / 180)`：与原实现保持一致的常量（与 JVM cos 逐位相同）。 */
private val COS179 = cos((179 * PI) / 180)

// ---------------------------------------------------------------------------
// 内部数据类型
// ---------------------------------------------------------------------------

/** 二维点（可变，等价于 v2 的 `IPt` 对象；输出时才转成 [Pt]）。 */
private class IPt(var x: Double, var y: Double)

private class Sum(
    val x: Double,
    val y: Double,
    val xy: Double,
    val x2: Double,
    val y2: Double
)

/** 二值位图；v2 的 `data` 是 `Uint8Array`（只存 0/1），这里用 `IntArray` 存 0/1。 */
private class Bm(val w: Int, val h: Int, val data: IntArray)

private class PrivCurve(val n: Int) {
    val tag = IntArray(n)
    val vertex = arrayOfNulls<IPt>(n)
    val c = arrayOfNulls<IPt>(n * 3)
    val alpha = DoubleArray(n)
    val alpha0 = DoubleArray(n)
    val beta = DoubleArray(n)
    var alphacurve = 0
}

private class PPath {
    var area = 0.0
    var len = 0
    var sign = ""
    val pt = ArrayList<IPt>()
    var minX = 100000.0
    var minY = 100000.0
    var maxX = -1.0
    var maxY = -1.0
    var x0 = 0.0
    var y0 = 0.0
    var m = 0
    var po: IntArray? = null
    var lon: IntArray? = null
    val sums = ArrayList<Sum>()
    var curve: PrivCurve? = null
}

private class Opti {
    var pen = 0.0
    val c = arrayOf(IPt(0.0, 0.0), IPt(0.0, 0.0))
    var t = 0.0
    var s = 0.0
    var alpha = 0.0
}

private fun newOpti(): Opti = Opti()

/** `{ ...p }`（v2 里的对象展开拷贝）。 */
private fun copyOf(p: IPt): IPt = IPt(p.x, p.y)

// ---------------------------------------------------------------------------
// 基础数学辅助（逐行移植）
// ---------------------------------------------------------------------------

private fun sign(i: Double): Int = if (i > 0) 1 else if (i < 0) -1 else 0

/** `Math.trunc(a / b)`：朝零截断（**不是** floorDiv）。 */
private fun tdiv(a: Double, b: Double): Int = (a / b).toInt()

/** 整数版 `Math.trunc(a / b)`；Kotlin 的 Int 除法同样是朝零截断。 */
private fun tdiv(a: Int, b: Int): Int = a / b

/** JS `a >= n ? a % n : a >= 0 ? a : n - 1 - ((-1 - a) % n)`（Int 版）。 */
private fun mod(a: Int, n: Int): Int =
    if (a >= n) a % n else if (a >= 0) a else n - 1 - ((-1 - a) % n)

/**
 * 同上，但 `a` 是浮点：`calcLon` 里 `j = a / -b` 在 TS 中未取整，
 * `mod(k1 + j, n)` 必须先按 Double 取模，写回 Int32Array 时才朝零截断。
 */
private fun mod(a: Double, n: Int): Double =
    if (a >= n) a % n else if (a >= 0) a else n - 1 - ((-1 - a) % n)

/** range over the straight line segment [a,b] when lambda ranges over [0,1] */
private fun interval(lambda: Double, a: IPt, b: IPt): IPt =
    IPt(a.x + lambda * (b.x - a.x), a.y + lambda * (b.y - a.y))

/** 90 度逆时针方向，但限制到主轴方向之一 */
private fun dorthInfty(p0: IPt, p2: IPt): IPt =
    IPt(-sign(p2.y - p0.y).toDouble(), sign(p2.x - p0.x).toDouble())

private fun ddenom(p0: IPt, p2: IPt): Double {
    val r = dorthInfty(p0, p2)
    return r.y * (p2.x - p0.x) - r.x * (p2.y - p0.y)
}

private fun dpara(p0: IPt, p1: IPt, p2: IPt): Double {
    val x1 = p1.x - p0.x
    val y1 = p1.y - p0.y
    val x2 = p2.x - p0.x
    val y2 = p2.y - p0.y
    return x1 * y2 - x2 * y1
}

private fun cprod(p0: IPt, p1: IPt, p2: IPt, p3: IPt): Double {
    val x1 = p1.x - p0.x
    val y1 = p1.y - p0.y
    val x2 = p3.x - p2.x
    val y2 = p3.y - p2.y
    return x1 * y2 - x2 * y1
}

private fun iprod(p0: IPt, p1: IPt, p2: IPt): Double {
    val x1 = p1.x - p0.x
    val y1 = p1.y - p0.y
    val x2 = p2.x - p0.x
    val y2 = p2.y - p0.y
    return x1 * x2 + y1 * y2
}

private fun iprod1(p0: IPt, p1: IPt, p2: IPt, p3: IPt): Double {
    val x1 = p1.x - p0.x
    val y1 = p1.y - p0.y
    val x2 = p3.x - p2.x
    val y2 = p3.y - p2.y
    return x1 * x2 + y1 * y2
}

private fun ddist(p: IPt, q: IPt): Double =
    sqrt((p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y))

private fun xprodi(p1: IPt, p2: IPt): Double = p1.x * p2.y - p1.y * p2.x

/** 循环意义下 a <= b < c < a（mod n） */
private fun cyclic(a: Int, b: Int, c: Int): Boolean {
    if (a <= c) return a <= b && b < c
    return a <= b || b < c
}

/** 二次型 Q 作用于向量 w=（w.x,w.y） */
private fun quadform(Q: DoubleArray, w: IPt): Double {
    val v = doubleArrayOf(w.x, w.y, 1.0)
    var sum = 0.0
    for (i in 0 until 3) for (j in 0 until 3) sum += v[i] * Q[i * 3 + j] * v[j]
    return sum
}

/** 贝塞尔曲线取点 */
private fun bezier(t: Double, p0: IPt, p1: IPt, p2: IPt, p3: IPt): IPt {
    val s = 1 - t
    return IPt(
        s * s * s * p0.x + 3 * (s * s * t) * p1.x + 3 * (t * t * s) * p2.x + t * t * t * p3.x,
        s * s * s * p0.y + 3 * (s * s * t) * p1.y + 3 * (t * t * s) * p2.y + t * t * t * p3.y
    )
}

/** 求贝塞尔 (p0,p1,p2,p3) 上与直线 q0-q1 相切的参数 t∈[0,1]，无解返回 -1 */
private fun tangent(p0: IPt, p1: IPt, p2: IPt, p3: IPt, q0: IPt, q1: IPt): Double {
    val A = cprod(p0, p1, q0, q1)
    val B = cprod(p1, p2, q0, q1)
    val C = cprod(p2, p3, q0, q1)
    val a = A - 2 * B + C
    val b = -2 * A + 2 * B
    val c = A
    val d = b * b - 4 * a * c
    if (a == 0.0 || d < 0) return -1.0
    val s = sqrt(d)
    val r1 = (-b + s) / (2 * a)
    val r2 = (-b - s) / (2 * a)
    if (r1 >= 0 && r1 <= 1) return r1
    if (r2 >= 0 && r2 <= 1) return r2
    return -1.0
}

/** 计算线段 i..j 的中心与斜率（需要 sums 已计算） */
private fun pointslope(path: PPath, i0: Int, j0: Int, ctr: IPt, dir: IPt) {
    val n = path.len
    val sums = path.sums
    var r = 0
    var i = i0
    var j = j0
    while (j >= n) {
        j -= n
        r += 1
    }
    while (i >= n) {
        i -= n
        r -= 1
    }
    while (j < 0) {
        j += n
        r -= 1
    }
    while (i < 0) {
        i += n
        r += 1
    }

    val x = sums[j + 1].x - sums[i].x + r * sums[n].x
    val y = sums[j + 1].y - sums[i].y + r * sums[n].y
    val x2 = sums[j + 1].x2 - sums[i].x2 + r * sums[n].x2
    val xy = sums[j + 1].xy - sums[i].xy + r * sums[n].xy
    val y2 = sums[j + 1].y2 - sums[i].y2 + r * sums[n].y2
    val k = j + 1 - i + r * n

    ctr.x = x / k
    ctr.y = y / k

    var a = (x2 - (x * x) / k) / k
    val b = (xy - (x * y) / k) / k
    var c = (y2 - (y * y) / k) / k

    val lambda2 = (a + c + sqrt((a - c) * (a - c) + 4 * b * b)) / 2
    a -= lambda2
    c -= lambda2

    var l: Double
    if (abs(a) >= abs(c)) {
        l = sqrt(a * a + b * b)
        if (l != 0.0) {
            dir.x = -b / l
            dir.y = a / l
        }
    } else {
        l = sqrt(c * c + b * b)
        if (l != 0.0) {
            dir.x = -c / l
            dir.y = b / l
        }
    }
    if (l == 0.0) {
        dir.x = 0.0
        dir.y = 0.0
    }
}

// ---------------------------------------------------------------------------
// 位图辅助
// ---------------------------------------------------------------------------

/**
 * v2 的 `bmAt` 直接拿 number 当下标（`bm.data[bm.w * y + x]`）。
 * 坐标在整个管线里都是整数值，取整不会改变结果；越界判断仍按浮点比较（与 JS 的短路一致）。
 */
private fun bmAt(bm: Bm, x: Double, y: Double): Boolean =
    x >= 0 && x < bm.w && y >= 0 && y < bm.h && bm.data[bm.w * y.toInt() + x.toInt()] == 1

private fun bmFlip(bm: Bm, x: Double, y: Double) {
    if (bmAt(bm, x, y)) bm.data[bm.w * y.toInt() + x.toInt()] = 0
    else bm.data[bm.w * y.toInt() + x.toInt()] = 1
}

/** 二值化：R+G+B 均值 < 阈值 视为前景（黑）；alpha<128 记为背景 */
private fun binarize(image: PotraceImage, threshold: Double, invert: Boolean): Bm {
    val w = image.width
    val h = image.height
    val src = image.data
    val out = IntArray(w * h)
    val n = w * h
    for (i in 0 until n) {
        val a = src[4 * i + 3]
        if (a < 128) {
            out[i] = 0
            continue
        }
        val sum = src[4 * i] + src[4 * i + 1] + src[4 * i + 2]
        val black = sum < threshold * 3
        out[i] = if (black) 1 else 0
    }
    if (invert) {
        for (i in 0 until n) out[i] = if (out[i] != 0) 0 else 1
    }
    return Bm(w, h, out)
}

/** 多数转向策略：以 (x,y) 为中心逐层检测 */
private fun majority(bm1: Bm, x: Double, y: Double): Boolean {
    for (i in 2 until 5) {
        var ct = 0
        for (a in -i + 1..i - 1) {
            ct += if (bmAt(bm1, x + a, y + i - 1)) 1 else -1
            ct += if (bmAt(bm1, x + i - 1, y + a - 1)) 1 else -1
            ct += if (bmAt(bm1, x + a - 1, y - i)) 1 else -1
            ct += if (bmAt(bm1, x - i, y + a)) 1 else -1
        }
        if (ct > 0) return true
        if (ct < 0) return false
    }
    return false
}

/** 从 from 起向后找第一个前景像素 */
private fun findNext(bm1: Bm, from: IPt): IPt? {
    var i = bm1.w * from.y.toInt() + from.x.toInt()
    val n = bm1.data.size
    while (i < n && bm1.data[i] != 1) i++
    if (i >= n) return null
    val y = i / bm1.w
    return IPt((i - y * bm1.w).toDouble(), y.toDouble())
}

/** 沿位图边界追踪一条路径 */
private fun findPath(bm0: Bm, bm1: Bm, point: IPt): PPath {
    val path = PPath()
    var x = point.x
    var y = point.y
    var dirx = 0.0
    var diry = 1.0
    var tmp = -1.0

    path.sign = if (bmAt(bm0, point.x, point.y)) "+" else "-"

    while (true) {
        path.pt.add(IPt(x, y))
        if (x > path.maxX) path.maxX = x
        if (x < path.minX) path.minX = x
        if (y > path.maxY) path.maxY = y
        if (y < path.minY) path.minY = y
        path.len++

        x += dirx
        y += diry
        path.area -= x * diry

        if (x == point.x && y == point.y) break

        val l = bmAt(bm1, x + tdiv(dirx + diry - 1, 2.0), y + tdiv(diry - dirx - 1, 2.0))
        val r = bmAt(bm1, x + tdiv(dirx - diry - 1, 2.0), y + tdiv(diry + dirx - 1, 2.0))

        if (r && !l) {
            // minority 转向策略
            if (!majority(bm1, x, y)) {
                tmp = dirx
                dirx = -diry
                diry = tmp
            } else {
                tmp = dirx
                dirx = diry
                diry = -tmp
            }
        } else if (r) {
            tmp = dirx
            dirx = -diry
            diry = tmp
        } else if (!l) {
            tmp = dirx
            dirx = diry
            diry = -tmp
        }
    }
    return path
}

/** 沿路径 XOR 翻转像素（把已追踪区域从工作位图中移除） */
private fun xorPath(bm1: Bm, path: PPath) {
    var y1 = path.pt[0].y
    val len = path.len
    for (i in 1 until len) {
        val x = path.pt[i].x
        val y = path.pt[i].y
        if (y != y1) {
            val minY = if (y1 < y) y1 else y
            val maxX = path.maxX
            var j = x
            while (j < maxX) {
                bmFlip(bm1, j, minY)
                j += 1.0
            }
            y1 = y
        }
    }
}

/** 位图 → 路径列表（XOR 分解） */
private fun bmToPathlist(bm: Bm, turdsize: Double): MutableList<PPath> {
    val bm1 = Bm(bm.w, bm.h, bm.data.copyOf())
    val list = ArrayList<PPath>()
    var found = findNext(bm1, IPt(0.0, 0.0))
    while (found != null) {
        val path = findPath(bm, bm1, found)
        xorPath(bm1, path)
        if (path.area > turdsize) list.add(path)
        found = findNext(bm1, found)
    }
    return list
}

// ---------------------------------------------------------------------------
// Stage 2: 最优多边形
// ---------------------------------------------------------------------------
private fun calcSums(path: PPath) {
    path.x0 = path.pt[0].x
    path.y0 = path.pt[0].y
    val s: MutableList<Sum> = path.sums
    s.add(Sum(0.0, 0.0, 0.0, 0.0, 0.0))
    for (i in 0 until path.len) {
        val x = path.pt[i].x - path.x0
        val y = path.pt[i].y - path.y0
        s.add(
            Sum(
                s[i].x + x,
                s[i].y + y,
                s[i].xy + x * y,
                s[i].x2 + x * x,
                s[i].y2 + y * y
            )
        )
    }
}

/** 计算 i→j 边的代价（需要 lon 与 sums） */
private fun penalty3(path: PPath, i: Int, j0: Int): Double {
    val n = path.len
    val pt = path.pt
    val sums = path.sums
    var r = 0
    var j = j0
    if (j >= n) {
        j -= n
        r = 1
    }
    val x: Double
    val y: Double
    val x2: Double
    val xy: Double
    val y2: Double
    val k: Int
    if (r == 0) {
        x = sums[j + 1].x - sums[i].x
        y = sums[j + 1].y - sums[i].y
        x2 = sums[j + 1].x2 - sums[i].x2
        xy = sums[j + 1].xy - sums[i].xy
        y2 = sums[j + 1].y2 - sums[i].y2
        k = j + 1 - i
    } else {
        x = sums[j + 1].x - sums[i].x + sums[n].x
        y = sums[j + 1].y - sums[i].y + sums[n].y
        x2 = sums[j + 1].x2 - sums[i].x2 + sums[n].x2
        xy = sums[j + 1].xy - sums[i].xy + sums[n].xy
        y2 = sums[j + 1].y2 - sums[i].y2 + sums[n].y2
        k = j + 1 - i + n
    }

    val px = (pt[i].x + pt[j].x) / 2.0 - pt[0].x
    val py = (pt[i].y + pt[j].y) / 2.0 - pt[0].y
    val ey = pt[j].x - pt[i].x
    val ex = -(pt[j].y - pt[i].y)

    val a = (x2 - (2 * x * px) / k) / k + px * px
    val b = (xy - (x * py) / k - (y * px) / k) / k + px * py
    val c = (y2 - (2 * y * py) / k) / k + py * py

    val s = ex * ex * a + 2 * ex * ey * b + ey * ey * c
    return sqrt(s)
}

/** 为每个点计算最远的、可由直线连接的枢轴点 */
private fun calcLon(path: PPath) {
    val n = path.len
    val pt = path.pt
    val pivk = IntArray(n)
    val nc = IntArray(n)
    val ct = IntArray(4)
    val lon = IntArray(n)
    path.lon = lon

    val constraint = arrayOf(IPt(0.0, 0.0), IPt(0.0, 0.0))
    val cur = IPt(0.0, 0.0)
    val off = IPt(0.0, 0.0)
    val dk = IPt(0.0, 0.0)
    var foundk: Int
    var j: Double
    var k1: Int
    var a: Double
    var b: Double
    var c: Double
    var d: Double

    var k = 0
    for (i in n - 1 downTo 0) {
        if (pt[i].x != pt[k].x && pt[i].y != pt[k].y) k = i + 1
        nc[i] = k
    }

    for (i in n - 1 downTo 0) {
        ct[0] = 0
        ct[1] = 0
        ct[2] = 0
        ct[3] = 0
        var dir = tdiv(3 + 3 * (pt[mod(i + 1, n)].x - pt[i].x) + (pt[mod(i + 1, n)].y - pt[i].y), 2.0)
        ct[dir]++

        constraint[0].x = 0.0
        constraint[0].y = 0.0
        constraint[1].x = 0.0
        constraint[1].y = 0.0

        k = nc[i]
        k1 = i
        while (true) {
            foundk = 0
            dir = tdiv(3 + 3 * sign(pt[k].x - pt[k1].x) + sign(pt[k].y - pt[k1].y), 2)
            ct[dir]++

            if (ct[0] == 1 && ct[1] == 1 && ct[2] == 1 && ct[3] == 1) {
                pivk[i] = k1
                foundk = 1
                break
            }

            cur.x = pt[k].x - pt[i].x
            cur.y = pt[k].y - pt[i].y

            if (xprodi(constraint[0], cur) < 0 || xprodi(constraint[1], cur) > 0) break

            if (!(abs(cur.x) <= 1 && abs(cur.y) <= 1)) {
                off.x = cur.x + (if (cur.y >= 0 && (cur.y > 0 || cur.x < 0)) 1.0 else -1.0)
                off.y = cur.y + (if (cur.x <= 0 && (cur.x < 0 || cur.y < 0)) 1.0 else -1.0)
                if (xprodi(constraint[0], off) >= 0) {
                    constraint[0].x = off.x
                    constraint[0].y = off.y
                }
                off.x = cur.x + (if (cur.y <= 0 && (cur.y < 0 || cur.x < 0)) 1.0 else -1.0)
                off.y = cur.y + (if (cur.x >= 0 && (cur.x > 0 || cur.y < 0)) 1.0 else -1.0)
                if (xprodi(constraint[1], off) <= 0) {
                    constraint[1].x = off.x
                    constraint[1].y = off.y
                }
            }
            k1 = k
            k = nc[k1]
            if (!cyclic(k, i, k1)) break
        }
        if (foundk == 0) {
            dk.x = sign(pt[k].x - pt[k1].x).toDouble()
            dk.y = sign(pt[k].y - pt[k1].y).toDouble()
            cur.x = pt[k1].x - pt[i].x
            cur.y = pt[k1].y - pt[i].y

            a = xprodi(constraint[0], cur)
            b = xprodi(constraint[0], dk)
            c = xprodi(constraint[1], cur)
            d = xprodi(constraint[1], dk)

            j = 10000000.0
            if (b < 0) j = a / -b
            if (d > 0) j = min(j, -c / d)
            pivk[i] = mod(k1 + j, n).toInt()
        }
    }

    j = pivk[n - 1].toDouble()
    lon[n - 1] = j.toInt()
    for (i in n - 2 downTo 0) {
        if (cyclic(i + 1, pivk[i], j.toInt())) j = pivk[i].toDouble()
        lon[i] = j.toInt()
    }

    var idx = n - 1
    while (cyclic(mod(idx + 1, n), j.toInt(), lon[idx])) {
        lon[idx] = j.toInt()
        idx--
    }
}

/** 求最优多边形（填充 path.m 与 path.po） */
private fun bestPolygon(path: PPath) {
    var thispen: Double
    var best: Double
    var i: Int
    var j: Int
    var m: Int
    val n = path.len
    var c: Int
    val clip0 = IntArray(n)
    val pen = DoubleArray(n + 1)
    val prev = IntArray(n + 1)
    val clip1 = IntArray(n + 1)
    val seg0 = IntArray(n + 1)
    val seg1 = IntArray(n + 1)
    val lon = path.lon!!

    for (i0 in 0 until n) {
        c = mod(lon[mod(i0 - 1, n)] - 1, n)
        if (c == i0) c = mod(i0 + 1, n)
        if (c < i0) clip0[i0] = n
        else clip0[i0] = c
    }

    j = 1
    for (i0 in 0 until n) {
        while (j <= clip0[i0]) {
            clip1[j] = i0
            j++
        }
    }

    i = 0
    j = 0
    while (i < n) {
        seg0[j] = i
        i = clip0[i]
        j++
    }
    seg0[j] = n
    m = j

    i = n
    j = m
    while (j > 0) {
        seg1[j] = i
        i = clip1[i]
        j--
    }
    seg1[0] = 0

    pen[0] = 0.0
    for (j0 in 1..m) {
        for (i0 in seg1[j0]..seg0[j0]) {
            best = -1.0
            var k = seg0[j0 - 1]
            while (k >= clip1[i0]) {
                thispen = penalty3(path, k, i0) + pen[k]
                if (best < 0 || thispen < best) {
                    prev[i0] = k
                    best = thispen
                }
                k--
            }
            pen[i0] = best
        }
    }
    path.m = m
    val po = IntArray(m)
    path.po = po
    i = n
    j = m - 1
    while (i > 0) {
        i = prev[i]
        po[j] = i
        j--
    }
}

// ---------------------------------------------------------------------------
// Stage 3: 顶点调整
// ---------------------------------------------------------------------------
private fun adjustVertices(path: PPath) {
    val m = path.m
    val po = path.po!!
    val pt = path.pt
    val x0 = path.x0
    val y0 = path.y0
    val ctr = arrayOfNulls<IPt>(m)
    val dir = arrayOfNulls<IPt>(m)
    val q = Array(m) { DoubleArray(9) }
    var i: Int
    var j: Int
    var k: Int
    var l: Int
    val v = DoubleArray(3)

    val s = IPt(0.0, 0.0)
    val curve = PrivCurve(m)
    path.curve = curve

    for (i0 in 0 until m) {
        j = po[mod(i0 + 1, m)]
        j = mod(j - po[i0], path.len) + po[i0]
        ctr[i0] = IPt(0.0, 0.0)
        dir[i0] = IPt(0.0, 0.0)
        pointslope(path, po[i0], j, ctr[i0]!!, dir[i0]!!)
    }

    for (i0 in 0 until m) {
        val d = dir[i0]!!.x * dir[i0]!!.x + dir[i0]!!.y * dir[i0]!!.y
        if (d == 0.0) {
            q[i0].fill(0.0)
        } else {
            v[0] = dir[i0]!!.y
            v[1] = -dir[i0]!!.x
            v[2] = -v[1] * ctr[i0]!!.y - v[0] * ctr[i0]!!.x
            for (l0 in 0 until 3) for (k0 in 0 until 3) q[i0][l0 * 3 + k0] = (v[l0] * v[k0]) / d
        }
    }

    var dx: Double
    var dy: Double
    var det: Double
    var z: Double
    var xmin: Double
    var ymin: Double
    var minv: Double
    var cand: Double

    for (i0 in 0 until m) {
        val Q = DoubleArray(9)
        val w = IPt(0.0, 0.0)
        s.x = pt[po[i0]].x - x0
        s.y = pt[po[i0]].y - y0
        j = mod(i0 - 1, m)
        for (l0 in 0 until 3) for (k0 in 0 until 3) Q[l0 * 3 + k0] = q[j][l0 * 3 + k0] + q[i0][l0 * 3 + k0]

        while (true) {
            det = Q[0] * Q[4] - Q[1] * Q[3]
            if (det != 0.0) {
                w.x = (-Q[2] * Q[4] + Q[5] * Q[1]) / det
                w.y = (Q[2] * Q[3] - Q[5] * Q[0]) / det
                break
            }
            if (Q[0] > Q[4]) {
                v[0] = -Q[1]
                v[1] = Q[0]
            } else if (Q[4] != 0.0) {
                v[0] = -Q[4]
                v[1] = Q[3]
            } else {
                v[0] = 1.0
                v[1] = 0.0
            }
            val d = v[0] * v[0] + v[1] * v[1]
            v[2] = -v[1] * s.y - v[0] * s.x
            for (l0 in 0 until 3) for (k0 in 0 until 3) Q[l0 * 3 + k0] += (v[l0] * v[k0]) / d
        }
        dx = abs(w.x - s.x)
        dy = abs(w.y - s.y)
        if (dx <= 0.5 && dy <= 0.5) {
            curve.vertex[i0] = IPt(w.x + x0, w.y + y0)
            continue
        }

        minv = quadform(Q, s)
        xmin = s.x
        ymin = s.y

        if (Q[0] != 0.0) {
            for (z0 in 0 until 2) {
                z = z0.toDouble()
                w.y = s.y - 0.5 + z
                w.x = -(Q[1] * w.y + Q[2]) / Q[0]
                dx = abs(w.x - s.x)
                cand = quadform(Q, w)
                if (dx <= 0.5 && cand < minv) {
                    minv = cand
                    xmin = w.x
                    ymin = w.y
                }
            }
        }

        if (Q[4] != 0.0) {
            for (z0 in 0 until 2) {
                z = z0.toDouble()
                w.x = s.x - 0.5 + z
                w.y = -(Q[3] * w.x + Q[5]) / Q[4]
                dy = abs(w.y - s.y)
                cand = quadform(Q, w)
                if (dy <= 0.5 && cand < minv) {
                    minv = cand
                    xmin = w.x
                    ymin = w.y
                }
            }
        }

        for (l0 in 0 until 2) {
            for (k0 in 0 until 2) {
                w.x = s.x - 0.5 + l0
                w.y = s.y - 0.5 + k0
                cand = quadform(Q, w)
                if (cand < minv) {
                    minv = cand
                    xmin = w.x
                    ymin = w.y
                }
            }
        }

        curve.vertex[i0] = IPt(xmin + x0, ymin + y0)
    }
}

// ---------------------------------------------------------------------------
// Stage 4: 平滑与拐角分析
// ---------------------------------------------------------------------------
private fun reverseCurve(path: PPath) {
    val curve = path.curve!!
    val v = curve.vertex
    var i = 0
    var j = curve.n - 1
    while (i < j) {
        val tmp = v[i]
        v[i] = v[j]
        v[j] = tmp
        i++
        j--
    }
}

private fun smooth(path: PPath, alphaMax: Double) {
    val curve = path.curve!!
    val m = curve.n
    if (path.sign == "-") reverseCurve(path)
    for (i in 0 until m) {
        val j = mod(i + 1, m)
        val k = mod(i + 2, m)
        val p4 = interval(1 / 2.0, curve.vertex[k]!!, curve.vertex[j]!!)

        val denom = ddenom(curve.vertex[i]!!, curve.vertex[k]!!)
        var alpha: Double
        if (denom != 0.0) {
            var dd = dpara(curve.vertex[i]!!, curve.vertex[j]!!, curve.vertex[k]!!) / denom
            dd = abs(dd)
            alpha = if (dd > 1) 1 - 1.0 / dd else 0.0
            alpha = alpha / 0.75
        } else {
            alpha = 4 / 3.0
        }
        curve.alpha0[j] = alpha

        if (alpha >= alphaMax) {
            curve.tag[j] = POTRACE_CORNER
            curve.c[3 * j + 1] = curve.vertex[j]!!
            curve.c[3 * j + 2] = p4
        } else {
            if (alpha < 0.55) alpha = 0.55
            else if (alpha > 1) alpha = 1.0
            val p2 = interval(0.5 + 0.5 * alpha, curve.vertex[i]!!, curve.vertex[j]!!)
            val p3 = interval(0.5 + 0.5 * alpha, curve.vertex[k]!!, curve.vertex[j]!!)
            curve.tag[j] = POTRACE_CURVETO
            curve.c[3 * j + 0] = p2
            curve.c[3 * j + 1] = p3
            curve.c[3 * j + 2] = p4
        }
        curve.alpha[j] = alpha
        curve.beta[j] = 0.5
    }
    curve.alphacurve = 1
}

// ---------------------------------------------------------------------------
// Stage 5: 曲线优化
// ---------------------------------------------------------------------------
/** 计算 i+.5 → j+.5 的最优拟合；成功返回 0，否则返回 1 */
private fun optiPenalty(
    path: PPath,
    i0: Int,
    j0: Int,
    res: Opti,
    opttolerance: Double,
    convc: IntArray,
    areac: DoubleArray
): Int {
    val curve = path.curve!!
    val m = curve.n
    val vertex = curve.vertex
    var k: Int
    var k1: Int
    var k2: Int
    val conv: Int
    val i1: Int
    var area: Double
    var alpha: Double
    var d: Double
    var d1: Double
    var d2: Double
    var p0: IPt
    var p1: IPt
    var p2: IPt
    val p3: IPt
    var pt: IPt
    var A: Double
    var R: Double
    var s: Double
    var t: Double
    var i = i0
    var j = j0

    if (i == j) return 1

    k = i
    i1 = mod(i + 1, m)
    k1 = mod(k + 1, m)
    conv = convc[k1]
    if (conv == 0) return 1
    d = ddist(vertex[i]!!, vertex[i1]!!)
    k = k1
    while (k != j) {
        k1 = mod(k + 1, m)
        k2 = mod(k + 2, m)
        if (convc[k1] != conv) return 1
        if (sign(cprod(vertex[i]!!, vertex[i1]!!, vertex[k1]!!, vertex[k2]!!)) != conv) return 1
        if (iprod1(vertex[i]!!, vertex[i1]!!, vertex[k1]!!, vertex[k2]!!) <
            d * ddist(vertex[k1]!!, vertex[k2]!!) * COS179
        ) {
            return 1
        }
        k = k1
    }

    p0 = copyOf(curve.c[mod(i, m) * 3 + 2]!!)
    p1 = copyOf(vertex[mod(i + 1, m)]!!)
    p2 = copyOf(vertex[mod(j, m)]!!)
    p3 = copyOf(curve.c[mod(j, m) * 3 + 2]!!)

    area = areac[j] - areac[i]
    area -= dpara(vertex[0]!!, curve.c[i * 3 + 2]!!, curve.c[j * 3 + 2]!!) / 2
    if (i >= j) area += areac[m]

    val A1 = dpara(p0, p1, p2)
    val A2 = dpara(p0, p1, p3)
    val A3 = dpara(p0, p2, p3)
    val A4 = A1 + A3 - A2

    if (A2 == A1) return 1

    t = A3 / (A3 - A4)
    s = A2 / (A2 - A1)
    A = (A2 * t) / 2.0

    if (A == 0.0) return 1

    R = area / A
    alpha = 2 - sqrt(4 - R / 0.3)

    res.c[0] = interval(t * alpha, p0, p1)
    res.c[1] = interval(s * alpha, p3, p2)
    res.alpha = alpha
    res.t = t
    res.s = s

    p1 = copyOf(res.c[0])
    p2 = copyOf(res.c[1])

    res.pen = 0.0
    k = mod(i + 1, m)
    while (k != j) {
        k1 = mod(k + 1, m)
        t = tangent(p0, p1, p2, p3, vertex[k]!!, vertex[k1]!!)
        if (t < -0.5) return 1
        pt = bezier(t, p0, p1, p2, p3)
        d = ddist(vertex[k]!!, vertex[k1]!!)
        if (d == 0.0) return 1
        d1 = dpara(vertex[k]!!, vertex[k1]!!, pt) / d
        if (abs(d1) > opttolerance) return 1
        if (iprod(vertex[k]!!, vertex[k1]!!, pt) < 0 || iprod(vertex[k1]!!, vertex[k]!!, pt) < 0) return 1
        res.pen += d1 * d1
        k = k1
    }
    k = i
    while (k != j) {
        k1 = mod(k + 1, m)
        t = tangent(p0, p1, p2, p3, curve.c[k * 3 + 2]!!, curve.c[k1 * 3 + 2]!!)
        if (t < -0.5) return 1
        pt = bezier(t, p0, p1, p2, p3)
        d = ddist(curve.c[k * 3 + 2]!!, curve.c[k1 * 3 + 2]!!)
        if (d == 0.0) return 1
        d1 = dpara(curve.c[k * 3 + 2]!!, curve.c[k1 * 3 + 2]!!, pt) / d
        d2 = dpara(curve.c[k * 3 + 2]!!, curve.c[k1 * 3 + 2]!!, vertex[k1]!!) / d
        d2 *= 0.75 * curve.alpha[k1]
        if (d2 < 0) {
            d1 = -d1
            d2 = -d2
        }
        if (d1 < d2 - opttolerance) return 1
        if (d1 < d2) res.pen += (d1 - d2) * (d1 - d2)
        k = k1
    }

    return 0
}

/** 用单段贝塞尔替换可合并的贝塞尔序列 */
private fun optiCurve(path: PPath, opttolerance: Double) {
    val curve = path.curve!!
    val m = curve.n
    val vert = curve.vertex
    val pt = IntArray(m + 1)
    val pen = DoubleArray(m + 1)
    val len = IntArray(m + 1)
    val opt = arrayOfNulls<Opti>(m + 1)
    var o = newOpti()
    val om: Int
    var i: Int
    var j: Int
    var r: Int
    val p0 = curve.vertex[0]!!
    var i1: Int
    var area: Double
    var alpha: Double
    val convc = IntArray(m)
    val areac = DoubleArray(m + 1)

    for (i0 in 0 until m) {
        if (curve.tag[i0] == POTRACE_CURVETO) {
            convc[i0] = sign(dpara(vert[mod(i0 - 1, m)]!!, vert[i0]!!, vert[mod(i0 + 1, m)]!!))
        } else {
            convc[i0] = 0
        }
    }

    area = 0.0
    areac[0] = 0.0
    for (i0 in 0 until m) {
        i1 = mod(i0 + 1, m)
        if (curve.tag[i1] == POTRACE_CURVETO) {
            alpha = curve.alpha[i1]
            area += (0.3 * alpha * (4 - alpha) * dpara(curve.c[i0 * 3 + 2]!!, vert[i1]!!, curve.c[i1 * 3 + 2]!!)) / 2
            area += dpara(p0, curve.c[i0 * 3 + 2]!!, curve.c[i1 * 3 + 2]!!) / 2
        }
        areac[i0 + 1] = area
    }

    pt[0] = -1
    pen[0] = 0.0
    len[0] = 0

    for (j0 in 1..m) {
        pt[j0] = j0 - 1
        pen[j0] = pen[j0 - 1]
        len[j0] = len[j0 - 1] + 1

        var i0 = j0 - 2
        while (i0 >= 0) {
            r = optiPenalty(path, i0, mod(j0, m), o, opttolerance, convc, areac)
            if (r == 1) break
            if (len[j0] > len[i0] + 1 || (len[j0] == len[i0] + 1 && pen[j0] > pen[i0] + o.pen)) {
                pt[j0] = i0
                pen[j0] = pen[i0] + o.pen
                len[j0] = len[i0] + 1
                opt[j0] = o
                o = newOpti()
            }
            i0--
        }
    }

    val omValue = len[m]
    if (omValue < 1) return
    om = omValue
    val ocurve = PrivCurve(om)
    val s = DoubleArray(om)
    val t = DoubleArray(om)

    j = m
    for (i0 in om - 1 downTo 0) {
        if (pt[j] == j - 1) {
            ocurve.tag[i0] = curve.tag[mod(j, m)]
            ocurve.c[i0 * 3 + 0] = curve.c[mod(j, m) * 3 + 0]
            ocurve.c[i0 * 3 + 1] = curve.c[mod(j, m) * 3 + 1]
            ocurve.c[i0 * 3 + 2] = curve.c[mod(j, m) * 3 + 2]
            ocurve.vertex[i0] = curve.vertex[mod(j, m)]
            ocurve.alpha[i0] = curve.alpha[mod(j, m)]
            ocurve.alpha0[i0] = curve.alpha0[mod(j, m)]
            ocurve.beta[i0] = curve.beta[mod(j, m)]
            s[i0] = 1.0
            t[i0] = 1.0
        } else {
            val oj = opt[j]!!
            ocurve.tag[i0] = POTRACE_CURVETO
            ocurve.c[i0 * 3 + 0] = oj.c[0]
            ocurve.c[i0 * 3 + 1] = oj.c[1]
            ocurve.c[i0 * 3 + 2] = curve.c[mod(j, m) * 3 + 2]
            ocurve.vertex[i0] = interval(oj.s, curve.c[mod(j, m) * 3 + 2]!!, vert[mod(j, m)]!!)
            ocurve.alpha[i0] = oj.alpha
            ocurve.alpha0[i0] = oj.alpha
            s[i0] = oj.s
            t[i0] = oj.t
        }
        j = pt[j]
    }

    for (i0 in 0 until om) {
        i1 = mod(i0 + 1, om)
        ocurve.beta[i0] = s[i0] / (s[i0] + t[i1])
    }
    ocurve.alphacurve = 1
    path.curve = ocurve
}

// ---------------------------------------------------------------------------
// 曲线 → 折线（贝塞尔自适应离散）
// ---------------------------------------------------------------------------
private fun pushPoint(out: MutableList<Pt>, p: IPt) {
    val last = out.lastOrNull()
    if (last != null && abs(last.x - p.x) < 1e-9 && abs(last.y - p.y) < 1e-9) return
    out.add(Pt(p.x, p.y))
}

private fun flattenCubicRec(p0: IPt, p1: IPt, p2: IPt, p3: IPt, tol: Double, depth: Int, out: MutableList<Pt>) {
    if (depth >= 20 || isFlat(p0, p1, p2, p3, tol)) {
        pushPoint(out, p3)
        return
    }
    val p01 = IPt((p0.x + p1.x) / 2, (p0.y + p1.y) / 2)
    val p12 = IPt((p1.x + p2.x) / 2, (p1.y + p2.y) / 2)
    val p23 = IPt((p2.x + p3.x) / 2, (p2.y + p3.y) / 2)
    val p012 = IPt((p01.x + p12.x) / 2, (p01.y + p12.y) / 2)
    val p123 = IPt((p12.x + p23.x) / 2, (p12.y + p23.y) / 2)
    val mid = IPt((p012.x + p123.x) / 2, (p012.y + p123.y) / 2)
    flattenCubicRec(p0, p01, p012, mid, tol, depth + 1, out)
    flattenCubicRec(mid, p123, p23, p3, tol, depth + 1, out)
}

private fun triArea(a: IPt, b: IPt, c: IPt): Double =
    abs(a.x * b.y + b.x * c.y + c.x * a.y - a.y * b.x - b.y * c.x - c.y * a.x) / 2

private fun isFlat(p0: IPt, p1: IPt, p2: IPt, p3: IPt, tol: Double): Boolean =
    sqrt(triArea(p0, p1, p2)) < tol && sqrt(triArea(p1, p2, p3)) < tol

/** 把单条路径的曲线集合转成闭合折线 */
private fun pathToPolyline(path: PPath, tol: Double): Polyline? {
    val curve = path.curve ?: return null
    if (curve.n < 1) return null
    val n = curve.n
    val start = curve.c[(n - 1) * 3 + 2] ?: return null
    val out = ArrayList<Pt>()
    out.add(Pt(start.x, start.y))
    var L: IPt = start
    for (j in 0 until n) {
        if (curve.tag[j] == POTRACE_CORNER) {
            val A = curve.c[j * 3 + 1] ?: return null
            val B = curve.c[j * 3 + 2] ?: return null
            pushPoint(out, A)
            pushPoint(out, B)
            L = B
        } else {
            val CP = curve.c[j * 3 + 0] ?: return null
            val A = curve.c[j * 3 + 1] ?: return null
            val B = curve.c[j * 3 + 2] ?: return null
            flattenCubicRec(L, CP, A, B, tol, 0, out)
            L = B
        }
    }
    if (out.size < 3) return null
    return Polyline(out, true)
}

// ---------------------------------------------------------------------------
// 对外接口
// ---------------------------------------------------------------------------
/**
 * 对位图执行 Potrace 轮廓矢量化。
 * 返回像素坐标系（y 向下）中的折线；孔洞与外轮廓都返回，均为闭合折线。
 *
 * 选项默认值就是 v2 `potraceTrace` 里的 `??` 兜底值（见 [PotraceOptions]）。
 */
fun potraceTrace(image: PotraceImage, options: PotraceOptions = PotraceOptions()): List<Polyline> {
    if (image.width <= 0 || image.height <= 0) return emptyList()
    val threshold = options.threshold
    val turdSize = options.turdSize
    val alphaMax = options.alphaMax
    val optTolerance = options.optTolerance
    val curveOptimizing = options.curveOptimizing
    val invert = options.invert
    val flattenTolerance = options.flattenTolerance

    val bm = binarize(image, threshold, invert)

    // 全白 / 全黑保护：全黑时整幅图像为单一区域，无有效轮廓
    val total = bm.w * bm.h
    var black = 0
    for (i in bm.data.indices) if (bm.data[i] != 0) black++
    if (black == 0 || black == total) return emptyList()

    val list = bmToPathlist(bm, turdSize)
    if (list.isEmpty()) return emptyList()

    val out = ArrayList<Polyline>()
    for (path in list) {
        if (path.len < 3) continue
        calcSums(path)
        calcLon(path)
        bestPolygon(path)
        if (path.m < 1) continue
        adjustVertices(path)
        smooth(path, alphaMax)
        if (curveOptimizing) optiCurve(path, optTolerance)
        val poly = pathToPolyline(path, flattenTolerance)
        if (poly != null) out.add(poly)
    }
    return out
}
