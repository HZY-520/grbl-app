package com.lasergrbl.core.vector

import com.lasergrbl.core.internal.jsHypot
import com.lasergrbl.core.native.jsRound
import kotlin.math.max

/**
 * 中心线走线（骨骼 / 中轴）矢量化 —— 逐行移植自 v2 `src/core/vector/Centerline.ts`。
 *
 * 原 LaserGRBL 的 Centerline 模式依赖外部 autotrace.exe，无法直接移植。
 * 这里改为纯 Kotlin 实现：
 *   1. 二值化（R+G+B 均值阈值；alpha<128 视为背景）
 *   2. Zhang-Suen 细化算法得到 1 像素宽的骨架
 *   3. 建立 8 邻域图，从端点/分支点出发 walk 出折线分支（纯环单独处理）
 *   4. 剪除短于 minBranchPx 的毛刺分支（迭代，支持级联）
 *   5. Douglas-Peucker 简化（同时合并共线点）
 *
 * 输出为像素坐标系（y 向下）中的单线折线，适合线稿 / 汉字笔画的“走线”雕刻。
 *
 * ⚠️ 必须原样复刻的 JS 语义（黄金样本 `golden/centerline.json` 钉住）：
 *  1. `Uint8Array` 用 `IntArray` 承载（取值只有 0/1，不存在回绕）；
 *  2. `Math.round` 是「并列取较大者（向 +∞）」—— 细化迭代上限走 `jsRound`，**不能**用 `round()`（并列取偶）；
 *  3. `visited` 集合的键是 `a * size + b`（JS number 精度可以到 2^53），
 *     这里用 `Long` 计算，避免大图时 `Int` 溢出导致边被误判为「已访问」；
 *  4. 遍历顺序（8 邻域偏移表的顺序、像素下标的升序、栈的 LIFO）决定分支 / 簇的产出顺序，
 *     一律与 v2 保持一致。
 */

/** 中心线走线选项。 */
data class CenterlineOptions(
    /** 二值化阈值 0..255，默认 128。 */
    val threshold: Double = 128.0,
    val invert: Boolean = false,
    /** 是否输出闭合折线（默认 false）。 */
    val closed: Boolean = false,
    /** 丢弃短于该像素长度的分支（去毛刺），默认 6。 */
    val minBranchPx: Double = 6.0,
    /** Douglas-Peucker 简化容差（像素），默认 1.2。 */
    val simplifyTolerance: Double = 1.2,
    /** 骨架化迭代上限，默认 60（`null` 等价于 v2 的 `undefined`）。 */
    val maxIterations: Double? = null
)

/** 8 邻域偏移（顺时针：左上、上、右上、右、右下、下、左下、左）。 */
private val OFFS: List<Pair<Int, Int>> = listOf(
    -1 to -1,
    0 to -1,
    1 to -1,
    1 to 0,
    1 to 1,
    0 to 1,
    -1 to 1,
    -1 to 0
)

/** 二值化：返回 1=前景（黑）的位图。 */
private fun binarize(image: PotraceImage, threshold: Double, invert: Boolean): IntArray {
    val w = image.width
    val h = image.height
    val src = image.data
    val n = w * h
    val out = IntArray(n)
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
    return out
}

/** 四周补一圈 0 的中间结果（v2 返回的是 `{ data, w, h }` 对象字面量）。 */
private class Padded(val data: IntArray, val w: Int, val h: Int)

/** 四周补一圈 0，避免贴边图形被细化算法误删。 */
private fun pad(src: IntArray, w: Int, h: Int): Padded {
    val pw = w + 2
    val ph = h + 2
    val out = IntArray(pw * ph)
    for (y in 0 until h) {
        for (x in 0 until w) out[(y + 1) * pw + (x + 1)] = src[y * w + x]
    }
    return Padded(out, pw, ph)
}

/**
 * Zhang-Suen 细化，原地修改，得到 1 像素宽骨架。
 * 返回是否「收敛」（最后一轮迭代没有再删除任何像素）。
 * 注意：粗图形（笔画宽 w）需要约 w/2 次迭代才能收敛，迭代上限因此会直接影响
 * 骨架是否真的只有 1 像素宽——centerlineTrace 保持原有的 60 次上限不变。
 */
