# Phase 4 开发约定（13 屏幕 + 向导）

> 给并行开发 13 个屏幕的工程师：**先读完本文件再动笔**。判据永远是 v2 的界面源码
> （`src/ui/views/*.vue`）与 `docs/V2-UI-INVENTORY.md`，不是计划书。

---

## 1. 脚手架已经就绪（不要重复造）

| 文件 | 作用 |
| --- | --- |
| `app/.../app/AppContainer.kt` | 进程级容器：`navigator` / `grbl`（状态层）/ `toasts` / `confirm` / `resampler` |
| `app/.../app/Navigation.kt` | `Screen`（13 个路由，含 `title`/`tab`/`path`）、`Tab`（5 个）、`AppNavigator` |
| `app/.../app/AppShell.kt` | 壳层：`LiquidScaffold` + 顶栏 + 5 标签栏 + **overlay 槽**（Toast / 向导 / 确认框）；`LocalApp`、`GlassNavCard` |
| `app/.../app/GlassUi.kt` | 惯用法层：`GlassCard` / `GlassButton` / `GlassIconButton` / `GlassSwitch` / `GlassSwitchRow` / `GlassSliderRow` / `GlassKeyValue` / `GlassStat` / `GlassBadge` / `GlassSectionHeader` / `GlassProgressLine` / `GlassEmpty` / `statusBadgeKind` / `logLineColor` / `formatNumber` |
| `app/.../app/GlassText.kt` | `GlassText` / `GlassIcon`（**`:app` 没有 material3 依赖**，不要写 `material3.Text`） |
| `app/.../app/GrblController.kt` | 状态层：连接、位置、覆盖倍率、进度、日志、设备、文件、`$$`、主题、`laserTest` |

**屏幕签名已固定**（`AppShell` 的 `when` 已引用，**不要改签名**）：

```kotlin
fun HomeScreen(container: AppContainer, backdrop: Backdrop)
fun ConvertHubScreen(container: AppContainer, backdrop: Backdrop)
fun ImageConvertScreen(container: AppContainer, backdrop: Backdrop, screen: Screen.ConvertImage)
fun TextConvertScreen(container: AppContainer, backdrop: Backdrop, screen: Screen.ConvertText)
fun SvgConvertScreen(container: AppContainer, backdrop: Backdrop, screen: Screen.ConvertSvg)
fun FileScreen(container: AppContainer, backdrop: Backdrop)
fun PreviewScreen(container: AppContainer, backdrop: Backdrop, screen: Screen.Preview)
fun JogScreen(container: AppContainer, backdrop: Backdrop)
fun ConnectScreen(container: AppContainer, backdrop: Backdrop)
fun ConfigScreen(container: AppContainer, backdrop: Backdrop)
fun TerminalScreen(container: AppContainer, backdrop: Backdrop)
fun SettingsScreen(container: AppContainer, backdrop: Backdrop)
fun AboutScreen(container: AppContainer, backdrop: Backdrop)
fun SetupWizardOverlay(container: AppContainer, backdrop: Backdrop)
```

每个屏幕的**文件已存在**（`Screens*.kt`），当前内容是 `ScreenPlaceholder(...)`。
把它替换成真实实现即可 —— **一个屏幕一个文件**，避免写入冲突。

---

## 2. 五条硬性约束（来自前面各阶段的结论）

1. **不能用 `Popup` / `Dialog` 做浮层**：会另开窗口，玻璃取不到背后内容。
   底部弹层用 `com.lasergrbl.glasskit.LiquidBottomSheet`，确认框用 `container.confirm = GlassConfirmState(...)`
   （由 `AppShell` 的 overlay 槽渲染）。Phase 1 架构结论，见 `docs/UI-3.0-PLAN.md` §12 Phase 1。
2. **连接动作必须放 `Dispatchers.IO`**：`transport.open()` 会阻塞调用线程
   （USB 权限弹窗最长 20 s、蓝牙 `connect()` 数秒）。`GrblController.connect()` 已经这么做了，
   屏幕**不要**再自己调 `core.open`。
3. **蓝牙连接走 `connect()`**（`GrblController.connect` → `BluetoothSerialTransport`），
   不要直接 `open()`（`BluetoothSerialTransport.connect(device, baud)` 会先过权限门禁；
   Phase 3 审计的 D2）。
4. **只用 `container` 里的单例**（`grbl` / `navigator` / `resampler`）：
   多造一个 `GrblCore` 或传输层会争抢串口与广播。
5. **中文文案逐字保留**：`docs/V2-UI-INVENTORY.md` §1 的控件计数与 §4 的行为契约是判据；
   v2 的每一句中文提示都要在 3.0 里出现（同义改写也不行）。

