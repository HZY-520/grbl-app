package com.lasergrbl.android.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lasergrbl.core.gcode.GcodeFileData
import com.lasergrbl.core.gcode.parseGcode
import com.lasergrbl.core.grbl.AppSettings
import com.lasergrbl.core.grbl.DetectedIssue
import com.lasergrbl.core.grbl.DeviceProfile
import com.lasergrbl.core.grbl.DeviceProfiles
import com.lasergrbl.core.grbl.Firmware
import com.lasergrbl.core.grbl.GPoint
import com.lasergrbl.core.grbl.GrblConfiguration
import com.lasergrbl.core.grbl.GrblCore
import com.lasergrbl.core.grbl.JogDirection
import com.lasergrbl.core.grbl.MacStatus
import com.lasergrbl.core.grbl.MessageType
import com.lasergrbl.core.grbl.StreamingMode
import com.lasergrbl.core.grbl.ThreadingMode
import com.lasergrbl.core.native.KeepAliveController
import com.lasergrbl.core.serial.SerialDeviceInfo
import com.lasergrbl.core.serial.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 界面状态层 —— 移植自 v2 `src/ui/store.ts`（504 行）。
 *
 * v2 用一个模块级 `reactive()` 对象把所有 `GrblCore` 事件镜像成响应式状态；
 * Kotlin/Compose 侧等价物是本类：
 *  * **状态**用 `mutableStateOf` 承载（Compose 按读取点精确重组）；
 *  * **事件**在 [bind] 里一次性订阅 `GrblCore` 的 12 个 `Emitter`；
 *  * **动作**是普通方法，异步的用 [scope] 起协程。
 *
 * ### 与 v2 逐条对齐的行为契约（不是实现细节）
 *  * `configRev` 在 `connected` 与每条 `MessageType.Config` 消息时自增 —— `ConfigScreen` 靠它重载；
 *  * 日志上限 **600** 条（v2 的 `splice(0, len-600)`）；
 *  * 断开（含连接失败 / 异常掉线）时**必须**结束保活，避免残留通知；
 *  * `progress` 事件里百分比 = `executed / total * 100`（`total = 0` 按 0% 处理，不除零）；
 *  * `runFile` 启动保活；`abortProgram` / `softReset` / `programEnd` / `disconnected` 结束保活；
 *  * `laserTest` 在指定时长后关激光，时长**钳到 50–10000 ms**，未连接或正在运行时拒绝。
 */