private fun thin(skel: IntArray, w: Int, h: Int, maxIter: Double): Boolean {
    var changed = true
    var iter = 0
    val del = mutableListOf<Int>()
    while (changed && iter < maxIter) {
        changed = false
        iter++
        for (pass in 0 until 2) {
            del.clear()
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val i = row + x
                    if (skel[i] == 0) continue
                    val p2 = skel[i - w]
                    val p3 = skel[i - w + 1]
                    val p4 = skel[i + 1]
                    val p5 = skel[i + w + 1]
                    val p6 = skel[i + w]
                    val p7 = skel[i + w - 1]
                    val p8 = skel[i - 1]
                    val p9 = skel[i - w - 1]
                    val b = p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9
                    if (b < 2 || b > 6) continue
                    // 0→1 跳变次数
                    var a = 0
                    if (p2 == 0 && p3 != 0) a++
                    if (p3 == 0 && p4 != 0) a++
                    if (p4 == 0 && p5 != 0) a++
                    if (p5 == 0 && p6 != 0) a++
                    if (p6 == 0 && p7 != 0) a++
                    if (p7 == 0 && p8 != 0) a++
                    if (p8 == 0 && p9 != 0) a++
                    if (p9 == 0 && p2 != 0) a++
                    if (a != 1) continue
                    if (pass == 0) {
                        if (p2 * p4 * p6 != 0) continue
                        if (p4 * p6 * p8 != 0) continue
                    } else {
                        if (p2 * p4 * p8 != 0) continue
                        if (p2 * p6 * p8 != 0) continue
                    }
                    del.add(i)
                }
            }
            if (del.isNotEmpty()) {
                changed = true
                for (i in del) skel[i] = 0
            }
        }
    }
    return !changed
}

/** 计算每个骨架像素的 8 邻域度数。 */
private fun computeDegree(skel: IntArray, w: Int, h: Int): IntArray {
    val deg = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            if (skel[i] == 0) continue
            var c = 0
            for (o in OFFS) {
                val nx = x + o.first
                val ny = y + o.second
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                if (skel[ny * w + nx] != 0) c++
            }
            deg[i] = c
        }
    }
    return deg
}

/** 折线分支（v2 的 `interface Branch`）。 */
private class Branch(val pix: List<Int>, val loop: Boolean)

/** 从骨架图中提取折线分支（端点/分支点之间的路径，以及孤立环）。 */
private fun extractBranches(skel: IntArray, w: Int, h: Int, deg: IntArray): List<Branch> {
    val size = w * h
    val visited = HashSet<Long>()
    val covered = IntArray(size)
    fun enc(a: Int, b: Int): Long = if (a < b) a.toLong() * size + b else b.toLong() * size + a
    fun isNode(i: Int): Boolean = deg[i] == 1 || deg[i] >= 3
    val branches = mutableListOf<Branch>()

    // 1) 从端点/分支点出发
    for (i in 0 until size) {
        if (skel[i] == 0 || !isNode(i)) continue
        val ix = i % w
        val iy = (i - ix) / w
        for (o in OFFS) {
            val nx = ix + o.first
            val ny = iy + o.second
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
            val j = ny * w + nx
            if (skel[j] == 0) continue
            if (visited.contains(enc(i, j))) continue

            val pix = mutableListOf(i)
            covered[i] = 1
            visited.add(enc(i, j))
            var prev = i
            var cur = j
            pix.add(cur)
            covered[cur] = 1
            while (!isNode(cur)) {
                val cx = cur % w
                val cy = (cur - cx) / w
                var next = -1
                for (oo in OFFS) {
                    val mx = cx + oo.first
                    val my = cy + oo.second
                    if (mx < 0 || mx >= w || my < 0 || my >= h) continue
                    val k = my * w + mx
                    if (skel[k] != 0 && k != prev) {
                        next = k
                        break
                    }
                }
                if (next < 0) break
                visited.add(enc(cur, next))
                pix.add(next)
                covered[next] = 1
                prev = cur
                cur = next
            }
            branches.add(Branch(pix, cur == i))
        }
    }

    // 2) 完全由 deg==2 像素构成的孤立环
    for (i in 0 until size) {
        if (skel[i] == 0 || covered[i] != 0) continue
        val ix = i % w
        val iy = (i - ix) / w
        var startEdge = -1
        for (o in OFFS) {
            val nx = ix + o.first
            val ny = iy + o.second
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
            val j = ny * w + nx
            if (skel[j] != 0 && !visited.contains(enc(i, j))) {
                startEdge = j
                break
            }
        }
        if (startEdge < 0) continue

        val pix = mutableListOf(i)
        covered[i] = 1
        visited.add(enc(i, startEdge))
        var prev = i
        var cur = startEdge
        while (cur != i) {
            pix.add(cur)
            covered[cur] = 1
            val cx = cur % w
            val cy = (cur - cx) / w
            var next = -1
            for (oo in OFFS) {
                val mx = cx + oo.first
                val my = cy + oo.second
                if (mx < 0 || mx >= w || my < 0 || my >= h) continue
                val k = my * w + mx
                if (skel[k] != 0 && k != prev && !visited.contains(enc(cur, k))) {
                    next = k
                    break
                }
            }
            if (next < 0) break
            visited.add(enc(cur, next))
            prev = cur
            cur = next
        }
        if (cur == i && pix.size >= 3) branches.add(Branch(pix, true))
    }

    return branches
}

