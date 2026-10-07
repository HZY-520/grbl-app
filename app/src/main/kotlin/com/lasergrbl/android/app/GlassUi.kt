package com.lasergrbl.android.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidButton as UpstreamLiquidButton
import com.kyant.backdrop.catalog.components.LiquidSlider as UpstreamLiquidSlider
import com.kyant.backdrop.catalog.components.LiquidToggle as UpstreamLiquidToggle
import com.lasergrbl.core.grbl.MacStatus
import com.lasergrbl.core.grbl.MessageType
import com.lasergrbl.glasskit.LiquidProgress
import com.lasergrbl.glasskit.LiquidSectionTitle
import com.lasergrbl.glasskit.LiquidSpinner
import com.lasergrbl.glasskit.LiquidSurface
import com.lasergrbl.glasskit.theme.LiquidColors
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 界面惯用法层 —— 把「上游组件 + 玻璃容器」的组合方式固定下来，
 * 让 13 个屏幕写起来只有一种写法。
 *
 * 统一约定：
 *  * 卡片一律 [GlassCard]、按钮一律 [GlassButton]/[GlassIconButton]、开关一律 [GlassSwitch]、
 *    文字一律 [GlassText]、图标一律 [GlassIcon]；
 *  * **不引入 Material**（`:app` 没有 material3 依赖，视觉全部由 `:glasskit` 定义）；
 *  * 弹层只走 `LiquidScaffold` 的 `overlay` 槽（Phase 1 架构结论），见 [GlassConfirmState]。
 */

/** 屏幕内容的滚动容器：统一 padding 与卡片间距，对应 v2 的 `.lg-body`。 */
@Composable
fun GlassScreenBody(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dimens = LiquidTheme.dimens
    // 读「高性能玻璃」开关。用 `LocalApp` 的 `grbl` 作为锚点：`AppSettings` 是普通可变对象，
    // Compose 观察不到它内部的变化；而 `GrblController.settings` 是 `mutableStateOf`，
    // 设置页写完之后它就会变，于是这里能正确重组。
    val container = LocalAppOrNull.current
    val refraction = container?.grbl?.settings?.get("Performance Glass") as? Boolean ?: true
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.screenPadding, vertical = dimens.gap),
        verticalArrangement = Arrangement.spacedBy(dimens.gap)
    ) {
        CompositionLocalProvider(LocalRefractionEnabled provides refraction) {
            content()
        }
    }
}

/**
 * 玻璃卡片（等价 v2 的 `<GlassSurface class="lg-section">`）。
 *
 * @param onClick 非空时整张卡片可点击（入口卡 / 外链卡用）。
 *   ⚠️ 曾经漏接过：`GlassNavCard` 声明了 `onClick` 却忘了往下传，而这里又没有可点击重载，
 *   结果首页 6 张 + 生成中心 3 张入口卡全是**纯装饰**，`/preview` 与三个转换屏
 *   （它们只有这一个入口）在设备上完全不可达。修好后由本参数承载。
 */
@Composable
fun GlassCard(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(LiquidTheme.dimens.pad),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    LiquidSurface(
        backdrop = backdrop,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(LiquidTheme.dimens.cardRadius),
        // 「高性能玻璃」开关（设置页的 `Performance Glass` 键）：
        // 关掉后所有卡片停止折射计算，低端机上明显更流畅。由 GlassScreenBody 提供，
        // 默认 true 以保证不挂屏幕的组件（如 Toast）行为不变。
        refraction = LocalRefractionEnabled.current,
        contentPadding = padding
    ) {
        content()
    }
}

/**
 * 是否启用玻璃折射。
 *
 * 对应 v2 的 `glassConfig.refraction`（设置页的「高性能玻璃」开关）。
 * 由 [GlassScreenBody] 从 `AppSettings` 的 `Performance Glass` 键读取后提供；
 * 默认 `true`，所以不经过 `GlassScreenBody` 的组件（Toast / 向导浮层）行为与之前一致。
 */
val LocalRefractionEnabled = compositionLocalOf { true }

/** 按钮尺寸（对应 v2 `GlassButton` 的 `normal` / `small` / `mini`）。 */
enum class GlassButtonSize { Normal, Small, Mini }

/**
 * 玻璃按钮。
 *
 * @param plain 只描边不填充（次级操作）
 * @param accent 用强调色（主操作）
 * @param block 撑满宽度
 */