class GrblController(
    val core: GrblCore,
    private val keepAlive: KeepAliveController,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {

    // ================= 连接与状态 =================

    var connected by mutableStateOf(false)
        private set
    var connecting by mutableStateOf(false)
        private set
    var status by mutableStateOf(MacStatus.Disconnected)
        private set
    var version by mutableStateOf("")
        private set
    var firmware by mutableStateOf(core.firmware)
        private set

    var pos by mutableStateOf(GPoint(0.0, 0.0, 0.0))
        private set
    var wpos by mutableStateOf(GPoint(0.0, 0.0, 0.0))
        private set
    var wco by mutableStateOf(GPoint(0.0, 0.0, 0.0))
        private set
    var feedValue by mutableStateOf(0.0)
        private set
    var spindle by mutableStateOf(0.0)
        private set
    var overrides by mutableStateOf(GrblCore.Overrides(100, 100, 100))
        private set
    var targetOverrides by mutableStateOf(core.targetOverrides)
        private set
    var progress by mutableStateOf(GrblCore.Progress(0, 0, 0))
        private set
    var running by mutableStateOf(false)
        private set
    var issue by mutableStateOf<DetectedIssue>(DetectedIssue.Unknown)
        private set

    // ================= 设备 =================

    var devices by mutableStateOf<List<SerialDeviceInfo>>(emptyList())
        private set
    var bluetoothDevices by mutableStateOf<List<SerialDeviceInfo>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var deviceKind by mutableStateOf(TransportKind.Usb)
        private set
    var deviceId by mutableStateOf(-1)
        private set
    var baud by mutableStateOf(AppSettings.get("Last Baud", 115200))
        private set

    // ================= 点动 =================

    var jogStep by mutableStateOf(core.jogStep)
        private set
    var jogSpeed by mutableStateOf(core.jogSpeed)
        private set

    // ================= 日志 =================

    var log by mutableStateOf<List<LogItem>>(emptyList())
        private set
    private var logSeq = 0L

    // ================= 文件 =================

    var file by mutableStateOf<GcodeFileData?>(null)
        private set

    /** 配置重载计数器（`connected` 与每条 Config 消息都自增）。 */
    var configRev by mutableStateOf(0)
        private set

    // ================= 设置 =================

    var settings by mutableStateOf(AppSettings.all())
        private set
    var needsSetup by mutableStateOf(false)
        private set
    var setupDeviceId by mutableStateOf(-1)
        private set
    var serialMonitor by mutableStateOf(AppSettings.get("Serial Monitor", true))
        private set

    /**
     * 主题是否为浅色。
     *
     * ⚠️ **这是 3.0 相对 v2 的一处修复**：v2 的 `theme` 只在内存里
     * （`GrblConfig.theme`），`setTheme` 从不写 localStorage，刷新必然回到深色
     * （见 `docs/V2-UI-INVENTORY.md` §4.4 的注记）。3.0 把它持久化到
     * `AppSettings` 的 `Theme` 键（默认 `dark`）。
     */
    var themeIsLight by mutableStateOf(AppSettings.get("Theme", "dark") == "light")
        private set

    private var bound = false

    // ================= 事件绑定 =================

    /** 订阅 `GrblCore` 的全部事件（幂等）。 */
    fun bind() {
        if (bound) return
        bound = true

        core.onStatus.on { s ->
            status = s
            connected = core.isConnected
            connecting = s == MacStatus.Connecting
        }
        core.onConnected.on {
            connected = true
            connecting = false
            version = core.version?.toString() ?: ""
            firmware = core.firmware
            pushLog("已连接（${if (version.isEmpty()) "未知版本" else version}）", MessageType.Startup)
            configRev++
            jogStep = core.jogStep
            jogSpeed = core.jogSpeed
        }
        core.onDisconnected.on {
            connected = false
            connecting = false
            running = false
            syncOverrides()
            // 断开（含连接失败 / 异常掉线）时必须结束保活，避免残留通知
            scope.launch { runCatching { keepAlive.stop() } }
        }
        core.onPosition.on { syncPosition() }
        core.onFss.on { v ->
            feedValue = v.f
            spindle = v.s
        }
        core.onOverride.on { syncOverrides() }
        core.onProgress.on { p ->
            progress = p
            running = true
            scope.launch {
                runCatching { keepAlive.updateProgress(p.executed.toDouble(), p.total.toDouble()) }
            }
        }
        core.onProgramEnd.on {
            running = false
            pushLog("任务执行完成", MessageType.Feedback)
            scope.launch { runCatching { keepAlive.stop() } }
        }
        core.onMessage.on { m ->
            pushLog(m.message, m.type)
            if (m.type == MessageType.Config) configRev++
        }
        core.onIssue.on { issue = it }
        core.onConnectTimeout.on {
            connecting = false
            pushLog("连接超时，请检查设备与波特率", MessageType.Warning)
        }
    }

    private fun syncPosition() {
        pos = core.position
        wpos = core.workPosition
        wco = core.wco
    }

    private fun syncOverrides() {
        overrides = core.overrides
        targetOverrides = core.targetOverrides
    }

    // ================= 日志 =================

    fun pushLog(text: String, kind: MessageType = MessageType.Others) {
        val item = LogItem(++logSeq, text, kind, System.currentTimeMillis())
        val next = log + item
        log = if (next.size > MAX_LOG) next.subList(next.size - MAX_LOG, next.size).toList() else next
    }

    fun clearLog() {
        log = emptyList()
    }

    // ================= 设备与连接 =================

    fun refreshDevices() {
        scanning = true
        scope.launch {
            try {
                devices = withContext(Dispatchers.IO) { core.listDevices() }
            } catch (e: Throwable) {
                pushLog("枚举设备失败：${e.message}", MessageType.Warning)
                devices = emptyList()
            } finally {
                scanning = false
            }
        }
    }

    fun refreshBluetoothDevices() {
        scanning = true
        scope.launch {
            try {
                bluetoothDevices = withContext(Dispatchers.IO) { core.listBluetoothDevices() }
            } catch (e: Throwable) {
                pushLog("枚举蓝牙设备失败：${e.message}", MessageType.Warning)
                bluetoothDevices = emptyList()
            } finally {
                scanning = false
            }
        }
    }

    fun updateBaud(value: Int) {
        baud = value
        AppSettings.set("Last Baud", value)
    }

    /**
     * 连接设备。
     *
     * ⚠️ **必须放 IO 调度器**：`transport.open()` 会阻塞调用线程
     * （USB 权限弹窗最长 20 s、蓝牙 `socket.connect()` 数秒）。在主线程调用会 ANR ——
     * 这是 Phase 3 审计列出的硬性约束。
     */
    fun connect(device: SerialDeviceInfo) {
        if (connected || connecting) return
        AppSettings.set("Last Baud", baud)
        val label = device.product ?: device.name.ifEmpty { device.address ?: "设备 #${device.deviceId}" }
        connecting = true
        pushLog("正在连接 $label @ $baud…", MessageType.Command)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.open(device, baud) }
                deviceKind = device.kind
                val numeric = device.deviceId
                deviceId = numeric?.toInt() ?: -1
                // 只有 USB 设备有稳定的数字编号，可绑定参数档案
                if (numeric != null) {
                    AppSettings.set("Last Port", numeric.toInt().toString())
                    val bound = DeviceProfiles.findProfileByDevice(numeric)
                    if (bound != null) {
                        DeviceProfiles.applyProfileToSettings(bound)
                        settings = AppSettings.all()
                        pushLog("已套用设备参数「${bound.name}」", MessageType.Feedback)
                    }
                    // 首次连接的新设备：提示进入初始化向导设置参数
                    if (!DeviceProfiles.isDeviceKnown(numeric)) {
                        setupDeviceId = numeric.toInt()
                        needsSetup = true
                        pushLog("首次连接设备 #${numeric.toInt()}，请先设置行程 / 功率等参数", MessageType.Feedback)
                    }
                }
            } catch (e: Throwable) {
                connecting = false
                pushLog("连接失败：${e.message}", MessageType.Warning)
                // 连接失败路径同样要清理保活状态，避免遗留通知
                runCatching { keepAlive.stop() }
            }
        }
    }

    fun disconnect() {
        scope.launch {
            runCatching { core.close(manual = true) }
            connected = false
            connecting = false
            running = false
            syncOverrides()
            runCatching { keepAlive.stop() }
        }
    }

    fun openSetupWizard(deviceId: Int = -1) {
        setupDeviceId = deviceId
        needsSetup = true
    }

    fun clearNeedsSetup() {
        needsSetup = false
    }

    fun reloadSettings() {
        AppSettings.load()
        settings = AppSettings.all()
        serialMonitor = AppSettings.get("Serial Monitor", true)
        themeIsLight = AppSettings.get("Theme", "dark") == "light"
    }

    fun setTheme(light: Boolean) {
        themeIsLight = light
        AppSettings.set("Theme", if (light) "light" else "dark")
    }

    // ================= 手动命令 =================

    fun sendCommand(line: String) {
        if (line.isBlank()) return
        core.enqueueRaw(line, preserveCase = true)
    }

    fun softReset() {
        core.softReset()
        running = false
        scope.launch { runCatching { keepAlive.stop() } }
    }

    fun feedHold() = core.feedHold()

    fun cycleStart() = core.cycleStart()

    fun homing() = core.homing()

    fun unlock() = core.unlock()

    fun setNewZero() = core.setNewZero()

    fun resetWCO() = core.resetWCO()

    fun jog(dir: JogDirection) = core.jog(dir, jogStep, jogSpeed)

    fun moveTo(x: Double, y: Double) = core.moveTo(x, y, jogSpeed)

    fun setJogParams(step: Int? = null, speed: Int? = null) {
        step?.let {
            jogStep = it
            core.jogStep = it
        }
        speed?.let {
            jogSpeed = it
            core.jogSpeed = it
        }
    }

    fun setTargetOverride(kind: OverrideKind, value: Int) {
        when (kind) {
            OverrideKind.Feed -> core.setFeedOverride(value)
            OverrideKind.Rapids -> core.setRapidOverride(value)
            OverrideKind.Power -> core.setPowerOverride(value)
        }
        syncOverrides()
    }

    fun setStreamingMode(mode: StreamingMode) = core.setStreamingMode(mode)

    fun setThreadingMode(name: String) {
        core.threadingMode = ThreadingMode.fromName(name)
    }

    fun currentStreamingMode(): StreamingMode = core.currentStreamingMode

    fun currentThreadingMode(): ThreadingMode = core.threadingMode

    /**
     * 激光测试：指定功率开激光，[durationMs] 后自动关闭。
     *
     * 对应 v2 `store.ts:383-401`。时长**钳到 50–10000 ms**；未连接或正在运行时拒绝。
     *
     * @return 是否真的启动了测试
     */
    fun laserTest(power: Int, durationMs: Int): Boolean {
        if (!connected || running) return false
        val ms = durationMs.coerceIn(50, 10_000)
        core.enqueueRaw("M3 S$power")
        scope.launch {
            delay(ms.toLong())
            core.enqueueRaw("M5")
        }
        return true
    }

    // ================= 文件 =================

    fun loadGcodeText(name: String, text: String) {
        file = parseGcode(name, text)
    }

    fun loadGcodeLines(name: String, lines: List<String>) {
        loadGcodeText(name, lines.joinToString("\n"))
    }

    fun clearFile() {
        file = null
        running = false
    }

    /**
     * 运行当前文件。
     *
     * @return 是否真的开始运行
     */
    fun runFile(resetBuffer: Boolean = true): Boolean {
        val f = file ?: return false
        if (!connected || running) return false
        val commands = f.commands.filter { !it.isEmpty }
        if (commands.isEmpty()) return false
        core.runProgram(commands, resetBufferAccounting = resetBuffer)
        running = true
        val name = f.name
        scope.launch { runCatching { keepAlive.start(name, 0.0) } }
        pushLog("开始运行 $name（${commands.size} 行）", MessageType.Command)
        return true
    }

    fun abortProgram() {
        core.abortProgram()
        running = false
        scope.launch { runCatching { keepAlive.stop() } }
    }

    // ================= 机器参数（$$） =================

    fun readMachineConfig() {
        if (!connected) return
        core.enqueueRaw("$$")
        settings = AppSettings.all()
    }

    fun writeMachineSetting(id: Int, value: Double) {
        core.writeSetting(id, value)
    }

    fun configEntries(): List<Pair<Int, Double>> = core.config.entries()

    fun settingInfo(id: Int): List<String> = core.settingInfo(id)

    fun machineConfig(): GrblConfiguration = core.config

    /** 读取机床行程与功率上限（向导 / 设置页用）。 */
    fun readMachineLimits(): MachineLimits = MachineLimits(
        travelX = core.config.get(130, 0),
        travelY = core.config.get(131, 0),
        maxPower = core.config.get(30, 0),
        minPower = core.config.get(31, 0)
    )

    /** 更新一条设置并落盘（设置页的通用入口）。 */
    fun updateSetting(key: String, value: Any?) {
        AppSettings.set(key, value)
        settings = AppSettings.all()
        when (key) {
            "Jog Step" -> (value as? Number)?.let { setJogParams(step = it.toInt()) }
            "Jog Speed" -> (value as? Number)?.let { setJogParams(speed = it.toInt()) }
            "Serial Monitor" -> serialMonitor = value as? Boolean ?: serialMonitor
            "Threading Mode" -> (value as? String)?.let { setThreadingMode(it) }
            "Theme" -> setTheme(value == "light")
            else -> Unit
        }
    }

    fun savedProfiles(): List<DeviceProfile> = DeviceProfiles.listSavedProfiles()

    fun builtinProfiles(): List<DeviceProfile> = DeviceProfiles.builtinProfiles()

    fun machineConfigGet(id: Int, default: Int = 0): Double = core.config.get(id, default)

    /** 机床限制快照（向导第 2 步与设置页共用）。 */
    data class MachineLimits(
        val travelX: Double,
        val travelY: Double,
        val maxPower: Double,
        val minPower: Double
    )

    companion object {
        /** 日志上限，与 v2 `store.ts:99` 的 600 一致。 */
        const val MAX_LOG = 600
    }
}

