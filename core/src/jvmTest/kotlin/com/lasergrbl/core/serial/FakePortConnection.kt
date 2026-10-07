package com.lasergrbl.core.serial

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.test.assertTrue

/**
 * 假 [PortConnection] —— `SerialPortBase` 状态机单测的依赖注入点。
 *
 * 设计要点（都是为了让断言**确定性**，不靠 sleep 猜时序）：
 *  * **读**：脚下有一个「读出脚本」队列（[scriptData] / [scriptBytesOneByOne] / [scriptZero] /
 *    [scriptEof] / [scriptFailure]）；脚本用尽后按 [readDefault] 行事。
 *    [FakeReadDefault.BLOCK_UNTIL_DATA_OR_CLOSE] 用一把锁加条件变量等待（不轮询），
 *    所以「读线程此刻确实阻塞在 read() 里」是可判定的事实（[awaitFirstReadEntered]），
 *    这是「读线程被 close 唤醒后不得重复通知」这类断言能稳定复现的前提。
 *  * **写**：记录每次 `write` 的**字节副本**，并统计「同一时刻是否有多于一个调用者正在 write」
 *    —— 这是验收清单第 1 条「写串行化」的直接证据（[concurrentWriteDetected]）。
 *  * **可注入**：写失败（[writeFailure]）、读失败（[readFailure]）、写变慢（[writeDelayMillis]）、
 *    写门闸（[holdWrites] / [releaseWrites]）。
 *    门闸用来强制「先把队列堆满、再放行」：否则写线程跑得比入队还快，FIFO 被改成 LIFO
 *    之类的缺陷会被掩盖（堆不满就看不出来）。
 */
