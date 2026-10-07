package com.lasergrbl.android.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 路由与导航 —— 移植自 v2 `src/router.ts` + `src/App.vue` 的壳层逻辑。
 *
 * ### 为什么不用 `navigation-compose`
 * v2 的导航语义只有三件事：**13 个固定路由**、**5 个 tab 的路由名与 `meta.tab`**、
 * **返回键守卫**（`canGoBack = !showNav && path !== '/home'`）。这些用一个 `BackStack` 就够，
 * 引入 `navigation-compose` 反而要迁就它的 `NavHost`/`NavController` 生命周期，
 * 而且 tab 与浮层的组合会更绕。这里保持"路由名 + 路径字符串"两个概念都在，
 * 因为 v2 的界面是按**路径字符串**跳转的（`router.push('/home')`）。
 *
 * ### 关键约束（来自 §7 与 Phase 1 的架构结论）
 * 顶层浮层（向导 / Toast）**必须**由壳层放进 `LiquidScaffold` 的 `overlay` 槽 ——
 * 用 `Popup`/`Dialog` 会另开窗口，玻璃就取不到背后的内容了。
 */
sealed interface Screen {
    /** v2 的 `meta.title`，导航栏标题。 */
    val title: String

    /** v2 的 `meta.tab`：非空表示这是一个 tab 页（显示底部标签栏）。 */
    val tab: Tab? get() = null

    /** 路径字符串（与 v2 的 `router.push('/...')` 一一对应，便于对照与调试）。 */
    val path: String

    data object Home : Screen {
        override val title = "激光雕刻"
        override val tab = Tab.Home
        override val path = "/home"
    }

    data object Convert : Screen {
        override val title = "图案生成"
        override val tab = Tab.Convert
        override val path = "/convert"
    }

    /** 图片转雕刻。 */
    data class ConvertImage(
        /** 已选图片的 URI（`content://` 或 `file://`）；null 表示还没选。 */
        val sourceUri: String? = null,
        val sourceName: String? = null
    ) : Screen {
        override val title = "图片转雕刻"
        override val path = "/convert/image"
    }

    /** 文字转雕刻。 */
    data class ConvertText(val initialText: String? = null) : Screen {
        override val title = "文字转雕刻"
        override val path = "/convert/text"
    }

    /** SVG 转雕刻。 */
    data class ConvertSvg(val sourceUri: String? = null, val sourceName: String? = null) : Screen {
        override val title = "SVG 转雕刻"
        override val path = "/convert/svg"
    }

    data object File : Screen {
        override val title = "雕刻文件"
        override val tab = Tab.File
        override val path = "/file"
    }

    /** 路径预览（可带放大高度，v2 的 200–640）。 */
    data class Preview(val zoomHeight: Int = 320) : Screen {
        override val title = "路径预览"
        override val path = "/preview"
    }

    data object Jog : Screen {
        override val title = "运动控制"
        override val tab = Tab.Jog
        override val path = "/jog"
    }

    data object Connect : Screen {
        override val title = "连接设备"
        override val path = "/connect"
    }

    data object Config : Screen {
        override val title = "机器参数"
        override val path = "/config"
    }

    data object Terminal : Screen {
        override val title = "串口终端"
        override val path = "/terminal"
    }

    data object Settings : Screen {
        override val title = "设置"
        override val tab = Tab.Settings
        override val path = "/settings"
    }

    data object About : Screen {
        override val title = "关于"
        override val path = "/about"
    }

    companion object {
        /** 按路径字符串查找路由（与 v2 的 `router.push(path)` 语义一致）。 */
        fun fromPath(path: String): Screen? = when (path) {
            "/home" -> Home
            "/convert" -> Convert
            "/convert/image" -> ConvertImage()
            "/convert/text" -> ConvertText()
            "/convert/svg" -> ConvertSvg()
            "/file" -> File
            "/preview" -> Preview()
            "/jog" -> Jog
            "/connect" -> Connect
            "/config" -> Config
            "/terminal" -> Terminal
            "/settings" -> Settings
            "/about" -> About
            else -> null
        }
    }
}

/** 5 个底部标签（顺序与 v2 `App.vue:12-18` 完全一致）。 */
enum class Tab(val label: String, val icon: String, val path: String) {
    Home("首页", "home", "/home"),
    Convert("生成", "grid", "/convert"),
    File("文件", "file", "/file"),
    Jog("点动", "move", "/jog"),
    Settings("设置", "settings", "/settings");

    /** 该标签对应的路由。 */
    fun screen(): Screen = when (this) {
        Home -> Screen.Home
        Convert -> Screen.Convert
        File -> Screen.File
        Jog -> Screen.Jog
        Settings -> Screen.Settings
    }

    companion object {
        /** 从当前屏推断激活的标签（非 tab 页返回 null）。 */
        fun of(screen: Screen): Tab? = screen.tab
    }
}

/**
 * 导航器：当前屏 + 返回栈。
 *
 * 语义与 v2 对齐：
 *  * 切标签 = `push` 到标签屏，并**清空**返回栈（标签之间不互相回退）；
 *  * 详情页 = `push`，返回键先弹详情页；
 *  * 在 `/home` 上没有可回退项（v2 `canGoBack = !showNav && path !== '/home'`）。
 */
class AppNavigator(initial: Screen = Screen.Home) {

    var current by mutableStateOf(initial)
        private set

    private var backStack by mutableStateOf<List<Screen>>(emptyList())

    /** 是否可以回退（决定导航栏是否显示返回按钮、返回键是否被消费）。 */
    val canGoBack: Boolean get() = backStack.isNotEmpty()

    /** 前进到一个详情页（保留返回栈）。 */
    fun push(screen: Screen) {
        if (screen == current) return
        backStack = backStack + current
        current = screen
    }

    /** 切标签：不保留标签之间的返回关系。 */
    fun selectTab(tab: Tab) {
        if (current.tab == tab) return
        backStack = emptyList()
        current = tab.screen()
    }

    /**
     * 返回上一屏。
     *
     * @return 是否消费了这次返回（false 表示已在根，交给系统处理 —— 对应 v2 的
     *   `window.history.length` 守卫与"在首页按返回退出应用"）
     */
    fun back(): Boolean {
        val stack = backStack
        if (stack.isEmpty()) return false
        current = stack.last()
        backStack = stack.dropLast(1)
        return true
    }

    /** 回到根标签页（例如向导完成后回首页）。 */
    fun goHome() = selectTab(Tab.Home)

    /** 直接替换当前屏（不污染返回栈；例如从 `/convert` 换成 `/convert/image` 后不想回退两次）。 */
    fun replace(screen: Screen) {
        current = screen
    }
}
