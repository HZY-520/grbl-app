package com.lasergrbl.core.serial

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.thread

/**
 * 增量（流式）UTF-8 解码器 —— 纯 Kotlin，无 `java.nio` / Android 依赖，可在 JVM 单测里直接验。
 *
 * 为什么不能用「每块 `decodeToString()`」：串口按块返回字节，一个多字节字符完全可能被切在两次
 * `read` 之间。v2 的 Web Serial 实现用 `TextDecoder.decode(value, { stream: true })`（跨块保持
 * 状态），而 v2 的 Android 插件是每块 `new String(bytes, UTF_8)` —— 后者会在块边界吐 `\uFFFD`，
 * 把一个汉字拆成两个替换符。这里复刻 **`{ stream: true }` 的语义**：不完整的尾字节留在内部状态，
 * 等下一块补齐。
 *
 * 三种形态与 WHATWG 解码器一致：
 *  1. 合法序列（1~4 字节）→ 正常字符；
 *  2. 非法序列（续字节不足、代理区 `ED A0..BF`、超出 `U+10FFFF`、过长编码、孤立续字节）→
 *     **每个**非法字节输出一个 `U+FFFD`，并在首个非法字节处重新同步；
 *  3. 截断在块尾的合法前缀 → 挂起、不输出，等下一块（这就是跨块拼接）。
 */
internal class Utf8StreamDecoder {

    /** 已收下但不足一个完整字符的字节（最多 3 个）。 */
    private val pending = ByteArray(MAX_PENDING)

    private var pendingCount = 0

    private val output = StringBuilder()

    /** 解码 `bytes[0, length)`，返回本次能确定的文本（可能为空）。未完成的尾序列留在内部。 */
    fun decode(bytes: ByteArray, length: Int = bytes.size): String {
        output.setLength(0)
        var i = 0
        while (i < length) {
            val b = bytes[i].toInt() and 0xFF
            if (pendingCount == 0 && b < 0x80) {
                output.append(b.toChar())
                i++
                continue
            }
            // 取首字节（若上一轮有残留则先用残留的）
            val first = if (pendingCount > 0) pending[0].toInt() and 0xFF else b
            if (pendingCount == 0) {
                pending[pendingCount++] = b.toByte()
                i++
            }
            val need = expectedLength(first)
            if (need < 0) {
                // 首字节本身非法（孤立的 0x80..0xBF，或 0xF8..0xFF）：一个替换字符后继续
                emitReplacementAndResync(1)
                continue
            }
            // 校验续字节：不合法就"只"吃首字节，**绝不吞掉后续合法字节**
            // （WHATWG 解码器就是在这个位置重新同步的）
            var valid = true
            while (pendingCount < need) {
                if (i >= length) break
                val next = bytes[i].toInt() and 0xFF
                if (next !in 0x80..0xBF) {
                    valid = false
                    break
                }
                pending[pendingCount++] = bytes[i].toByte()
                i++
            }
            if (!valid) {
                emitReplacementAndResync(1)
                continue
            }
            if (pendingCount < need) {
                // 还差字节：等下一块补齐（跨块拼接）
                break
            }
            val decoded = decodeSequence(need)
            if (decoded < 0) {
                // 过长编码 / 代理区 / 超出 U+10FFFF：同样是"每个非法字节一个替换符"
                emitReplacementAndResync(1)
                continue
            }
            output.appendCodePoint(decoded)
            pendingCount = 0
        }
        return output.toString()
    }

    /** 冲刷：把挂起的截断序列按「每个字节一个替换符」输出（流结束/关闭时调用）。 */
    fun flush(): String {
        if (pendingCount == 0) return ""
        output.setLength(0)
        repeat(pendingCount) { output.append(REPLACEMENT) }
        pendingCount = 0
        return output.toString()
    }

    private fun emitReplacementAndResync(consumed: Int) {
        output.append(REPLACEMENT)
        val rest = pendingCount - consumed
        for (j in 0 until rest) pending[j] = pending[j + consumed]
        pendingCount = rest
    }

    private fun expectedLength(first: Int): Int = when {
        first < 0x80 -> 1
        first in 0xC2..0xDF -> 2
        first in 0xE0..0xEF -> 3
        first in 0xF0..0xF4 -> 4
        else -> -1
    }