@Composable
fun GlassButton(
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: GlassButtonSize = GlassButtonSize.Normal,
    plain: Boolean = false,
    block: Boolean = false,
    enabled: Boolean = true,
    accent: Boolean = false,
    loading: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = LiquidTheme.colors
    val dimens = LiquidTheme.dimens
    val height = when (size) {
        GlassButtonSize.Normal -> 46.dp
        GlassButtonSize.Small -> 36.dp
        GlassButtonSize.Mini -> 30.dp
    }
    val pad = when (size) {
        GlassButtonSize.Normal -> 16.dp
        GlassButtonSize.Small -> 12.dp
        GlassButtonSize.Mini -> 8.dp
    }
    Box(
        modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .alpha(if (enabled && !loading) 1f else 0.45f)
    ) {
        UpstreamLiquidButton(
            onClick = { if (enabled && !loading) onClick() },
            backdrop = backdrop,
            // ⚠️ 必须把**外部约束传下去**（`fillMaxWidth` / `weight` / `widthIn` 都在这里）：
            // 只给一个 `height` 的话，`weight(1f)` 的宽度约束传不进这个 Row，
            // 内容（图标 + 文字）就会溢出按钮边界 —— 四列并排的任务按钮上肉眼可见。
            modifier = Modifier
                .then(if (block) Modifier.fillMaxWidth() else Modifier)
                .then(modifier)
                .height(height),
            isInteractive = enabled && !loading,
            surfaceColor = when {
                accent -> colors.accentSoft
                plain -> Color.Transparent
                else -> colors.fill1
            }
        ) {
            // ⚠️ 这里**只能**在 `block = true` 时才 `fillMaxWidth()`。
            //
            // 背景（两次踩坑，别再来第三次）：`LiquidButton` 的 content 是 `RowScope.() -> Unit`，
            // 子项按"自身固有宽度"测量，**不继承**按钮宽度约束 —— 所以 `maxLines=1` + 省略号
            // 永不触发，长文字会溢出按钮之外（四列并排的窄按钮上肉眼可见）。
            // 当时加了无条件 `fillMaxWidth()` 来修溢出，但那是**过度修正**：
            // `LiquidButton` 的根节点本身就是一个 wrap-content 的 `Row`，
            // 内层无条件 `fillMaxWidth()` 会向上取整行 maxWidth，于是
            // `Row { 文本列.weight(1f); GlassButton(...) }` 里的文本列被压到 ≈0 ——
            // 实测表现为文字**每个字一行竖排**、卡片高度爆炸（`uiautomator` 实测某标签
            // 只分到 21×49 px，而按钮 297×116 px）。
            //
            // 正解：`block = true`（`fillMaxWidth` 的按钮）才铺满；其余交给
            // `width(IntrinsicSize.Max)` —— 它按内容理想宽度定宽，但**受父级上界约束**，
            // 所以窄按钮里 `GlassText` 仍能拿到有界宽度而正常省略，同时不抢兄弟的宽度。
            Row(
                Modifier
                    .then(
                        if (block) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.width(IntrinsicSize.Max)
                        }
                    )
                    .padding(horizontal = pad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (loading) LiquidSpinner(size = 14.dp, color = colors.textDim)
                content()
            }
        }
    }
}

/** 带图标 + 文字的按钮（v2 里最常见的组合）。 */
@Composable
fun GlassIconButton(
    backdrop: Backdrop,
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: GlassButtonSize = GlassButtonSize.Normal,
    plain: Boolean = false,
    enabled: Boolean = true,
    accent: Boolean = false,
    block: Boolean = false,
    /**
     * 是否显示图标。
     *
     * ⚠️ **四列并排的窄按钮请传 false**：中文标签（「运行」两个字）加上图标后
     * 在 1/4 宽度里放不下，会被省略号截成「运…」。这是实测过的渲染结果，
     * 不是预防性设计（见 `docs/PHASE4-PROGRESS.md` 的截图对照）。
     */
    showIcon: Boolean = true
) {
    val colors = LiquidTheme.colors
    GlassButton(
        backdrop = backdrop,
        onClick = onClick,
        modifier = modifier,
        size = size,
        plain = plain,
        enabled = enabled,
        accent = accent,
        block = block
    ) {
        if (showIcon) {
            GlassIcon(
                name = icon,
                size = if (size == GlassButtonSize.Normal) 20.dp else 16.dp,
                tint = if (enabled) colors.text else colors.textFaint
            )
        }
        // 用按钮专用标签：拿到有界宽度才会真正省略（见 GlassButtonLabel 的说明）。
        GlassButtonLabel(
            text = label,
            color = if (enabled) colors.text else colors.textFaint,
            fontSize = when (size) {
                GlassButtonSize.Normal -> 14.sp
                GlassButtonSize.Small -> 13.sp
                GlassButtonSize.Mini -> 12.sp
            }
        )
    }
}

/** 开关。 */
@Composable
fun GlassSwitch(
    backdrop: Backdrop,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    UpstreamLiquidToggle(
        selected = { checked },
        onSelect = { if (enabled) onCheckedChange(it) },
        backdrop = backdrop,
        modifier = modifier
    )
}

/** 带标题的开关行（设置页最常见的形态）。 */
@Composable
fun GlassSwitchRow(
    backdrop: Backdrop,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true
) {
    val colors = LiquidTheme.colors
    GlassCard(backdrop, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GlassText(title, color = colors.text, fontSize = 14.5.sp)
                if (description != null) {
                    Spacer(Modifier.height(2.dp))
                    GlassText(description, color = colors.textFaint, fontSize = 12.sp)
                }
            }
            GlassSwitch(backdrop, checked, onCheckedChange, enabled = enabled)
        }
    }
}

