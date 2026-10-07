package com.lasergrbl.android.serial

import com.lasergrbl.core.serial.PortConnection
import com.lasergrbl.core.serial.PortConnectionFactory
import com.lasergrbl.core.serial.SerialDeviceInfo
import com.lasergrbl.core.serial.SerialPortBase
import com.lasergrbl.core.serial.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * USB 传输层在 **JVM 上可验的那一部分** —— 从旧的 `platform/UsbSerialTransportTest` 迁移而来
 * （旧的 `UsbSerialGateway` 接缝已随架构统一删除，见 Lead 的裁决）。
 *
 * ### 这个文件为什么用 [UsbSeamTransport] 而不是直接 new [UsbSerialTransport]
 * `UsbSerialTransport` 的构造函数需要 `Context`，而设备枚举 / 权限 / 打开路径需要
 * `UsbManager` / `UsbDevice` —— 这三者在 JVM 单测里**无法构造**（`UsbDevice` 是 final 且没有
 * 公开构造函数，android.jar 的方法体是抛 `Stub!` 的桩）。因此：
 *
 *  * **状态机**（写串行化、拔出只通知一次、`close()` 幂等、UTF-8 跨块、`isOpen()` 状态转换）用
 *    [UsbSeamTransport] 驱动 —— 它就是 `SerialPortBase(TransportKind.Usb, factory)`，
 *    与 [UsbSerialTransport] 的接线**完全相同**（`UsbSerialTransport` 也只是把
 *    `UsbSerialConnectionFactory` 交给同一个基类），只是把工厂换成假实现；
 *  * **USB 专有的纯规则**（设备名拼装、拔出广播过滤、权限广播判据）直接调生产函数，见本文件
 *    末尾与 [UsbSerialDeviceInfoTest]。
 *
 * ### 无法离线验证（不在这里假装验证）
 * 真实设备枚举、权限弹窗与 20 s 超时、实际波特率、DTR/RTS 是否被芯片接受、拔出广播的到达时机、
 * `UsbSerialConnectionFactory` 里三条失败路径的资源释放 —— 全部需要真机。
 */
class UsbSerialTransportTest {

    // ==================== 写串行化 ====================

    /**
     * 顺序 + 互斥一起钉死：第一条写被门闩卡在端口里（已经进入 `port.write`，但还没返回），
     * 后两条此时已经入队。如果实现是「每次写各起一个线程/协程」或用非 FIFO 的锁，
     * 落盘顺序就会变成 L2/L3/L1，或者并发度 > 1。
     *
     * 注意信号用的是 [FakePortConnection.writesStarted]（进入端口即自增）而**不是**
     * `received`（门闩放行后才记录）—— 后者会与门闩互相等待，是一个必然超时的写法。
     */
    @Test
    fun writesReachThePortInCallOrderAndNeverOverlap() = runBlocking {
        val factory = FakeConnectionFactory()
        val transport = UsbSeamTransport(factory)
        transport.open(device(7), 115200)
        val port = factory.connections.single()

        val gate = CountDownLatch(1)
        port.writeGate = gate

        transport.write("L1\n")
        awaitUntil("第一条写进入端口") { port.writesStarted.get() == 1 }
        transport.write("L2\n")
        transport.write("L3\n")

        gate.countDown()
        awaitUntil("三条写全部落盘") { port.received.size == 3 }

        assertEquals(listOf("L1\n", "L2\n", "L3\n"), port.received.map { it.toString(Charsets.UTF_8) })
        assertEquals("任何时刻最多一次写在途", 1, port.maxConcurrentWrites.get())

        transport.close()
    }

