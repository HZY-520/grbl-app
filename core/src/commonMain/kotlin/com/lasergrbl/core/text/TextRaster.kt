package com.lasergrbl.core.text

import com.lasergrbl.core.vector.PotraceImage

/**
 * 文字光栅化接缝 —— 对应 v2 `src/core/vector/ImageVector.ts` 的 `renderTextToImage()`。
 *
 * ### 为什么必须放在平台层
 * v2 用浏览器 Canvas 做文字排版与栅格化：
 * `ctx.font = '700 180px <family>'` → `measureText`（`actualBoundingBoxAscent/Descent`）
 * → `fillText` / `strokeText` → `getImageData`。`:core` 是纯 Kotlin（KMP），
 * 既没有字体引擎也没有像素画布，所以这一步只能由平台实现；
 * 而"排版决策"（行距、pad、竖排旋转、墨迹裁剪、`pxPerMm` 换算）是**纯算术**，
 * 留在 [TextRasterizer] 的实现里反而会两边漂移 —— 因此约定：
 *
 *  * **平台负责**：画字 + 取像素（[TextRasterizer.render] 的实现）；
 *  * **算法负责**：`convertTextVector` 里的"渲染 → convertImageVector"编排放在 `:core`。
 *
 * 3.0 的实现是 `com.lasergrbl.android.platform.AndroidTextRasterizer`（Android `Canvas` + `Paint`）。
 *
 * ⚠️ **一条无法消除的差异**：WebView 的字体栈（PingFang SC / Noto Sans CJK SC / …）与
 * Android 平台的 `Typeface.DEFAULT` 不是同一套字体，**同一段中文的墨迹包围盒必然不同**。
 * 因此"文字转雕刻"的输出**不可能与 v2 逐像素一致**，3.0 只能保证算法与参数一致。
 * 这条已记录在 `docs/PHASE3-PLATFORM-MODULES.md` §5。
 */

/** v2 的默认中文字体栈（仅作为"提示"，Android 侧不一定装有这些字体）。 */
const val DEFAULT_TEXT_FONT =
    "\"PingFang SC\", \"Noto Sans CJK SC\", \"Source Han Sans SC\", \"Microsoft YaHei\", sans-serif"

/** 文字排版方向（对应 v2 的 `orientation: 'horizontal' | 'vertical'`）。 */
enum class TextOrientation(val value: String) {
    Horizontal("horizontal"),
    Vertical("vertical");

    companion object {
        fun fromValue(value: String): TextOrientation? = entries.firstOrNull { it.value == value }
    }
}

/** 文字渲染选项（对应 v2 `TextRenderOptions`）。 */
data class TextRenderOptions(
    val text: String,
    /** 单字高度 (mm)。 */
    val sizeMm: Double,
    val bold: Boolean,
    val orientation: TextOrientation,
    /** 行距倍率，默认 1.5。 */
    val lineSpacing: Double = 1.5,
    /** 字体族（v2 是 CSS 字体栈；Android 侧可忽略或映射到 Typeface）。 */
    val fontFamily: String = DEFAULT_TEXT_FONT,
    /** 渲染时的像素字高（越大越精细），默认 180。 */
    val renderSizePx: Int = 180
)

/** 文字渲染结果（对应 v2 `TextRenderResult`）。 */
data class TextRenderResult(
    /** **裁剪后**的位图（只含墨迹区域，含 1 px 边距）。 */
    val image: PotraceImage,
    /** 每毫米对应的像素数。 */
    val pxPerMm: Double,
    /** 文本实际尺寸 (mm)。 */
    val widthMm: Double,
    /** 文本实际尺寸 (mm)。 */
    val heightMm: Double
)

/** 平台侧的文字光栅化器。 */
fun interface TextRasterizer {
    /**
     * 把 [options] 渲染成位图（白底黑字），并裁掉四周空白。
     *
     * 无墨迹（例如只有空格）时必须返回 **1×1 的空图 + `pxPerMm = 1` + 宽高为 0**
     * （与 v2 一致），由调用方决定是否提示用户。
     */
    fun render(options: TextRenderOptions): TextRenderResult
}
