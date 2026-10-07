package com.lasergrbl.core.serial

import com.lasergrbl.core.grbl.FakeSettings
import com.lasergrbl.core.grbl.GrblCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **跨层写顺序验证**（Lead 独立复核）—— 真实 [SerialPortBase] 状态机 + 真实 [GrblCore]。
 *
 * 为什么必须单独写：既有的 `GrblCoreTest` 用 `TestScope` 的**单线程**调度器，
 * 永远不会重排，所以它既证明不了顺序成立，也抓不到"顺序被破坏"的回归；
 * 而真机（以及本文件）里 `txScope = CoroutineScope(Dispatchers.Default)` 是**多线程**的。
 *
 * 被钉住的机制（`GrblCore.sendLine()` / `sendImmediate()`）：
 * `txScope.launch(start = CoroutineStart.UNDISPATCHED)` —— 协程体在**调用线程上立即执行到
 * 第一个挂起点**。传输层契约要求"入队发生在第一个挂起点之前且不挂起"，
 * 于是**调用返回时字节已经入队**，"调用顺序 = 入队顺序"在调用线程上同步成立，与调度器无关。
 * 传输层的单写线程再保证"入队顺序 = 落盘顺序"。
 *
 * 两段论证各有对应用例：
 *  * [immediateSendEnqueuesBeforeReturning]：**确定性**判别"入队是否同步发生"
 *    —— 它正是 `UNDISPATCHED` 的看门狗（把 UNDISPATCHED 改回普通 launch 必红）。
 *  * [sequentialWritesKeepCallOrderOnRealDispatcher]：多线程调度器下 200 连发的落盘顺序。
 */
class GrblCoreSerialOrderingTest {

    /** 记录写调用的假连接（入口即计数，出口不计数）。 */
    private class RecordingConnection : PortConnection {

        /** 落盘顺序（`write` 收到的文本）。 */
        val written = CopyOnWriteArrayList<String>()

        /** 并发写在途计数（单写线程的话永远是 0）。 */
        val overlapped = AtomicInteger(0)

        private val inFlight = AtomicInteger(0)

        override fun read(buffer: ByteArray): Int {
            Thread.sleep(1) // 无数据，退让（真实 USB 是阻塞读）
            return 0
        }