    /** 多线程并发调用（真并行）：互斥必须成立，且每个请求恰好落盘一次、内容不被拆开。 */
    @Test
    fun concurrentWritersAreSerializedOnThePort() = runBlocking {
        val factory = FakeConnectionFactory()
        val transport = UsbSeamTransport(factory)
        transport.open(device(7), 115200)
        val port = factory.connections.single()

        val writers = 4
        val perWriter = 50
        val expected = (0 until writers).flatMap { w -> (0 until perWriter).map { i -> "T$w-$i\n" } }.toSet()

        (0 until writers).map { w ->
            async(Dispatchers.Default) {
                repeat(perWriter) { i -> transport.write("T$w-$i\n") }
            }
        }.awaitAll()

        awaitUntil("全部落盘") { port.received.size == writers * perWriter }
        val received = port.received.map { it.toString(Charsets.UTF_8) }
        assertEquals(writers * perWriter, received.size)
        assertEquals("不能丢包或改内容", expected, received.toSet())
        assertEquals("任何时刻最多一次写在途", 1, port.maxConcurrentWrites.get())

        transport.close()
    }

    /** `write` 与 `writeBytes` 共用同一条队列：实时命令字节不会被文本写插队，且原样单字节。 */
    @Test
    fun writeAndWriteBytesShareOneOrderedQueue() = runBlocking {
        val factory = FakeConnectionFactory()
        val transport = UsbSeamTransport(factory)
        transport.open(device(7), 115200)
        val port = factory.connections.single()

        transport.write("G0X0\n")
        transport.writeBytes(byteArrayOf(0x3F)) // '?' 状态查询
        transport.write("G1X1\n")
        transport.writeBytes(byteArrayOf(0x18)) // 软复位

        awaitUntil("四条写全部落盘") { port.received.size == 4 }
        assertEquals(
            listOf("G0X0\n", "?", "G1X1\n", "\u0018"),
            port.received.map { it.toString(Charsets.UTF_8) }
        )
        // 实时命令必须是原样单字节（`:core` 的契约：ByteArray 不做任何编码转换）
        assertEquals(1, port.received[1].size)
        assertEquals(0x3F, port.received[1][0].toInt())
        assertEquals(1, port.received[3].size)
        assertEquals(0x18, port.received[3][0].toInt())

        transport.close()
    }

    /** 写失败：fire-and-forget 路径不回抛，但必须记进 [SerialPortBase.lastError]，且写线程不死。 */
    @Test
    fun writeFailureIsRecordedAndDoesNotKillTheWriter() = runBlocking {
        val factory = FakeConnectionFactory()
        val transport = UsbSeamTransport(factory)
        transport.open(device(7), 115200)
        val port = factory.connections.single()

        port.writeFailure = IOException("device gone")
        transport.write("G1X1\n")
        awaitUntil("失败被记录") { transport.lastError?.contains("写入串口失败") == true }
        assertTrue("写失败不应拆掉连接", transport.isOpen())

        // 写线程必须还活着：恢复后下一条写仍能落盘
        port.writeFailure = null
        transport.write("G1X2\n")
        awaitUntil("恢复后仍能写") { port.received.any { it.toString(Charsets.UTF_8) == "G1X2\n" } }

        transport.close()
    }

    // ==================== 拔出 / 断开通知 / close 幂等 ====================

    /** 拔出：只通知一次；`isOpen()` 立刻 false；句柄只关一次；重新 open 后标记复位。 */
    @Test
    fun externalDisconnectNotifiesExactlyOnceAndResetsOnReopen() = runBlocking {
        val factory = FakeConnectionFactory()
        val transport = UsbSeamTransport(factory)
        val notifications = AtomicInteger(0)
        transport.onClose { notifications.incrementAndGet() }

        transport.open(device(7), 115200)
        val first = factory.connections.single()
        assertTrue(transport.isOpen())

        transport.fireExternalDisconnect()
        assertFalse("拔出后 isOpen() 必须立刻为 false", transport.isOpen())
        assertEquals(1, notifications.get())
        assertEquals(1, first.closeCalls.get())
        assertEquals(SerialPortBase.State.Disconnected, transport.state)

        // 重复广播：不得再次通知、不得重复关句柄
        transport.fireExternalDisconnect()
        assertEquals("拔出只通知一次", 1, notifications.get())
        assertEquals(1, first.closeCalls.get())

        // 重新 open 会复位「已通知」标记，第二次断开仍恰好通知一次
        transport.open(device(7), 115200)
        transport.fireExternalDisconnect()
        assertEquals(2, notifications.get())

        transport.close()
    }

