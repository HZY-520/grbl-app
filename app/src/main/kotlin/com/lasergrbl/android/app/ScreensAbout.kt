package com.lasergrbl.android.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 关于 —— v2 `src/ui/views/AboutView.vue`（150 行）的 Compose 版（`/about`）。
 *
 * ### 逐块对应
 * | v2 | 这里 |
 * | --- | --- |
 * | 版本号（`APP_VERSION` = `'2.0'`） | [APP_VERSION] 常量（见下方说明） |
 * | 标题 + 副标题「Android 激光雕刻控制」 | 头部卡片 |
 * | 功能列表 | 「功能」卡片 |
 * | 开源许可（**GPL-3.0**，逐字保留中文原话） | 「许可」卡片 |
 * | 外链（项目主页 / LaserGRBL / 依赖库） | 可点击的 `GlassNavCard`，走 `ACTION_VIEW` |
 *
 * ### 版本号来源（重要）
 * v2 是 `src/ui/version.ts` 里的手写常量 `'2.0'`；3.0 改成**从 `BuildConfig.VERSION_NAME` 读**
 * （`app/build.gradle.kts` 的 `versionName`），这样 Phase 5 改版本号只需要改一处。
 * `VERSION_NAME` 在 Gradle 配置阶段注入，见 `app/build.gradle.kts` 的 `buildConfigField`。
 *
 * ⚠️ **当前 `versionName` 仍是 `3.0.0-phase0`** —— Phase 5 要改成 `3.0.0`；
 * 在那之前这里会显示 `3.0.0-phase0`（这是**正确**行为，不是 bug）。
 */
@Composable
fun AboutScreen(container: AppContainer, backdrop: Backdrop) {
    val colors = LiquidTheme.colors
    val context = container.context

    fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    GlassScreenBody {
        // ---- 头部 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassText(
                    "iGRBL 3.0",
                    color = colors.text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
                GlassText(
                    "Android 激光雕刻控制 · v$APP_VERSION",
                    color = colors.textDim,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(2.dp))
                GlassText(
                    "基于 LaserGRBL 的协议与算法重写，界面为 3.0 全新玻璃设计。",
                    color = colors.textFaint,
                    fontSize = 12.sp
                )
            }
        }

        // ---- 功能 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassSectionHeader("功能")
                FEATURES.forEach { (title, desc) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        GlassText("·", color = colors.accent, fontSize = 14.sp)
                        Column(Modifier.weight(1f)) {
                            GlassText(title, color = colors.text, fontSize = 13.5.sp)
                            GlassText(desc, color = colors.textFaint, fontSize = 11.5.sp)
                        }
                    }
                }
            }
        }

        // ---- 技术栈 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassSectionHeader("技术栈")
                GlassKeyValue("界面", "Jetpack Compose + 液态玻璃", mono = false)
                GlassKeyValue("核心逻辑", "Kotlin Multiplatform（:core）", mono = false)
                GlassKeyValue("串口", "usb-serial-for-android + 经典蓝牙 SPP", mono = false)
                GlassKeyValue("黄金样本", "17 个夹具 / 302 条目（对照 v2 TypeScript 实现）", mono = false)
            }
        }

        // ---- 许可 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassSectionHeader("开源许可")
                GlassText(
                    "遵循 LaserGRBL 的开源许可协议发布（GPL-3.0）。",
                    color = colors.text,
                    fontSize = 13.sp
                )
                GlassText(
                    "本项目包含第三方组件：Kyant0/AndroidLiquidGlass（Apache-2.0，玻璃渲染引擎）、" +
                        "usb-serial-for-android（Apache-2.0）。",
                    color = colors.textFaint,
                    fontSize = 11.5.sp
                )
            }
        }

        // ---- 外链 ----
        GlassNavCard(
            backdrop = backdrop,
            icon = "link",
            title = "LaserGRBL 项目主页",
            description = "github.com/arkypita/LaserGRBL",
            onClick = { open("https://github.com/arkypita/LaserGRBL") }
        )
        GlassNavCard(
            backdrop = backdrop,
            icon = "layers",
            title = "AndroidLiquidGlass（玻璃引擎）",
            description = "github.com/Kyant0/AndroidLiquidGlass",
            onClick = { open("https://github.com/Kyant0/AndroidLiquidGlass") }
        )
    }
}

/**
 * 应用版本号。
 *
 * ⚠️ 这里**刻意**是手写常量而不是 `BuildConfig.VERSION_NAME`：`:app` 的 Gradle 配置里
 * 没有开 `buildConfig = true`，为了一个字符串去开 `buildConfig` 会多一个生成类、
 * 也会让构建变慢。所以改版本号时，**两处都要改**：
 * `app/build.gradle.kts` 的 `versionName` 与这里的 [APP_VERSION]。
 * （Phase 5 已把两处都改成 `3.0.0`，`versionCode = 30`。）
 */
private const val APP_VERSION = "3.0.0"

/** 功能清单（v2 `AboutView.vue` 的 `features` 数组，逐条保留）。 */
private val FEATURES = listOf(
    "图像转雕刻" to "抖动 / 逐行扫描 / 轮廓描线 / 中心线走线 / 智能选择",
    "文字转雕刻" to "Hershey 单线字体 + 中文骨架化（中心线）",
    "SVG 转雕刻" to "几何解析或光栅化后矢量化",
    "运动控制" to "九宫格点动、步长/速度、覆盖倍率、激光测试",
    "设备连接" to "USB 串口与蓝牙 SPP，支持多机型参数档案",
    "机器参数" to "读写 GRBL 设置（\$\$），导入 .nc 预设",
    "后台保活" to "雕刻期间前台服务 + 常驻进度通知"
)
