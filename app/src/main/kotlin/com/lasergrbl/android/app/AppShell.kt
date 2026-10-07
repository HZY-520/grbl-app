package com.lasergrbl.android.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import com.kyant.backdrop.catalog.components.LiquidButton
import com.lasergrbl.core.grbl.MacStatus
import com.lasergrbl.glasskit.LiquidDialog
import com.lasergrbl.glasskit.LiquidScaffold
import com.lasergrbl.glasskit.LiquidToastHost
import com.lasergrbl.glasskit.theme.LiquidTheme

/** 当前应用容器（导航器 + 状态层 + 浮层宿主），由 [AppShell] 提供给所有屏幕。 */
/**
 * 当前会话的容器。屏幕通过它拿状态层与导航器（少一层参数透传）。
 *
 * 可空版本 [LocalAppOrNull] 供**不保证挂在 AppShell 里**的通用组件使用
 * （例如 `GlassScreenBody` 要读设置、组件预览里渲染单个卡片）。
 * 直接读本 local 而不在 AppShell 内会抛异常 —— 这是刻意的，能及早发现接错位置。
 */
val LocalApp = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 未提供：屏幕必须挂在 AppShell 内")
}

/** [LocalApp] 的可空版本：未提供时为 `null`，不抛异常。 */
val LocalAppOrNull = staticCompositionLocalOf<AppContainer?> { null }

/**
 * 应用壳层 —— 对应 v2 `src/App.vue`（102 行）。
 *
 * 结构（与 v2 严格对应）：
 * ```
 * LiquidScaffold(title = 当前屏 meta.title,
 *                navigation = 返回按钮或品牌标识,
 *                actions    = 连接状态胶囊（点进 /connect）,
 *                bottomBar  = 5 标签栏（仅 tab 页）,
 *                overlay    = Toast + 设置向导 + 确认框（同窗口浮层）)
 * ```
 *
 * ⚠️ **浮层必须留在 [LiquidScaffold] 的 `overlay` 槽里**（Phase 1 的架构结论）：
 * 用 `Popup`/`Dialog` 会另开窗口，玻璃就取不到背后的内容。
 *
 * 实现细节：`LiquidScaffold` 只在 `content`/`bottomBar`/`overlay` 槽里给出它内部的
 * `backdrop`，而 `navigation`/`actions` 槽拿不到。所以顶栏那两处玻璃件用壳层自己
 * 建的 backdrop —— 它们与内容层折射的是同一片极光背景，视觉一致。
 */
@Composable
fun AppShell(container: AppContainer) {
    val navigator = container.navigator
    val grbl = container.grbl
    val screen = navigator.current

    val topBackdrop = rememberLayerBackdrop()

    // 返回键：先弹返回栈；栈空时交给系统（在首页按返回即退出应用）
    BackHandler(enabled = navigator.canGoBack) { navigator.back() }

    LiquidScaffold(
        title = screen.title,
        navigation = {
            if (navigator.canGoBack) {
                LiquidButton(
                    onClick = { navigator.back() },
                    backdrop = topBackdrop,
                    modifier = Modifier.width(42.dp).height(34.dp)
                ) {
                    GlassIcon("back", size = 18.dp, tint = LiquidTheme.colors.text)
                }
            } else {
                GlassText(
                    text = "iGRBL",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LiquidTheme.colors.text
                )
            }
        },
        actions = {
            ConnectionPill(
                backdrop = topBackdrop,
                status = grbl.status,
                connected = grbl.connected,
                connecting = grbl.connecting,
                onClick = { navigator.push(Screen.Connect) }
            )
        },
        bottomBar = { backdrop ->
            // v2 只在有 meta.tab 的路由上渲染标签栏
            val activeTab = screen.tab
            if (activeTab != null) {
                AppTabBar(activeTab, { navigator.selectTab(it) }, backdrop)
            }
        },
        overlay = { backdrop ->
            // 同窗口浮层：Toast 常驻；向导按 needsSetup 挂载卸载；确认框 / 屏幕级弹层按状态渲染
            LiquidToastHost(controller = container.toasts, backdrop = backdrop)
            if (grbl.needsSetup) {
                SetupWizardOverlay(container, backdrop)
            }
            val confirm = container.confirm
            if (confirm != null) {
                LiquidDialog(
                    backdrop = backdrop,
                    onDismissRequest = {
                        confirm.onDismiss()
                        container.confirm = null
                    },
                    title = confirm.title,
                    message = confirm.message,
                    confirmText = confirm.confirmText,
                    destructive = confirm.destructive,
                    onConfirm = {
                        confirm.onConfirm()
                        container.confirm = null
                    }
                )
            }
            // 屏幕级浮层（底部弹层等）：由屏幕通过 container.screenOverlay 注册
            container.screenOverlay?.invoke(backdrop)
        }
    ) { backdrop ->
        // 切屏时清掉屏幕级浮层，避免上一个屏幕的弹层跨屏残留
        LaunchedEffect(screen) {
            container.screenOverlay = null
        }
        CompositionLocalProvider(
            LocalApp provides container,
            LocalAppOrNull provides container
        ) {
            ScreenHost(container, backdrop)
        }
    }
}

