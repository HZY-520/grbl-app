# Phase 4 进度记录（13 屏幕 + 向导）

> 记录 Phase 4 的骨架设计与已完成屏幕。约定见 [PHASE4-SCREEN-CONVENTIONS.md](PHASE4-SCREEN-CONVENTIONS.md)。
> 判据永远是 v2 的 `src/ui/**`，不是计划书。

---

## 1. 骨架（已完成，已双端编译 + 模拟器截图验证）

| 文件 | 作用 | 对应 v2 |
| --- | --- | --- |
| `app/.../app/AppContainer.kt` | 进程级容器：`grbl`（状态层）/ `navigator` / `toasts` / `confirm` / `resampler`；`AppContainerHolder` 由 `IgRblApplication.onCreate` 装配 | v2 `main.ts` 的模块级单例 |
| `app/.../app/Navigation.kt` | `Screen`（13 路由，带 `title`/`tab`/`path`）、`Tab`（5 个）、`AppNavigator`（push / selectTab / back / replace） | `router.ts` + `App.vue` 的 tab 逻辑 |
| `app/.../app/GrblController.kt` | 状态层：12 个 `Emitter` 订阅、连接、位置、覆盖倍率、进度、日志（上限 600）、设备、文件、`$$`、`laserTest`（50–10000 ms 钳制）、主题持久化 | `ui/store.ts` 504 行 |
| `app/.../app/AppShell.kt` | `LiquidScaffold` + 顶栏（返回/品牌 + 连接胶囊）+ 5 标签栏 + **overlay 槽**（Toast / 向导 / 确认框） | `App.vue` 102 行 |
| `app/.../app/GlassUi.kt` | 惯用法层：`GlassCard`/`GlassButton`/`GlassIconButton`/`GlassSwitch(Row)`/`GlassSliderRow`/`GlassKeyValue`/`GlassStat`/`GlassBadge`/`GlassSectionHeader`/`GlassProgressLine`/`GlassEmpty`/`statusBadgeKind`/`logLineColor` | v2 的 `src/styles/glass.css` 语义 |
| `app/.../app/GlassText.kt` | `GlassText`（`BasicText`）/ `GlassIcon`（`ImageVector` + `ColorFilter.tint`） | v2 `AppIcon.vue` |
| `app/.../app/GlassLogList.kt` | 日志列表（**倒序 `LazyColumn`**，自动滚到最新） | `LogList.vue` 86 行 |
| `app/.../app/GcodePreview.kt` | 路径预览画布：几何归一化 + 完整轮廓 + **进度驱动的部分描边** + **指数逼近缓动** + 减少动画吸附 | `GcodePreview.vue` 462 行 |

### 1.1 三处刻意的架构差异（都写进 KDoc 了）

1. **不引入 Material**：`:app` 没有 `material3` 依赖，文字走 `BasicText`、图标走
   `ImageVector`。好处是不会出现"Material 主题色与玻璃主题色两套"。
2. **`GcodePreview` 不做位图缓存**：v2 把画布内容缓存成 bitmap 再局部重绘（DOM canvas 的
   `drawImage` 比重新描 5000 条线段便宜）；Compose 的 `Canvas` 描 `Path` 由 GPU 合成，
   提前拍成 bitmap 反而多一次上传。改为"缓存归一化 Path + 每帧一次变换"。
3. **日志倒序渲染**：v2 是正序 + `scrollTop = scrollHeight`。倒序 `LazyColumn` 视觉效果相同，
   但新增日志时不必重算整个列表（上限 600 条时差异明显）。

### 1.2 把 v2 的重复实现合并成一份

v2 在 `App.vue:62-69` 与 `HomeView.vue:30-37` **各写了一遍**状态→颜色派生；
3.0 只有 [statusBadgeKind]（`GlassUi.kt`）一份，壳层与首页共用。

---

## 2. 已完成屏幕（14 / 14，**零 `ScreenPlaceholder`**）

