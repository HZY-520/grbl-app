# Phase 3 外设层记录（USB 串口 / 蓝牙 SPP / 前台服务）

> 本文件是 `docs/UI-3.0-PLAN.md` §6 / §6.1 的执行记录与交接材料。
> 完成时间：2026-10-07。所有结论都标注了**证据出处**，请勿把"构建通过"当成"真机可用"。

---

## 1. 验收清单（§6.1）逐条结论

| # | 要求 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | **写串行化**：所有 `write`/`writeBytes` 走同一把锁/同一队列，并给出顺序论证 | ✅ 满足 | 传输层单消费者队列（`SerialPortBase` 的写线程 / `UsbSerialTransport` 的 `runWriter`）+ 调用侧 `CoroutineStart.UNDISPATCHED`。论证见 §3 |
| 2 | **单测覆盖状态机**（写顺序、拔出只通知一次、`close()` 幂等、UTF-8 跨块、`isOpen()` 转换） | ✅ 满足 | `:core:jvmTest` **26 套件 / 177 用例 / 0 失败**；`:app:testDebugUnitTest` **7 套件 / 70 用例 / 0 失败**（0 跳过）；逐条映射见 §4 |
| 3 | **不得回归**：`:core:jvmTest` 仍是 22 套件 / 125 用例 / 0 失败 | ✅ 不回归 | 现在是 **26 套件 / 177 用例 / 0 失败**（基线 22/125 全部在内，新增 4 套件 52 用例）；另有 `:app` 侧 6 套件 53 用例 |
| 4 | **显式列出"无法离线验证"的条目** | ✅ 见 §6 | |
| 5 | `:app:assembleDebug` 出包；行为照抄 v2（8N1、DTR/RTS、写超时 2000 ms、读超时 20000 ms、权限广播 + 20 s、拔出只通知一次） | ✅ 出包 | APK `app/build/outputs/apk/debug/app-debug.apk` ≈ 9.9 MB。**注意：`§6.1` 把"读超时 20000 ms"写错了，见 §5** |
| 6 | 前台服务/通知照搬 v2 | ✅ 满足 | 模拟器实测 `dumpsys` 证据见 §2.3 |

---

## 2. 验证证据

### 2.1 编译与测试

**最终验收（串行、`--rerun-tasks` 真跑，XML 已归档到 `build/verify-20261007-105746/`，含 APK 副本）**：

```
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.18'
.\gradlew.bat :core:jvmTest :app:testDebugUnitTest :app:assembleDebug --rerun-tasks
→ BUILD SUCCESSFUL
```

* `:core:jvmTest` → **26 套件 / 177 用例 / 0 失败 / 0 跳过**
* `:app:testDebugUnitTest` → **7 套件 / 70 用例 / 0 失败 / 0 跳过**
* `:app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` ≈ **9.87 MB**
* `:core:compileAndroidMain`、`:app:compileDebugKotlin` 全部 `BUILD SUCCESSFUL`

> ⚠️ **证据归档纪律**（本轮踩过）：任何**带 `--tests` 过滤**的运行都会覆盖
> `app/build/test-results/`，把上一次全量跑的 XML 冲掉。取最终证据时必须
> **一个人、串行、全量跑完立刻归档**（`Copy-Item` 到 `build/verify-<时间戳>/`），否则数字无法复现。

### 2.1.1 变异自证（证明测试有牙齿）

由独立的 `serial-tests` teammate 执行，每处都精确还原并给出 sha256：

| 变异 | 结果 | 归因 |
| --- | --- | --- |
| `teardown` 把 `connection = null` 提前到排空之前 | `SerialPortBaseTest` 20 条里恰好 **3 条红**（都是排空组），其余 17 条全绿 | 精确命中"关闭前排空" |
| `Utf8StreamDecoder` 的跨块挂起改成"每块独立解码" | 35 条里 **7 条红**（6 条解码器 + 1 条端到端逐字节），**非法序列表全绿** | 只打中跨块语义，不误伤 |
| `GrblCore` 的 `UNDISPATCHED` 改回普通 `launch` | `GrblCoreSerialOrderingTest` 4 条里 **2 条红**，其中 `sequentialWritesKeepCallOrderOnRealDispatcher` 报出**真实重排**（`expected:<[?, !, ~, ?, …]>`） | 证明"调用侧顺序"这条论证不是形式化看门狗 |

### 2.1.2 独立复核（蓝牙，对照 v2 逐条审计）