/** 分支点簇（v2 的 `interface Clusters`）。 */
private class Clusters(
    /** 每个像素所属的分支点簇编号，非分支点为 -1 */
    val id: IntArray,
    /** 各簇的质心 x */
    val cx: List<Double>,
    /** 各簇的质心 y */
    val cy: List<Double>
)

/**
 * 细化算法在粗交叉处常留下 2x2 左右的“分支点簇”。
 * 这里把 8 连通的分支点（deg>=3）聚为一簇，用质心代表，
 * 从而把簇内的微小碎分支合并掉，得到干净的单线交叉。
 */
private fun junctionClusters(skel: IntArray, w: Int, h: Int, deg: IntArray): Clusters {
    val size = w * h
    val id = IntArray(size) { -1 }
    val cx = mutableListOf<Double>()
    val cy = mutableListOf<Double>()
    val stack = mutableListOf<Int>()
    for (i in 0 until size) {
        if (skel[i] == 0 || deg[i] < 3 || id[i] != -1) continue
        val cid = cx.size
        var sx = 0
        var sy = 0
        var cnt = 0
        stack.clear()
        stack.add(i)
        id[i] = cid
        while (stack.isNotEmpty()) {
            val p = stack.removeAt(stack.size - 1)
            val px = p % w
            val py = (p - px) / w
            sx += px
            sy += py
            cnt++
            for (o in OFFS) {
                val nx = px + o.first
                val ny = py + o.second
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                val q = ny * w + nx
                if (skel[q] != 0 && deg[q] >= 3 && id[q] == -1) {
                    id[q] = cid
                    stack.add(q)
                }
            }
        }
        cx.add(sx.toDouble() / cnt)
        cy.add(sy.toDouble() / cnt)
    }
    return Clusters(id, cx, cy)
}

/** 迭代剪除短毛刺（返回是否发生修改）。 */
private fun pruneSpurs(skel: IntArray, w: Int, h: Int, minBranchPx: Double): Boolean {
    var anyChange = false
    for (pass in 0 until 10) {
        val deg = computeDegree(skel, w, h)
        val branches = extractBranches(skel, w, h, deg)
        val remove = mutableListOf<Int>()
        var changed = false
        for (br in branches) {
            if (br.loop) continue
            val pix = br.pix
            val n = pix.size
            if (n >= minBranchPx) continue
            val aTip = deg[pix[0]] == 1
            val bTip = deg[pix[n - 1]] == 1
            val aJun = deg[pix[0]] >= 3
            val bJun = deg[pix[n - 1]] >= 3
            if ((aTip && bJun) || (bTip && aJun) || (aTip && bTip)) {
                changed = true
                if (aTip && bJun) {
                    for (i in 0 until n - 1) remove.add(pix[i]) // 保留连接点
                } else if (bTip && aJun) {
                    for (i in 1 until n) remove.add(pix[i]) // 保留连接点
                } else {
                    for (i in 0 until n) remove.add(pix[i])
                }
            }
        }
        if (!changed) break
        anyChange = true
        for (i in remove) skel[i] = 0
    }
    return anyChange
}

// ---------------------------------------------------------------------------
// 骨架化中间结果导出（新增，供「智能识别」统计笔画宽度使用）
//
// 说明：以下两个导出只是把原有内部实现暴露出来，centerlineTrace 的行为、
// 数值与调用方式完全不变；智能识别必须与真正走线时使用同一份二值化 / 细化实现，
// 否则「判定用的骨架」与「实际输出的骨架」会不一致。
// ---------------------------------------------------------------------------