| 路由 | 状态 | 证据 |
| --- | --- | --- |
| `/home` | ✅ | `ScreensHome.kt`：状态/固件/版本 + 6 个坐标统计 + 进度条 + 运行/暂停/继续/中止 + 回零/解锁/设零/清 WCO + 7 个入口卡 + 路径预览 + 日志 |
| `/convert` | ✅ | `ScreensConvertHub.kt` 413 行：3 张入口卡 + 4 条流程说明 + 「打开文件」→ 文件页 |
| `/convert/image` | ✅ | `ScreensConvertImage.kt` 1068 行：5 档转换方式、扫描方向/分辨率/阈值/偏移/反相、9 种抖动算法、线性三套参数、雕刻参数、图像预处理折叠、二值化预览 + 路径预览 |
| `/convert/text` | ✅ | `ScreensConvertText.kt` 618 行：多行输入、4 档引擎 + Auto 判定说明、排版方向、字体 4 预设、字号/行距/起点、矢量参数、**两遍尺寸自适应**（先测后排） |
| `/convert/svg` | ✅ | `ScreensConvertSvg.kt` 504 行：轮廓提取完整可用（含行程自适应重算）；**中心线/智能显式拒绝**（见 §2.2） |
| `/file` | ✅ | `ScreensFile.kt`：打开文件（GBK 回退）、统计、运行控制、预览、另存、已存文件列表 + 删除确认 |
| `/preview` | ✅ | `ScreensPreview.kt`：200–640 px 缩放（含滑块/±40/适应）、统计 6 项、任务控制 |
| `/jog` | ✅ | `ScreensJog.kt`：九宫格点动、步长预设 + 自定义 + 速度滑块、绝对移动、三路覆盖倍率、激光测试 |
| `/connect` | ✅ | `ScreensConnect.kt`：USB/蓝牙切换、7 档波特率、设备列表（`LiquidRow`）、扫描加载态、连接/断开、系统蓝牙设置入口 |
| `/config` | ✅ | `ScreensConfig.kt`：`$$` 读取、参数列表（编号 + 中文说明 + 当前值 + 行内写入）、`.nc` 预设导入（报跳过行数） |
| `/terminal` | ✅ | `ScreensTerminal.kt`：日志（倒序 + 自动滚顶）、命令输入、**v2 的 6 个快捷命令逐字**、进给保持/循环启动/软复位 |
| `/settings` | ✅ | `ScreensSettings.kt`：v2 的 8 个分组全部落地（外观/连接与通讯/设备与行程/设备档案/雕刻默认参数/程序头尾/点动默认参数/更多），键名逐字取自 `:core` 的 `DEFAULT_SETTINGS`（28 项） |
| `/about` | ✅ | `ScreensAbout.kt`：版本、功能 7 条、技术栈、GPL-3.0 声明（v2 中文原话）、3 个外链 |
| 向导 | ✅ | `SetupWizardOverlay.kt`：4 步（基本信息 / 行程范围 / 激光与测试 / 雕刻参数），完成时写设置 + `markDeviceKnown` + `saveProfile` + `setSetupDone` |

### 2.1 两个共享工具（别的屏幕直接复用）

* **`GcodeFilePicker.kt`**：`rememberGcodeFilePicker(context) { name, text -> ... }` → `picker.launch()`。
  走 `ActivityResultContracts.OpenDocument`，**UTF-8 严格解码失败时回退 GBK**（LaserGRBL 在 Windows 上
  默认导出 GBK，浏览器里 `FileReader` 不会遇到，Android 直接 `readText()` 会得到替换字符）。
* **`GcodeFileStore`**：已存 G 代码落在 `filesDir/gcode/*.gcode`，`AppSettings` 只留文件名。
  v2 是把整份正文塞进 localStorage（几 MB 字符串）—— 语义相同、不会撑爆设置存储。
* **`container.screenOverlay`**：**屏幕级浮层注册口**（本轮新增）。屏幕拿不到 `LiquidScaffold` 的
  `overlay` 槽，而 Phase 1 结论禁止 `Popup`/`Dialog`；现在屏幕可以把 `LiquidBottomSheet` 注册进这个
  槽位，由 `AppShell` 在同窗口渲染。切屏时自动清空，避免浮层跨屏残留。

### 2.2 明确做不到的（能力缺口，不是"未验证"）

* **SVG 中心线描线 / 智能模式**：v2 走 `convertSvgVector`（SVG → 位图 → 骨架化 / 智能判定）；
  `:core` **没有** `SvgVector.kt`，`:app` **没有** SVG 位图化实现，补齐需要新依赖
  （WebView 或 SVG 库），违反"不引入新依赖"。处理方式：界面与参数面板保留、
  常驻 `LiquidAlert(Warning)`、点生成时给明确中文拒绝并**不生成** —— 不静默降级冒充成功。
* **文字转雕刻不可能与 v2 逐像素一致**：`AndroidTextRasterizer` 用系统 `Typeface`，
  v2 用 WebView 字体栈；字号/字重/字距都不同（Phase 3 已记录）。
* **字体下拉不影响渲染**：同上，4 个预设与中文标签保留 + 一行说明。
* **重采样不可能与 v2 逐像素一致**：v2 是 canvas `drawImage`，3.0 是自写 `TriangleResampler`
  （Phase 3 已记录，浏览器像素一致性无法离线验证）。
* **「高性能玻璃」开关尚未接线**：`Settings` 页可写 `Performance Glass` 键，但
  `AppShell`/`GlassCard` 还没读它去控制 `LiquidSurface(refraction = ...)`。



