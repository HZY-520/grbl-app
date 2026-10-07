package com.lasergrbl.core.native

import com.lasergrbl.core.internal.jsTrim
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 后台保活通知的**纯格式化部分** —— 移植自 v2 `src/core/native/KeepAlive.ts`。
 *
 * 与原生交互的那一半（前台服务 / 通知）在 `:app` 里用 Kotlin 重写（Phase 3），
 * 但字符串与百分比规则必须逐字一致，否则通知内容会变。
 */

/** 通知标题（需求固定）。 */
const val NOTIFICATION_TITLE = "iGRBL 正在雕刻"

/** 文件名最大长度：超长时截断，避免通知正文被系统整段折叠。 */
const val MAX_NAME_LENGTH = 24

/** 进度变化阈值（百分比）：小于该值不触发原生通知刷新，避免刷爆通知。 */
const val PROGRESS_STEP = 1

/**
 * 文件名截断：过长时保留前 [MAX_NAME_LENGTH] 个字符并追加省略号。
 *
 * ⚠️ v2 用的是 JS `String.length` / `slice`，按 UTF-16 code unit 计数，
 * Kotlin 的 `String.length` / `substring` 语义相同（连代理对会各算两个字符这点也一致）。
 */
fun truncateName(name: String?): String {
    // 注意：必须用 jsTrim（JS 的 trim 会去掉 U+FEFF/BOM，Kotlin 的 trim() 不会）
    val trimmed = jsTrim(name ?: "")
    if (trimmed.length <= MAX_NAME_LENGTH) return trimmed
    return trimmed.substring(0, MAX_NAME_LENGTH) + "…"
}

/** 百分比换算：total 为 0（或非法）时返回 0，避免除零产生 NaN。 */
fun percentOf(executed: Double, total: Double): Int {
    if (!total.isFinite() || total <= 0.0) return 0
    val value = (executed / total) * 100.0
    if (!value.isFinite()) return 0
    return max(0, min(100, jsRound(value)))
}

/** 规整外部传入的百分比（0~100 整数）。 */
internal fun clampPercent(percent: Double): Int {
    if (!percent.isFinite()) return 0
    return max(0, min(100, jsRound(percent)))
}

/** 通知正文：`<文件名> · <百分比>%`。 */
fun formatText(name: String?, percent: Double): String {
    val label = truncateName(name)
    return if (label.isNotEmpty()) "$label · ${clampPercent(percent)}%" else "${clampPercent(percent)}%"
}

/**
 * 等价于 JS `Math.round`：**四舍五入但 .5 向 +∞ 取整**（`Math.round(-2.5) == -2`）。
 * Kotlin 的 `roundToInt()` 与之相同；这里包一层是为了把语义写在名字上。
 */
internal fun jsRound(value: Double): Int = value.roundToInt()