由**另一个** teammate（`bt-audit`，不参与写蓝牙代码）逐条审计 v2 `BluetoothSerialPlugin.java`：
**7 条一致、1 条行为差异、1 条前提需修正**，另查出 1 处 v2 行号引用错误、2 处注释与平台事实不符。
它新增 `BluetoothV2AuditTest`（17 用例，**运行时直接解析 v2 的 `.java` 文件**再比字面量，而不是手抄）。

**已由 Lead 修掉的项**：

| 编号 | 问题 | 处理 |
| --- | --- | --- |
| D2 | **`open()` 路径不再过权限门禁**：v2 的 `open` 也先 `withPermission`（`BluetoothSerialPlugin.java:111-113`），未授权时**先弹框**；3.0 的 `open` 是基类 `final`，门禁只挂在 `list()` 上 ⇒ "自动重连/深链直达/用户撤销权限后重进" 会只报错、无法自助恢复 | 新增 `BluetoothSerialTransport.connect(device, baudRate)`（= 先 `ensureBluetoothPermission()` 再 `open`），KDoc 写明 Phase 4 必须走它 |
| D1 | v2 行号引用错 1 处（`252` 应为 `251`），出现在 3 个文件 | 三处已改 |
| D8 | 两处注释与平台事实不符：① 蓝牙读 EOF **不是**返回 `-1`，AOSP 的 RFCOMM 把负数转成 `IOException("bt socket closed, read return: -1")`（android-28/30/31/35 一致）⇒ 真机走异常分支；② `BluetoothAdapter.isEnabled` 实际标 `@RequiresNoPermission`，那个 catch 是防御性死代码 | 两处注释已按实测事实改写（代码行为不变，两条分支结果一致） |

**留给 Phase 4 / 产品决策（未改）**：

* **D3**：`socket.connect()` 在**调用线程**上阻塞数秒（v2 放在独立线程）。Phase 4 的连接动作**必须** `withContext(Dispatchers.IO)`，否则主线程 ANR。
* **D5**：`BluetoothSerialTransport.create(context)` 每次造新实例、各自注册 ACL 接收器 ⇒ Phase 4 只应使用 `IgRblApplication.bluetoothTransport` 单例（当前已是 `by lazy`）。
* **D9**：`cancelDiscovery()` 需要 `BLUETOOTH_SCAN`（AOSP android-31），而 v2 与 3.0 的清单都**没有**该权限 ⇒ Android 12+ 上这一步必然抛 `SecurityException`（被吞掉是常规路径，**两版一致、不是回归**）。若要真正取消扫描需加权限（多一次弹窗），属产品决策。
* **D6**：写失败在 3.0 只记 `lastError`（fire-and-forget），v2 是 reject Promise（但 v2 的 JS 侧也不 await ⇒ unhandled rejection）。建议 Phase 4 把 `lastError` 显示进日志。
* **D7**：蓝牙工厂用 `device.address ?: device.id` 兜底，v2 的 TS 层是显式 `缺少蓝牙 MAC 地址`。正常流程不可达（list 产出的 `id == address`）。

### 2.2 跨层顺序（§6.1 第 1 条的核心）

`core/src/jvmTest/.../serial/GrblCoreSerialOrderingTest.kt` 把**真实 `SerialPortBase`** 接到
**真实 `GrblCore`**（`txScope = Dispatchers.Default`，多线程，与真机一致）上：

| 用例 | 断言 |
| --- | --- |
| `immediateSendEnqueuesBeforeReturning` | `sendImmediate()` **返回时**字节已进入传输层（接缝层委托取号，零等待）—— `UNDISPATCHED` 的确定性看门狗 |
| `sequentialWritesKeepCallOrderOnRealDispatcher` | 200 次实时命令的落盘顺序 = 调用顺序，且从未并发写 |
| `handshakeWritesKeepCallOrder` | `$I` → `$$` → `$#` 的落盘顺序正确 |
| `closeFlushesQueuedWritesEvenWhenWritesAreSlow` | 写慢（1 ms）× 200 条立刻 `close()`，**一个字节都不丢** |

### 2.3 模拟器实测（AVD `iGRBL_API33`，Android 13 / API 33）

安装 `app-debug.apk` 后：

* `MainActivity` 正常启动，`logcat` 无 `FATAL`。
* 前台服务（通过 debug-only 的 `DebugKeepAliveActivity` 触发，见 §7）：

```
ServiceRecord{... com.lasergrbl.android/.keepalive.EngraveService}
  isForeground=true foregroundId=1001
  foregroundNoti=Notification(channel=igrbl_engrave ... category=progress vis=PUBLIC)
```

* 通知渠道（`dumpsys notification`）：

```
NotificationChannel{mId='igrbl_engrave', mName=雕刻进度, mImportance=2,
                    mSound=null, mShowBadge=false, mFgServiceShown=true, ...}
```

