package com.lasergrbl.core.grbl

import com.lasergrbl.core.Emitter
import com.lasergrbl.core.internal.jsParseFloat
import com.lasergrbl.core.internal.jsToFixed
import com.lasergrbl.core.internal.jsTrim
import com.lasergrbl.core.internal.nowMillis
import com.lasergrbl.core.serial.SerialDeviceInfo
import com.lasergrbl.core.serial.SerialTransport
import com.lasergrbl.core.serial.TransportKind
import com.lasergrbl.core.grbl.parseVersionBanner as lookupParseVersionBanner
import com.lasergrbl.core.grbl.parseVerMessage as lookupParseVerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * GRBL 核心通讯与流式发送引擎 —— 逐行移植自 v2 `src/core/grbl/GrblCore.ts`
 * （其本身移植自 LaserGRBL `Core/GrblCore.cs`，仅保留激光雕刻相关功能）。
 *
 * 负责：串口连接握手、命令队列、缓冲流式发送、实时状态解析、覆盖倍率、点动、
 * 配置读写、报警/错误处理。
 *
 * 与 v2 的三处**有意**差异（都是为了可在没有硬件的 JVM 上确定性地测试，见各自注释）：
 *  1. `window.setInterval(() => this.txTick(), 4)` → [txLoop] 协程（`delay(txPeriodMillis)`），
 *     并且把 [txTick] 暴露成 `internal`，测试可以直接驱动一个 tick；
 *  2. 时间来源从 `Date.now()` 改成可注入的 [clock]（默认 `nowMillis()`）；
 *  3. 传输层从 `createTransport('usb'/'bluetooth')` 改成可注入的 [transport] + [transportFactory] 钩子
 *     （不 import Capacitor）；握手/滴答的等待时长可注入，默认值与 v2 一致。
 * 协议逻辑（4 ms 发送节拍、字节预算、三种流式模式、覆盖倍率阶梯、命令行装配、4096 守卫、
 * 握手时序、10 s 连接超时、卡死检测、v0.9/v1.1 状态解析）逐行对齐，没有改动。
 */

/** GRBL 串口接收缓冲默认大小（v0.9/v1.1 为 127 可用字节）。 */
const val DEFAULT_BUFFER_SIZE: Int = 127

/** 默认的发送节拍（v2：`window.setInterval(..., 4)`）。 */
const val DEFAULT_TX_PERIOD_MILLIS: Long = 4

/** 连接超时（v2：`Date.now() - this.connectStart > 10000`）。 */
const val CONNECT_TIMEOUT_MILLIS: Long = 10_000

/** 无换行脏数据的上限；超过就只保留最后 1024 字节（v2 的 4096 守卫）。 */
const val RX_BUFFER_GUARD: Int = 4096

/** 触发放弃时保留的尾部长度。 */
const val RX_BUFFER_KEEP: Int = 1024

/** `setAutoBufferSize(v, false)` 白名单（与 v2 的数组一致，顺序也一致）。 */
private val DEFAULT_BUFFER_SIZE_CANDIDATES = setOf(128, 255, 256, 10240, 254)

/** 连接握手各步之后的等待时长（毫秒），与 v2 的 400/350/250/150 一一对应。 */
data class HandshakeDelays(
    /** 软复位 0x18 之后的等待。 */
    val resetMillis: Long = 400,
    /** `$I` 之后的等待。 */
    val afterBuildInfoMillis: Long = 350,
    /** `$$` 之后的等待。 */
    val afterSettingsMillis: Long = 250,
    /** `$#` 之后的等待。 */
    val afterCoordinatesMillis: Long = 150
) {
    companion object {
        val Default = HandshakeDelays()

        /** 全部为 0：测试用（握手瞬间完成，不消耗虚拟时间）。 */
        val Immediate = HandshakeDelays(0, 0, 0, 0)
    }
}

/**
 * 设置读取口 —— 对应 v2 构造函数里的 `AppSettings.get<T>(key, def)`。
 *
 * 抽成接口是为了让测试能注入任意「Threading Mode / Firmware Type / Jog Speed / Jog Step /
 * Reset Grbl On Connect」，而不必去动全局的 `AppSettings`（那是 lazy 单例，进程内只会初始化一次）。
 */
interface GrblSettingsSource {
    fun getString(key: String, default: String): String
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean
}

/** 默认实现：直接读全局 `AppSettings`（与 v2 的行为逐字对应）。 */
object AppSettingsSource : GrblSettingsSource {
    override fun getString(key: String, default: String): String = AppSettings.get(key, default)
    override fun getInt(key: String, default: Int): Int = AppSettings.get(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = AppSettings.get(key, default)
}

/**
 * 复刻 JS `parseInt(str, 10)`：跳过前导空白，取最长的十进制整数前缀；没有则 `NaN`。
 * v2 在 `GrblConfiguration.addOrUpdate`（设置项编号）与 `parseOpt` / `parseBf` /
 * `parseOverrides`（缓冲区与倍率）里都靠它，`NaN` 会一路传播到 `Number.isNaN` 判断。
 */
internal fun jsParseInt10(value: String): Double {
    var i = 0
    val n = value.length
    while (i < n && value[i].isJsWhitespaceForParseInt()) i++
    val start = i
    if (i < n && (value[i] == '+' || value[i] == '-')) i++
    val digitsStart = i
    while (i < n && value[i] in '0'..'9') i++
    if (i == digitsStart) return Double.NaN
    val sign = if (value[start] == '-') "-" else ""
    return (sign + value.substring(digitsStart, i)).toDoubleOrNull() ?: Double.NaN
}

/** JS `parseInt` 之前的 `TrimString` 用的是 WhiteSpace ∪ LineTerminator，与 `jsTrim` 同一套。 */
private fun Char.isJsWhitespaceForParseInt(): Boolean = com.lasergrbl.core.internal.isJsWhitespace(this)

/** GRBL 设置项集合（`$` 参数）—— 对应 v2 的 `GrblConfiguration`。 */
class GrblConfiguration {