    /** 读返回 EOF（负数）→ 关连接 + 只通知一次（v2 的 readLoop 读异常路径）。 */
    @Test
    fun readEofClosesAndNotifiesOnce() = runBlocking {
        val factory = FakeConnectionFactory()
        val transport = UsbSeamTransport(factory)
        val notifications = AtomicInteger(0)
        transport.onClose { notifications.incrementAndGet() }
        transport.open(device(7), 115200)
        val port = factory.connections.single()

        port.eof = true
        // 不能只等 isOpen() 变 false：teardown 是「先复位状态、再关句柄、最后通知」，
        // 状态与通知之间隔着关句柄和 join 读线程。所以要等「通知已经发生」再断言次数。
        awaitUntil("读到 EOF 后自动关闭并通知") { notifications.get() >= 1 && !transport.isOpen() }
        assertEquals(1, notifications.get())
        assertEquals(1, port.closeCalls.get())
    }

    /** 读抛异常（连接失效）→ 同一条「关闭 + 通知一次」路径。 */
    @Test
    fun readFailureClosesAndNotifiesOnce() = runBlocking {
        val factory = FakeConnectionFactory()
        val transport = UsbSeamTransport(factory)
        val notifications = AtomicInteger(0)
        transport.onClose { notifications.incrementAndGet() }
        transport.open(device(7), 115200)
        val port = factory.connections.single()

        port.readFailure = IOException("device gone")
        awaitUntil("读失败后自动关闭并通知") { notifications.get() >= 1 && !transport.isOpen() }
        assertEquals(1, notifications.get())
        assertEquals(1, port.closeCalls.get())
    }

    /** 两条断开路径**同时**发生（拔出广播 + 读失败）：仍然只通知一次、只关一次句柄。 */
    @Test
    fun concurrentDetachAndReadFailureNotifyOnlyOnce() = runBlocking {
        val factory = FakeConnectionFactory()
        val transport = UsbSeamTransport(factory)
        val notifications = AtomicInteger(0)
        transport.onClose { notifications.incrementAndGet() }
        transport.open(device(7), 115200)
        val port = factory.connections.single()

        val barrier = CyclicBarrier(2)
        val detach = Thread { barrier.await(); transport.fireExternalDisconnect() }
        val readFail = Thread {
            barrier.await()
            port.readFailure = IOException("device gone")
        }
        detach.start()
        readFail.start()
        detach.join()
        readFail.join()

        awaitUntil("断开已通知") { notifications.get() >= 1 && !transport.isOpen() }
        Thread.sleep(200)
        assertEquals("并发下重复通知", 1, notifications.get())
        assertEquals("并发下重复关闭句柄", 1, port.closeCalls.get())
    }

    /** `close()` 幂等：重复调用不抛、句柄只关一次、**不**触发 onClose（与 v2 的手动关闭一致）。 */
    @Test
    fun closeIsIdempotentAndDoesNotNotify() = runBlocking {
        val factory = FakeConnectionFactory()
        val transport = UsbSeamTransport(factory)
        val notifications = AtomicInteger(0)
        transport.onClose { notifications.incrementAndGet() }
        transport.open(device(7), 115200)
        val port = factory.connections.single()

        transport.close()
        transport.close()
        transport.close()

        assertFalse(transport.isOpen())
        assertEquals(SerialPortBase.State.Disconnected, transport.state)
        assertEquals("句柄只关一次", 1, port.closeCalls.get())
        assertEquals("手动 close 不通知", 0, notifications.get())

        // 未打开时 close() 也必须安全
        val fresh = UsbSeamTransport(FakeConnectionFactory())
        fresh.close()
        assertFalse(fresh.isOpen())
    }

    // ==================== open 生命周期 ====================

