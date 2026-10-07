package com.lasergrbl.core.internal

/**
 * 等价于 JS `Number.prototype.toFixed(decimals)`。
 *
 * 为什么需要它：v2 的 G 代码数字格式化用的是 `toFixed` + 去尾零（对齐 C# 的 `"0.###"`），
 * 而 JS 的 toFixed 规则是「取最接近的 n/10^d；**并列时取较大的 n**」（即并列朝 +∞ 取整）。
 * Kotlin 的 `String.format` 是 HALF_UP（并列远离零），负数并列时会不一致（-1.5 → JS `-1`，HALF_UP `-2`）。
 */
internal expect fun jsToFixed(value: Double, decimals: Int): String

/**
 * 等价于 JS `String(number)`（即 ECMAScript `Number::toString`，最短可往返十进制表示）。
 *
 * 为什么需要它：v2 的 `formatDecimal()` 就是 `String(v)`，G 代码、终端回显、日志里到处都在用。
 * Kotlin 的 `Double.toString()` 与 JS 的规则不同：
 *   * `1e-7`  → JS `"1e-7"`，Kotlin `"1.0E-7"`
 *   * `0.000001` → JS `"0.000001"`，Kotlin `"1.0E-6"`
 *   * `1e21`  → JS `"1e+21"`，Kotlin `"1.0E21"`
 *   * `100.0` → JS `"100"`，Kotlin `"100.0"`
 * 规则：取最短的 s（k 位）使 `s × 10^(n−k)` 等于该 double 且能往返；并列取偶数。
 * 输出格式按 k/n 分档（普通小数 / 指数，指数阈值 21 与 -6）。
 */
internal expect fun jsNumberToString(value: Double): String

/** JS 数字格式化用的正则：`parseFloat` 只吃前缀，遇到不认识的字符就停。 */
private val JS_NUMBER_PREFIX = Regex(
    "^[\\s]*([+-]?(?:Infinity|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?))"
)

/**
 * 等价于 JS `parseFloat(str)`：跳过前导空白，取最长的合法数字前缀；
 * 没有合法前缀时返回 `NaN`（而不是抛异常）。十六进制前缀按 JS 规则解析为 `0`。
 */
internal fun jsParseFloat(str: String): Double {
    val s = str.trimStart()
    if (s.isEmpty()) return Double.NaN
    val m = JS_NUMBER_PREFIX.find(s) ?: return Double.NaN
    val token = m.groupValues[1]
    return when (token) {
        "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
        "-Infinity" -> Double.NEGATIVE_INFINITY
        else -> token.toDoubleOrNull() ?: Double.NaN
    }
}