/** 滑块行：标题 + 数值 + 滑块。 */
@Composable
fun GlassSliderRow(
    backdrop: Backdrop,
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    suffix: String = "",
    enabled: Boolean = true,
    decimals: Int = 0
) {
    val colors = LiquidTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassText(title, color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            GlassText(formatNumber(value, decimals) + suffix, color = colors.textDim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        UpstreamLiquidSlider(
            value = { value },
            onValueChange = { if (enabled) onValueChange(it) },
            valueRange = valueRange,
            visibilityThreshold = if (steps > 0) {
                (valueRange.endInclusive - valueRange.start) / (steps + 1)
            } else {
                0f
            },
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 数值格式化（整数不带小数点，与 v2 的显示习惯一致）。 */
fun formatNumber(v: Float, decimals: Int): String =
    if (decimals <= 0) v.toInt().toString() else String.format("%.${decimals}f", v)

/** 键值行（对应 v2 的 `.lg-kv`）。 */
@Composable
fun GlassKeyValue(key: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    val colors = LiquidTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        GlassText(key, color = colors.textDim, fontSize = 13.sp)
        GlassText(
            value,
            color = colors.text,
            fontSize = 13.sp,
            fontFamily = if (mono) FontFamily.Monospace else null
        )
    }
}

/** 统计块（对应 v2 的 `.lg-stat`）。 */
@Composable
fun GlassStat(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    val colors = LiquidTheme.colors
    Column(modifier) {
        GlassText(label, color = colors.textFaint, fontSize = 11.5.sp)
        Spacer(Modifier.height(2.dp))
        GlassText(
            value,
            color = accent ?: colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** 徽标语义色。 */
enum class BadgeKind { Ok, Warn, Err, Idle }

/** 状态徽标（对应 v2 的 `.lg-badge`）。 */
@Composable
fun GlassBadge(text: String, kind: BadgeKind = BadgeKind.Idle, modifier: Modifier = Modifier) {
    val colors = LiquidTheme.colors
    val color = when (kind) {
        BadgeKind.Ok -> colors.success
        BadgeKind.Warn -> colors.warning
        BadgeKind.Err -> colors.danger
        BadgeKind.Idle -> colors.textFaint
    }
    Box(modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        GlassText(text, color = color, fontSize = 11.5.sp)
    }
}

/** 状态 → 徽标语义（v2 在 App.vue 与 HomeView 里各写了一遍；这里只留一份）。 */
fun statusBadgeKind(status: MacStatus, connected: Boolean, connecting: Boolean): BadgeKind = when {
    connecting -> BadgeKind.Warn
    !connected -> BadgeKind.Idle
    status == MacStatus.Alarm -> BadgeKind.Err
    status == MacStatus.Run || status == MacStatus.Jog -> BadgeKind.Ok
    status == MacStatus.Hold || status == MacStatus.Door || status == MacStatus.AutoHold -> BadgeKind.Warn
    else -> BadgeKind.Ok
}

/** 日志行颜色（对应 v2 `.lg-log__line--*`）。 */
fun logLineColor(kind: MessageType, colors: LiquidColors): Color = when (kind) {
    MessageType.Alarm -> colors.danger
    MessageType.Warning -> colors.warning
    MessageType.Startup, MessageType.Feedback -> colors.success
    MessageType.Command -> colors.accent
    MessageType.Config -> colors.info
    else -> colors.textDim
}

/** 分节标题（对应 v2 的 `.lg-title`）。 */
@Composable
fun GlassSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    LiquidSectionTitle(text = title, modifier = modifier, trailing = trailing)
}

/** 进度条（0..1）。 */
@Composable
fun GlassProgressLine(fraction: Float, modifier: Modifier = Modifier, showLabel: Boolean = false) {
    LiquidProgress(
        value = fraction.coerceIn(0f, 1f),
        modifier = modifier.fillMaxWidth(),
        showLabel = showLabel
    )
}

/** 空状态提示（对应 v2 的 `.lg-empty`）。 */
@Composable
fun GlassEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        GlassText(text, color = LiquidTheme.colors.textFaint, fontSize = 13.sp)
    }
}

/** 确认框的待确认内容（屏幕用 `container.confirm = ...` 触发，由 `AppShell` 的 overlay 槽渲染）。 */
data class GlassConfirmState(
    val title: String,
    val message: String? = null,
    val confirmText: String = "确定",
    val destructive: Boolean = false,
    val onConfirm: () -> Unit = {},
    val onDismiss: () -> Unit = {}
)

/** 记住一个可空的本地确认状态（需要一个屏幕内的 `var` 时用）。 */
@Composable
fun rememberConfirmState(): Pair<GlassConfirmState?, (GlassConfirmState?) -> Unit> {
    var state by remember { mutableStateOf<GlassConfirmState?>(null) }
    return state to { state = it }
}

/** 常用尺寸别名，减少屏幕里的魔法数字。 */
object GlassDimens {
    val buttonHeight: Dp = 46.dp
    val iconSize: Dp = 20.dp
    val hairline: Dp = 1.dp
    val controlWidth: Dp = 60.dp
    val gapTiny: Dp = 4.dp
    val gapSmall: Dp = 8.dp
}