    private val map: MutableMap<Int, Double> = linkedMapOf()
    private var version: GrblVersionInfo? = null

    fun reset(version: GrblVersionInfo?) {
        map.clear()
        this.version = version
    }

    /** `$NUM=VAL`（v2 用 `line.trim()`，所以这里必须用 `jsTrim`）。 */
    fun addOrUpdate(line: String) {
        val m = CONF_LINE_REGEX.find(jsTrim(line)) ?: return
        val id = jsParseInt10(m.groupValues[1])
        if (id.isNaN()) return
        map[id.toInt()] = jsParseFloat(m.groupValues[2])
    }

    fun get(id: Int, def: Int = 0): Double = map[id] ?: def.toDouble()

    fun has(id: Int): Boolean = map.containsKey(id)

    /** 按编号升序的条目（对应 v2 的 `[...map.entries()].sort((a, b) => a[0] - b[0])`）。 */
    fun entries(): List<Pair<Int, Double>> = map.entries.map { it.key to it.value }.sortedBy { it.first }

    /** 生成写回命令。 */
    fun toCommands(): List<String> = entries().map { (k, v) -> "\$$k=${formatDecimal(v)}" }

    fun getVersion(): GrblVersionInfo? = version

    private companion object {
        /** `^\$(\d+)\s*=\s*(-?[\d.]+)` —— 注意 `group(2)` 只吃 `-`、数字和点。 */
        val CONF_LINE_REGEX = Regex("^\\$(\\d+)\\s*=\\s*(-?[\\d.]+)")
    }
}

/**
 * GRBL 核心。
 *
 * 事件与 v2 的 `CoreEvents` 一一对应，但按 Kotlin 的约定拆成**每种载荷一个 [Emitter]**：
 * `connected` / `disconnected` / `status` / `position` / `message` / `commandStatus` /
 * `progress` / `programEnd` / `override` / `fss` / `issue` / `connectTimeout`。
 */
class GrblCore(
    /**
     * 传输层工厂钩子 —— 对应 v2 的 `createTransport(kind)`。
     * 默认两个传输都是 null（Kotlin 侧不 import Capacitor），此时 [listDevices] / [listBluetoothDevices]
     * 会抛异常；`open()` 用**调用方传入的设备**打开，不需要工厂（v2 用 `device.kind` 选 transport）。
     * Android 侧由 `:app` 注入真正的实现。
     */
    private val transportFactory: ((TransportKind) -> SerialTransport?)? = null,
    /** 直接注入的传输层（测试用假串口；非 null 时优先于 [transportFactory]）。 */
    private val injectedTransport: SerialTransport? = null,
    /** 设置读取口（默认全局 `AppSettings`）。 */
    private val settingsSource: GrblSettingsSource = AppSettingsSource,
    /** 发送循环所在的作用域（默认 [Dispatchers.Default]）。 */
    private val txScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    /** 是否自动启动 4 ms 发送循环（v2 永远启动；测试设为 false 后用 [txTick] 手动驱动）。 */
    private val autoTxLoop: Boolean = true,
    /** 时间来源；测试可注入可推进的假时钟（连接超时 / 卡死检测都靠它）。 */
    internal val clock: () -> Long = { nowMillis() },
    /** 发送节拍，默认 4 ms。 */
    private val txPeriodMillis: Long = DEFAULT_TX_PERIOD_MILLIS,
    /** 握手等待时长，默认 400/350/250/150 ms。 */
    private val handshakeDelays: HandshakeDelays = HandshakeDelays.Default
) {

    // ---- 传输 ----
    private val usbTransport: SerialTransport? = injectedTransport ?: transportFactory?.invoke(TransportKind.Usb)
    private val bluetoothTransport: SerialTransport? = injectedTransport ?: transportFactory?.invoke(TransportKind.Bluetooth)
    private var transport: SerialTransport? = usbTransport

    // ---- 连接 ----
    /** 机器状态。 */
    var machineStatus: MacStatus = MacStatus.Disconnected
        private set

    /** 固件版本上下文（握手/欢迎行填充）。 */
    var version: GrblVersionInfo? = null
        private set

    /** 已读到的 `$` 设置项。 */
    val config: GrblConfiguration = GrblConfiguration()

    /** 固件类型。 */
    var firmware: Firmware

    // ---- 事件（v2 的 `CoreEvents` 表拆成一个个 Emitter）----
    val onConnected = Emitter<Unit>()
    val onDisconnected = Emitter<Unit>()
    val onStatus = Emitter<MacStatus>()
    val onPosition = Emitter<GPoint>()
    val onMessage = Emitter<GrblMessage>()
    val onCommandStatus = Emitter<GrblCommand>()

    /** 任务进度。 */
    data class Progress(val sent: Int, val total: Int, val executed: Int)

    val onProgress = Emitter<Progress>()
    val onProgramEnd = Emitter<Unit>()

    /** 覆盖倍率快照。 */
    data class Overrides(val feed: Int, val rapids: Int, val power: Int)

    /** 进给 / 功率实时值。 */
    data class FeedSpindle(val f: Double, val s: Double)

    val onOverride = Emitter<Overrides>()
    val onFss = Emitter<FeedSpindle>()
    val onIssue = Emitter<DetectedIssue>()
    val onConnectTimeout = Emitter<Unit>()

    // ---- 位置 ----
    private var mMPos = GPoint(0.0, 0.0, 0.0)
    private var mWCO = GPoint(0.0, 0.0, 0.0)
    private var curF = 0.0
    private var curS = 0.0

    // ---- 覆盖倍率 ----
    private var curOvFeed = 100
    private var curOvRapids = 100
    private var curOvPower = 100
    private var tarOvFeed = 100
    private var tarOvRapids = 100
    private var tarOvPower = 100

    // ---- 队列 / 流式 ----
    private var queue: MutableList<GrblCommand> = mutableListOf()
    private var pending: MutableList<GrblCommand> = mutableListOf()
    private var retryQueue: GrblCommand? = null
    private var usedBuffer = 0
    private var autoBufferSize = DEFAULT_BUFFER_SIZE
    private var streamingMode: StreamingMode = StreamingMode.Buffered

    /** 是否正在执行一段程序。 */
    var inProgram: Boolean = false
        private set

    // ---- 任务统计 ----
    private var jobTotal = 0
    private var jobSent = 0
    private var jobExecuted = 0
    private var jobStartTime = 0L

    // ---- 运行控制 ----
    private var txJob: Job? = null
    private var rxBuffer = ""
    private var statusQueryTimer = 0
    private var lastStatusAt = 0L
    private var connectStart = 0L

    /** 通讯速度模式。 */
    var threadingMode: ThreadingMode

    /** 最近一次检测到的问题。 */
    var lastIssue: DetectedIssue = DetectedIssue.Unknown
        private set

    // ---- 点动 ----
    var jogSpeed: Int
    var jogStep: Int

    /** 机器上报的规划器块数（`Bf:` 的第一个字段）。 */
    var grblBlocks: Int = 0

    /** 缓冲上限（`Bf:` / `[OPT:]` 推导出来的；v2 里这个字段只写不读）。 */
    var grblBuffer: Int = 0

    /** 传输层持有的底层锁，保证 tick / 握手 / 回调不会交错。 */
    private val txLock = Mutex()

    init {
        // v2 构造函数：Threading Mode / Firmware Type / Jog Speed / Jog Step 四项来自设置
        val modeName = settingsSource.getString("Threading Mode", "Fast")
        // v2 GrblCore.ts:148 是 `ThreadingMode.all().find(...) ?? ThreadingMode.Fast`：
        // 未登记的名字回退到 **Fast**（statusQuery=500），不是列表首项 Slow（2000）。
        threadingMode = ThreadingMode.all.firstOrNull { it.name == modeName } ?: ThreadingMode.Fast

        firmware = Firmware.fromValue(settingsSource.getString("Firmware Type", Firmware.Grbl.value))
            ?: Firmware.Grbl

        jogSpeed = settingsSource.getInt("Jog Speed", 1000)
        jogStep = settingsSource.getInt("Jog Step", 1)

        // v2：installDecoders(...)。Kotlin 侧要用本实例的版本上下文来做解码。
        installDecodersForVersion(version)
    }

    // ================= 连接管理 =================

    /** 已连接（既不是断开也不是连接中）。 */
    val isConnected: Boolean
        get() = machineStatus != MacStatus.Disconnected && machineStatus != MacStatus.Connecting

    /** 正在连接。 */
    val isConnecting: Boolean
        get() = machineStatus == MacStatus.Connecting

    /** 串口是否已打开。 */
    val isOpen: Boolean
        get() = transport?.isOpen() ?: false

    /** 机械坐标。 */
    val position: GPoint get() = mMPos

    /** 工作坐标（`MPos - WCO`）。 */
    val workPosition: GPoint get() = mMPos - mWCO

    val wco: GPoint get() = mWCO

    val feed: Double get() = curF

    val spindle: Double get() = curS

    /** 当前生效的覆盖倍率（机器回报的）。 */
    val overrides: Overrides get() = Overrides(curOvFeed, curOvRapids, curOvPower)

    /** 期望的覆盖倍率（界面设定，由 [manageOverrides] 逐步逼近）。 */
    val targetOverrides: Overrides get() = Overrides(tarOvFeed, tarOvRapids, tarOvPower)

    /** 任务进度统计（v2 里是私有字段，仅用于 emit；这里暴露出来便于断言）。 */
    val progress: Progress get() = Progress(jobSent, jobTotal, jobExecuted)

    /** 当前已计入的缓冲占用字节数。 */
    internal val bufferUsed: Int get() = usedBuffer

    /**
     * 当前生效的自动缓冲上限（`Bf:` / `[OPT:]` 推导）。
     */
    internal val bufferSize: Int get() = autoBufferSize

    /**
     * 测试用：直接把**期望的**覆盖倍率设成机器当前回报的值，让 [manageOverrides] 进入"已同步"状态。
     *
     * v2 里没有这个方法（目标值只能通过 [setFeedOverride] / [setPowerOverride] / [setRapidOverride] 改，
     * 而且改了就一定会朝目标值逼近），但界面本来就是这么用的：连上机器、读到 `Ov:` 之后
     * 把滑块对到机器当前值。测试需要这个"干净起点"，否则每条用例都要先算一遍
     * `manageOverrides` 会顺手发哪些复位字节。
     */
    internal fun syncTargetOverridesToMachine() {
        tarOvFeed = curOvFeed
        tarOvRapids = curOvRapids
        tarOvPower = curOvPower
    }

    internal val pendingCount: Int get() = pending.size

    internal val queueCount: Int get() = queue.size

    /**
     * 待发队列里的命令文本（v2 里 `queue` 是私有字段）。
     *
     * 命令文本与真正写出去的字节一一对应（`serialData` 只是再去掉空格并补一个 `\n`），
     * 所以点动/移动这类只关心「拼出来是什么」的行为可以在不连串口的情况下断言。
     */
    fun queuedCommandTexts(): List<String> = queue.map { it.command }

    /**
     * 最近一次收到应答的命令（v2 通过 `commandStatus` 事件暴露，这里额外留一个同步访问口）。
     * v2 里 `pending` 是私有字段，仅用于 emit；测试可以用它断言缓冲记账与响应状态。
     */
    var lastCommandStatus: GrblCommand? = null
        private set

    internal val jobStartMillis: Long get() = jobStartTime

    suspend fun listDevices(): List<SerialDeviceInfo> = requireTransport(usbTransport).list()

    suspend fun listBluetoothDevices(): List<SerialDeviceInfo> = requireTransport(bluetoothTransport).list()

    private fun requireTransport(t: SerialTransport?): SerialTransport =
        t ?: error("未注入串口传输层（commonMain 不 import Capacitor，请由 :app 提供 transportFactory）")

    /** 打开串口并开始握手。 */
    suspend fun open(device: SerialDeviceInfo, baudRate: Int) {
        if (isConnected) return
        val t = requireTransport(if (device.kind == TransportKind.Bluetooth) bluetoothTransport else usbTransport)
        transport = t
        setStatus(MacStatus.Connecting)
        connectStart = clock()
        version = null
        installDecodersForVersion(null)
        config.reset(null)
        queue = mutableListOf()
        pending = mutableListOf()
        retryQueue = null
        usedBuffer = 0
        autoBufferSize = DEFAULT_BUFFER_SIZE
        rxBuffer = ""
        lastIssue = DetectedIssue.Unknown

        t.onData { chunk -> onData(chunk) }
        t.onClose { onTransportClosed() }

        t.open(device, baudRate)

        statusQueryTimer = 0
        lastStatusAt = clock()
        startTxLoop()

        // 复位 + 握手（对应 LaserGRBL StartTX）
        if (firmware != Firmware.Smoothie && settingsSource.getBoolean("Reset Grbl On Connect", true)) {
            t.writeBytes(byteArrayOf(0x18)) // 软复位
            sleep(handshakeDelays.resetMillis)
        }
        sendHandshake()
    }

    private suspend fun sendHandshake() {
        val t = requireTransport(transport)
        t.write("\r\n")
        // 请求 build info，用于确定缓冲区与版本
        enqueueRaw("\$I", true)
        sleep(handshakeDelays.afterBuildInfoMillis)
        enqueueRaw("\$\$") // 读取设置
        sleep(handshakeDelays.afterSettingsMillis)
        enqueueRaw("\$#") // 读取坐标系参数
        sleep(handshakeDelays.afterCoordinatesMillis)
        queryPosition()
    }

    /** v2 的 `await new Promise((r) => setTimeout(r, ms))`：让出线程但不阻塞发送循环。 */
    private suspend fun sleep(ms: Long) {
        if (ms > 0) delay(ms) else yield()
    }

    /** 关闭串口。[manual] = false 表示是内部（超时/断线）关闭，不记手动断开。 */
    suspend fun close(manual: Boolean = true) {
        stopTxLoop()
        runCatching { transport?.close() }
        queue = mutableListOf()
        pending = mutableListOf()
        usedBuffer = 0
        inProgram = false
        if (manual) setIssue(DetectedIssue.ManualDisconnect)
        setStatus(MacStatus.Disconnected)
        onDisconnected.emit(Unit)
    }

    private fun onTransportClosed() {
        if (machineStatus != MacStatus.Disconnected) {
            stopTxLoop()
            if (lastIssue == DetectedIssue.Unknown) setIssue(DetectedIssue.UnexpectedDisconnect)
            setStatus(MacStatus.Disconnected)
            onDisconnected.emit(Unit)
        }
    }

    private fun startTxLoop() {
        if (txJob != null) return
        if (!autoTxLoop) return
        txJob = txScope.launch {
            while (isActive) {
                txTick()
                delay(txPeriodMillis)
            }
        }
    }

    private fun stopTxLoop() {
        txJob?.cancel()
        txJob = null
    }

    /**
     * 一个发送节拍 —— 对应 v2 的 `private txTick()`，也是 v2 `window.setInterval(..., 4)` 的回调体。
     *
     * ⚠️ **这是本次移植唯一有意放宽的可见性**：v2 里 `txTick` 是私有的、只能由定时器驱动；
     * 这里放开成 `internal`，好让测试用假传输层 + 假时钟逐拍驱动，不必依赖真实时钟
     * （否则 4 ms 节拍 / 10 s 超时会让测试又慢又不稳定）。逻辑本身逐行不变。
     */
    internal suspend fun txTick() {
        withTxLock {
            val t = transport
            if (t == null || !t.isOpen()) return@withTxLock

            // 连接超时检测
            if (machineStatus == MacStatus.Connecting && clock() - connectStart > CONNECT_TIMEOUT_MILLIS) {
                onConnectTimeout.emit(Unit)
                close(false)
                return@withTxLock
            }

            // 状态查询
            if (clock() - lastStatusAt >= threadingMode.statusQuery) {
                queryPosition()
            }

            // 发送队列
            if (canSend()) sendLine()

            // 覆盖倍率同步
            manageOverrides()

            // 运行中卡死检测
            detectHang()
        }
    }

    /**
     * tick / 握手 / 接收回调共用一把锁，模拟 v2 的单线程事件循环（那边所有这些都是同一个
     * JS 任务队列里的同步段）。这样测试里手动调用 [txTick] 与真实 4 ms 循环可以并存。
     */
    private suspend fun <T> withTxLock(block: suspend () -> T): T = txLock.withLock { block() }

    private fun detectHang() {
        if (lastIssue == DetectedIssue.Unknown && machineStatus == MacStatus.Run && inProgram) {
            val since = clock() - lastStatusAt
            if (since > maxOf(threadingMode.statusQuery * 10L, 5000L)) {
                setIssue(DetectedIssue.StopResponding)
            }
        }
    }

    // ================= 流式发送 =================

    fun setStreamingMode(mode: StreamingMode) {
        if (streamingMode == mode) return
        streamingMode = mode
    }

    /** 当前流式模式（v2 里是私有字段）。 */
    val currentStreamingMode: StreamingMode get() = streamingMode

    private fun peekNext(): GrblCommand? {
        if (pending.isNotEmpty() && pending[0].isWriteEEPROM) return null
        if (streamingMode == StreamingMode.Buffered && queue.isNotEmpty()) return queue[0]
        if (streamingMode != StreamingMode.Buffered && pending.isEmpty()) {
            retryQueue?.let { return it }
            return queue.firstOrNull()
        }
        return null
    }

    private fun hasSpaceInBuffer(cmd: GrblCommand): Boolean =
        usedBuffer + cmd.serialData.length <= autoBufferSize

    private fun canSend(): Boolean {
        val next = peekNext() ?: return false
        return hasSpaceInBuffer(next)
    }

    private fun removeManagedCommand() {
        if (retryQueue != null) retryQueue = null else if (queue.isNotEmpty()) queue.removeAt(0)
    }

    private fun sendLine() {
        val tosend = peekNext() ?: return
        var helperAlive = false
        try {
            tosend.buildHelper()
            helperAlive = true
            tosend.setSending()
            pending.add(tosend)
            removeManagedCommand()
            usedBuffer += tosend.serialData.length
            // v2 是 `void this.transport.write(...)`：不等待写入完成。
            //
            // ⚠️ **必须用 UNDISPATCHED 启动**（Phase 3 审计结论）：`txScope` 是
            // `Dispatchers.Default`（多线程），普通 `launch` 只是把协程**排队**，两个相邻的
            // 写谁先真正跑起来由调度器决定 —— 于是传输层收到 `write()` 的**顺序可能与调用顺序不同**，
            // 而"谁先调用谁先发"是 v2 的语义（JS 单线程里 write 同步入队）。
            // UNDISPATCHED 让协程体在**当前线程立即执行到第一个挂起点**；按传输层契约
            // （见 `com.lasergrbl.core.serial.SerialPortBase` / `UsbSerialTransport` 的 KDoc），
            // 入队发生在第一个挂起点之前且不挂起，因此**入队顺序 = 调用顺序**，
            // 与 v2 逐字节一致。仍然不等待落盘完成（该挂起点之后的部分照旧异步）。
            txScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport?.write(tosend.serialData) }
            }
            jobSent++
            if (inProgram) {
                onProgress.emit(Progress(jobSent, jobTotal, jobExecuted))
            }
        } finally {
            // v2 是 try/catch(console.error)/finally；Kotlin 这里让异常照常传播，
            // 但 helper 一定要删掉（同 finally 语义）
            if (helperAlive) tosend.deleteHelper()
        }
    }

    /** 处理命令响应（ok / error / ALARM 等）。 */
    private fun manageCommandResponse(rline: String) {
        lastStatusAt = clock()
        if (pending.isNotEmpty()) {
            val p = pending.removeAt(0)
            p.setResult(rline)
            usedBuffer = maxOf(0, usedBuffer - p.serialData.length)
            lastCommandStatus = p
            onCommandStatus.emit(p)

            if (inProgram) {
                if (p.repeatCount == 0) {
                    jobExecuted++
                    onProgress.emit(Progress(jobSent, jobTotal, jobExecuted))
                }
                if (p.status == CommandStatus.ResponseBad) setIssue(DetectedIssue.MachineAlarm)
            }

            if (p.isWriteEEPROM && p.status == CommandStatus.ResponseGood) {
                config.addOrUpdate(p.getDecodedMessage())
            }

            if (inProgram &&
                streamingMode == StreamingMode.RepeatOnError &&
                pending.isEmpty() &&
                p.status == CommandStatus.ResponseBad &&
                p.repeatCount < 3
            ) {
                val retry = GrblCommand(p.command, p.repeatCount + 1)
                retry.buildHelper()
                retryQueue = retry
            }
        }

        if (inProgram && queue.isEmpty() && pending.isEmpty()) {
            onProgramEnd()
        }
    }

    private fun onProgramEnd() {
        inProgram = false
        onProgramEnd.emit(Unit)
    }

    /** 入队一条原始命令。 */
    fun enqueueRaw(line: String, preserveCase: Boolean = false) {
        if (jsTrim(line).isEmpty()) return
        val cmd = GrblCommand(line, 0, preserveCase)
        queue.add(cmd)
    }

    fun enqueue(cmd: GrblCommand) {
        queue.add(cmd)
    }

    /** 开始执行一段程序（命令列表）。 */
    fun runProgram(commands: List<GrblCommand>, resetBufferAccounting: Boolean = true) {
        if (resetBufferAccounting) {
            usedBuffer = 0
            pending = mutableListOf()
            queue = mutableListOf()
        }
        queue.addAll(commands)
        jobTotal = commands.size
        jobSent = 0
        jobExecuted = 0
        jobStartTime = clock()
        inProgram = true
        onProgress.emit(Progress(0, jobTotal, 0))
    }

    /** 中止当前任务。 */
    fun abortProgram() {
        queue = mutableListOf()
        retryQueue = null
        inProgram = false
        setIssue(DetectedIssue.ManualAbort)
    }

    // ================= 接收处理 =================

    private fun onData(chunk: String) {
        // v2 里 onData 是同步回调（在 JS 事件循环的同一个同步段里跑）；Kotlin 的传输层
        // 可能从任意线程回调，所以这里要和 tick 抢同一把锁，避免 rxBuffer / 队列被并发改写。
        txScope.launch {
            withTxLock {
                rxBuffer += chunk
                while (true) {
                    val idx = rxBuffer.indexOf('\n')
                    if (idx < 0) break
                    var line = rxBuffer.substring(0, idx)
                    rxBuffer = rxBuffer.substring(idx + 1)
                    line = line.replace(TRAILING_CRLF, "").let { jsTrim(it) }
                    if (line.isEmpty()) continue
                    manageReceivedLine(line)
                }
                // 防止无换行的脏数据无限增长
                if (rxBuffer.length > RX_BUFFER_GUARD) rxBuffer = rxBuffer.substring(rxBuffer.length - RX_BUFFER_KEEP)
            }
        }
    }

    /** 测试用：直接把一段文本喂给接收装配器（等价于假串口触发 onData 回调）。 */
    internal suspend fun feedData(chunk: String) {
        withTxLock {
            rxBuffer += chunk
            while (true) {
                val idx = rxBuffer.indexOf('\n')
                if (idx < 0) break
                var line = rxBuffer.substring(0, idx)
                rxBuffer = rxBuffer.substring(idx + 1)
                line = line.replace(TRAILING_CRLF, "").let { jsTrim(it) }
                if (line.isEmpty()) continue
                manageReceivedLine(line)
            }
            if (rxBuffer.length > RX_BUFFER_GUARD) rxBuffer = rxBuffer.substring(rxBuffer.length - RX_BUFFER_KEEP)
        }
    }

    /** 测试用：当前还在装配中的残余文本。 */
    internal fun pendingRx(): String = rxBuffer

    private fun manageReceivedLine(line: String) {
        // 实时状态
        if (line.startsWith("<") && line.endsWith(">")) {
            manageRealtimeStatus(line)
            return
        }
        // 命令响应
        val upper = line.uppercase()
        if (upper.startsWith("OK")) {
            manageCommandResponse("ok")
            return
        }
        if (upper.startsWith("ERROR")) {
            manageCommandResponse(line)
            pushMessage(line, MessageType.Others)
            return
        }
        if (upper.startsWith("ALARM")) {
            // ALARM 也可能代表状态
            manageCommandResponse(line)
            pushMessage(line, MessageType.Alarm)
            return
        }
        // 欢迎/版本信息
        if (upper.startsWith("GRBL")) {
            parseVersionBanner(line)
            pushMessage(line, MessageType.Startup)
            return
        }
        if (upper.startsWith("[VER:")) {
            parseVerMessage(line)
            pushMessage(line, MessageType.Startup)
            return
        }
        if (upper.startsWith("[OPT:")) {
            parseOptMessage(line)
            pushMessage(line, MessageType.Feedback)
            return
        }
        if (line.startsWith("$") && line.contains('=')) {
            config.addOrUpdate(line)
            pushMessage(line, MessageType.Config)
            return
        }
        if (line.startsWith("[") && line.endsWith("]")) {
            pushMessage(line, MessageType.Feedback)
            return
        }
        pushMessage(line, MessageType.Others)
    }

    private fun pushMessage(line: String, type: MessageType) {
        val msg = GrblMessage(line, type)
        if (type == MessageType.Config || type == MessageType.Alarm) {
            // 触发解码
            val decoded = GrblMessage.fromLine(line, true)
            msg.message = decoded.message
            msg.tooltip = decoded.tooltip
        }
        onMessage.emit(msg)
    }

    private fun parseVersionBanner(line: String) {
        // 形如 "Grbl 1.1f ['$' for help]"
        // 注意：这里必须用别名导入的顶层解析函数 —— 同名的成员函数会遮蔽它。
        val p = lookupParseVersionBanner(line) ?: return
        val v = GrblVersionInfo(p.major, p.minor, p.build)
        val cur = version
        if (cur == null || cur.lt(v)) {
            version = v
            installDecodersForVersion(v)
            config.reset(v)
        }
    }

    private fun parseVerMessage(line: String) {
        // [VER:1.1f.20170801:]
        val p = lookupParseVerMessage(line) ?: return
        version = GrblVersionInfo(p.major, p.minor, p.build, p.vendorInfo, p.vendorVersion)
        installDecodersForVersion(version)
        config.reset(version)
    }

    private fun parseOptMessage(line: String) {
        // [OPT:VZ,15,128]
        if (line.length < 2) return
        val body = line.substring(1, line.length - 1)
        val parts = body.split(':')
        if (parts.size < 2) return
        val opts = parts[1].split(',')
        if (opts.size >= 3) {
            val buf = jsParseInt10(opts[2])
            if (!buf.isNaN() && buf > 0) setAutoBufferSize(buf.toInt(), true)
        }
    }

    private fun setAutoBufferSize(v: Int, force: Boolean) {
        if (force) {
            autoBufferSize = v
            return
        }
        if (autoBufferSize == DEFAULT_BUFFER_SIZE) {
            if (DEFAULT_BUFFER_SIZE_CANDIDATES.contains(v)) autoBufferSize = v
        }
    }

    private fun statusReportVersion(line: String): GrblVersionInfo {
        version?.let { return it }
        if (line.contains('|') && !line.contains("Pin:")) return GrblVersionInfo(1, 1)
        if (line.contains('|') && line.contains("Pin:")) return GrblVersionInfo(1, 0, "c")
        return GrblVersionInfo(0, 9)
    }

    private fun manageRealtimeStatus(line: String) {
        lastStatusAt = clock()
        val rline = line.substring(1, line.length - 1)
        val rv = statusReportVersion(rline)
        if (rv.gte(GrblVersionInfo(1, 1))) {
            val arr = rline.split('|')
            parseMachineStatus(arr[0])
            for (i in 1 until arr.size) {
                val a = arr[i]
                when {
                    a.startsWith("Ov:") -> parseOverrides(a)
                    a.startsWith("Bf:") -> parseBf(a)
                    a.startsWith("WPos:") -> parseWPos(a)
                    a.startsWith("MPos:") -> parseMPos(a)
                    a.startsWith("WCO:") -> parseWCO(a)
                    a.startsWith("FS:") -> parseFS(a)
                    a.startsWith("F:") -> setFS(jsParseFloat(a.substring(2)), 0.0)
                }
            }
        } else {
            val arr = rline.split(',')
            if (arr.isNotEmpty()) parseMachineStatus(arr[0])
            if (arr.size > 3) {
                setMPosition(
                    GPoint(jsParseFloat(arr[1].substring(5)), jsParseFloat(arr[2]), jsParseFloat(arr[3]))
                )
            }
            if (arr.size > 6) {
                setWCO(
                    mMPos - GPoint(jsParseFloat(arr[4].substring(5)), jsParseFloat(arr[5]), jsParseFloat(arr[6]))
                )
            }
        }
    }

    private fun parseMachineStatus(data: String) {
        val name = if (data.contains(':')) data.substring(0, data.indexOf(':')) else data
        MacStatus.fromValue(name)?.let { setStatus(it) }
    }

    private fun parseOverrides(p: String) {
        val arr = p.substring(3).split(',')
        // v2：`parseInt(arr[0], 10)`；越界时 arr[i] 是 undefined（NaN）→ toInt() 也得到 0，
        // 与 JS 里 `NaN | 0` 的结果一致
        val feed = jsParseInt10(arr.getOrElse(0) { "" }).toInt()
        val rapids = jsParseInt10(arr.getOrElse(1) { "" }).toInt()
        val power = jsParseInt10(arr.getOrElse(2) { "" }).toInt()
        val changed = feed != curOvFeed || rapids != curOvRapids || power != curOvPower
        curOvFeed = feed
        curOvRapids = rapids
        curOvPower = power
        if (changed) onOverride.emit(overrides)
        manageOverrides()
    }

    private fun parseBf(p: String) {
        val ab = p.substring(3).split(',')
        val blocks = jsParseInt10(ab.getOrElse(0) { "" })
        val buffer = jsParseInt10(ab.getOrElse(1) { "" })
        if (!buffer.isNaN()) setAutoBufferSize(buffer.toInt(), false)
        grblBlocks = blocks.toInt()
    }

    private fun parseWCO(p: String) {
        val xyz = p.substring(4).split(',')
        setWCO(xyz3(xyz))
    }

    private fun parseWPos(p: String) {
        val xyz = p.substring(5).split(',')
        setMPosition(mWCO + xyz3(xyz))
    }

    private fun parseMPos(p: String) {
        val xyz = p.substring(5).split(',')
        setMPosition(xyz3(xyz))
    }

    /** v2 里这三处都是 `parseFloat(xyz[0/1/2])`：越界 → undefined → `parseFloat(undefined)` = NaN。 */
    private fun xyz3(xyz: List<String>): GPoint = GPoint(
        jsParseFloat(xyz.getOrElse(0) { "" }),
        jsParseFloat(xyz.getOrElse(1) { "" }),
        jsParseFloat(xyz.getOrElse(2) { "" })
    )

    private fun parseFS(p: String) {
        val fs = p.substring(3).split(',')
        // v2：`parseFloat(fs[0]) || 0` —— NaN 与 ±0 都回退到 0
        val f = jsParseFloat(fs.getOrElse(0) { "" })
        val s = jsParseFloat(fs.getOrElse(1) { "" })
        setFS(if (f == 0.0 || f.isNaN()) 0.0 else f, if (s == 0.0 || s.isNaN()) 0.0 else s)
    }

    private fun setFS(f: Double, s: Double) {
        val changed = f != curF || s != curS
        curF = f
        curS = s
        if (changed) onFss.emit(FeedSpindle(f, s))
    }

    private fun setMPosition(pos: GPoint) {
        mMPos = pos
        onPosition.emit(pos)
    }

    private fun setWCO(wco: GPoint) {
        mWCO = wco
    }

    private fun setStatus(s: MacStatus) {
        if (machineStatus == s) return
        val wasConnecting = machineStatus == MacStatus.Connecting
        machineStatus = s
        onStatus.emit(s)
        if (wasConnecting && s != MacStatus.Disconnected && s != MacStatus.Connecting) {
            lastIssue = DetectedIssue.Unknown
            onConnected.emit(Unit)
        }
    }

    private fun setIssue(issue: DetectedIssue) {
        lastIssue = issue
        onIssue.emit(issue)
    }

    // ================= 实时命令 =================

    /** 发送单字节实时命令。 */
    fun sendImmediate(byte: Int) {
        val t = transport ?: return
        if (!t.isOpen()) return
        val b = (byte and 0xff).toByte()
        // v2 是 `void transport.writeBytes(...)`：不等写入完成。
        // UNDISPATCHED 的理由同 sendLine()：实时命令（`!` 进给保持、`~` 循环启动、覆盖倍率字节）
        // 的**先后顺序是协议语义的一部分**，不能让调度器重排。
        txScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { transport?.writeBytes(byteArrayOf(b)) }
        }
    }

    /** 查询状态 `?`。 */
    fun queryPosition() {
        sendImmediate(0x3f)
    }

    /** 软复位 0x18。 */
    fun softReset() {
        if (inProgram) setIssue(DetectedIssue.ManualReset)
        sendImmediate(0x18)
        queue = mutableListOf()
        pending = mutableListOf()
        retryQueue = null
        usedBuffer = 0
        inProgram = false
    }

    /** 进给保持 `!`。 */
    fun feedHold() {
        sendImmediate(0x21)
    }

    /** 循环启动（继续）`~`。 */
    fun cycleStart() {
        sendImmediate(0x7e)
    }

    // ---- 覆盖倍率 ----

    fun setFeedOverride(target: Int) {
        tarOvFeed = maxOf(10, minOf(200, target))
    }

    fun setRapidOverride(target: Int) {
        tarOvRapids = target
    }

    fun setPowerOverride(target: Int) {
        tarOvPower = maxOf(10, minOf(200, target))
    }

    /**
     * 覆盖倍率同步 —— 逐字对应 v2 的差分阶梯（顺序也是 feed → power → rapids）。
     *
     * 注意 v2 里 `target` 先经过 `Math.round`，Kotlin 侧参数已经是 `Int`，
     * 因此 `Math.round` 一步由调用方（界面）完成，写入值语义相同。
     */
    private fun manageOverrides() {
        val t = transport ?: return
        if (!t.isOpen()) return

        if (tarOvFeed == 100 && curOvFeed != 100) sendImmediate(0x90)
        else if (tarOvFeed - curOvFeed >= 10) sendImmediate(0x91)
        else if (curOvFeed - tarOvFeed >= 10) sendImmediate(0x92)
        else if (tarOvFeed - curOvFeed >= 1) sendImmediate(0x93)
        else if (curOvFeed - tarOvFeed >= 1) sendImmediate(0x94)

        if (tarOvPower == 100 && curOvPower != 100) sendImmediate(0x99)
        else if (tarOvPower - curOvPower >= 10) sendImmediate(0x9a)
        else if (curOvPower - tarOvPower >= 10) sendImmediate(0x9b)
        else if (tarOvPower - curOvPower >= 1) sendImmediate(0x9c)
        else if (curOvPower - tarOvPower >= 1) sendImmediate(0x9d)

        if (tarOvRapids == 100 && curOvRapids != 100) sendImmediate(0x95)
        else if (tarOvRapids == 50 && curOvRapids != 50) sendImmediate(0x96)
        else if (tarOvRapids == 25 && curOvRapids != 25) sendImmediate(0x97)
    }

    // ================= 常用操作 =================

    /** 回原点 `$H`。 */
    fun homing() {
        if (canDoHoming) enqueueRaw("\$H")
    }

    /** 解锁 `$X`。 */
    fun unlock() {
        enqueueRaw("\$X")
    }

    /** 设置新零点 `G92`。 */
    fun setNewZero() {
        enqueueRaw("G92 X0 Y0 Z0")
    }

    /** 清除坐标系偏移。 */
    fun resetWCO() {
        enqueueRaw("G92.1")
    }

    /** 写入一条 GRBL 设置 `$num=val`。 */
    fun writeSetting(num: Int, value: Double) {
        enqueueRaw("\$$num=${formatDecimal(value)}")
    }

    /** 清空报警 / 恢复。 */
    fun killAlarm() {
        enqueueRaw("\$X")
    }

    val canDoHoming: Boolean
        get() = isConnected && machineStatus != MacStatus.Alarm && machineStatus != MacStatus.Run

    val canReset: Boolean get() = isConnected

    val canFeedHold: Boolean
        get() = isConnected && (machineStatus == MacStatus.Run || machineStatus == MacStatus.Jog)

    /** 点动。 */
    fun jog(dir: JogDirection, step: Int = jogStep, speed: Int = jogSpeed) {
        if (!isConnected) return
        val s = jsToFixed(step.toDouble(), 1)
        val v = version
        if (v != null && v.gte(GrblVersionInfo(1, 1))) {
            if (dir == JogDirection.Home) {
                enqueueRaw("\$J=G90X0Y0F$speed")
                return
            }
            var cmd = "\$J=G91"
            if (dir == JogDirection.NE || dir == JogDirection.E || dir == JogDirection.SE) cmd += "X$s"
            if (dir == JogDirection.NW || dir == JogDirection.W || dir == JogDirection.SW) cmd += "X-$s"
            if (dir == JogDirection.NW || dir == JogDirection.N || dir == JogDirection.NE) cmd += "Y$s"
            if (dir == JogDirection.SW || dir == JogDirection.S || dir == JogDirection.SE) cmd += "Y-$s"
            if (dir == JogDirection.Zdown) cmd += "Z-$s"
            if (dir == JogDirection.Zup) cmd += "Z$s"
            cmd += "F$speed"
            enqueueRaw(cmd)
        } else {
            // v0.9 用相对移动模拟
            var cmd = "G91 G1"
            if (dir == JogDirection.NE || dir == JogDirection.E || dir == JogDirection.SE) cmd += "X$s"
            if (dir == JogDirection.NW || dir == JogDirection.W || dir == JogDirection.SW) cmd += "X-$s"
            if (dir == JogDirection.NW || dir == JogDirection.N || dir == JogDirection.NE) cmd += "Y$s"
            if (dir == JogDirection.SW || dir == JogDirection.S || dir == JogDirection.SE) cmd += "Y-$s"
            cmd += "F$speed"
            enqueueRaw(cmd)
            enqueueRaw("G90")
        }
    }

    /** 移动到一个工作坐标点（G90 绝对坐标）。 */
    fun moveTo(x: Double, y: Double, speed: Int = jogSpeed) {
        enqueueRaw("G90 G1 X${jsToFixed(x, 3)} Y${jsToFixed(y, 3)} F$speed")
    }

    /** 设置当前位置为工作原点。 */
    fun setZeroHere(x: Double = 0.0, y: Double = 0.0) {
        enqueueRaw("G92 X${formatDecimal(x)} Y${formatDecimal(y)}")
    }

    /**
     * 查表 —— 与 v2 私有 `lookupCode` 一致：先用**本实例的版本上下文**选分组名
     * （分组名逻辑复用 [lookupGroupName]，不在这里重写），再两级回退 `v1.1` / `standard`。
     */
    private fun lookupCode(table: Map<String, Map<String, List<String>>>, key: String, idx: Int): String? {
        val groupName = lookupGroupName(config.getVersion())
        val g = table[groupName] ?: table["v1.1"] ?: table["standard"] ?: return null
        val entry = g[key] ?: return null
        return entry.getOrNull(idx)
    }

    /** 查找错误码描述。 */
    fun errorDescription(code: String): String? = lookupCode(CsvData.ERROR_CODES, code, 1)

    /** 查找设置项说明：`[名称, 单位, 说明]`。 */
    fun settingInfo(id: Int): List<String> {
        val key = formatDecimal(id.toDouble())
        return listOf(
            lookupCode(CsvData.SETTING_CODES, key, 0) ?: "",
            lookupCode(CsvData.SETTING_CODES, key, 1) ?: "",
            lookupCode(CsvData.SETTING_CODES, key, 2) ?: ""
        )
    }

    /** 查找报警码描述。 */
    fun alarmInfo(code: String): List<String> = listOf(
        lookupCode(CsvData.ALARM_CODES, code, 0) ?: "",
        lookupCode(CsvData.ALARM_CODES, code, 1) ?: ""
    )

    private companion object {
        /** v2：`line.replace(/[\r\n]+$/g, '')` —— 只吃行尾的 CR/LF。 */
        val TRAILING_CRLF = Regex("[\\r\\n]+$")
    }
}
