# iGRBL

iGRBL 是一款面向 Android 平台的激光雕刻机控制应用，将
[LaserGRBL](https://github.com/arkypita/LaserGRBL) 的激光雕刻功能移植到 Android 平台。
应用以中文为主语言，界面基于 [Varlet UI](https://github.com/varletjs/varlet)，
通过 USB（USB Host / USB 串口）连接 GRBL 激光雕刻机。

## 功能

- **设备连接**：枚举 USB 串口设备，选择波特率连接 / 断开，支持插拔自动检测。
- **机器控制**：实时状态与坐标（MPos / WPos / WCO）、进给与功率、倍率调节。
- **运动控制**：九宫格点动、步长 / 速度设置、回零、解锁、设置原点。
- **图案生成**：
  - 图片转雕刻：Line2Line 与多种误差扩散抖动（Floyd–Steinberg、Atkinson 等）。
  - 文字转雕刻：Hershey 矢量字体，支持横向 / 纵向排版。
  - SVG 转雕刻：轮廓提取，曲线离散为平滑折线。
- **文件管理**：载入本地 G 代码、保存 / 删除应用内文件、路径预览。
- **任务执行**：流式发送、暂停 / 继续 / 中止、进度与剩余时间估算。
- **机器参数**：读取 / 修改 GRBL `$$` 设置项（含参数说明与单位）。
- **串口终端**：查看通讯日志、发送原始命令与快捷命令。
- **外观设置**：深色 / 浅色主题切换。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 界面 | Vue 3 + TypeScript + Varlet UI |
| 构建 | Vite |
| 容器 | Capacitor 6 |
| 原生 | Android（Java）+ usb-serial-for-android |

GRBL 协议解析、命令队列与流式发送、图像 / 矢量 / 文字转 G 代码等核心逻辑，
由原项目的 C# 实现移植为 TypeScript（`src/core`）。

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
src/core/vector      SVG 矢量转 G 代码
src/core/text        Hershey 字体转 G 代码
src/core/gcode       G 代码加载、分析与预览
src/core/serial      USB 串口传输抽象（Android 原生 / Web Serial）
src/ui               Vue 界面、状态管理与组件
android               Capacitor Android 工程与 USB 串口插件
```

## 许可证

移植自 LaserGRBL（GPL-3.0）。本项目同样以 GPL-3.0 发布。