        override fun write(bytes: ByteArray) {
            if (inFlight.incrementAndGet() > 1) overlapped.incrementAndGet()
            try {
                written.add(String(bytes, Charsets.UTF_8))
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun close() {}
    }

    /**
     * 记录**接缝层**入口顺序的传输层（委托给真实状态机）。
     *
     * 为什么要委托而不是在 [SerialPortBase] 上取号：`SerialPortBase.write/writeBytes` 是 final，
     * 子类无法插桩；而 [PortConnection] 的记录发生在**写线程**上（异步），
     * 拿它断言"`sendImmediate` 返回时是否已入队"是在跟调度器赌运气。
     * 接缝层（[SerialTransport]）才是"调用顺序"的可观测点。
     */
    private class EntryRecordingTransport(private val inner: SerialTransport) :
        SerialTransport by inner {

        data class Entry(val seq: Int, val text: String)

        val entries = CopyOnWriteArrayList<Entry>()

        private val counter = AtomicInteger()

        override suspend fun write(text: String) {
            entries.add(Entry(counter.incrementAndGet(), text))
            inner.write(text)
        }

        override suspend fun writeBytes(bytes: ByteArray) {
            entries.add(Entry(counter.incrementAndGet(), bytes.decodeToString()))
            inner.writeBytes(bytes)
        }

        fun clearEntries() = entries.clear()
    }

    /** 最小传输层：把假连接交给 [SerialPortBase] 的状态机。 */
    private class RecordingTransport(connection: RecordingConnection) :
        SerialPortBase(
            kind = TransportKind.Usb,
            factory = PortConnectionFactory { _, _ -> connection },
            readIdleDelayMillis = 5L
        ) {
        override suspend fun list(): List<SerialDeviceInfo> =
            listOf(
                SerialDeviceInfo(
                    id = "1",
                    kind = TransportKind.Usb,
                    name = "Fake Laser",
                    deviceId = 1.0
                )
            )
    }

    private fun fakeDevice(): SerialDeviceInfo = SerialDeviceInfo(
        id = "1",
        kind = TransportKind.Usb,
        name = "Fake Laser",
        deviceId = 1.0
    )

    /** 单线程 `txScope`：让"非 UNDISPATCHED 的 launch"必然**稍后**才执行，从而可确定性判别。 */
    private fun singleThreadTxScope(): CoroutineScope =
        CoroutineScope(Executors.newSingleThreadExecutor { r -> Thread(r, "tx-test").apply { isDaemon = true } }.asCoroutineDispatcher())

    private fun coreWith(transport: SerialTransport, txScope: CoroutineScope): GrblCore = GrblCore(
        injectedTransport = transport,
        settingsSource = FakeSettings(
            mapOf(
                "Threading Mode" to "Fast",
                "Firmware Type" to "Grbl",
                "Jog Speed" to 1000,
                "Jog Step" to 1,
                "Reset Grbl On Connect" to false
            )
        ),
        txScope = txScope,
        autoTxLoop = false
    )

    /**
     * **确定性**判别：`sendImmediate` 返回时字节必须已经离开调用线程、进入传输层的写队列。
     *
     * 手法：`txScope` 用**单线程**执行器（区别于调用线程），并且**不做任何 yield/sleep** 就断言。
     *  * `UNDISPATCHED`：`writeBytes` 在调用线程上同步跑到入队点 → 断言立刻成立；
     *  * 普通 `launch`：任务只是排进 tx 线程的队列，此刻必然还没执行 → 断言失败。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun immediateSendEnqueuesBeforeReturning() = runBlocking {
        val connection = RecordingConnection()
        val seam = EntryRecordingTransport(RecordingTransport(connection))
        val txScope = singleThreadTxScope()
        val grbl = coreWith(seam, txScope)
        grbl.open(fakeDevice(), 115200)

        // 让握手把在途的写都走完，再清空接缝记录，下面只观察实时命令
        assertTrue(
            waitFor(WRITE_TIMEOUT_MILLIS) { connection.written.isNotEmpty() },
            "握手第一步没有写出任何字节"
        )
        seam.clearEntries()

        grbl.sendImmediate(0x3f)

        // ⚠️ 这里刻意"零等待"：这正是 UNDISPATCHED 与普通 launch 的分水岭。
        // 断言看的是**接缝层入口**（同步发生），而不是端口落盘（异步发生）。
        assertEquals(
            listOf("?"),
            seam.entries.map { it.text },
            "sendImmediate 返回时字节尚未进入传输层 —— 说明写协程没有用 UNDISPATCHED 同步启动，"
                + "真机上（多线程 Dispatchers.Default）调用顺序会被调度器重排"
        )

        grbl.close(manual = true)
    }

    /**
     * 关口的**排空**必须有牙齿：写很慢时 `close()` 不能丢尾巴。
     *
     * 这条是回归看门狗 —— 曾经有一版 `teardown()` 把 `connection = null` 放在排空之前，
     * 于是写线程拿到 null 静默丢弃剩余字节，而 `pendingWrites` 照减、`lastError` 还是 null，
     * "有界排空"看起来瞬间成功。
     *
     * ⚠️ **刻意在传输层（[SerialPortBase]）上测，而不是通过 `GrblCore.sendImmediate`**：
     * 后者会额外引入 `GrblCore` 的状态查询定时器（4 ms 节拍），在机器负载高时
     * 会多写一个 `?` 字节，让"恰好 200"这种精确断言偶发假红（本测试曾因此在并发构建下红过一次）。
     * 这里直接数 `transport.writeBytes` 的调用，计数完全可控。
     * `GrblCore` 与传输层的**接线**顺序由本文件另外两条用例覆盖。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closeFlushesQueuedWritesEvenWhenWritesAreSlow() = runBlocking {
        val connection = SlowConnection(writeDelayMillis = 1L)
        val transport = SlowTransport(connection)
        val device = fakeDevice()

        transport.open(device, 115200)
        // 注意：握手是 `GrblCore` 的事，传输层 `open()` 本身不写任何字节
        assertTrue(transport.isOpen(), "打开后 isOpen() 应为 true")

        // 200 次写入入队，紧接着立刻 close：排空必须把 200 个字节全部写完
        val total = 200
        repeat(total) { transport.writeBytes(byteArrayOf(0x3f)) }
        transport.close()

        // close 之后不能再有新的写入进来
        val afterClose = connection.written.size
        assertEquals(
            total,
            afterClose,
            "close() 丢失了排空中的字节：写线程在 connection 被置空后静默丢弃（实际落盘 $afterClose / $total）"
        )
        assertEquals(null, transport.lastError, "排空过程不应记录错误：${transport.lastError}")
    }

    /** 写很慢的假连接（用于制造"close 时队列非空"）。 */
    private class SlowConnection(private val writeDelayMillis: Long) : PortConnection {
        val written = CopyOnWriteArrayList<Byte>()

        override fun read(buffer: ByteArray): Int {
            Thread.sleep(1)
            return 0
        }

        override fun write(bytes: ByteArray) {
            Thread.sleep(writeDelayMillis)
            for (b in bytes) written.add(b)
        }

        override fun close() {}
    }

    private class SlowTransport(connection: SlowConnection) :
        SerialPortBase(
            kind = TransportKind.Usb,
            factory = PortConnectionFactory { _, _ -> connection },
            readIdleDelayMillis = 5L,
            // 排空上限要够 200 次 × 1 ms
            flushTimeoutMillis = 30_000L
        ) {
        override suspend fun list(): List<SerialDeviceInfo> = emptyList()
    }

    /** 多线程调度器下：200 连发的落盘顺序 = 调用顺序，且从不并发写。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sequentialWritesKeepCallOrderOnRealDispatcher() = runBlocking {
        val connection = RecordingConnection()
        val transport = RecordingTransport(connection)
        val grbl = coreWith(transport, CoroutineScope(Dispatchers.Default)) // 与真机一致
        grbl.open(fakeDevice(), 115200)

        // 握手写出后再清空，避免握手字节混进断言
        assertTrue(waitFor(WRITE_TIMEOUT_MILLIS) { connection.written.isNotEmpty() }, "握手没有写出任何字节")
        connection.written.clear()

        val expected = (0 until 200).map { intArrayOf(0x3f, 0x21, 0x7e, 0x18)[it % 4] }
        for (b in expected) grbl.sendImmediate(b)

        assertTrue(
            waitFor(WRITE_TIMEOUT_MILLIS) { connection.written.size >= expected.size },
            "超时：只写出 ${connection.written.size} / ${expected.size}"
        )

        val actual = connection.written.take(expected.size).map { it.toByteArray().first().toInt() and 0xff }
        assertEquals(
            expected.map { it.toChar() },
            actual.map { it.toChar() },
            "200 次实时命令的落盘顺序与调用顺序不一致"
        )
        assertEquals(0, connection.overlapped.get(), "出现了并发写：同一时刻有两次 write 在途")

        grbl.close(manual = true)
    }

    /** 串口打开期间，握手/查询写出的行必须按 `GrblCore` 的调用顺序落盘（多线程调度器）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun handshakeWritesKeepCallOrder() = runBlocking {
        val connection = RecordingConnection()
        val transport = RecordingTransport(connection)
        val grbl = coreWith(transport, CoroutineScope(Dispatchers.Default))

        grbl.open(fakeDevice(), 115200)
        // 握手把 `$I` / `$$` / `$#` 排进发送队列，靠 txTick 逐拍发出（autoTxLoop 在测试里关掉了）
        repeat(20) { grbl.txTick() }

        assertTrue(
            waitFor(WRITE_TIMEOUT_MILLIS) { connection.written.count { it.endsWith("\n") } >= 3 },
            "握手命令没有全部写出：state=${transport.state} lastError=${transport.lastError} "
                + "written=${connection.written}"
        )

        val lines = connection.written.map { it.trim() }.filter { it.isNotEmpty() }
        // 握手顺序（v2 StartTX）："\r\n" → "$I" → "$$" → "$#" → 位置查询
        val buildInfo = lines.indexOf("\$I")
        val settings = lines.indexOf("\$\$")
        val coordinates = lines.indexOf("\$#")
        assertTrue(buildInfo in 0 until settings, "`\$I` 必须出现在 `\$\$` 之前：$lines")
        assertTrue(settings < coordinates, "`\$\$` 必须出现在 `\$#` 之前：$lines")
        assertEquals(0, connection.overlapped.get(), "握手期间出现了并发写")

        grbl.close(manual = true)
    }

    private suspend fun waitFor(timeoutMillis: Long, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (!condition()) {
                kotlinx.coroutines.yield()
                Thread.sleep(1)
            }
            true
        } ?: false

    private companion object {
        const val WRITE_TIMEOUT_MILLIS = 5_000L
    }
}