    /** 打开失败：状态回到未连接、不残留半开状态；第二次 open 仍能成功。 */
    @Test
    fun openFailureLeavesStateDisconnectedAndAllowsRetry() = runBlocking {
        val failure = IOException("初始化串口失败: no endpoints")
        val factory = FakeConnectionFactory(failureOnce = failure)
        val transport = UsbSeamTransport(factory)

        val thrown = runCatching { transport.open(device(7), 115200) }.exceptionOrNull()

        assertTrue("应把工厂异常原样抛出，实际 $thrown", thrown === failure)
        assertFalse(transport.isOpen())
        assertEquals(SerialPortBase.State.Disconnected, transport.state)
        assertNotNull(transport.lastError)
        assertTrue("失败原因要留在 lastError：${transport.lastError}", transport.lastError!!.contains("打开串口失败"))

        // 失败后写入必须被丢弃（而不是挂起或写进一个不存在的连接）
        transport.write("G0X0\n")
        assertTrue(transport.lastError!!.contains("串口未打开"))

        // 第二次 open 必须能成功（失败路径没有污染状态）
        transport.open(device(7), 115200)
        assertTrue(transport.isOpen())
        assertEquals(1, factory.connections.size)
        transport.close()
    }

    /** 换设备打开：旧连接先被释放，之后的写只落到新连接（v2 的 `closePortInternal` 语义）。 */
    @Test
    fun openReleasesThePreviousConnectionFirst() = runBlocking {
        val factory = FakeConnectionFactory()
        val transport = UsbSeamTransport(factory)

        transport.open(device(7), 115200)
        val first = factory.connections.single()
        transport.open(device(9), 250000)
        val second = factory.connections.last()

        assertEquals("旧连接必须先释放", 1, first.closeCalls.get())
        assertTrue(transport.isOpen())
        assertEquals(250000, (transport.state as SerialPortBase.State.Open).baudRate)
        assertEquals("每次 open 都是新句柄", 2, factory.connections.size)

        transport.write("G0X0\n")
        awaitUntil("写落到新连接") { second.received.isNotEmpty() }
        assertTrue("旧连接不应再收到写", first.received.isEmpty())

        transport.close()
    }

    // ==================== UTF-8 跨块（端到端，经真实读线程与真实增量解码器）====================

    /**
     * 最坏情况：一次只喂 1 个字节。`onData` 拼起来必须逐字符等于原文，**不允许出现 U+FFFD**。
     *
     * 注意这里是端到端验证：字节走真实的 `PortConnection.read` → `SerialPortBase` 的读线程 →
     * 增量解码器。字节级的畸形序列语义由 `:core` 的 `SerialPortBaseTest` 覆盖
     * （解码器是 `:core` 的 internal 类型，:app 看不到）。
     */
    @Test
    fun bytesFedOneAtATimeArriveAsCompleteCharacters() = runBlocking {
        val text = "Grbl 1.1h ['$' for help]\r\n温度 ✓ é 😀\r\n"
        val factory = FakeConnectionFactory()
        val transport = UsbSeamTransport(factory)
        val received = StringBuilder()
        transport.onData { chunk -> synchronized(received) { received.append(chunk) } }
        transport.open(device(7), 115200)
        val port = factory.connections.single()

        for (byte in text.toByteArray(Charsets.UTF_8)) {
            port.feed(byteArrayOf(byte))
        }
        awaitUntil("收齐全部字符") { synchronized(received) { received.length } == text.length }

        assertEquals(text, synchronized(received) { received.toString() })
        assertFalse("不能出现替换字符", synchronized(received) { received.contains('\uFFFD') })

        transport.close()
    }

    /** 三字节字符被拆成三段：前两段不许吐字符，第三段才出完整字符。 */
    @Test
    fun threeByteCharacterSplitAcrossThreeChunks() = runBlocking {
        val factory = FakeConnectionFactory()
        val transport = UsbSeamTransport(factory)
        val received = StringBuilder()
        transport.onData { chunk -> synchronized(received) { received.append(chunk) } }
        transport.open(device(7), 115200)
        val port = factory.connections.single()

        val check = "✓".toByteArray(Charsets.UTF_8)
        assertEquals(3, check.size)
        port.feed(byteArrayOf(check[0]))
        port.feed(byteArrayOf(check[1]))
        port.feed(byteArrayOf(check[2]))

        awaitUntil("收到完整字符") { synchronized(received) { received.toString() } == "✓" }
        assertEquals("✓", synchronized(received) { received.toString() })

        transport.close()
    }