* 通知记录：`pkg=com.lasergrbl.android id=1001 channel=igrbl_engrave`；
  `numPostedByApp=1`、`numUpdatedByApp=1` —— **重复 start/update 只复用同一条通知**。
* `stop()` 之后：`EngraveService` 不再出现在 `dumpsys activity services`，
  且 `dumpsys notification` 里已无 `com.lasergrbl.android` 的通知 —— **无僵尸通知**。
* 文案探针（`formatText`）：`"abcdefghijklmnopqrstuvwxyz0123456789.nc"` + 7% →
  `abcdefghijklmnopqrstuvwx… · 7%`（24 字符截断 + `文件名 · 百分比%`）。
* **设备枚举手链路**（`ACTION_LIST_DEVICES` 走 `IgRblApplication.grblCore` → `transportFactory`
  → 真实 `UsbSerialTransport` / `BluetoothSerialTransport`）：

```
listDevices -> OK size=0 []                                  ← USB Host 无设备，不崩、返回空表
listBluetoothDevices -> ERR SecurityException: 未获得蓝牙权限  ← 未授权时抛错（v2 语义）
listBluetoothDevices -> OK size=0 []                          ← pm grant 之后正常枚举（模拟器无配对设备）
```

  说明：模拟器**没有** USB Host 串口与已配对蓝牙设备，所以"size=0"才是预期；这一段证明的是
  **这条链路真的接通了、异常路径文案正确、进程不崩**（`pidof` 确认进程存活、无 FATAL），
  而不是真机识别结果。

---

## 3. 写顺序论证（回答 §6.1 第 1 条）

原来有两处会破坏顺序，**都已修**：

1. **调用侧**：`GrblCore.sendLine()` / `sendImmediate()` 原先是
   `txScope.launch { transport.write(...) }`，而 `txScope = Dispatchers.Default`（多线程）。
   普通 `launch` 只是**排队**，两个相邻的写谁先真正跑由调度器决定 ⇒ 传输层收到的调用顺序
   可能与调用顺序不同。**已改成 `launch(start = CoroutineStart.UNDISPATCHED)`**：
   协程体在**调用线程上立即执行到第一个挂起点**。
2. **传输层**：`write()` 必须"入队发生在第一个挂起点之前且不挂起"。两个实现都满足：
   * `SerialPortBase`：`write()` 是 suspend 但**不挂起**，只在锁内 `writeQueue.add(bytes)`；
   * `UsbSerialTransport`：`enqueueWrite()` 先把 `WriteRequest` 放进
     `Channel.UNLIMITED`（`send` 不挂起），然后才 `await()` 完成信号。

由此得到闭环：
**调用顺序 =（UNDISPATCHED + 不挂起入队）⇒ 入队顺序 =（FIFO 队列）⇒ 出队顺序 =（单消费者/单写线程）⇒ 落盘顺序**。

跨线程并发调用时"意图顺序"本身无法定义，但仍保证：**不并发写、不丢字节、不把单次写的字节拆开或交错**。

---

## 4. §6.1 第 2 条：单测逐条映射

| 要求 | 用例 |
| --- | --- |
| 写顺序 | `GrblCoreSerialOrderingTest`（4 条）× `SerialPortBaseTest`（`:core`）+ `serial/UsbSerialTransportTest`（`:app`，`writesReachThePortInCallOrderAndNeverOverlap` / `concurrentWritersAreSerializedOnThePort` / `writeAndWriteBytesShareOneOrderedQueue`） |
| 拔出只通知一次 | `SerialPortBaseTest` 的断开组 + `UsbSerialTransportTest.externalDisconnectNotifiesExactlyOnceAndResetsOnReopen` / `readEofClosesAndNotifiesOnce` / `readFailureClosesAndNotifiesOnce` / `concurrentDetachAndReadFailureNotifyOnlyOnce` |
| `close()` 幂等 | `SerialPortBaseTest` 的幂等组 + `UsbSerialTransportTest.closeIsIdempotentAndDoesNotNotify` |
| UTF-8 跨块拼接（增量解码器） | `Utf8StreamDecoderTest`（`:core`，13 条，含非法序列/过长编码/代理区/4 字节切块）+ `UsbSerialTransportTest`（3 条端到端：逐字节喂、3 字节切 3 段、4096 边界切在首字节后） |
| `isOpen()` 状态转换 | `SerialPortBaseTest` 的状态组 + `UsbSerialTransportTest.openFailureLeavesStateDisconnectedAndAllowsRetry` / `openReleasesThePreviousConnectionFirst` |
| 关闭前排空写队列 | `GrblCoreSerialOrderingTest.closeFlushesQueuedWritesEvenWhenWritesAreSlow` |
| 权限门禁 | `UsbSerialTransportTest.permissionPredicatesMatchV2` + 蓝牙 `BluetoothPermissionGateDecisionTest`（6 条）+ `NotificationPermissionGate`（见 §7 待补） |

