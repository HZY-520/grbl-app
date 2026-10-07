# Phase 5 验收与发布记录

> 逐条对应 `docs/UI-3.0-PLAN.md` §9 的 Phase 5 清单。所有结论都有可复现的命令与归档证据。

---

## 1. 已完成项（全部有证据）

| # | 项目 | 状态 | 证据 |
| --- | --- | --- | --- |
| 1 | `versionCode = 30` / `versionName = "3.0.0"` | ✅ | `app/build.gradle.kts:17-18`；`aapt2 dump badging` 输出 `versionCode='30' versionName='3.0.0'` |
| 2 | 关于页版本号同步 | ✅ | `ScreensAbout.kt` 的 `APP_VERSION = "3.0.0"`（该常量刻意手写，注释说明了两处都要改） |
| 3 | 删除 `android/`（v2 Capacitor 工程） | ✅ | 已删；**签名密钥先搬走**（见 §2） |
| 4 | release 构建可用 | ✅ | `:app:assembleRelease` BUILD SUCCESSFUL，APK 7,247,785 字节 |
| 5 | release APK 签名正确 | ✅ | `apksigner verify --print-certs` → SHA-256 `1d7fb8d9…41e25`，与 `keytool -list` 读出的指纹一致 |
| 6 | 删除 `ScreenPlaceholder.kt` | ✅ | 已删；全仓 grep 零引用 |
| 7 | `Performance Glass` 开关接线 | ✅ | `GlassUi.kt` 的 `LocalRefractionEnabled` + `GlassScreenBody` 读设置 + `GlassCard` 传给 `LiquidSurface(refraction=)` |
| 8 | 删除 v2 前端遗留 | ✅ | `index.html` / `vite.config.ts` / `tsconfig.json` / `capacitor.config.ts` / `dist/` / `src/styles` / `src/ui` / `src/App.vue` / `src/main.ts` / `src/router.ts` 全部删除 |
| 9 | 全量回归 | ✅ | `:core:jvmTest` 31 套件 + `:app:testDebugUnitTest` 8 套件 = **287 用例 / 0 失败 / 0 错误** |
| 10 | oracle 可复现性 | ✅ | `npx --yes tsx tools/golden/generate.ts` 重跑，18 个夹具**逐字节一致**（合集 sha256 `9AC33990CC082AFC88404A9D322A9AC4622A7754AA60E6C96A9122085338A257`，manifest `c3ef77edd336`，302 条目） |
| 11 | 模拟器验收 | ✅ | `docs/screenshots/3.0-phase5-home.png`（Phase 5 改动后重验，无 FATAL） |
| 12 | 证据归档 | ✅ | `build/verify-phase5-20261007-145628/`（24 文件：夹具副本 + 哈希表 + APK badging/signer/info + oracle 复现结论 + 生成器脚本副本） |

---

## 2. 过程中的一个真实陷阱：删 `android/` 会打断 release 签名

`app/build.gradle.kts` 的 release 签名配置指向 **`android/lasergrbl-release.jks`** ——
那个文件在旧的 Capacitor 工程里。直接 `Remove-Item android` 会让 `:app:assembleRelease`
在配置阶段就失败（密钥找不到），而且这个错误**只在 release 变体出现**，debug 构建完全正常，
很容易到发版前才发现。

处理方式：
1. 先 `Move-Item android/lasergrbl-release.jks keystore/lasergrbl-release.jks`；
2. 用 `keytool -list` **验证新位置的文件确实可读且指纹不变**（不是只看文件搬过去了）；
3. 改 `app/build.gradle.kts` 的 `storeFile = rootProject.file("keystore/lasergrbl-release.jks")`；
4. 删 `android/`；
5. `:app:assembleRelease` + `apksigner verify` 复核指纹与 keytool 一致。

---

## 3. 已决定：**保留** `src/core/**` 与 `package.json`（oracle 取舍的记录）

计划书里有两句互相冲突的话：

* `docs/UI-3.0-PLAN.md:62`：「`src/` + `android/` + `dist/` ⚠️ v2 遗留（Phase 5 完成后删除）」
* `docs/UI-3.0-PLAN.md:63`：「`package.json` **仅保留给 tools/golden 使用**，Phase 5 后删除」
* `docs/UI-3.0-PLAN.md:146`：「在移植完成前**不删除任何 TS 代码**」
* `docs/UI-3.0-PLAN.md:328`：「`npx --yes tsx tools/golden/generate.ts` 重放 oracle（确定性，两次 sha256 相同）」

**实测事实**：`tools/golden/generate.ts` 用 **20 处 `await import()`** 动态加载 `src/core/**`
（第 359–378 行：Potrace / Centerline / StrokeAnalysis / Dithering / Hershey / hersheyData /
Paths / SvgToGcode / GrblFile / GrblCommand / csvData / types / SettingsPreset / DeviceProfile /
GrblConfig / KeepAlive / ImageTransform / ImageVector / SmartVector / RasterConverter）。