    /** 分块边界正好切在「✓」的首字节之后：顺序不能乱、字符不能坏。 */
    @Test
    fun largeChunkBoundaryKeepsMultibyteCharacter() = runBlocking {
        val factory = FakeConnectionFactory()
        val transport = UsbSeamTransport(factory)
        val received = StringBuilder()
        transport.onData { chunk -> synchronized(received) { received.append(chunk) } }
        transport.open(device(7), 115200)
        val port = factory.connections.single()

        // 4095 个 'A' + "✓B\r\n"：第一块正好在 ✓ 的首字节处结束
        val payload = "A".repeat(4095) + "✓B\r\n"
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val splitAt = 4096
        port.feed(bytes.copyOfRange(0, splitAt))
        port.feed(bytes.copyOfRange(splitAt, bytes.size))

        awaitUntil("收齐") { synchronized(received) { received.length } == payload.length }
        assertEquals(payload, synchronized(received) { received.toString() })
        assertFalse(synchronized(received) { received.contains('\uFFFD') })

        transport.close()
    }

    // ==================== USB 专有的纯规则（不碰 Android 类型）====================

    /** 拔出广播过滤：v2 的 `device != null ? deviceId 相同 : openedDeviceId >= 0`。 */
    @Test
    fun detachBroadcastFilterMatchesV2() {
        assertTrue("广播带的就是当前设备", UsbSerialTransport.shouldHandleDetach(7, 7))
        assertFalse("别的设备被拔出不影响当前连接", UsbSerialTransport.shouldHandleDetach(8, 7))
        assertFalse("未打开时忽略（deviceId=-1）", UsbSerialTransport.shouldHandleDetach(7, -1))
        assertTrue("广播没带设备但当前已打开 -> 处理", UsbSerialTransport.shouldHandleDetach(null, 7))
        assertFalse("广播没带设备且未打开 -> 忽略", UsbSerialTransport.shouldHandleDetach(null, -1))
    }

    /** 权限广播判据与结果合成（v2 `acquirePermission` 的两条纯逻辑）。 */
    @Test
    fun permissionPredicatesMatchV2() {
        assertTrue("没带设备的广播按目标设备处理", UsbPermissionGate.shouldAcceptPermissionResult(null, 7))
        assertTrue(UsbPermissionGate.shouldAcceptPermissionResult(7, 7))
        assertFalse("别的设备的结果不算数", UsbPermissionGate.shouldAcceptPermissionResult(8, 7))

        assertTrue("广播说授权了", UsbPermissionGate.resolvePermissionResult(granted = true, hasPermission = false))
        assertTrue("广播说没授权但复核已授权（v2 的兜底）", UsbPermissionGate.resolvePermissionResult(false, true))
        assertFalse(UsbPermissionGate.resolvePermissionResult(false, false))
    }

    /** 读超时/写超时常量必须与 v2 逐字一致（0 = 无限阻塞读，2000 ms 写）。 */
    @Test
    fun timeoutsMatchV2() {
        assertEquals(0, UsbSerialPortConnection.READ_TIMEOUT_FOREVER)
        assertEquals(2000, UsbSerialPortConnection.WRITE_TIMEOUT_MILLIS)
        assertEquals(20_000L, UsbPermissionGate.DEFAULT_TIMEOUT_MILLIS)
        assertEquals("com.lasergrbl.android.USB_PERMISSION", UsbPermissionGate.ACTION_USB_PERMISSION)
    }

    // ==================== 工具 ====================

    private fun device(deviceId: Int): SerialDeviceInfo = SerialDeviceInfo(
        id = deviceId.toString(),
        kind = TransportKind.Usb,
        name = "Fake CH340",
        deviceId = deviceId.toDouble(),
        vendor = "wch.cn",
        product = "USB Serial",
        vendorId = 0x1A86.toDouble(),
        productId = 0x7523.toDouble()
    )
}