---

## 3. 页面清单与主要控件（来自 §7 与 §1）

| 路由 | 屏幕 | v2 文件 | 主要控件 | 负责人 |
| --- | --- | --- | --- | --- |
| `/home` | `HomeScreen` | `HomeView.vue` 280 | 状态/坐标统计、任务运行/暂停/中止、快捷入口、日志列表 | Lead |
| `/convert` | `ConvertHubScreen` | `ConvertHubView.vue` 116 | 3 个入口卡 + 流程说明 | T1 |
| `/convert/image` | `ImageConvertScreen` | `ImageConvertView.vue` 685 | 输入 17、开关 9、行 9、滑块 5、下拉 3、警告 2、折叠 1 | T1 |
| `/convert/text` | `TextConvertScreen` | `TextConvertView.vue` 442 | 输入 11、开关 4、行 4、警告 3 | T1 |
| `/convert/svg` | `SvgConvertScreen` | `SvgConvertView.vue` 508 | 输入 11、开关 3、行 3、滑块 2 | T1 |
| `/file` | `FileScreen` | `FileView.vue` 249 | 按钮 8、已存文件列表、预览 | Lead |
| `/preview` | `PreviewScreen` | `PreviewView.vue` 217 | 画布（缩放 200–640）、统计、任务控制 | Lead |
| `/jog` | `JogScreen` | `JogView.vue` 342 | 九宫格点动、步长/速度、覆盖倍率、激光测试 | T2 |
| `/connect` | `ConnectScreen` | `ConnectView.vue` 302 | 设备列表、波特率下拉、USB/蓝牙切换、加载态 | T2 |
| `/config` | `ConfigScreen` | `ConfigView.vue` 303 | `$$` 读写、`.nc` 预设导入 | T2 |
| `/terminal` | `TerminalScreen` | `TerminalView.vue` 135 | 日志列表 + 命令输入 + 6 个快捷命令 | T2 |
| `/settings` | `SettingsScreen` | `SettingsView.vue` 421 | 输入 14、行 10、开关 6、下拉 2 | T2 |
| `/about` | `AboutScreen` | `AboutView.vue` 150 | 版本、许可、外链 | T3 |
| 首次启动 | `SetupWizardOverlay` | `SetupWizard.vue` 505 | 4 步 + 底部固定操作 | T3 |

---

## 4. 必须保留的行为（§7 明确列出）

* 路由名与 `meta.tab` 语义 → 已由 `Screen` / `Tab` 承载；
* 路径字符串跳转 → `AppNavigator.push(Screen.fromPath("/home")!!)` 或直接 `Screen.X`；
* 返回键守卫 → 已由 `AppShell` 的 `BackHandler(enabled = navigator.canGoBack)` 承载；
* **数值输入契约**：v2 的每个数字输入框在 `@blur` 里解析、非法值回退到原值
  （`(ev.target as HTMLInputElement).value`）。Compose 侧用 `LiquidTextField` + `onCommit`；
* 日志自动滚底 → 用 `LazyColumn` + `LaunchedEffect(log.size) { listState.animateScrollToItem(0) }`
  （**倒序**列表：v2 是正序 + `scrollTop = scrollHeight`；两者等价，但倒序性能更好）；
* `laserTest` 的 50–10000 ms 钳制 → `GrblController.laserTest` 已实现；
* Toast 默认 2.2 s → `container.toasts.show(...)` 的默认值就是 2200 ms；
* `keep-alive` 与运行生命周期绑定 → `GrblController.runFile/abortProgram/softReset` 已接保活。

---

## 5. 写作风格

* 所有注释与文案**中文**；KDoc 里**禁止**出现裸 `/*` 序列（Kotlin 块注释可嵌套，本项目已中招两次）。
* 每个屏幕的 KDoc 顶部写明：**对应 v2 哪个文件、多少行、保留了哪些行为、哪些做不到**。
* 屏幕内容用 `GlassScreenBody { ... }`（已含滚动与间距）；卡片用 `GlassCard(backdrop) { ... }`。
* 不要引入新依赖（`:app` 目前只有 core-ktx / activity-compose / :glasskit / :core / coroutines / usb-serial）。
* 不要用 `String.format` 做数字显示（项目统一 `formatNumber` 或 `com.lasergrbl.core.internal.jsNumberToString`，
  因为 v2 的数字格式化是 JS 语义）。

---

## 6. 验收

* `:app:compileDebugKotlin` 必须过；`:app:testDebugUnitTest` 不许回归；
* 模拟器上每个路由都要能跑到（Lead 会逐屏截图）；
* **不许留 `ScreenPlaceholder`**：Phase 5 之前所有占位必须消失。