```ts
const Potrace = await import('../../src/core/vector/Potrace')
const ImageTransform = await import('../../src/core/raster/ImageTransform')
const RasterConverter = await import('../../src/core/raster/RasterConverter')
// …共 20 个
```

`package.json` 则是 `tsx` 与 Node 依赖的声明处（`npx --yes tsx` 依赖它来解析模块与版本）。

**结论**：这两项**不是"可以删的遗留代码"，而是黄金样本 oracle 的运行期依赖**。
删掉它们之后：
* **无法再重跑 oracle**，无法重新生成或扩展黄金样本；
* 现在这 302 个黄金条目**永久冻结**（它们仍然有效、测试仍然会跑，但失去"再证明一次"的能力）；
* 计划书 :328 承诺的"重放 oracle（确定性）"这条可复现性路径会消失。

所以我**只删了确定无 oracle 功能的遗留**（Capacitor 工程 + v2 前端 UI/CSS/构建配置），
把 `src/core/**`（25 文件）与 `package.json` / `package-lock.json` 保留到现在。

### ✅ 已决定：方案 A —— 保留 oracle（2026-10-07，用户拍板）

**保留 `src/core/**`（25 个 TS 文件）+ `package.json` + `package-lock.json` + `node_modules/` + `tools/golden/`。**

理由与影响：
* 它们**不参与 Android 构建** —— 新工程是独立的 Gradle 根，`settings.gradle.kts` 只 include
  `:app` / `:core` / `:glasskit`，`src/` 与 `package.json` 不进任何 Gradle 任务，**零构建成本**；
* 保留后可随时重跑 oracle、扩大黄金样本、复核任何 parity 争议
  （`npx --yes tsx tools/golden/generate.ts`，本次回归实测逐字节一致）；
* 代价：仓库里留 25 个 TS 文件 + 1 个 `package.json` + `node_modules/`。
* **本节 §3 的 B / C 方案作废**，后续任何会话不要再尝试删除这两项；若要改主意，
  请先读本节的理由（删掉即永久失去重放 oracle 的能力，302 条黄金样本冻结）。

> 三个方案（A 保留 / B 严格删 / C 打包留档）曾在 §3 列出供决策；现已选定 A。

---

## 4. 独立验收发现的缺陷与修复（**重要：这是发布前必须看的一节**）

我派了一个**只读**的独立验收子代理做逐屏模拟器截图 + 亲眼审图（不改任何代码）。
它抓出 **1 个 P0 功能缺陷 + 2 个 P1 渲染缺陷 + 1 个 P2**，全部已在最新 APK 上修好并复验。

### P0-1 `GlassNavCard` 的 `onClick` 从未接线 → 4 条路由在设备上不可达

**这个缺陷我自己的验收漏掉了**：我只截了 `/home`，而首页截图里卡片看起来完全正常 ——
它只是**不可点**，静态截图看不出来。

* 根因：`AppShell.kt` 的 `GlassNavCard` 声明了 `onClick: () -> Unit` 参数，**函数体里从未使用**；
  `GlassUi.kt` 的 `GlassCard` 也只接受 `backdrop/modifier/padding/content`，没有可点击重载。
  于是首页 6 张 + 生成中心 3 张 + 关于页 3 张卡片全是**纯装饰**。
* 影响：`/preview`、`/convert/image`、`/convert/text`、`/convert/svg` **只有这一个入口**
  → 这 4 屏在设备上完全无法到达，也就无法验收。
* 修法：`GlassCard` 新增 `onClick: (() -> Unit)? = null`（非空时加 `Modifier.clickable`），
  `GlassNavCard` 把 `onClick` 真的传下去（`enabled = false` 时传 `null`）。
* **复验（客观证据）**：
  * `uiautomator dump` 里 `clickable="true"` 节点从「卡片的 clickable 数 = 0」变为 **18 个**，
    含入口卡的 `bounds=[37,1381][1043,1556]`；
  * `adb shell input tap 540 1468` 后 dump 里出现
    `传输方式` / `USB 串口` / `波特率` / `USB 设备` → **确实跳转到了 `/connect`**；
  * 截图 `docs/screenshots/3.0-p4b-connect-reached.png`。

### P1-2（**我自己引入的过度修正**）`GlassButton` 无条件 `fillMaxWidth()` 压垮 `weight(1f)` 兄弟

* 根因：`GlassUi.kt` 的 `GlassButton` 给内容 `Row` 加了 `.fillMaxWidth()`。
  那是我在 Phase 4 为修「按钮文字溢出」加的（见 §4.1），但 `LiquidButton` 的**根节点本身
  就是 wrap-content 的 `Row`**，内层无条件 `fillMaxWidth()` 会向上取整行 maxWidth →
  按钮吃掉整行宽度 → `Row { 文本列.weight(1f); GlassButton }` 里的文本列被压到 ≈0。
