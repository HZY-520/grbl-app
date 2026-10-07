package com.lasergrbl.core.internal

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 等价于 JS `Math.hypot(x, y)`（两参情形）。
 *
 * ⚠️ **不能**用 `kotlin.math.hypot`，也**不能**直接写 `sqrt(a*a + b*b)`：
 *   * JVM 的 `hypot` 是 fdlibm 那套「缩放 + sqrt + 修正」，与 V8 差最后 1 ulp；
 *   * 朴素 `sqrt(a*a+b*b)` 在 V8 里也不是 `Math.hypot` 的结果。
 *
 * V8 对两参有专门快路径（`FastMathHypot`，见 V8 `src/builtins/math.tq`）：
 * ```
 * x = |x|; y = |y|
 * if (x < y) swap(x, y)
 * if (x == Infinity) return Infinity
 * if (x == 0) return 0
 * const yOverX = y / x
 * return x * sqrt(1 + yOverX * yOverX)
 * ```
 * 这里逐行复刻。它影响的是**几何量本身**（路径长度、点到线段距离 → 影响抽稀保点），
 * 不只是打印格式，所以必须精确。
 */
internal fun jsHypot(x: Double, y: Double): Double {
    if (x.isNaN() || y.isNaN()) return Double.NaN
    var a = abs(x)
    var b = abs(y)
    if (a < b) {
        val t = a
        a = b
        b = t
    }
    if (a == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY
    if (a == 0.0) return 0.0 // 两者都是 0（含 -0）
    val yOverX = b / a
    return a * sqrt(1.0 + yOverX * yOverX)
}
