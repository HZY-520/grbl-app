package com.lasergrbl.android.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lasergrbl.glasskit.LiquidIcons
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 文本与图标的**极简封装**。
 *
 * 为什么不直接用 `androidx.compose.material3.Text` / `Icon`：**:app 没有 material3 依赖**。
 * 整个项目的视觉由 `:glasskit` 自己的主题与组件定义（刻意不引入 Material），
 * 所以这里文字走 `BasicText`、图标走 `ImageVector` + `rememberVectorPainter`。
 * 这样也顺手避免了"Material 主题色与玻璃主题色两套"的问题。
 */
@Composable
fun GlassText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LiquidTheme.colors.text,
    fontSize: TextUnit = 13.sp,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            textAlign = textAlign ?: TextAlign.Unspecified
        ),
        maxLines = maxLines,
        overflow = overflow
    )
}

/**
 * **按钮标签** —— 所有 `GlassButton` / `GlassIconButton` 里的文字都该用它。
 *
 * 三个约束缺一不可（都是踩出来的）：
 * 1. `fillMaxWidth()`：`LiquidButton` 的 content 是 `RowScope`，子项按自身固有宽度测量，
 *    没拿到有界宽度时 `maxLines` / 省略号**永不生效**，长文字会溢出按钮之外；
 *    拿到有界宽度后短文本由 `textAlign = Center` 居中，视觉上与之前一致。
 * 2. `maxLines = 1` + `Ellipsis`：窄按钮（四列并排 / 1/4 屏宽）里长标签截断而不是折成两行。
 *    实测反例：设置页波特率 `115200` 曾折成「11520」+「0」，通讯模式 `UltraFast` 折成
 *    「Ultr」+「aFa」且**尾部 "st" 被吞** —— 内容真的丢了，不是纯视觉问题。
 * 3. 不自己加 `padding`：内边距由 `GlassButton` 统一给；重复加会挤掉本就紧张的宽度。
 */
@Composable
fun GlassButtonLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LiquidTheme.colors.text,
    fontSize: TextUnit = 13.sp,
    fontWeight: FontWeight? = null
) {
    GlassText(
        text = text,
        modifier = modifier.fillMaxWidth(),
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 波特率的**紧凑显示**（按钮里用）。
 *
 * 为什么不用完整数字：`115200` / `230400` / `250000` 这三位在 1/4 屏宽的窄按钮里
 * **放不下一行**。实测两种表现都不可接受：
 * 折行 → `11520` + `0` 两行（文字被切成两段）；省略 → `1152…`（信息丢失）。
 *
 * 所以改用工程界通用的紧凑写法，且**保证无歧义**（`230.4k` 不能误读成别的速率）：
 * | 值 | 显示 |
 * | --- | --- |
 * | 9600 | `9600` |
 * | 115200 | `115.2k` |
 * | 230400 | `230.4k` |
 * | 250000 | `250k` |
 *
 * 完整数值始终在「波特率」键值行里显示（`115200 bps`），所以信息没有丢失。
 */
fun formatBaud(rate: Int): String =
    if (rate < 100_000) {
        rate.toString()
    } else {
        // 去掉多余的小数位：250000 → 250k 而不是 250.0k
        val k = rate / 1000.0
        if (k == k.toLong().toDouble()) "${k.toLong()}k" else "${k}k"
    }

/** 图标（`LiquidIcons` 的 42 个内置矢量之一）。未知名字自动回退到 `info`。 */
@Composable
fun GlassIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LiquidTheme.colors.text
) {
    val painter = rememberVectorPainter(LiquidIcons.get(name))
    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint)
    )
}
