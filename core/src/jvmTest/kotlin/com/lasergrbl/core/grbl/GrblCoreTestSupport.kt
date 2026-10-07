package com.lasergrbl.core.grbl

import com.lasergrbl.core.serial.SerialDeviceInfo
import com.lasergrbl.core.serial.SerialTransport
import com.lasergrbl.core.serial.TransportKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * `GrblCore` 单元测试的脚手架：**假串口** + 假设置 + 事件记录器。
 *
 * 为什么需要它：v2 的 GrblCore 没有黄金样本（它构造时就会去 `registerPlugin`，
 * Node 里跑不起来），所以这条链路只能靠「假传输层 + 假时钟 + 手动 tick」来验证。
 *
 * 本文件里的东西都是测试专用，不进入 commonMain。
 */

/**
 * 假串口：记录所有写出的内容，并允许测试主动"喂"数据（触发 `onData`）或"拔线"（触发 `onClose`）。
 *
 * 对应 v2 的 `SerialTransport` 接口，但 `write`/`writeBytes` 是同步记录 —— 真实实现是异步的，
 * 语义上等价于「字节已经发出去了」，测试只需要顺序，不需要时序。
 */
class FakeTransport(
    override val kind: TransportKind = TransportKind.Usb
) : SerialTransport {

    private var opened = false
    private var dataCallback: ((String) -> Unit)? = null
    private var closeCallback: (() -> Unit)? = null

    /** 所有写出的文本，按调用顺序（`write` 与 `writeBytes` 共用同一个序列，顺序才是断言对象）。 */
    val writes: MutableList<String> = mutableListOf()

    /** `writeBytes` 的原始字节（实时命令走这条路径）。 */
    val bytes: MutableList<Int> = mutableListOf()

    /** `open()` 被调用的次数与参数（v2 里 `open` 会重置回调和状态）。 */
    var openCount: Int = 0
        private set
    var lastDevice: SerialDeviceInfo? = null
        private set
    var lastBaudRate: Int? = null
        private set

    var closeCount: Int = 0
        private set

    override suspend fun list(): List<SerialDeviceInfo> = emptyList()

    override suspend fun open(device: SerialDeviceInfo, baudRate: Int) {
        openCount++
        lastDevice = device
        lastBaudRate = baudRate
        opened = true
    }

    override suspend fun close() {
        closeCount++
        opened = false
    }

    override suspend fun write(text: String) {
        writes.add(text)
    }

    override suspend fun writeBytes(bytes: ByteArray) {
        for (b in bytes) {
            this.bytes.add(b.toInt() and 0xff)
            writes.add(String(byteArrayOf(b), Charsets.ISO_8859_1))
        }
    }

    override fun onData(cb: (chunk: String) -> Unit) {
        dataCallback = cb
    }

    override fun onClose(cb: () -> Unit) {
        closeCallback = cb
    }

    override fun isOpen(): Boolean = opened

    /** 模拟收到一段串口数据（分块边界由调用方决定 —— 这正是要测的东西）。 */
    fun feed(chunk: String) {
        dataCallback?.invoke(chunk)
    }

    /** 模拟设备掉线。 */
    fun simulateClose() {
        opened = false
        closeCallback?.invoke()
    }

    /** 清空记录（用于「只断言这一段」的场景）。 */
    fun clear() {
        writes.clear()
        bytes.clear()
    }

    /** 已写出的字节，`write` 的文本按 UTF-8 展开（ASCII 场景下等价于逐字符）。 */
    fun writtenBytes(): List<Int> = writes.flatMap { s -> s.map { it.code } }

    /** 已写出的完整行（去掉 `[OPT:]` 之外的实时命令）。 */
    fun writtenLines(): List<String> =
        writes.filter { it.contains('\n') }.map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * 假设置：v2 的构造函数读 4 项设置（`Threading Mode` / `Firmware Type` / `Jog Speed` / `Jog Step`），
 * `open()` 还会读 `Reset Grbl On Connect`。
 *
 * 不能直接改全局 `AppSettings`（它是 lazy 单例，进程内只会初始化一次），所以走注入。
 */
class FakeSettings(initial: Map<String, Any> = emptyMap()) : GrblSettingsSource {

    private val map: MutableMap<String, Any> = LinkedHashMap(initial)

    fun set(key: String, value: Any) {
        map[key] = value
    }

    override fun getString(key: String, default: String): String = map[key] as? String ?: default

    override fun getInt(key: String, default: Int): Int = (map[key] as? Number)?.toInt() ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
}

/** GRBL 实时命令的字节常量（GRBL v1.1 官方文档）。 */
object Rt {
    const val STATUS_QUERY = 0x3f // '?'
    const val SOFT_RESET = 0x18
    const val FEED_HOLD = 0x21 // '!'
    const val CYCLE_START = 0x7e // '~'

    // ---- 进给倍率 ----
    const val FEED_RESET = 0x90
    const val FEED_UP_10 = 0x91
    const val FEED_DOWN_10 = 0x92
    const val FEED_UP_1 = 0x93
    const val FEED_DOWN_1 = 0x94

    // ---- 快速移动（G0）倍率 ----
    const val RAPID_RESET = 0x95
    const val RAPID_50 = 0x96
    const val RAPID_25 = 0x97

    // ---- 激光功率倍率 ----
    const val POWER_RESET = 0x99
    const val POWER_UP_10 = 0x9a
    const val POWER_DOWN_10 = 0x9b
    const val POWER_UP_1 = 0x9c
    const val POWER_DOWN_1 = 0x9d
}

/** 测试用的设备信息（USB）。 */
fun fakeDevice(kind: TransportKind = TransportKind.Usb): SerialDeviceInfo = SerialDeviceInfo(
    id = if (kind == TransportKind.Usb) "1" else "AA:BB:CC:DD:EE:FF",
    kind = kind,
    name = "Fake Laser",
    deviceId = if (kind == TransportKind.Usb) 1.0 else null
)

/**
 * 喂一段数据并**立刻**把它推进到 `GrblCore.rxBuffer`。
 *
 * `GrblCore.onData` 是同步回调（与 v2 的 JS 事件循环一致），但 Kotlin 侧要先把工作
 * 投递到 [com.lasergrbl.core.grbl.GrblCore] 的协程作用域上，所以测试里必须
 * `runCurrent()` 一次才算「数据已经收到」。
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
fun FakeTransport.feedSync(scope: TestScope, chunk: String) {
    feed(chunk)
    flushWrites(scope)
}

/**
 * 把「fire-and-forget」的写任务推完。
 *
 * v2 里 `transport.write()` / `writeBytes()` 都是 `void`（不 await），Kotlin 侧对应
 * `txScope.launch { ... }`。这些任务排在同一个 TestDispatcher 队列上，而且**处理过程中
 * 还会继续 `launch` 出新的任务**（rx 回调里解析状态 → `manageOverrides()` → `sendImmediate`），
 * 所以只跑一轮 `runCurrent()` 不保险。这里连跑几轮；`runCurrent()` 只执行「当前虚拟时刻
 * 可执行」的任务、**不推进时钟**，所以多跑几轮是幂等且安全的。
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
fun flushWrites(scope: TestScope) {
    repeat(6) { scope.runCurrent() }
}

/**
 * 「先把在途的写推完，再清空记录」—— 测试里凡是要开一段新断言，都应该用它而不是裸 `clear()`。
 *
 * 裸 `clear()` 会漏掉已经 `launch` 但还没执行的写任务：它们会在下一次
 * `runCurrent()` 里落进 `writes`，把下一段断言的字节数搞乱
 * （典型来源是 `manageOverrides()` 在解析 `Ov:` 时顺手发的那个复位字节）。
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
fun FakeTransport.resetWrites(scope: TestScope) {
    flushWrites(scope)
    clear()
}

/**
 * 读一条**纯拼装**命令的文本（不发送）。
 *
 * 用于点动 / moveTo / 设置写回这类只关心命令文本的断言：不连串口时 `txTick()` 不会做任何事，
 * 队列原样留在原地，而 `GrblCommand.command` 的文本与真正写出去的一模一样
 * （`serialData` 只是再去掉空格并补一个 `\n`）。
 */
fun GrblCore.queuedTexts(): List<String> = queuedCommandTexts()

/** 取出队首命令文本（队列为空返回 null）。 */
fun GrblCore.peekQueuedText(): String? = queuedTexts().firstOrNull()
