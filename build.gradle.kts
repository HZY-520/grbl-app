// iGRBL 3.0 顶层构建脚本
// 版本集中在 gradle/libs.versions.toml；工具链与上游 Kyant0/AndroidLiquidGlass 对齐。
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.jetbrains.compose) apply false
}