* 表现：文字**每字一行竖排**（`3 0 0 × 2 0 0`、`桌 面 入 门 机`），卡片高度爆炸到数千 px。
  验收方的客观几何证据：「更多」行 `串口终端` 标签只分到 **21×49 px**，而按钮本体 297×116 px；
  「关于」的文字节点直接为**空**。
* 修法：只在 `block = true` 时 `fillMaxWidth()`，否则用 `Modifier.width(IntrinsicSize.Max)`
  —— 按内容理想宽度定宽且**受父级上界约束**，既保留省略号能力又不抢兄弟宽度。
* **复验**：同一批标签宽度 21px → **136 / 136 / 68 px**，全部完整可读；
  「USB 设备（0）」标题不再竖排（截图 `3.0-p4b-connect-fixed.png`）。

### P1-1 窄按钮文字折行 / 内容丢失

* 现象：`115200` → 「11520」+「0」两行；`UltraFast` → 「Ultr」+「aFa」且**尾部 "st" 被吞**
  （内容真的丢了，不是纯视觉）。根因是这几处是**手写 `GlassText`**，没有 `maxLines=1` + 省略号
  （Phase 4 的修复只覆盖了 `GlassIconButton` 的 label）。
* 修法两条：
  1. 新增 `GlassButtonLabel`（`GlassText.kt`）作为**所有按钮标签的唯一入口**：
     `fillMaxWidth()` + `textAlign = Center` + `maxLines = 1` + `Ellipsis`。
     替换了 6 个文件里 10 处手写标签（设置页 3、连接页 1、点动页 3、终端页 1、向导 2）。
  2. 波特率改用**紧凑无歧义**写法 `formatBaud()`：`115200→115.2k`、`230400→230.4k`、`250000→250k`
     （完整值仍显示在「波特率」键值行：`115200 bps`）。
     `230.4k` 这种写法保证不会被误读成别的速率 —— 若只写成 `230k` 就丢信息了。
* **复验**：`uiautomator` 读出 7 个按钮文案为
  `9600 / 19200 / 38400 / 57600 / 115.2k / 230.4k / 250k`，无截断、无折行。

### P2-1 底部标签栏同时高亮两个标签

* 修法：三列并排的「机器参数 / 串口终端 / 关于」改为 `showIcon = false`
  —— 图标 + 4 个汉字在 1/3 屏宽里放不下，会被省略成「机器…」「串口…」。
* **复验**：截图 `3.0-p4b-settings-more.png` 里三个标签完整，且只有「设置」一个标签高亮。

### 4.1 一个方法论教训（值得记住）

`GlassButton` 的 `fillMaxWidth()` 是**为修一个缺陷而引入另一个更严重缺陷**的典型：
修「文字溢出按钮」（视觉问题）时加的无条件 `fillMaxWidth()`，制造了「兄弟元素被压成竖排」
（布局结构问题，且卡片高度爆炸）。两次都是**只有看图才能发现**，编译与单测全绿。

结论：**每一次「为修视觉问题而改布局」都必须重新截图对照，不能只验被修的那一处。**
而且静态截图**无法**发现"控件不可点"这类功能缺陷 —— 必须配合
`uiautomator dump` 查 `clickable` / 用 `input tap` + 跳转后复查语义树。

---

## 5. 仍未完成 / 已知限制

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 13 屏逐屏模拟器截图 | 🔄 进行中 | 已派独立验收子代理（只读、不改代码）在跑；目前只有 `/home` 与壳层有截图 |
| `DebugKeepAliveActivity` | ⏸ 保留 | 它是 **debug 源集**（`app/src/debug/`），不进 release APK，也不被 `:app:assembleRelease` 编译。是 Phase 3 保活验收的取证入口（`adb` 无法启动 `exported=false` 的 `EngraveService`，只能靠它）。删掉会失去复验手段，故暂留并在此登记 |
| SVG 中心线 / 智能模式 | ⛔ 能力缺口 | `:core` 无 `SvgVector.kt`、`:app` 无 SVG 位图化；补齐需新依赖。界面保留 + 明确中文拒绝，不静默降级 |
| 文字转雕刻 / 重采样像素一致性 | ⛔ 不可能一致 | Android 系统字体 vs WebView 字体栈；自写 `TriangleResampler` vs canvas `drawImage` |
| USB / 蓝牙真机链路 | ⛔ 离线不可验 | 枚举、权限弹窗、波特率、DTR/RTS、配对、吞吐、时序（Phase 3 已逐条登记 8 项） |
| `docs/UI-3.0-PLAN.md` §12 进度日志 | ⏳ 待更新 | 需要补 Phase 3 / 4 / 5 的收尾条目 |
