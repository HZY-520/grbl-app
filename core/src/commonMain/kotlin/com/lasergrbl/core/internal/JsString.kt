package com.lasergrbl.core.internal

/**
 * 等价于 JS `String.prototype.trim()`。
 *
 * ⚠️ 与 Kotlin 的 `trim()`（按 `Char.isWhitespace()`）**不完全一致**：
 * JS 的 WhiteSpace 还包含 **U+FEFF（BOM/ZWNBSP）**，而 Kotlin 不认为它是空白 ——
 * v2 的 `truncateName("\\uFEFFbom.nc")` 会去掉 BOM，直接用 `trim()` 就会留下一个不可见字符。
 *
 * 完整集合 = JS WhiteSpace（TAB/VT/FF/SP/NBSP/ZWNBSP + Unicode Zs）+ LineTerminator（LF/CR/LS/PS）。
 */
internal fun jsTrim(value: String): String {
    var start = 0
    var end = value.length
    while (start < end && isJsWhitespace(value[start])) start++
    while (end > start && isJsWhitespace(value[end - 1])) end--
    return value.substring(start, end)
}

/** JS 规范里的 WhiteSpace ∪ LineTerminator。 */
internal fun isJsWhitespace(c: Char): Boolean = when (c) {
    '\u0009', // TAB
    '\u000A', // LF
    '\u000B', // VT
    '\u000C', // FF
    '\u000D', // CR
    '\u0020', // SP
    '\u00A0', // NBSP
    '\u1680', // OGHAM SPACE MARK
    '\u2028', // LINE SEPARATOR
    '\u2029', // PARAGRAPH SEPARATOR
    '\u202F', // NARROW NO-BREAK SPACE
    '\u205F', // MEDIUM MATHEMATICAL SPACE
    '\u3000', // IDEOGRAPHIC SPACE
    '\uFEFF' -> true // BOM / ZWNBSP —— Kotlin 的 trim() 不会去掉它

    else -> c in '\u2000'..'\u200A' // EN QUAD … HAIR SPACE
}