    /** 校验续字节并解出码点；非法返回 -1（范围 / 代理区 / 过长编码检查）。 */
    private fun decodeSequence(need: Int): Int {
        var cp = pending[0].toInt() and MASK_FIRST[need]
        for (k in 1 until need) {
            val cont = pending[k].toInt() and 0xFF
            if (cont !in 0x80..0xBF) return -1
            cp = (cp shl 6) or (cont and 0x3F)
        }
        if (cp > 0x10FFFF) return -1
        if (cp in 0xD800..0xDFFF) return -1
        if (cp < MIN_CODE_POINT[need]) return -1
        return cp
    }

    private companion object {
        const val MAX_PENDING = 4
        const val REPLACEMENT = '\uFFFD'

        /** 各长度下首字节携带的有效位掩码。 */
        val MASK_FIRST = intArrayOf(0, 0x7F, 0x1F, 0x0F, 0x07)

        /** 各长度下允许的最小码点（防过长编码）。 */
        val MIN_CODE_POINT = intArrayOf(0, 0x0, 0x80, 0x800, 0x10000)
    }
}

/**
 * 串口传输层的**共有状态机**（USB 与蓝牙 SPP 共用）—— 等价于 v2
 * `android/.../UsbSerialPlugin.java` 与 `BluetoothSerialPlugin.java` 里逐字重复的那套语义。
 *
 * 存在的理由有两个：
 *  1. v2 把「写锁 + 读取线程 + `closed` 只通知一次 + 关闭幂等」抄了两遍，这里只写一遍；
 *  2. **可测**（`docs/UI-3.0-PLAN.md` §6.1 第 2 条）：本类与 [PortConnection] 都不依赖 Android
 *     类型，于是能用假连接在 JVM 单测里逐条断言状态机（`UsbDevice` 是 final，单测里既不能构造
 *     也不能可靠替换，所以真机 API 触碰的部分必须薄到"看一眼就知道对不对"）。
 *
 * ### 验收清单 §6.1 第 1 条：写串行化（顺序论证）
 * `GrblCore` 每次写都是独立的 `txScope.launch`（`Dispatchers.Default`，**多线程**），而 v2 的
 * `write()` 在**调用线程同步落盘** —— JS 里"谁先调用谁先发"。Kotlin 里若直接写端口，两个协程会在
 * `UsbDeviceConnection` 上乱序甚至并发。所以顺序必须由传输层保证，做法：
 *
 *  1. [write] / [writeBytes] 在同一把锁内**同步入队**（非阻塞，不丢字节）；
 *  2. 唯一的写线程按 FIFO 出队，串行调用 [PortConnection.write]。
 *
 * 顺序论证：入队动作在 `lock` 内完成 ⇒ 入队顺序 = 调用顺序（同一线程上的程序顺序、
 * 或跨线程时的锁获取顺序）；队列是 FIFO ⇒ 出队顺序 = 入队顺序；消费者只有一个线程 ⇒
 * 落盘顺序 = 出队顺序。三者串联得 **调用顺序 = 落盘顺序**。
 * 对 `$I` → `$$` → `$#` 这类同一线程上的握手序列（v2 的 `GrblCore` 就是顺序 `await` 的），
 * 程序顺序即落盘顺序，**与 v2 逐字节一致**。跨线程并发调用时"意图顺序"本身无法定义，
 * 但仍保证：不并发写、不丢字节、不把单次写的字节拆开或交错。
 *
 * ### 调用方必须知道的一条契约（Phase 3 审计补充）
 * 入队动作发生在**第一个挂起点之前**，并且 [write] / [writeBytes] 本身**不会挂起**。
 * 这一点是 `GrblCore` 用 `CoroutineStart.UNDISPATCHED` 启动写协程的前提：
 * 它保证"调用顺序 = 入队顺序"在**调用线程上同步成立**，从而不依赖调度器的唤醒顺序。
 * 任何新的 [SerialTransport] 实现都必须保持这个性质（入队不得先挂起等待锁/信号量）。
 *
 * ### 与 v2 的其它逐条对齐
 *  * `close()` 幂等；手动 [close] **不**触发 `closed` 回调（v2 只有拔出/读异常才通知）；
 *  * 拔出 / 读 EOF / 读异常 → 关连接 + **只通知一次** `closed`；重新 [open] 会重置该标记；
 *  * 打开新设备前先释放旧连接（v2 `open()` 第一行就是 `closePortInternal()`）；
 *  * 打开失败不留半开状态（连接、线程、状态全部回滚到未连接）；
 *  * 写队列在关闭前**有界排空**（v2 的写是同步的，不存在"关太快丢掉尾巴"的问题，
 *    这里是新增的补偿：最多等 [flushTimeoutMillis]，超时才丢弃并记录到 [lastError]）。
 */
