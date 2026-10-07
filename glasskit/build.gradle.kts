import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :glasskit —— iGRBL 3.0 的玻璃组件库
//
// 组成：
//   1) Kyant0/AndroidLiquidGlass 的 5 个示例组件 + 2 个内部工具（**逐字节原样内联**，
//      见 UPSTREAM-SOURCES.json 的 SHA256 清单）——com.kyant.backdrop.catalog.*
//   2) 依据他的 drawBackdrop 引擎原语补齐的组件（Phase 1，需用户逐条确认）
//   3) 视觉基准唯一来源：io.github.kyant0:backdrop + io.github.kyant0:shapes
plugins {
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    android {
        minSdk = 24
        compileSdk = 37
        buildToolsVersion = "37.0.0"
        namespace = "com.lasergrbl.glasskit"
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // api：调用方（:app）需要直接拿到 Backdrop / Capsule 等类型
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.ui.graphics)
            api(libs.kyant.backdrop)
            api(libs.kyant.shapes)
        }

        androidMain.dependencies {
            // 上游 utils/Coroutines.kt 的 actual 实现用到 kotlinx.coroutines.android.awaitFrame
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}
