package com.lasergrbl.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.lasergrbl.android.app.AppContainerHolder
import com.lasergrbl.android.app.AppShell
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 应用唯一 Activity —— 对应 v2 的 `MainActivity` + `App.vue` 的组合。
 *
 * 3.0 与 v2 的差异：
 *  * v2 在 `MainActivity.java` 里 `registerPlugin` 三个 Capacitor 插件（串口/蓝牙/保活）；
 *    3.0 这些能力已经是进程内的 Kotlin 实现（`:app/serial`、`:app/keepalive`），
 *    由 [IgRblApplication] 装配进 `AppContainer`，**不需要任何桥接**；
 *  * v2 的主题是 `html[data-theme]`；3.0 是 `LiquidTheme(darkTheme = ...)`，
 *    并且**修掉了 v2 的缺陷：主题现在会持久化**（v2 的 `setTheme` 从不写 localStorage，
 *    刷新就回到深色 —— 见 `docs/V2-UI-INVENTORY.md` §4.4 的注记）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val app = application as IgRblApplication
        app.attachActivity(this)
        val container = AppContainerHolder.create(app)

        setContent {
            // 默认深色（v2 同为深色默认）；用户可在设置页切换，且会持久化
            LiquidTheme(darkTheme = !container.grbl.themeIsLight) {
                AppShell(container)
            }
        }
    }

    override fun onDestroy() {
        (application as IgRblApplication).detachActivity(this)
        super.onDestroy()
    }
}
