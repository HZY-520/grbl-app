# iGRBL 3.0

面向 Android 的激光雕刻机控制应用，把 [LaserGRBL](https://github.com/arkypita/LaserGRBL)
的激光雕刻能力带到手机上。界面为自研的 iOS 26 风格「液态玻璃」，通过
**USB 串口**或**经典蓝牙 SPP** 连接 GRBL 雕刻机。

> **3.0 是原生重写。** 2.0 是 Vue 3 + Capacitor 的 Web 壳；3.0 用
> **Kotlin Multiplatform + Compose** 重写整个应用，玻璃折射改为 Android
> `RuntimeShader` 真实透镜折射（不再是 Web CSS 模拟）。2.0 的 TypeScript 实现
> 被保留为**算法 oracle**（见下文「黄金样本」）。

---

## 功能

- **设备连接**：枚举 USB 串口设备或已配对的蓝牙串口设备（HC-05 / HC-06 等），
  7 档波特率，插拔自动检测，按设备绑定参数档案。
- **机器控制**：实时状态与坐标（MPos / WPos / WCO）、进给与主轴、三路倍率调节
  （进给 / 快速 / 功率）。
- **运动控制**：九宫格点动、步长与速度、绝对移动、回零、解锁、设零、清零 WCO、
  激光测试（50–10000 ms 钳制）。
- **图案生成**：
  - **图片转雕刻**：Line2Line 与 9 种误差扩散抖动（Floyd–Steinberg、Atkinson 等），
    轮廓描线（Potrace）、中心线描线（Zhang-Suen 骨架化）、**智能模式**（自动判别线稿 / 实心）；
    含灰度公式、亮度 / 对比度 / 白色裁剪、二值化预览。
  - **文字转雕刻**：Hershey 矢量单线字体、轮廓描线、中心线描线、智能模式（按字符集自动选择）；
    横向 / 纵向排版、字号行距、尺寸自适应。
  - **SVG 转雕刻**：轮廓提取（含曲线精度与行程自适应）。
    ⚠️ 中心线 / 智能模式**当前不可用**，见「已知限制」。
- **文件管理**：载入本地 G 代码（UTF-8 严格解码，失败回退 GBK）、保存 / 删除应用内文件、
  路径预览（按雕刻进度实时动画推进 + 发光激光头）。
- **任务执行**：流式发送、暂停 / 继续 / 中止、进度与预计时间；
  执行期间通过**前台服务 + 常驻进度通知**保活后台。
- **机器参数**：读取 / 写入 GRBL `$$` 设置项（含中文参数说明）；
  导入 `.nc` 参数预设（每行 `$编号=值`），并报告跳过的行数。
- **串口终端**：通讯日志、原始命令输入、6 个快捷命令、进给保持 / 循环启动 / 软复位。
- **外观**：深色 / 浅色主题（**3.0 修复了 2.0 主题不持久化的缺陷**）、
  「高性能玻璃」开关（关闭折射计算，低端机更流畅）。

---

## 技术栈

| 层 | 技术 |
| --- | --- |
| 语言 | Kotlin 2.4.10（KMP：`jvm` + `android` 双目标） |
| 界面 | Jetpack Compose + 自研 `:glasskit` 玻璃组件库 |
| 玻璃折射 | [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（`io.github.kyant0:backdrop` + `shapes`），10 个组件逐字节内联，清单见 `glasskit/UPSTREAM-SOURCES.json` |
| 构建 | Gradle 9.7.1 + AGP 9.3.2，compileSdk 37，minSdk 24 |
| 串口 | usb-serial-for-android 3.11.0（USB）+ 经典蓝牙 SPP |
| 保活 | 前台服务 + 常驻通知 |

**:app 刻意不依赖 material3** —— 文字走 `BasicText`、图标走 `ImageVector`，
避免「Material 主题色与玻璃主题色两套」。

---

## 模块结构

```
core       纯 Kotlin KMP：GRBL 协议 / 命令队列 / 流式发送 / 光栅 / 矢量 / 文字 / G 代码分析
           （无 Android 依赖，可用 jvmTest 直接跑）
glasskit   Compose Multiplatform 玻璃组件库（内联上游 + 本项目补缺的组件）
app        Android 应用：13 个屏幕 + 首次设置向导、平台模块、外设层、前台服务
tools      golden/   Node 脚本：从 2.0 的 TS 实现生成黄金样本（移植期的 oracle）
docs       计划书、各阶段验收记录、进度日志、截图
src/core   2.0 的 TypeScript 核心实现 —— **保留作为 oracle**，不参与 Android 构建
```

---

## 构建

需要**完整 JDK**（含 `jlink`）。若 `JAVA_HOME` 指向 JRE（例如 IntelliJ 自带的 JBR），
`assembleDebug` 会因 `JdkImageTransform` 失败并报 `jlink executable ... does not exist`。

```powershell
# Windows（本仓库路径含中文，已设 android.overridePathCheck，不走 NDK）
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.18'

.\gradlew.bat :app:assembleDebug            # 调试包
.\gradlew.bat :app:assembleRelease          # 发布包（读取 keystore/lasergrbl-release.jks）
.\gradlew.bat :core:jvmTest                 # 核心逻辑单测
.\gradlew.bat :app:testDebugUnitTest        # 应用层单测
```

产物：`app/build/outputs/apk/{debug,release}/`。

### 签名

发布包使用自签名密钥 `keystore/lasergrbl-release.jks`（**已 gitignore，不入库**）。
换机器时需要自行放入该文件，或修改 `app/build.gradle.kts` 的 `signingConfigs`。

---

## ⚠️ 灾备：源码物理备份在哪

做 git 历史整理（换基点、`read-tree`、`clean`）之前必须知道这一条 —— 我为此差点丢掉 300 多个源文件：

```
D:\工作台\_igrbl-stash-backup\
├── stash-tracked.zip        54 个文件（当时被 git 跟踪的）9.8 MB
├── stash-untracked.zip     246 个文件（当时未跟踪的：app/ core/ glasskit/ 全部源码）33 MB
└── keep\
    ├── dist\                两个交付 APK
    ├── keystore\            lasergrbl-release.jks（release 签名密钥，已 gitignore 不入库）
    └── local.properties     SDK 路径
```

两个 zip 由 `git archive` 从 stash 提交导出，**是当时工作区的权威快照**。
从零恢复：把两个 zip 解压到仓库根（先 `stash-tracked`，后 `stash-untracked`），
再跑 `:app:assembleDebug` + `:core:jvmTest` 确认 287 用例全绿。

**为什么需要它**：那次我把 3.0 工作区 `git stash push -u` 后想基于 `origin/main` 建分支，
用了两个错误做法 ——

1. `git read-tree 'stash@{0}'` 只读取**第一个父提交**（被跟踪的部分，仅 54 个文件）；
   未跟踪文件在**第三个父提交**（`stash@{0}^3`）里；
2. 随后 `git clean -fd` 把索引之外的一切删掉 —— 包括那 246 个未跟踪的源文件。

**避免这个过程的正确做法**：不要 stash，先把工作区**物理复制**出去，再操作 git。
已落到 stash 里时，用 `git archive 'stash@{0}^3'` 取未跟踪部分、`'stash@{0}'` 取跟踪部分。
另外 `git checkout 'stash@{0}' -- .` **会连同 v2 的删除记录一起写进索引**，
用它建分支会得到「v2 文件被标记为新增」的错误 diff。

---

## 验证

### 黄金样本（移植正确性的判据）

3.0 的算法移植以 2.0 的 TypeScript 实现为**唯一判据**：先用 Node 跑 2.0 代码把输出
固化成 JSON 夹具，再用 Kotlin 逐字段 / 逐行 / 逐像素比对。

```bash
npx --yes tsx tools/golden/generate.ts     # 重放 oracle（确定性）
```

当前夹具：**17 个用例 / 302 条目 / 1,946,670 字节**，manifest sha256 `c3ef77edd336`。
*夹具是编译期资源*，`:core:jvmTest` 直接比对，因此 oracle 只在需要**新增/重生成**样本时才要跑。

> `src/core/**` 与 `package.json` 是**有意保留**的：`tools/golden/generate.ts` 用 20 处
> `await import()` 从 `src/core/**` 动态加载 oracle，删掉即永久失去重跑黄金样本的能力。
> 它们不参与任何 Gradle 任务，零构建成本。详见 [docs/PHASE5-RELEASE.md](docs/PHASE5-RELEASE.md) §3。

### 当前状态

| 检查 | 结果 |
| --- | --- |
| `:core:jvmTest` | **31 套件 / 203 用例 / 0 失败** |
| `:app:testDebugUnitTest` | **8 套件 / 84 用例 / 0 失败** |
| `:app:assembleDebug` / `assembleRelease` | **BUILD SUCCESSFUL** |
| 发布包 | versionCode **30** / versionName **3.0.0**，签名 SHA-256 `1d7fb8d9…41e25` |
| 屏幕 | **14 / 14**（13 条路由 + 首次设置向导），无占位残留 |
| 模拟器 | AVD `iGRBL_API33`（Android 13），无 FATAL / 无 ANR |

---

## 已知限制

这些是**能力缺口或客观不可验**，不是「还没测」：

- **SVG 中心线 / 智能模式不可用**：需要 SVG 位图化 + 骨架化，`core` 与 `app` 均未实现，
  补齐需引入新依赖。当前界面与参数面板保留、顶部常驻警告、点生成时给明确中文提示并
  **拒绝生成** —— 不静默退回轮廓提取冒充成功。
- **文字转雕刻与 2.0 不可能逐像素一致**：Android 系统 `Typeface` vs 浏览器 WebView 字体栈，
  字形与度量必然不同。
- **重采样与 2.0 不可能逐像素一致**：2.0 是 canvas `drawImage`，3.0 是自写 `TriangleResampler`。
- **真机外设链路未验**：USB 枚举 / 权限弹窗 / 波特率 / DTR-RTS 时序、蓝牙配对与吞吐 —— 
  这些只能在真机验证，逐条登记在 [docs/PHASE3-PERIPHERALS.md](docs/PHASE3-PERIPHERALS.md)。
- **逐屏目视验收未走完**：`/home` `/convert` `/file` `/jog` `/settings` `/connect` 已截图审过；
  `/config` `/terminal` `/about` `/preview` 与三个转换屏**尚未逐屏截图**。

---

## 接下来的方向

按优先级：

1. **补完逐屏目视验收**（当前最该补的证据缺口）。上一轮唯一发现的 P0
   （入口卡 `onClick` 未接线导致 4 条路由不可达）就是「只看没看过的那一屏」才发现的，
   所以剩余 7 屏不能默认没问题。方法上必须配合 `uiautomator dump` 查 `clickable`
   与 `input tap` 后复查语义树 —— **静态截图发现不了「控件不可点」**。
2. **真机联调**：USB（枚举 / 权限 / 波特率 / 插拔时序）与蓝牙 SPP（配对 / 吞吐 / ACL 时序）。
3. **首次设置向导在最新包上重验**（旧包上截过 1/2 步，渲染正常）。
4. **补 SVG 中心线 / 智能模式**：需先决定是否接受新依赖（WebView 或 SVG 库）。
5. **冷启动性能**：模拟器冷启动首帧实测 15.2 s（软件渲染环境，**不能当真机数据**），
   真机需实测，必要时压缩首屏玻璃面数量。
6. **CI**：把 `:core:jvmTest` + `:app:testDebugUnitTest` + `assembleRelease` 接成固定流水线。

---

## 许可证

移植自 LaserGRBL（GPL-3.0）。本项目同样以 **GPL-3.0** 发布。
