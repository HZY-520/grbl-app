import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core —— iGRBL 3.0 的核心逻辑（v2 的 src/core 10,393 行 TS 移植目标）
//
// 设计约束：
//   * 纯 Kotlin、无 Android 依赖的部分放在 commonMain，jvm() 目标用于快速单元测试；
//   * 需要平台能力的地方（SVG 光栅化、文字光栅化、文件 IO）走 expect/actual；
//   * 移植期间以 v2 的 TS 实现为 oracle，用 tools/golden 生成黄金样本做回归
//     （见 docs/UI-3.0-PLAN.md §5）。
plugins {
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    android {
        minSdk = 24
        compileSdk = 37
        buildToolsVersion = "37.0.0"
        namespace = "com.lasergrbl.core"
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    jvm()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // 设置 / 设备档案的本地持久化：v2 用 localStorage + JSON，Kotlin 侧用同一个 JSON 格式
            // （只用到 JsonElement API，不需要序列化编译器插件）
            implementation(libs.kotlinx.serialization.json)
            // 串口抽象与 GrblCore 的定时/挂起逻辑
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            // GrblCore 的测试需要虚拟时间：4 ms 发送节拍、握手等待（400/350/250/150 ms）、
            // 10 s 连接超时都靠 runTest + advanceTimeBy 推进，否则测试会真的等上十几秒。
            // 该产物已在本地 Gradle 缓存里（1.11.0，与 kotlinx-coroutines-core 同版本）。
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }

        // 黄金样本比对测试跑在 JVM 上（样本是 JSON，用序列化库解析 JsonElement 即可）
        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }

        all {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }
}