/**
 * 连接状态胶囊 —— 对应 v2 `App.vue:73-77` 的手写 `.lg-status` 按钮。
 *
 * 状态派生只在 [statusBadgeKind] 里写一份（v2 在 `App.vue` 与 `HomeView.vue` 各有一份）。
 */
@Composable
private fun ConnectionPill(
    backdrop: Backdrop,
    status: MacStatus,
    connected: Boolean,
    connecting: Boolean,
    onClick: () -> Unit
) {
    val colors = LiquidTheme.colors
    val dot = when (statusBadgeKind(status, connected, connecting)) {
        BadgeKind.Ok -> colors.success
        BadgeKind.Warn -> colors.warning
        BadgeKind.Err -> colors.danger
        BadgeKind.Idle -> colors.textFaint
    }
    val label = if (connecting) "连接中" else if (!connected) "未连接" else status.label()
    LiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = Modifier.height(34.dp)
    ) {
        Canvas(Modifier.size(7.dp)) { drawCircle(color = dot) }
        Spacer(Modifier.width(6.dp))
        GlassText(label, color = colors.text, fontSize = 12.sp)
    }
}

/** 底部标签栏（5 项，顺序与 v2 `App.vue:12-18` 一致）。 */
@Composable
private fun AppTabBar(selected: Tab, onSelect: (Tab) -> Unit, backdrop: Backdrop) {
    val tabs = Tab.entries
    val colors = LiquidTheme.colors
    LiquidBottomTabs(
        selectedTabIndex = { tabs.indexOf(selected).coerceAtLeast(0) },
        onTabSelected = { onSelect(tabs[it]) },
        backdrop = backdrop,
        tabsCount = tabs.size,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(58.dp)
    ) {
        tabs.forEach { tab ->
            LiquidBottomTab(onClick = { onSelect(tab) }) {
                val active = tab == selected
                GlassIcon(
                    name = tab.icon,
                    size = 21.dp,
                    tint = if (active) colors.accent else colors.textDim
                )
                GlassText(
                    text = tab.label,
                    color = if (active) colors.accent else colors.textDim,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/** 屏幕分发：13 个屏固定，用 `when` 做编译期穷尽检查，与 v2 的静态路由表等价。 */
@Composable
private fun ScreenHost(container: AppContainer, backdrop: Backdrop) {
    when (val screen = container.navigator.current) {
        Screen.Home -> HomeScreen(container, backdrop)
        Screen.Convert -> ConvertHubScreen(container, backdrop)
        is Screen.ConvertImage -> ImageConvertScreen(container, backdrop, screen)
        is Screen.ConvertText -> TextConvertScreen(container, backdrop, screen)
        is Screen.ConvertSvg -> SvgConvertScreen(container, backdrop, screen)
        Screen.File -> FileScreen(container, backdrop)
        is Screen.Preview -> PreviewScreen(container, backdrop, screen)
        Screen.Jog -> JogScreen(container, backdrop)
        Screen.Connect -> ConnectScreen(container, backdrop)
        Screen.Config -> ConfigScreen(container, backdrop)
        Screen.Terminal -> TerminalScreen(container, backdrop)
        Screen.Settings -> SettingsScreen(container, backdrop)
        Screen.About -> AboutScreen(container, backdrop)
    }
}

/** 可点击的入口卡片（首页 / 生成中心 / 设置页多处复用）。 */
@Composable
fun GlassNavCard(
    backdrop: Backdrop,
    icon: String,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = LiquidTheme.colors
    // ⚠️ `onClick` 必须真的传下去 —— 这里曾经漏传，导致 9 张入口卡全是纯装饰。
    GlassCard(
        backdrop = backdrop,
        modifier = modifier,
        onClick = if (enabled) onClick else null
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassIcon(
                name = icon,
                size = 22.dp,
                tint = if (enabled) colors.accent else colors.textFaint
            )
            Column(Modifier.weight(1f)) {
                GlassText(
                    title,
                    color = if (enabled) colors.text else colors.textDim,
                    fontSize = 15.sp
                )
                GlassText(description, color = colors.textFaint, fontSize = 12.sp)
            }
            GlassIcon(name = "forward", size = 18.dp, tint = colors.textFaint)
        }
    }
}