class FakePortConnection(
    /** 便于失败信息里区分多个假连接。 */
    val label: String = "fake"
) : PortConnection {

    // ================= 关闭 =================

    val closeCount = AtomicInteger()

    @Volatile
    var closed: Boolean = false
        private set

    /** 非 null 时 [close] 抛它。 */
    @Volatile
    var closeFailure: Throwable? = null

    // ================= 写 =================

    /** 每一次 write 的字节副本，按**落盘顺序**追加（并发安全）。 */
    val writes: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()

    @Volatile
    var writeFailure: Throwable? = null

    /** 模拟「写很慢」（毫秒）；用于验证 close 会等写队列排空。 */
    @Volatile
    var writeDelayMillis: Long = 0L

    /** 曾经观测到两个调用者同时处于 write() 内部 —— 即「并发写」，正常情况下必须永远是 false。 */
    @Volatile
    var concurrentWriteDetected: Boolean = false
        private set

    /** 历史上同时在 write() 内部的最大调用者数（正常恒为 1）。 */
    @Volatile
    var maxConcurrentWriters: Int = 0
        private set

    private val activeWriters = AtomicInteger()

    private val gateLock = ReentrantLock()
    private val gateCondition = gateLock.newCondition()
    private var gateHeld = false

    // ================= 读 =================

    private val steps = LinkedBlockingQueue<FakeReadStep>()

    /** 脚本用尽后的默认读行为。 */
    @Volatile
    var readDefault: FakeReadDefault = FakeReadDefault.BLOCK_UNTIL_DATA_OR_CLOSE

    /** 非 null 时每次 [read] 都抛它（模拟读失败 / 拔出时驱动报错）。 */
    @Volatile
    var readFailure: Throwable? = null

    val readCallCount = AtomicInteger()

    private val firstReadEntered = CountDownLatch(1)

    /** 监视器：同时用于「读等待」与「写门闸」，避免两把锁互相唤醒的复杂性。 */
    private val monitor = ReentrantLock()
    private val monitorSignal = monitor.newCondition()

    // ================= PortConnection =================

    override fun read(buffer: ByteArray): Int {
        readCallCount.incrementAndGet()
        firstReadEntered.countDown()
        while (true) {
            val step = steps.poll()
            if (step != null) return applyStep(step, buffer)
            if (closed) return -1
            val failure = readFailure
            if (failure != null) throw failure
            when (readDefault) {
                FakeReadDefault.RETURN_ZERO -> return 0
                FakeReadDefault.RETURN_EOF -> return -1
                FakeReadDefault.THROW_IO -> throw IOException("假连接：注入的读异常")
                FakeReadDefault.BLOCK_UNTIL_DATA_OR_CLOSE -> {
                    monitor.lock()
                    try {
                        if (!closed && steps.isEmpty()) {
                            monitorSignal.await(READ_WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS)
                        }
                    } finally {
                        monitor.unlock()
                    }
                }
            }
        }
    }

    override fun write(bytes: ByteArray) {
        val failure = writeFailure
        if (failure != null) throw failure
        val active = activeWriters.incrementAndGet()
        if (active > 1) concurrentWriteDetected = true
        if (active > maxConcurrentWriters) maxConcurrentWriters = active
        try {
            passWriteGate()
            val delay = writeDelayMillis
            if (delay > 0) Thread.sleep(delay)
            writes.add(bytes.copyOf())
        } finally {
            activeWriters.decrementAndGet()
        }
    }

    override fun close() {
        closeCount.incrementAndGet()
        val failure = closeFailure
        if (failure != null) throw failure
        closed = true
        signalAll()
    }

    // ================= 测试脚本：读 =================

    /** 排入一段「read 返回这些字节」的动作。 */
    fun scriptData(bytes: ByteArray) {
        feedStep(FakeReadStep.Data(bytes))
    }

    /** 排入一段文本（按 UTF-8 展开为字节，仍作为**一块**返回）。 */
    fun scriptText(text: String) {
        scriptData(text.encodeToByteArray())
    }

    /**
     * 把 [bytes] **逐字节**排进脚本：每次 read 只返回 1 个字节。
     *
     * 这是「UTF-8 跨块拼接」最狠的形态：任何「每块独立解码」的实现都会立刻露馅。
     */
    fun scriptBytesOneByOne(bytes: ByteArray) {
        for (b in bytes) feedStep(FakeReadStep.Data(byteArrayOf(b)))
    }

    /** 排入一次「read 返回 0」。 */
    fun scriptZero() {
        feedStep(FakeReadStep.Zero)
    }

    /** 排入一次「read 返回 -1（EOF / 拔出）」。 */
    fun scriptEof() {
        feedStep(FakeReadStep.Eof)
    }

    /** 排入一次「read 抛异常」。 */
    fun scriptFailure(error: Throwable = IOException("假连接：注入的读异常")) {
        feedStep(FakeReadStep.Fail(error))
    }

    fun feedStep(step: FakeReadStep) {
        steps.add(step)
        signalAll()
    }

    /** 等待读线程**第一次**进入 [read]（之后它是否还阻塞在 read 里由 [readDefault] 决定）。 */
    fun awaitFirstReadEntered(timeoutMillis: Long = 2_000L): Boolean =
        firstReadEntered.await(timeoutMillis, TimeUnit.MILLISECONDS)

    // ================= 测试脚本：写 =================

    /** 关上门闸：此后所有 write 都会阻塞，直到 [releaseWrites]（用于强制写队列堆积）。 */
    fun holdWrites() {
        gateLock.lock()
        try {
            gateHeld = true
        } finally {
            gateLock.unlock()
        }
    }

    /** 打开门闸（可重复调用）。 */
    fun releaseWrites() {
        gateLock.lock()
        try {
            gateHeld = false
            gateCondition.signalAll()
        } finally {
            gateLock.unlock()
        }
    }

    private fun passWriteGate() {
        gateLock.lock()
        try {
            while (gateHeld) gateCondition.await(READ_WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS)
        } finally {
            gateLock.unlock()
        }
    }

    /** 等待写出的条数达到 [expected]（轮询 + 超时；超时即断言失败）。 */
    fun awaitWriteCount(expected: Int, timeoutMillis: Long = 2_000L) {
        awaitUntil(timeoutMillis, "写出条数达到 $expected") { writes.size >= expected }
    }

    /** 已写出的文本（按 UTF-8 解码，ASCII 场景下逐字节等价）。 */
    fun writtenTexts(): List<String> = writes.map { it.decodeToString() }

    private fun applyStep(step: FakeReadStep, buffer: ByteArray): Int = when (step) {
        is FakeReadStep.Data -> {
            val n = minOf(step.bytes.size, buffer.size)
            step.bytes.copyInto(buffer, 0, 0, n)
            n
        }
        is FakeReadStep.Zero -> 0
        is FakeReadStep.Eof -> -1
        is FakeReadStep.Fail -> throw step.error
    }

    private fun signalAll() {
        monitor.lock()
        try {
            monitorSignal.signalAll()
        } finally {
            monitor.unlock()
        }
    }

    override fun toString(): String = "FakePortConnection($label)"

    private companion object {
        /** 条件等待的切片（毫秒）：只为兜住「丢唤醒」的极端情况，不是轮询节拍。 */
        const val READ_WAIT_SLICE_MILLIS = 50L
    }
}