---

## 3. 模拟器验收

截图（AVD `iGRBL_API33`，Android 13）：

| 文件 | 内容 |
| --- | --- |
| `docs/screenshots/3.0-phase4-shell.png` | 壳层首帧：玻璃卡片 + 5 标签栏 + 状态胶囊 |
| `docs/screenshots/3.0-phase4-home5.png` | 首页最终形态：坐标 6 项、进度条、任务 4 按钮、快捷 4 按钮、入口卡、日志 |

命令：`tools/verify/screenshot.ps1 -Name <名字> -SettleSeconds 7`。

### 3.1 本轮实测修掉的一个真实渲染缺陷（值得记住）

**按钮文字溢出按钮边界**：`LiquidButton` 的 `content` 是 `RowScope.() -> Unit`，
它的子项**按自身固有宽度测量**，不继承按钮的宽度约束 —— 于是
`GlassText(maxLines = 1, overflow = Ellipsis)` 的省略号永远不触发，中文标签反而溢出到按钮之外。
四列并排的窄按钮（1/4 屏宽）上肉眼可见，截图是 `3.0-phase4-shell.png` → `3.0-phase4-home2.png` → `home5.png` 三次迭代。

两处修复（都在 `GlassUi.kt`）：
1. `GlassButton` 把**外部约束传下去**（`Modifier.then(modifier)`，原来只传了一个 `height`，
   `weight(1f)` 的宽度约束根本没进到上游按钮）；
2. 内容 `Row` 加 `fillMaxWidth()`，测量宽度才等于按钮宽度；
3. 四列并排的按钮加 `showIcon = false`（新增参数）—— 图标 + 两个字的中文标签在 1/4 宽度里确实放不下，
   实测省略号会把「运行」截成「运…」，去掉图标才是可用的。

---

## 4. 过程中的真实踩坑（值得记住）

1. **不要用 PowerShell 的 `Set-Content` 改 Kotlin 文件**：它会把 UTF-8 中文写成 GBK 乱码，
   我因此把 `AppShell.kt` 与 `GrblController.kt` 各写坏一次，只能整文件重写。
   规则：**改 Kotlin 一律用 `edit`/`write` 工具**。
2. **KDoc 里裸 `/*` 会吞掉整个文件**：`<input accept="image/*">` 里的 `/*` 触发 Kotlin
   块注释嵌套，导致 `FilePicker.kt` 报 `Unclosed comment`、所有引用它的屏连锁报未解析。
3. **`GlassSwitch` 的尾随 lambda 会落到 `modifier` 参数上** → 必须写 `onCheckedChange = { … }`。
4. **共享 build 目录**：多个 teammate 同时跑 Gradle 会互相踩
   （`Timeout waiting to lock`、`Failed to create MD5 hash`）。更麻烦的是
   **带 `--tests` 的过滤运行会覆盖 `build/test-results/`**，
   把上一次全量跑的 XML 冲掉 —— 取证据必须"一个人、串行、全量跑完立刻归档"。
5. **teammate 并发上限是 8 名**（含 Lead）：本会话已有 7 名 inactive teammate 占着名额，
   `settings-screens` 与 `wizard-preview` 两次 spawn 都被拒 → 后两组屏幕（以及向导/关于）
   最终由 Lead 亲手完成。

---

## 5. 当前验证状态（可复现）

| 检查 | 结果 |
| --- | --- |
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL** |
| `:app:assembleDebug` | **BUILD SUCCESSFUL**（APK ≈ 10.8 MB） |
| `:core:jvmTest` | **31 套件 / 203 用例 / 0 失败** |
| `:app:testDebugUnitTest` | **8 套件 / 84 用例 / 0 失败** |
| `ScreenPlaceholder` 残留 | **0**（`ScreenPlaceholder.kt` 文件本体待 Phase 5 删除） |
| 模拟器逐屏截图 | 未逐屏完成（见 §6） |

---

## 6. 下一步（Phase 4 收尾 → Phase 5）

1. **逐屏模拟器截图**：13 条路由 + 向导；当前只验了 `/home` 与壳层。
2. **`Performance Glass` 接线**：`AppShell`/`GlassCard` 读该设置去控制 `LiquidSurface(refraction=)`。
3. **删除 `ScreenPlaceholder.kt`** 与 debug-only 的 `DebugKeepAliveActivity`。
4. **Phase 5**：`versionName = 3.0.0`（同步 `ScreensAbout.kt` 的 `APP_VERSION` 常量）、
   全量回归、归档证据、删除 `src/` + `android/` + `package.json` 遗留、更新 `docs/UI-3.0-PLAN.md` 的进度日志。