/** 骨架化结果。 */
data class SkeletonResult(
    /** 骨架位图（1=骨架），尺寸为原图四周各补 1 像素（补边避免贴边图形被误删）。 */
    val data: IntArray,
    /** 补边后的宽度（= 原图宽度 + 2）。 */
    val width: Int,
    /** 补边后的高度（= 原图高度 + 2）。 */
    val height: Int,
    /** 前景（墨水）像素数，按原图尺寸统计。 */
    val inkArea: Int,
    /**
     * 细化是否在给定迭代上限内收敛。
     * false 表示笔画比「上限 × 2」还粗（骨架仍不是 1 像素宽），
     * 此时面积 / 骨架长度不能当作笔画宽度使用。
     */
    val converged: Boolean
)

/**
 * 只做「二值化 + Zhang-Suen 细化」，返回骨架（不做剪毛刺与折线提取）。
 * 与 centerlineTrace 的前两步是同一份实现，迭代上限可由调用方指定。
 */
fun skeletonize(image: PotraceImage, options: CenterlineOptions = CenterlineOptions()): SkeletonResult {
    if (image.width <= 0 || image.height <= 0) {
        return SkeletonResult(IntArray(0), 0, 0, 0, true)
    }
    val threshold = options.threshold
    val invert = options.invert
    // v2：Math.max(1, Math.round(options.maxIterations ?? 60))
    val maxIterations = max(1, jsRound(options.maxIterations ?: 60.0))

    val bin = binarize(image, threshold, invert)
    var inkArea = 0
    for (i in bin.indices) if (bin[i] != 0) inkArea++

    val p = pad(bin, image.width, image.height)
    val converged = thin(p.data, p.w, p.h, maxIterations.toDouble())
    return SkeletonResult(p.data, p.w, p.h, inkArea, converged)
}

/** 8 邻域度数（供智能识别统计端点 / 分支点）。 */
fun skeletonDegree(skel: IntArray, w: Int, h: Int): IntArray = computeDegree(skel, w, h)

/**
 * 单线“走线”矢量化。
 * 返回像素坐标系（y 向下）中的折线；默认输出开放折线。
 */
fun centerlineTrace(image: PotraceImage, options: CenterlineOptions = CenterlineOptions()): List<Polyline> {
    if (image.width <= 0 || image.height <= 0) return emptyList()
    val threshold = options.threshold
    val invert = options.invert
    val closed = options.closed
    val minBranchPx = options.minBranchPx
    val simplifyTolerance = options.simplifyTolerance
    // 注意：centerlineTrace 不做 Math.round，直接把 options 里的值交给 thin 的迭代上限
    val maxIterations = options.maxIterations ?: 60.0

    val bin = binarize(image, threshold, invert)

    var black = 0
    val total = image.width * image.height
    for (i in 0 until total) if (bin[i] != 0) black++
    if (black == 0 || black == total) return emptyList() // 空 / 全白 / 全黑

    val p = pad(bin, image.width, image.height)
    thin(p.data, p.w, p.h, maxIterations)
    pruneSpurs(p.data, p.w, p.h, minBranchPx)

    val deg = computeDegree(p.data, p.w, p.h)
    val branches = extractBranches(p.data, p.w, p.h, deg)
    val clusters = junctionClusters(p.data, p.w, p.h, deg)

    val out = mutableListOf<Polyline>()
    for (br in branches) {
        // 去掉补边偏移
        val pts: MutableList<Pt> = br.pix.mapTo(mutableListOf()) { i ->
            val x = i % p.w
            val y = (i - x) / p.w
            Pt((x - 1).toDouble(), (y - 1).toDouble())
        }
        val aId = clusters.id[br.pix[0]]
        val bId = clusters.id[br.pix[br.pix.size - 1]]
        if (!br.loop && aId >= 0 && aId == bId) continue // 分支点簇内的碎分支：合并丢弃
        // 端点落在分支点簇上时，吸附到簇质心，使交叉处干净相接
        if (aId >= 0) pts[0] = Pt(clusters.cx[aId] - 1, clusters.cy[aId] - 1)
        if (bId >= 0) pts[pts.size - 1] = Pt(clusters.cx[bId] - 1, clusters.cy[bId] - 1)

        val simplified = simplifyPath(pts, simplifyTolerance)
        if (simplified.size < 2) continue
        if (jsHypot(simplified[0].x - simplified[simplified.size - 1].x, simplified[0].y - simplified[simplified.size - 1].y) < 1e-6) {
            continue
        }
        out.add(Polyline(simplified, br.loop && closed))
    }
    return out
}