/** 日志条目（对应 v2 `LogItem`）。 */
data class LogItem(
    val id: Long,
    val text: String,
    val kind: MessageType,
    val time: Long
)

/** 覆盖倍率种类（对应 v2 `setTargetOverride(kind, value)` 的 kind）。 */
enum class OverrideKind { Feed, Rapids, Power }

/** 状态中文名（对应 v2 `STATUS_LABELS`，逐字）。 */
val STATUS_LABELS: Map<MacStatus, String> = mapOf(
    MacStatus.Disconnected to "未连接",
    MacStatus.Connecting to "连接中",
    MacStatus.Idle to "空闲",
    MacStatus.Run to "运行中",
    MacStatus.Hold to "已暂停",
    MacStatus.Door to "门打开",
    MacStatus.Home to "回零中",
    MacStatus.Alarm to "报警",
    MacStatus.Check to "校验模式",
    MacStatus.Jog to "点动中",
    MacStatus.Queue to "排队中",
    MacStatus.Cooling to "冷却中",
    MacStatus.AutoHold to "自动暂停",
    MacStatus.Tool to "换刀中"
)

/** 固件中文名（对应 v2 `FIRMWARE_LABELS`）。 */
val FIRMWARE_LABELS: Map<Firmware, String> = mapOf(
    Firmware.Grbl to "GRBL",
    Firmware.Smoothie to "Smoothieware",
    Firmware.Marlin to "Marlin",
    Firmware.Vigo to "Vigo"
)

/** 状态名（未知状态回退到 enum 名，与 v2 的 `?? state.status` 行为一致）。 */
fun MacStatus.label(): String = STATUS_LABELS[this] ?: name
