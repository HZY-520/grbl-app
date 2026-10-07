pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Phase 3 外设层：usb-serial-for-android 只发布在 JitPack（v2 的 android/build.gradle 也一样）。
        // 限定 group，避免其它依赖被意外地从 JitPack 解析。
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mik3y") }
        }
    }
}

rootProject.name = "iGRBL"

// iGRBL 3.0 原生工程
include(":app")
include(":glasskit")
include(":core")

// ⚠️ 遗留：v2 的 Capacitor 工程位于 android/，有它自己的 Gradle 构建，不纳入本工程。
//    3.0 完成后连同 src/ 一起删除（见 docs/UI-3.0-PLAN.md §9 Phase 5）。