---

## 5. 与 §6.1 原文的两处事实更正

1. **"读超时 20000 ms"是笔误**。v2 `UsbSerialPlugin.java` 里
   `READ_TIMEOUT_MS = 0`（**无限阻塞**读，跑在工作线程），`PERMISSION_TIMEOUT_MS = 20000L`
   是**权限广播等待**超时。3.0 按 v2 实现：读超时 0、权限等待 20 s。
   另注：usb-serial-for-android 源码明确警告"非 0 读超时 + 高波特率连续传输会丢数据"，
   所以不要为了"看起来安全"改成有界读超时。
2. **前台服务的包名**是 `com.lasergrbl.android.keepalive.EngraveService`
   （不是根包 `.EngraveService`），清单里已按真实包名声明。

---

## 6. 无法离线验证的条目（**不要当作已验证**）

这些必须在真机上联调，当前一律标记为**未验证**：

1. **USB 真机枚举**：`UsbSerialProber` 能否识别目标雕刻机的芯片（CH340 / CP2102 / FTDI / …）。
2. **USB 权限弹窗**：首次插入时的系统弹窗、20 s 超时行为、拒绝后的错误文案。
3. **实际波特率与 8N1 + DTR/RTS 的握手效果**（部分 CH340 需要 DTR/RTS 才能收到数据）。
4. **拔出行为**：`ACTION_USB_DEVICE_DETACHED` 的到达时机与"只通知一次"在真机上的表现；
   拔出瞬间跑在 4 ms 节拍上的写会不会卡住（写超时 2000 ms 是兜底）。
5. **4 ms 节拍能否跟上**：真机 USB 的实际写延迟 vs 4 ms tick（模拟器上无 USB 串口，测不了）。
6. **蓝牙 SPP**：配对、`createRfcommSocketToServiceRecord` 连接成功率、吞吐、
   `ACL_DISCONNECTED` 的时机、Android 12+ 的 `BLUETOOTH_CONNECT` 运行时授权。
7. **通知权限的真实弹窗**（模拟器上是 `pm grant` 直接给的）。
8. **`[OPT:]` / `Bf:` 的真实取值**与软复位后固件启动时间（是否够 400 ms）—— Phase 2 遗留。

---

## 7. 本轮实现改动清单（含抓到的真 bug）

### 7.1 结构性改动

* **`PortConnection` 接缝拆分为两个类型**（`core/.../serial/PortConnection.kt`）：
  `PortConnection{read,write,close}` 只代表"一条已打开的连接"，
  打开动作移到 `fun interface PortConnectionFactory { create(device, baudRate) }`。
  这样句柄不需要实现对它没有意义的 `list()` / `open()`，也就不会出现"为满足接口而抛异常的桩"。
* **新增 `SerialPortBase`**（`core/.../serial/SerialPortBase.kt`）：USB 与蓝牙共用的状态机
  （写队列串行化、专用读线程 + 增量 UTF-8、`close()` 幂等、`closed` 只通知一次、有界排空）。
* **`:app` 侧**：`serial/UsbSerialTransport.kt`、`serial/UsbSerialPortConnection.kt`、
  `serial/UsbPermissionGate.kt`、`serial/BluetoothSerialTransport.kt`、
  `serial/BluetoothSocketConnection.kt`、`serial/BluetoothPermissionGate.kt`；
  前台服务 `keepalive/EngraveService.kt` + `keepalive/AndroidNativeKeepAlive.kt` +
  `keepalive/NotificationPermissionGate.kt`。
* **`:core` 侧**：`native/KeepAlive.kt`（`KeepAliveController` 节流策略 + `NativeKeepAlive` 接缝）、
  `serial/SerialPortBase.kt`、`serial/PortConnection.kt`。

### 7.2 本轮抓到的真 bug（都改了实现，不是改断言）