/**
 * 「接缝版」USB 传输层测试替身：与生产接线**逐字相同**（`SerialPortBase(TransportKind.Usb, factory)`），
 * 只是把工厂换成 [FakeConnectionFactory]，并暴露 `protected` 的拔出入口。
 *
 * 之所以不能直接实例化 [UsbSerialTransport]：它的构造函数要 `Context`，而设备/权限路径要
 * `UsbManager` / `UsbDevice` —— 这些类型在 JVM 单测里无法构造（见类文档）。
 */
private class UsbSeamTransport(factory: PortConnectionFactory) : SerialPortBase(
    kind = TransportKind.Usb,
    factory = factory,
    readIdleDelayMillis = 1L,
    flushTimeoutMillis = 500L
) {

    /** 设备枚举在生产的 [UsbSerialTransport.list] 里（需要 `UsbManager`），这里不涉及。 */
    override suspend fun list(): List<SerialDeviceInfo> = emptyList()

    /** 生产代码里由 `ACTION_USB_DEVICE_DETACHED` 接收器调用。 */
    fun fireExternalDisconnect(reason: String = "USB 设备已拔出") {
        handleExternalDisconnect(reason)
    }
}

/** 每次 `create` 都产出一个新的 [FakePortConnection]；可选「第一次就失败」。 */
private class FakeConnectionFactory(private val failureOnce: IOException? = null) : PortConnectionFactory {

    val connections: MutableList<FakePortConnection> = Collections.synchronizedList(mutableListOf())

    override fun create(device: SerialDeviceInfo, baudRate: Int): PortConnection {
        failureOnce?.let { failure ->
            if (connections.isEmpty() && !failedOnce) {
                failedOnce = true
                throw failure
            }
        }
        return FakePortConnection().also { connections.add(it) }
    }

    private var failedOnce = false
}

/** 假句柄：记录写、可喂读、可注入失败、可统计 close 次数与并发写峰值。 */
private class FakePortConnection : PortConnection {

    private val incoming = ArrayDeque<ByteArray>()

    val received: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

    val closeCalls = AtomicInteger(0)

    private val inFlight = AtomicInteger(0)

    val maxConcurrentWrites = AtomicInteger(0)

    /** 「进入 `write`」的次数（在门闩之前自增）：用来构造「写还在途中」的确定时序。 */
    val writesStarted = AtomicInteger(0)

    @Volatile
    var readFailure: IOException? = null

    @Volatile
    var writeFailure: IOException? = null

    @Volatile
    var eof: Boolean = false

    /** 非 null 时第一条写会卡在这里（用来构造「写还在途中」的时序）。 */
    @Volatile
    var writeGate: CountDownLatch? = null

    /** 喂一段读数据（模拟串口收到字节）。 */
    fun feed(bytes: ByteArray) {
        synchronized(incoming) { incoming.addLast(bytes) }
    }

    override fun read(buffer: ByteArray): Int {
        readFailure?.let { throw it }
        if (eof) return -1
        val chunk = synchronized(incoming) { incoming.removeFirstOrNull() } ?: return 0
        chunk.copyInto(buffer, 0, 0, minOf(chunk.size, buffer.size))
        return minOf(chunk.size, buffer.size)
    }

    override fun write(bytes: ByteArray) {
        val concurrent = inFlight.incrementAndGet()
        maxConcurrentWrites.updateAndGet { maxOf(it, concurrent) }
        writesStarted.incrementAndGet()
        try {
            writeGate?.await(5, TimeUnit.SECONDS)
            writeFailure?.let { throw it }
            received.add(bytes.copyOf())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("写被中断", interrupted)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override fun close() {
        closeCalls.incrementAndGet()
    }
}

/** 轮询等待条件成立（避免依赖固定 sleep 的脆弱断言）。 */
private fun awaitUntil(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(5)
    }
    assertTrue("等待超时：$what", condition())
}