abstract class SerialPortBase(
    /** 传输类型（回给 [SerialTransport.kind]）。 */
    final override val kind: TransportKind,
    /** 连接工厂：每次 [open] 新建一个 [PortConnection]。 */
    private val factory: PortConnectionFactory,
    /** [PortConnection.read] 返回 0（暂无数据）时的退让间隔，避免忙等。 */
    private val readIdleDelayMillis: Long = DEFAULT_READ_IDLE_DELAY_MILLIS,
    /** [close] 时等待写队列清空的最长时间；超时则丢弃剩余字节并记录到 [lastError]。 */
    private val flushTimeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS
) : SerialTransport {

    /** 连接状态（[SerialTransport.isOpen] 的语义来源）。 */
    sealed interface State {
        /** 未连接。 */
        data object Disconnected : State

        /** 正在建立连接。 */
        data object Connecting : State

        /** 已连接（可读写）。 */
        data class Open(val baudRate: Int) : State

        /** 正在关闭。 */
        data object Closing : State
    }

    /** 状态锁：保护 [currentState] / [connection] / [openedDevice] / [pendingWrites] / [closedNotified]。 */
    private val lock = ReentrantLock()

    @Volatile
    private var currentState: State = State.Disconnected

    /** 当前状态（只读；UI 与测试都可观察）。 */
    val state: State get() = currentState

    /**
     * 是否"绑定着一条连接"—— 与 [currentState] 分开维护，因为 [close] 期间状态会先变成
     * [State.Closing]，但此刻**已经入队的写仍然应该落盘**（[enqueue] 的准入条件用它判断）。
     */
    @Volatile
    private var bound: Boolean = false

    @Volatile
    private var connection: PortConnection? = null

    @Volatile
    private var openedDevice: SerialDeviceInfo? = null

    /** 当前打开的原始设备信息（未连接时为 null）。 */
    val device: SerialDeviceInfo? get() = openedDevice

    // ---- 回调 ----
    @Volatile
    private var dataCallback: ((String) -> Unit)? = null

    @Volatile
    private var closeCallback: (() -> Unit)? = null

    // ---- 线程 ----
    @Volatile
    private var writerThread: Thread? = null

    @Volatile
    private var readerThread: Thread? = null

    private val writeQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

    /** 待落盘字节数（[close] 的有界排空靠它判断何时排空完成）。 */
    private var pendingWrites = 0

    private var closedNotified = false

    /** 是否正处于 [teardown] 中（幂等闸门；见 [teardown] 的注释）。 */
    private var closing = false

    /** 写线程的停止请求（[stopWriter] 里置位；兜住"排空超时时 POISON 已被 poll 掉"的路径）。 */
    @Volatile
    private var stopRequested = false

    /** 最近一次失败/异常原因（成功不覆盖，便于真机排查；[resetError] 可清空）。 */
    @Volatile
    var lastError: String? = null
        private set

    // ================= 子类扩展点 =================

    /**
     * 打开底层连接（阻塞）。USB 子类在这里做设备查找与权限申请；蓝牙子类在这里连 socket。
     * 默认实现就是 `factory.create(device, baudRate)`。
     */
    protected open fun openConnection(device: SerialDeviceInfo, baudRate: Int): PortConnection =
        factory.create(device, baudRate)

    /** [open] 成功之后调用（子类可注册拔出广播等）。 */
    protected open fun onOpened(device: SerialDeviceInfo) {}

    /** 连接已被关掉（含读线程自行断开）之后调用（子类可注销广播）。必须幂等。 */
    protected open fun onClosed() {}

    // ================= SerialTransport =================

    final override fun isOpen(): Boolean = currentState is State.Open

    final override fun onData(cb: (chunk: String) -> Unit) {
        dataCallback = cb
    }

    final override fun onClose(cb: () -> Unit) {
        closeCallback = cb
    }

    /**
     * 打开连接：先释放旧连接，再建立新连接并启动读写线程。
     * 失败时抛异常，且状态回到 [State.Disconnected]、不残留连接与线程。
     */
    final override suspend fun open(device: SerialDeviceInfo, baudRate: Int) {
        // v2 open() 第一行：先关掉已有连接
        teardown(notify = false, reason = null, awaitFlush = false)
        lock.lock()
        try {
            currentState = State.Connecting
            closedNotified = false
            closing = false
            stopRequested = false
        } finally {
            lock.unlock()
        }

        val opened = try {
            openConnection(device, baudRate)
        } catch (t: Throwable) {
            // 失败时不留半开状态。句柄的释放由 [PortConnectionFactory.create] 的契约保证
            // （"要么成功返回交给调用方，要么自己关干净再抛"），这里只负责状态与线程。
            recordError("打开串口失败", t)
            lock.lock()
            try {
                currentState = State.Disconnected
                bound = false
            } finally {
                lock.unlock()
            }
            throw t
        }
        lock.lock()
        try {
            connection = opened
            openedDevice = device
            currentState = State.Open(baudRate)
            bound = true
        } finally {
            lock.unlock()
        }
        startWriter()
        try {
            // 先注册（拔广播等），再启动读线程：否则读线程可能立刻 EOF 并调用 onClosed()，
            // 让"注销"发生在"注册"之前，留下一个永不注销的接收器
            onOpened(device)
        } catch (t: Throwable) {
            // 子类后置动作（注册广播等）失败不留半开状态
            recordError("打开后置动作失败", t)
            teardown(notify = false, reason = null, awaitFlush = false)
            throw t
        }
        startReader()
    }

    /**
     * 关闭连接（**幂等**，可在任意线程/任意状态调用）。
     * 先有界排空写队列（已入队的 G 代码尽量落盘），再关连接、停线程、复位状态。
     * **不触发** `closed` 回调 —— 与 v2 的手动 `close()` 一致。
     */
    final override suspend fun close() {
        teardown(notify = false, reason = null, awaitFlush = true)
    }

    /**
     * 同步写入：**调用即入队**（不挂起、不等待落盘），落盘顺序由内部写队列保证。
     *
     * 与 v2 的唯一差异是"是否 await 写完成"：v2 的 `write()` 返回 Promise 而调用方
     * （`GrblCore` 的 fire-and-forget 路径）并不 await，所以对调用方完全等价。
     */
    final override suspend fun write(text: String) {
        enqueue(text.encodeToByteArray())
    }

    /** 同步写入原始字节（实时命令：`?` / 0x18 / 0x21 / 0x7E / 覆盖倍率）。 */
    final override suspend fun writeBytes(bytes: ByteArray) {
        enqueue(bytes)
    }

    /** 清空最近一次错误（重连前调用）。 */
    fun resetError() {
        lastError = null
    }

    // ================= 内部：写路径 =================

    private fun enqueue(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        lock.lock()
        try {
            // 用 bound 而不是 state：close() 期间状态已是 Closing，但此刻已入队的写仍应落盘
            if (!bound || connection == null) {
                recordError("串口未打开，丢弃 ${bytes.size} 字节")
                return
            }
            pendingWrites++
            writeQueue.add(bytes)
        } finally {
            lock.unlock()
        }
    }

    private fun startWriter() {
        val worker = thread(name = "$WRITER_THREAD_PREFIX$kind", isDaemon = true) {
            while (true) {
                val bytes = try {
                    writeQueue.take()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@thread
                }
                // `stopWriter` 走"排空超时"路径时会先把 POISON 从队列里 poll 掉，
                // 之后写线程若还活着就会永久阻塞在 take() 上（daemon 线程，功能无影响但会泄漏）。
                // 用 stopRequested 兜住这一条路径。
                if (bytes === POISON || stopRequested) return@thread
                try {
                    connection?.write(bytes)
                } catch (t: Throwable) {
                    // v2 把写失败回给调用方；这里是 fire-and-forget 路径，记下原因即可，
                    // 绝不能让写线程死掉（否则后续所有写都会静默丢弃）
                    recordError("写入串口失败", t)
                } finally {
                    lock.lock()
                    try {
                        pendingWrites--
                    } finally {
                        lock.unlock()
                    }
                }
            }
        }
        writerThread = worker
    }

    /**
     * 停掉写线程并**有界排空**队列（不掉尾；超时则丢弃并记录到 [lastError]）。
     *
     * @param awaitFlush false 时直接丢弃队列（打开新设备 / 拔出场景，v2 也是直接放弃旧连接）
     */
    private fun stopWriter(awaitFlush: Boolean) {
        val worker = writerThread
        writerThread = null
        if (worker == null) return
        if (awaitFlush) {
            val deadline = System.nanoTime() + flushTimeoutMillis * 1_000_000L
            while (true) {
                lock.lock()
                val left = pendingWrites
                lock.unlock()
                if (left <= 0) break
                if (System.nanoTime() >= deadline) {
                    // 注意口径：这里 N 是**队列条数**（`pendingWrites` 计条目），
                    // 而下面 "丢弃未落盘字节" 才是真正的字节数。
                    recordError("关闭前排空写队列超时（剩余 $left 条未落盘）")
                    // ⚠️ 只在超时路径置位：下面的 leftover 排空会把 POISON 一起 poll 掉，
                    // 写线程若还活着就会永久阻塞在 take() 上。正常排空路径由 POISON 收尾，
                    // 绝不能置位 —— 否则写线程可能在处理完队尾之前就退出，重现"静默丢字节"。
                    stopRequested = true
                    break
                }
                Thread.sleep(1L)
            }
        }
        writeQueue.add(POISON)
        try {
            worker.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        var leftover = 0
        while (true) {
            val item = writeQueue.poll() ?: break
            if (item !== POISON) leftover += item.size
        }
        if (leftover > 0) recordError("关闭时丢弃未落盘字节 $leftover 个")
    }

    // ================= 内部：读路径 =================

    private fun startReader() {
        val decoder = Utf8StreamDecoder()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val worker = thread(name = "$READER_THREAD_PREFIX$kind", isDaemon = true) {
            while (true) {
                // 停止判据用 bound（而不是 connection）：teardown 必须能在**排空写队列之前**
                // 就告诉读线程"别再读了"，同时又不能把 connection 提前置空 —— 否则排队中的
                // 字节会在写线程里被静默丢弃（这是本轮审计抓到的回归）。
                val conn = if (bound) connection else null
                if (conn == null) break
                val read = try {
                    conn.read(buffer)
                } catch (t: Throwable) {
                    // v2：读异常即视为断开（设备被拔 / 连接失效）。
                    // ⚠️ 只有 `bound` 仍为真才由**读线程**发起 teardown：
                    // 手动 close() 会先把 bound 置 false 再关句柄，于是被唤醒的读线程在这里
                    // 直接退出，**不会**多发一次 `closed`（v2 的 `reading` 标志位语义）。
                    recordError("读取串口失败", t)
                    if (bound) teardown(notify = true, reason = "读取串口失败", awaitFlush = false)
                    return@thread
                }
                if (read < 0) {
                    // EOF：契约上表示"流结束"。⚠️ 实测（本机 Android SDK 源码 android-28/30/31/35）：
                    // 经典蓝牙的 RFCOMM 实现把负数**转成 IOException**（`bt socket closed, read return: -1`），
                    // 所以真机上通常走上面的异常分支；这条分支主要为 JVM 单测与未来的其它实现保留。
                    // 两条分支的结果一致（只通知一次 `closed`），只有 `lastError` 文案不同。
                    if (bound) teardown(notify = true, reason = "串口读取结束", awaitFlush = false)
                    return@thread
                }
                if (read == 0) {
                    if (readIdleDelayMillis > 0) Thread.sleep(readIdleDelayMillis)
                    continue
                }
                val text = decoder.decode(buffer, read)
                if (text.isNotEmpty()) {
                    try {
                        dataCallback?.invoke(text)
                    } catch (t: Throwable) {
                        recordError("数据回调抛异常", t)
                    }
                }
            }
            // 只有"连接已结束"才补冲刷残留的半个字符；被 close() 叫停时同样冲刷（幂等且无害）
            val tail = decoder.flush()
            if (tail.isNotEmpty()) runCatching { dataCallback?.invoke(tail) }
        }
        readerThread = worker
    }

    // ================= 内部：关闭路径 =================

    /**
     * 统一的关闭实现（幂等）。
     *
     * **状态先落地、再关原生句柄**：`bound = false` 与 `state = Disconnected` 都在关闭
     * [PortConnection] **之前**完成，于是读线程被 `read()` 抛出的异常唤醒时，
     * 它看到的已经是"未绑定"，其 `teardown(notify = true)` 会直接返回 —— 手动 [close]
     * **不会**因为"读线程把 close 引发的 EOF 误判成拔出"而多通知一次 `closed`。
     *
     * @param notify     是否触发 `closed` 回调（拔出 / 读 EOF 为 true，手动 [close] 为 false）
     * @param awaitFlush 是否等待写队列排空（只有手动 [close] 等）
     */
    protected fun teardown(notify: Boolean, reason: String?, awaitFlush: Boolean) {
        val proceed = lock.lock().let {
            try {
                // 幂等闸门：`bound` 在排空期间就必须是 false（用来叫停读线程），
                // 所以不能用它判断"是否已经在关闭中"，否则并发/重复的 close()
                // 会各自跑一遍 stopWriter + conn.close()。用独立的 closing 标志。
                if (closing || (!bound && connection == null)) {
                    false
                } else {
                    closing = true
                    currentState = State.Closing
                    true
                }
            } finally {
                lock.unlock()
            }
        }
        if (!proceed) {
            currentState = State.Disconnected
            return
        }
        if (reason != null) recordError(reason)

        // ⚠️ 顺序很讲究（本轮审计踩过一次）：
        //  1. 先 bound=false：读线程的停止判据是 bound，于是它不会再发起新的 read，
        //     也不会把"我们自己关端口导致的 EOF"误当成拔出（否则会多发一次 closed）；
        //  2. **connection 仍然保留**：写线程要用它把队列里剩下的字节落盘，
        //     提前置空会让"有界排空"变成静默丢弃（pendingWrites 照减，lastError 还是 null）；
        //  3. 排空完成后再摘掉 connection、关句柄。
        bound = false
        val conn = connection
        val reader = readerThread
        stopWriter(awaitFlush)

        lock.lock()
        try {
            connection = null
            openedDevice = null
            readerThread = null
            currentState = State.Disconnected
            closing = false
        } finally {
            lock.unlock()
        }

        try {
            conn?.close()
        } catch (t: Throwable) {
            recordError("关闭串口失败", t)
        }

        if (reader != null && reader !== Thread.currentThread()) {
            try {
                reader.join(JOIN_TIMEOUT_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        runCatching { onClosed() }.onFailure { recordError("关闭后置动作失败", it) }

        if (notify) notifyClosedOnce()
    }

    /** **只通知一次** `closed`（v2 的 `notifyClosedOnce`）。 */
    protected fun notifyClosedOnce() {
        val shouldNotify = lock.lock().let {
            try {
                if (closedNotified) {
                    false
                } else {
                    closedNotified = true
                    true
                }
            } finally {
                lock.unlock()
            }
        }
        if (!shouldNotify) return
        runCatching { closeCallback?.invoke() }.onFailure { recordError("断开回调抛异常", it) }
    }

    /**
     * 外部事件触发断开（USB `ACTION_USB_DEVICE_DETACHED` / 蓝牙 `ACTION_ACL_DISCONNECTED`）：
     * 关连接 + 只通知一次。可从容地在广播接收器线程直接调用。
     */
    protected fun handleExternalDisconnect(reason: String) {
        teardown(notify = true, reason = reason, awaitFlush = false)
    }

    /** 清空错误并复位"已通知断开"标记（子类重新注册广播前可调用，等价 v2 `open()` 里的 `closedNotified = false`）。 */
    protected fun resetClosedNotified() {
        lock.lock()
        try {
            closedNotified = false
        } finally {
            lock.unlock()
        }
    }

    protected fun recordError(message: String, cause: Throwable? = null) {
        val detail = cause?.let { "${it::class.simpleName}: ${it.message}" }
        lastError = if (detail != null) "$message（$detail）" else message
    }

    private companion object {
        /** 读缓冲区大小（字节）—— v2 `READ_BUFFER_SIZE`。 */
        const val READ_BUFFER_SIZE = 4096

        /** `read` 返回 0 时的退让间隔（v2 USB 是真阻塞读，不会返回 0；这里只为防御忙等）。 */
        const val DEFAULT_READ_IDLE_DELAY_MILLIS = 2L

        /** 关闭时等待写队列清空的上限。 */
        const val DEFAULT_FLUSH_TIMEOUT_MILLIS = 2000L

        /** `join` 的上限（v2 用 1000 ms）。 */
        const val JOIN_TIMEOUT_MILLIS = 1000L

        /** 写队列的结束哨兵。 */
        val POISON = ByteArray(0)

        const val WRITER_THREAD_PREFIX = "SerialPort-writer-"
        const val READER_THREAD_PREFIX = "SerialPort-reader-"
    }
}
