package com.lasergrbl.core.internal

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/**
 * JS `toFixed` 的精确复刻：对 double 的**精确十进制值**做舍入，
 * 并列（exact half）时**远离零**取整 —— 正负都是 `HALF_UP`（Java 的 `HALF_UP` 就是
 * "round half away from zero"，与符号无关）。
 *
 * ⚠️ 这里曾经写成"负数用 `HALF_DOWN`"（说成"并列朝 +∞"），那是**错的**。
 * 本机 V8 实测（`node -e`）：
 * ```
 * ( 1.5625).toFixed(3) ===  "1.563"      (-1.5625).toFixed(3) === "-1.563"
 * ( 0.0625).toFixed(3) ===  "0.063"      (-0.0625).toFixed(3) === "-0.063"
 * ( 1.5   ).toFixed(0) ===  "2"          (-1.5   ).toFixed(0) === "-2"
 * ```
 *
 * **为什么以 V8 为准，而不是照抄规范文本**：ECMAScript `Number::toFixed` 的步骤 8/9 在
 * "两个 n 都满足 `|n/10^f - x| <= 10^-f/2`"时明确要求 **"pick the larger n"**。
 * 对正数，那等于远离零；对**负数**（幅度超过一半时）"取较大的 n"会得到**朝零**的结果
 * （例如 `-1.5625 → -1.562`），而 V8 实测给的是 `-1.563`（对幅度做 half-up）。
 * 也就是说 **V8 的实现与规范文本在负数并列上不一致**。
 * 黄金样本由 V8 生成、真机跑的是 V8 的产物，所以判据只能是 V8 的实际行为。
 * 这不是学术差异：`y = -92 * (30/128) + 20 = -1.5625` 这种二进制精确值真的会出现，
 * 而 `G1 X.. Y-1.563` 里的 0.001 mm 是**协议输出**的一部分。
 */
internal actual fun jsToFixed(value: Double, decimals: Int): String {
    if (!value.isFinite()) return "0"
    val exact = BigDecimal(value)
    return exact.setScale(decimals, RoundingMode.HALF_UP).toPlainString()
}

/**
 * JS `Number::toString` 的精确复刻。步骤：
 *  1. `k` 从 1 递增到 17，对精确值按 `k` 位有效数字做 HALF_EVEN 舍入，取第一个能往返的 —— 这就是最短表示；
 *  2. 由 `s`（有效数字串，长度 k）与 `scale` 反推 `n = k − scale`；
 *  3. 按 ECMAScript 的 k/n 分档输出（普通小数 / 指数）。
 */
internal actual fun jsNumberToString(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value == 0.0) return "0" // 含 -0
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"

    val negative = value < 0.0
    val magnitude = abs(value)
    val exact = BigDecimal(magnitude)

    var shortest: BigDecimal? = null
    for (precision in 1..17) {
        val candidate = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
        if (candidate.toDouble() == magnitude) {
            shortest = candidate
            break
        }
    }
    val normalized = (shortest ?: exact.round(MathContext(17, RoundingMode.HALF_EVEN))).stripTrailingZeros()

    val digits = normalized.unscaledValue().toString()
    val k = digits.length
    val n = k - normalized.scale()

    val sb = StringBuilder()
    if (negative) sb.append('-')
    when {
        k <= n && n <= 21 -> {
            sb.append(digits)
            repeat(n - k) { sb.append('0') }
        }

        n in 1..21 -> {
            sb.append(digits, 0, n)
            sb.append('.')
            sb.append(digits, n, k)
        }

        n in -5..0 -> {
            sb.append("0.")
            repeat(-n) { sb.append('0') }
            sb.append(digits)
        }

        else -> {
            if (k == 1) {
                sb.append(digits)
            } else {
                sb.append(digits[0])
                sb.append('.')
                sb.append(digits, 1, k)
            }
            sb.append('e')
            val exponent = n - 1
            sb.append(if (exponent >= 0) '+' else '-')
            sb.append(abs(exponent))
        }
    }
    return sb.toString()
}