| # | 缺陷 | 影响 | 修法 |
| --- | --- | --- | --- |
| 1 | `Utf8StreamDecoder` 在"非法序列后面跟着合法字节"时**吞掉后续合法字节**（续字节校验发生在收满 `need` 个字节之后，失败时丢首字节却不重新取输入） | 畸形输入后会丢一个正常字符 | 改成**边收边校验**：续字节不合法就只吃首字节、立即重新同步（WHATWG 语义），保证每个非法字节恰好一个 U+FFFD 且后续字节一个不丢 |
| 2 | 手动 `close()` 会**多发一次 `closed`**：`teardown` 先 `stopWriter` 才关句柄，读线程被唤醒后把"我们自己关的端口"当成拔出 | 上层会收到假断开事件 | 读线程的停止判据改成 `bound`，且读线程只在 `bound` 仍为真时才发起 `teardown(notify=true)`；`close()` 先置 `bound=false` 再排空、再关句柄 |
| 3 | `open()` 失败会**泄漏**已获取的句柄（基类拿不到半成品句柄） | 真机上"第二次连接永远失败" | 把"失败必须自己关干净再抛"写成 `PortConnectionFactory.create()` 的**硬契约**（KDoc + 可执行示例用例） |
| 4 | `teardown` 把 `connection = null` 放在排空**之前**（我修 #2 时引入的回归） | `close()` 时排队中的字节被静默丢弃，`pendingWrites` 照减、`lastError` 为 null，"有界排空"假装成功 | 顺序改为 `bound=false` → **保留 connection** → `stopWriter(awaitFlush)` → 摘 connection → 关句柄；并加独立 `closing` 幂等闸门 |
| 5 | `GrblCore` 的 fire-and-forget 写用普通 `launch`（多线程调度器会重排） | 真机上命令/实时字节可能乱序落盘 | 改 `launch(start = CoroutineStart.UNDISPATCHED)` |
| 6 | `app/build.gradle.kts` 的 catalog 别名 `usb-serial-for-android` 生成含 Kotlin 关键字的访问器 | **整个 Gradle 构建配置期失败**，所有人被阻塞 | catalog 别名改为 `usb-serial-driver` |
| 7 | `:app` 的 `kotlinx-coroutines` 被传递依赖压到 1.9.0，而 `:core` 用 1.11.0 | 同一份类型两个版本，容易出现难查的类型不匹配 | `:app` 显式钉 `libs.kotlinx.coroutines.android`（catalog 的 1.11.0） |
| 8 | "排空超时"的错误文案把**条数**写成"字节" | 真机排查时误判剩余量 | 文案改"剩余 N 条未落盘"，并区分下面那条真正的字节数文案 |
| 9 | 排空超时路径下写线程会永久阻塞在 `take()`（POISON 已被 leftover 排空 poll 掉） | daemon 线程泄漏（功能无影响） | 只在**超时路径**置位 `stopRequested`，写线程出队后一并检查；正常路径仍由 POISON 收尾（否则会在处理完队尾前退出，重现"静默丢字节"——本轮就是这么被自己的用例抓回来的） |

### 7.3 被删除的重复实现

`app/src/main/kotlin/com/lasergrbl/android/platform/UsbSerialTransport.kt` +
`AndroidSerialDevices.kt`（以及 `platform/` 下 4 个测试文件）在 Phase 3 早期短暂存在过一版
"自带写队列与解码器、不走 `SerialPortBase` 接缝"的 USB 实现；已**全部删除**，
用例迁移到 `serial/UsbSerialTransportTest.kt`（17 条）与 `serial/UsbSerialDeviceInfoTest.kt`（7 条）。
现在 `:app` 里只有**一套** USB 传输层。
（`platform/AndroidSettingsPersistence.kt` 是 Phase 2 的，保留。）

---

## 8. 本轮新增的验证入口（**仅 debug 变体**）

`app/src/debug/` 下有 `DebugKeepAliveActivity`（`exported=true`，`Theme.NoDisplay`，`noHistory`）。
存在理由：`EngraveService` 是 `exported="false"`（正确的安全配置），
所以 `adb shell am start-foreground-service` 会被系统拒绝
（实测报 `Error: Requires permission not exported from uid ...`），模拟器上无法从外部触发验收。

```
adb shell am start -n com.lasergrbl.android/.debug.DebugKeepAliveActivity \
    -a com.lasergrbl.android.debug.KEEPALIVE_START --es name "job.nc" --ei percent 0
adb shell am start -n com.lasergrbl.android/.debug.DebugKeepAliveActivity \
    -a com.lasergrbl.android.debug.KEEPALIVE_PROGRESS --ei executed 42 --ei total 100
adb shell am start -n com.lasergrbl.android/.debug.DebugKeepAliveActivity \
    -a com.lasergrbl.android.debug.KEEPALIVE_STOP
```

它走的是与 Phase 4 界面**完全一致**的链路
（`IgRblApplication.keepAlive` → `KeepAliveController` → `AndroidNativeKeepAlive` → `EngraveService`）。
`release` 变体不包含该文件；Phase 5 清理时一并复核。
