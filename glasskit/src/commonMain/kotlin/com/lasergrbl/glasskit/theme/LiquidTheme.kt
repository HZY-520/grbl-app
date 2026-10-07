package com.lasergrbl.glasskit.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iGRBL 3.0 设计令牌。
 *
 * ⚠️ 这里**不定义任何视觉风格**：色板、圆角、间距全部沿用 v2（`src/styles/glass.css` 的 `--g-*` 令牌），
 * 玻璃效果本身完全交给 Kyant0 的 `drawBackdrop` 引擎（见 docs/UI-3.0-PLAN.md §4）。
 * 本文件只做「把 v2 的令牌搬到 Compose」这一件事，方便 Phase 4 逐页对齐。
 */
@Immutable
data class LiquidColors(
    val isLight: Boolean,
    val background: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val hairline: Color,
    /** 压在玻璃之上的表面色（v2 的 --g-fill 系列） */
    val fill1: Color,
    val fill2: Color,
    val fill3: Color,
    /**
     * 模态玻璃面（底部弹层 / 对话框 / Toast）的叠加色。
     * 这些浮层盖在**内容**之上，必须比卡片更不透明，否则背后的文字会透上来（v2 的 `.g-sheet` 也是近乎实底的）。
     */
    val modalSurface: Color,
    /** 无玻璃能力（API < 31）时的卡片纯色底 */
    val fallbackSurface: Color,
    val scrim: Color
)

/** 极光色板（v2 `glass.css` 的 .g-aurora 四团）。 */
@Immutable
data class LiquidAuroraColors(
    val blob1: Color,
    val blob2: Color,
    val blob3: Color,
    val blob4: Color
)

@Immutable
data class LiquidDimens(
    val cardRadius: Dp = 26.dp,
    val controlRadius: Dp = 18.dp,
    val fieldRadius: Dp = 14.dp,
    val gap: Dp = 12.dp,
    val pad: Dp = 14.dp,
    val topBarHeight: Dp = 52.dp,
    val tabBarHeight: Dp = 64.dp,
    val screenPadding: Dp = 14.dp
)

/** 文字样式（v2 的字号节奏；颜色由使用处按当前主题给定）。 */
object LiquidType {
    val display = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val sectionTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 15.sp)
    val label = TextStyle(fontSize = 13.sp)
    val caption = TextStyle(fontSize = 12.sp)
    val stat = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val mono = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}

private val DarkColors = LiquidColors(
    isLight = false,
    background = Color(0xFF05060A),
    text = Color(0xFFF5F5F7),
    textDim = Color(0xFF9B9BA6),
    textFaint = Color(0xFF6C6C78),
    accent = Color(0xFFFF8A2B),
    accentStrong = Color(0xFFFF6A00),
    accentSoft = Color(0x29FF8A2B),
    success = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    danger = Color(0xFFFF453A),
    info = Color(0xFF0A84FF),
    hairline = Color(0x1FFFFFFF),
    fill1 = Color(0x12FFFFFF),
    fill2 = Color(0x1CFFFFFF),
    fill3 = Color(0x29FFFFFF),
    modalSurface = Color(0xD914151C),
    fallbackSurface = Color(0xE616171E),
    scrim = Color(0xB805060A)
)

private val LightColors = LiquidColors(
    isLight = true,
    background = Color(0xFFEEF0F6),
    text = Color(0xFF101014),
    textDim = Color(0xFF5A5A66),
    textFaint = Color(0xFF8A8A96),
    accent = Color(0xFFF07800),
    accentStrong = Color(0xFFD96600),
    accentSoft = Color(0x29F07800),
    success = Color(0xFF248A3D),
    warning = Color(0xFFC93400),
    danger = Color(0xFFD70015),
    info = Color(0xFF0071E3),
    hairline = Color(0x1F000000),
    fill1 = Color(0x14000000),
    fill2 = Color(0x1F000000),
    fill3 = Color(0x29000000),
    modalSurface = Color(0xD9F5F6FA),
    fallbackSurface = Color(0xE6FFFFFF),
    scrim = Color(0x99000000)
)

private val DarkAurora = LiquidAuroraColors(
    blob1 = Color(0xFFFF8A2B),
    blob2 = Color(0xFF5E3EF0),
    blob3 = Color(0xFF10A8C4),
    blob4 = Color(0xFFE03094)
)

private val LightAurora = LiquidAuroraColors(
    blob1 = Color(0xFFFFA53D),
    blob2 = Color(0xFF7B5CFF),
    blob3 = Color(0xFF35C4DC),
    blob4 = Color(0xFFFF6FB5)
)

internal val LocalLiquidColors = staticCompositionLocalOf { DarkColors }
internal val LocalLiquidAuroraColors = staticCompositionLocalOf { DarkAurora }
internal val LocalLiquidDimens = staticCompositionLocalOf { LiquidDimens() }

object LiquidTheme {
    val colors: LiquidColors
        @Composable @ReadOnlyComposable get() = LocalLiquidColors.current

    val aurora: LiquidAuroraColors
        @Composable @ReadOnlyComposable get() = LocalLiquidAuroraColors.current

    val dimens: LiquidDimens
        @Composable @ReadOnlyComposable get() = LocalLiquidDimens.current
}

@Composable
fun LiquidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalLiquidColors provides if (darkTheme) DarkColors else LightColors,
        LocalLiquidAuroraColors provides if (darkTheme) DarkAurora else LightAurora,
        LocalLiquidDimens provides LiquidDimens(),
        content = content
    )
}