/** 读出脚本用尽后 [FakePortConnection.read] 的默认行为。 */
enum class FakeReadDefault {
    /** 阻塞等待下一段脚本或 [FakePortConnection.close]（模拟真阻塞读）。 */
    BLOCK_UNTIL_DATA_OR_CLOSE,

    /** 立刻返回 0（「暂无数据」）。 */
    RETURN_ZERO,

    /** 立刻返回 -1（EOF / 已拔出）。 */
    RETURN_EOF,

    /** 立刻抛 [IOException]（读失败）。 */
    THROW_IO
}

/** 一次 [FakePortConnection.read] 的脚本动作。 */
sealed interface FakeReadStep {
    /** 返回这段字节（超出缓冲区则截断）。 */
    class Data(val bytes: ByteArray) : FakeReadStep

    /** 返回 0。 */
    data object Zero : FakeReadStep

    /** 返回 -1。 */
    data object Eof : FakeReadStep

    /** 抛出异常。 */
    class Fail(val error: Throwable = IOException("假连接：注入的读异常")) : FakeReadStep
}

/**
 * 假 [PortConnectionFactory]：把「设备查找 / 权限申请 / 打开端口」这一段替换成可断言的记录。
 *
 *  * [createCount] / [lastDevice] / [lastBaudRate]：断言 `SerialPortBase.open()` 真的把
 *    **原始** [SerialDeviceInfo]（而不是转换过的对象）交给了工厂；
 *  * [onCreateHook]：在 `create()` 内部执行，用来观察「打开过程中」的状态必须是 Connecting；
 *  * [createFailure]：按调用序号注入失败。失败时**按 [PortConnectionFactory.create] 的契约**
 *    先把造出来的半成品句柄关掉、再抛异常 —— 这正是该契约的可执行示例（基类拿不到半成品句柄，
 *    因此无法替实现方兜底，只能由实现方自己保证；真机上违反它的后果是"第二次连接永远失败"）。
 */
class FakePortFactory(
    private val connections: List<FakePortConnection>
) : PortConnectionFactory {

    constructor(vararg connections: FakePortConnection) : this(connections.toList())

    val createCount = AtomicInteger()

    @Volatile
    var lastDevice: SerialDeviceInfo? = null
        private set

    @Volatile
    var lastBaudRate: Int? = null
        private set

    /** 在 `create()` 内部执行（观察 open 过程中的状态）。 */
    @Volatile
    var onCreateHook: (() -> Unit)? = null

    /** 按 0 起的调用序号返回要抛的异常（返回 null 表示这次正常打开）。 */
    @Volatile
    var createFailure: ((callIndex: Int) -> Throwable?)? = null

    /** 失败路径上「造出来又关掉」的半成品句柄（用于把 create 的清理契约变成可执行断言）。 */
    val halfBuilt: MutableList<FakePortConnection> = CopyOnWriteArrayList()

    private val handedOut = AtomicInteger()

    override fun create(device: SerialDeviceInfo, baudRate: Int): PortConnection {
        val callIndex = createCount.getAndIncrement()
        lastDevice = device
        lastBaudRate = baudRate
        onCreateHook?.invoke()

        val failure = createFailure?.invoke(callIndex)
        if (failure != null) {
            val dirty = FakePortConnection("half-built-$callIndex").also { halfBuilt.add(it) }
            // 契约：失败必须自己把已获取的原生资源关干净再抛
            dirty.close()
            throw failure
        }
        val index = handedOut.getAndIncrement()
        return connections.getOrElse(index) { connections.last() }
    }
}

/**
 * 轮询等待 [condition] 成立，超时即断言失败。
 *
 * 为什么不用 sleep 猜时序：串口状态机是**多线程**的（写线程 / 读线程），固定 sleep
 * 在慢机器上必然假红、在快机器上又测不到东西。这里统一「等到条件成立或超时」，
 * 超时信息带上下文，失败时能直接定位。
 */
fun awaitUntil(timeoutMillis: Long = 2_000L, what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(1L)
    }
    assertTrue(condition(), "等待超时（$timeoutMillis ms）：$what")
}
