# iGRBL

iGRBL 是一款面向 Android 平台的激光雕刻机控制应用，将
[LaserGRBL](https://github.com/arkypita/LaserGRBL) 的激光雕刻功能移植到 Android 平台。
应用以中文为主语言，界面为自研的 iOS 26 风格「液态玻璃」组件库
（折射引擎 [simple-liquid-glass](https://github.com/lucaperullo/simple-liquid-glass)），
通过 USB（USB Host / USB 串口）或蓝牙（经典蓝牙 SPP）连接 GRBL 激光雕刻机。

## 功能

- **设备连接**：枚举 USB 串口设备或已配对的蓝牙串口设备（HC-05 / HC-06 等），
  选择波特率连接 / 断开，支持插拔自动检测。
- **机器控制**：实时状态与坐标（MPos / WPos / WCO）、进给与功率、倍率调节。
- **运动控制**：九宫格点动、步长 / 速度设置、回零、解锁、设置原点。
- **图案生成**：
  - 图片转雕刻：Line2Line 与多种误差扩散抖动（Floyd–Steinberg、Atkinson 等），
    以及**中心线描线**（骨架化，适合线稿）与**智能模式**（自动判别线稿 / 实心）。
  - 文字转雕刻：Hershey 矢量字体（拉丁字母，单线「写字」效果）、
    轮廓描线、**中心线描线**（中文等非 ASCII 走骨架化）与**智能模式**（按字符集自动选择）。
  - SVG 转雕刻：轮廓提取、**中心线描线**（光栅化后骨架化，线稿不会描成双边）与**智能模式**。
- **文件管理**：载入本地 G 代码、保存 / 删除应用内文件、路径预览。
- **任务执行**：流式发送、暂停 / 继续 / 中止、进度与剩余时间估算；
  **执行期间通过前台服务 + 常驻进度通知保活后台**。
- **路径预览**：按雕刻进度**实时动画推进**，末端显示激光头位置，
  并标注作品实际尺寸（宽 × 高 mm）。
- **机器参数**：读取 / 修改 GRBL `$$` 设置项（含参数说明与单位）；
  支持导入 `.nc` 参数预设（每行一条 `$编号=值`），预览后一键下发到设备。
- **串口终端**：查看通讯日志、发送原始命令与快捷命令。
- **外观设置**：深色 / 浅色主题切换。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 界面 | Vue 3 + TypeScript + 自研玻璃组件库（iOS 26 液态玻璃） |
| 折射 | [simple-liquid-glass](https://github.com/lucaperullo/simple-liquid-glass)（Web Component，零依赖） |
| 构建 | Vite |
| 容器 | Capacitor 6 |
| 原生 | Android（Java）+ usb-serial-for-android（USB）/ 经典蓝牙 SPP（蓝牙）/ 前台服务保活 |

GRBL 协议解析、命令队列与流式发送、图像 / 矢量 / 文字转 G 代码等核心逻辑，
由原项目的 C# 实现移植为 TypeScript（`src/core`）。

> **构建环境要求**：AGP 需要**完整 JDK**（含 `jlink`）。若 `JAVA_HOME` 指向 JRE
> （例如 IntelliJ 自带的 JBR），`assembleDebug` 会因 `JdkImageTransform` 失败而报
> `jlink executable ... does not exist`，请指向真正的 JDK，例如
> `set JAVA_HOME=C:\Program Files\Java\jdk-17.0.18`。
> 另外本仓库路径含中文，已在 `android/gradle.properties` 里设置
> `android.overridePathCheck=true` 以绕过 AGP 的非 ASCII 路径检查（本工程不使用 NDK）。

## 开发

```bash
npm install          # 安装依赖
npm run dev          # 启动 Web 开发服务器（浏览器调试可用 Web Serial）
npm run build        # 类型检查 + 前端构建
npx cap sync android # 同步前端资源到 Android 工程
npm run apk          # 构建 Debug APK
```

发布版 APK：

```bash
cd android && ./gradlew assembleRelease
# 产物：android/app/build/outputs/apk/release/app-release.apk
```

签名参数位于 `android/gradle.properties`（自签名分发密钥
`android/lasergrbl-release.jks`）。

## 目录结构

```
src/core/grbl        GRBL 协议、命令、核心通讯与流式发送
src/core/raster      光栅图像 / 抖动转 G 代码
src/core/vector      矢量：轮廓（Potrace）、中心线（Zhang-Suen 骨架化）、SVG 光栅化、智能判别
src/core/text        Hershey 单线字体转 G 代码
src/core/gcode       G 代码加载、分析与预览
src/core/serial      串口传输抽象（USB：Android 原生 / Web Serial；蓝牙：Android SPP）
src/core/native      原生能力封装（前台服务保活 / 进度通知）
src/ui               Vue 界面、状态管理与组件
src/ui/glass         iOS 液态玻璃组件库（GlassSurface 等 19 个组件）
src/styles/glass.css 设计令牌、极光背景与全部控件外观
android              Capacitor Android 工程、USB / 蓝牙串口插件与保活前台服务
docs                 改造规范、选型调研与各页面截图
```

## 许可证

移植自 LaserGRBL（GPL-3.0）。本项目同样以 GPL-3.0 发布。