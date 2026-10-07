package com.lasergrbl.core.internal

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Android 侧与 JVM 侧实现相同（`java.math` 在两边都可用）。
 * 之所以不放进 commonMain：Kotlin 公共代码不能引用 `java.*`。
 */

/**
 * 见 commonMain 的同名 expect 声明。
 *
 * 与 JVM 侧**逐行同构**（必须如此：两边行为不一致的话，单测绿而真机红）。
 * 并列值远离零取整 —— 详细的 V8 实测依据见 `jvmMain/.../JsNumber.jvm.kt` 的 KDoc。
 */
internal actual fun jsToFixed(value: Double, decimals: Int): String {
    if (!value.isFinite()) return "0"
    val exact = BigDecimal(value)
    return exact.setScale(decimals, RoundingMode.HALF_UP).toPlainString()
}

/** 见 commonMain 的同名 expect 声明（JS `Number::toString` 的精确复刻）。 */
internal actual fun jsNumberToString(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value == 0.0) return "0"
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
