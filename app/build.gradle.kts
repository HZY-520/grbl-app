// :app —— iGRBL 3.0 的 Android 应用
// 工具链与上游 androidApp 模块一致（AGP 9 内置 Kotlin + Compose 编译器插件）。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lasergrbl.android"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.lasergrbl.android"
        minSdk = 24
        targetSdk = 37
        versionCode = 30
        versionName = "3.0.0"
        androidResources.localeFilters += arrayOf("zh", "en")
    }

    signingConfigs {
        // 复用 v2 的自签名密钥，Phase 5 发布时启用。
        // 密钥在 Phase 5 从旧的 Capacitor 工程 `android/` 移到了仓库根的 `keystore/`
        // （随 `android/` 一起删除会导致 release 签名失败）。
        create("release") {
            storeFile = rootProject.file("keystore/lasergrbl-release.jks")
            storePassword = "lasergrbl"
            keyAlias = "lasergrbl"
            keyPassword = "lasergrbl"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            vcsInfo.include = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += arrayOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt"
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(project(":glasskit"))
    implementation(project(":core"))

    // 协程要显式钉在 catalog 的 1.11.0 上：:core 用 1.11.0，而 :app 的
    // debugCompileClasspath 会被某些传递依赖压到 1.9.0，两边版本不一致时
    // 「同一个 Dispatchers/Job 类型」会出现两份，编译期就会报类型不匹配。
    // kotlinx-coroutines-android 同时带来 Dispatchers.Main（Phase 4 的 UI 接线要用）。
    implementation(libs.kotlinx.coroutines.android)

    // USB 串口驱动库（Phase 3 外设层），版本在 gradle/libs.versions.toml 的 usbSerial。
    // 版本 3.11.0 = JitPack 上该库当前的最新稳定发行版（maven-metadata 的 <release>），
    // 理由：① 我逐一 javap 核对了本工程用到的 API（UsbSerialProber.getDefaultProber /
    // findAllDrivers、UsbSerialDriver.device / ports、UsbSerialPort.open / setParameters /
    // setDTR / setRTS / read(byte[],int) / write(byte[],int) / close）在 3.11.0 全部存在且签名不变；
    // ② minSdk 仍是 17（本工程 24），字节码仍是 Java 8（major 52），AAR 无 aar-metadata 约束，
    // 不会与 compileSdk 37 / AGP 9.3.2 冲突；③ 比 v2 用的 3.7.0 多两年半的设备兼容修复
    // （CH34x / CP210x / FTDI 等）。传递依赖只有 kotlin-stdlib 与 androidx.annotation，
    // 二者本工程已有且版本更高。
    // 注意：该库只发布在 JitPack，仓库声明见 settings.gradle.kts。
    // 别名用 usb-serial-driver 而不是 usb-serial-for-android：后者的访问器含 Kotlin 关键字 `for`，
    // 加反引号也解析不了（实测 "Unresolved reference 'for'"），见 gradle/libs.versions.toml。
    implementation(libs.usb.serial.driver)

    // JVM 单测（无需真机/模拟器）：写顺序、拔出只通知一次、close() 幂等、UTF-8 跨块、状态机
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.coroutines.core)
}
