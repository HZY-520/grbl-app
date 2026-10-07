package com.lasergrbl.core.serial

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SerialPortBase] 的**对抗性验收单测** —— 逐条钉死 `docs/UI-3.0-PLAN.md` §6.1 的硬性条目：
 *
 * | §6.1 | 本文件里的用例 |
 * | --- | --- |
 * | 写串行化（落盘顺序 = 调用顺序、不并发写） | [连续两百次写的落盘顺序与调用顺序逐条一致_且没有并发写] |
 * | 跨线程写不丢字节、不把单次写拆开/交错 | [四线程并发写不丢字节不交错_且每线程内保序] |
 * | 拔出只通知一次、重新 open 后重置 | [拔出只通知一次_重复触发不增加_重新open后重置]、[读失败触发断开时也恰好通知一次]、[读返回EOF时也恰好通知一次] |
 * | `close()` 幂等、手动 close 不通知 | [close幂等_连续三次不抛异常且连接只关一次]、[手动close不触发closed回调_即使读线程正阻塞在read里] |
 * | UTF-8 跨块拼接 | [UTF8跨块拼接_逐字节喂入_中文与度数符号不得出现替换符]（解码器本身的用例见 [Utf8StreamDecoderTest]） |
 * | `isOpen()` 状态转换 | [open成功后状态与设备信息正确_close后回到Disconnected]、[open过程中isOpen为false且状态为Connecting]、[open失败时基类不残留状态_句柄清理由工厂契约保证] |
 * | 关闭前排空写队列 | [close前会排空写队列_五百条一条不掉]、[close会等待慢写落盘_不丢尾]、[排空超时_丢弃剩余并记录错误] |
 * | 打开新设备前先释放旧连接 | [连续open会先释放旧连接_写只落到新连接] |
 * | `read` 返回 0 不得忙等 | [read返回0时不忙等] |
 *
 * 驱动方式：真线程 + 真 `Thread.sleep`（状态机本身就是多线程的，用虚拟时间测不出并发），
 * 所有等待都是「轮询 + 超时」（[awaitUntil]），不用 sleep 猜时序。
 *
 * ### 本文件测不到的（不假装测过）
 * 真机 USB 枚举/权限弹窗、`UsbDeviceConnection` 的真实读超时、拔出广播的到达时机、
 * 8N1/DTR/RTS、实际波特率、蓝牙 SPP 吞吐、4 ms 发送节拍能否跟上 —— 这些都必须真机验证。
 * 这里能证明的只有：**给定 [PortConnection] / [PortConnectionFactory] 契约，状态机语义正确**。
 */
class SerialPortBaseTest {

    /** 本测试创建过的端口，用于 `@AfterTest` 收尾（避免泄漏读写线程）。 */
    private val createdPorts = CopyOnWriteArrayList<TestPort>()

    @AfterTest
    fun 收尾() {
        for (port in createdPorts) {
            runCatching { runBlocking { port.close() } }
        }
        createdPorts.clear()
    }

    // =========================================================================
    // 1. 状态转换与设备信息
    // =========================================================================

    @Test
    fun 初始状态为未连接_未打开时写入被丢弃并记录错误() {
        val fake = FakePortConnection("never-opened")
        val factory = FakePortFactory(fake)
        val port = newPortWith(factory)
        assertEquals(SerialPortBase.State.Disconnected, port.state)
        assertFalse(port.isOpen())
        assertNull(port.device)
        assertNull(port.lastError)

        runBlocking { port.write("G1 X0\n") }
        assertEquals(0, factory.createCount.get(), "没打开时不该碰工厂/连接")
        assertEquals(0, fake.writes.size, "没打开时写入不得落盘")
        val err = port.lastError
        assertNotNull(err, "丢弃写入必须记录原因")
        assertTrue(err.contains("未打开"), "实际错误：$err")

        runBlocking { port.close() }
        assertEquals(SerialPortBase.State.Disconnected, port.state)
    }

    @Test
    fun open成功后状态与设备信息正确_close后回到Disconnected() = runBlocking {
        val fake = FakePortConnection("dev")
        val factory = FakePortFactory(fake)
        val port = newPortWith(factory)
        val requested = deviceInfo("7")

        port.open(requested, 115200)
        assertTrue(port.isOpen())
        assertEquals(SerialPortBase.State.Open(115200), port.state)
        assertEquals(TransportKind.Usb, port.kind)
        assertEquals(1, factory.createCount.get())
        assertEquals(115200, factory.lastBaudRate)
        assertEquals(1, port.onOpenedCount.get(), "onOpened 应在 open 成功后恰好一次")

        val handedToFactory = factory.lastDevice
        assertNotNull(handedToFactory, "工厂应收到设备信息")
        assertEquals(requested, handedToFactory, "工厂必须收到原始 SerialDeviceInfo（不做有损转换）")
        assertEquals("7", handedToFactory.id)
        assertEquals("假激光 7", handedToFactory.name)
        assertEquals(0x1A86.toDouble(), handedToFactory.vendorId)
        assertEquals(0x7523.toDouble(), handedToFactory.productId)

        assertNotNull(port.device, "打开后 device 应可观察")
        assertEquals("7", port.device?.id)
        assertEquals(requested, port.device)

        port.close()
        assertFalse(port.isOpen())
        assertEquals(SerialPortBase.State.Disconnected, port.state)
        assertNull(port.device, "关闭后不得残留设备信息")
        assertEquals(1, fake.closeCount.get(), "底层连接应恰好关一次")
    }

    @Test
    fun open过程中isOpen为false且状态为Connecting() = runBlocking {
        val fake = FakePortConnection("connecting")
        val factory = FakePortFactory(fake)
        val port = newPortWith(factory)
        val observedState = arrayOfNulls<SerialPortBase.State>(1)
        val observedIsOpen = arrayOfNulls<Boolean>(1)
        // 在工厂 create() 内部观察：此刻底层还没打开，状态应为 Connecting
        factory.onCreateHook = {
            observedState[0] = port.state
            observedIsOpen[0] = port.isOpen()
        }

        port.open(deviceInfo(), 115200)
        assertEquals(SerialPortBase.State.Connecting, observedState[0], "打开过程中状态应为 Connecting")
        assertEquals(false, observedIsOpen[0], "打开过程中 isOpen() 必须为 false")
        assertTrue(port.isOpen(), "打开成功后 isOpen() 必须为 true")
        port.close()
    }

    @Test
    fun open失败时基类不残留状态_句柄清理由工厂契约保证() = runBlocking {
        val factory = FakePortFactory(FakePortConnection("good"))
        factory.createFailure = { index -> if (index == 0) IOException("权限被拒") else null }
        val port = newPortWith(factory)
        val threadsBefore = serialThreadIds()

        val thrown = openExpectingFailure(port, deviceInfo(), 115200)
        assertTrue(thrown is IOException, "期望 IOException，实际 ${thrown::class.simpleName}")
        assertEquals("权限被拒", thrown.message, "工厂抛出的文案应原样透传（UI 直接展示它）")

        assertFalse(port.isOpen())
        assertEquals(SerialPortBase.State.Disconnected, port.state, "打开失败后状态必须回到 Disconnected")
        assertNull(port.device, "打开失败后不得残留设备信息")
        val err = port.lastError
        assertNotNull(err, "打开失败必须记录 lastError")
        assertTrue(err.contains("打开串口失败") && err.contains("权限被拒"), "实际错误：$err")

        assertTrue(
            serialThreadIds().all { it in threadsBefore },
            "打开失败不得残留读写线程；新增线程 id：${serialThreadIds() - threadsBefore}"
        )

        // create() 的契约：失败必须自己把半成品句柄关干净再抛（基类拿不到它，无法兜底）
        val halfBuilt = factory.halfBuilt
        assertEquals(1, halfBuilt.size, "工厂应恰好造出一个半成品句柄")
        assertEquals(1, halfBuilt[0].closeCount.get(), "半成品句柄必须被工厂自己关掉")

        // bound 行为：失败后写入必须被拒，且一个字节都不落盘
        port.write("G1 X0\n")
        assertTrue(halfBuilt[0].writes.isEmpty(), "半成品句柄不该收到任何写")
        val writeErr = port.lastError
        assertNotNull(writeErr)
        assertTrue(writeErr.contains("未打开"), "失败后写入应被拒并记录原因：$writeErr")

        // 第二次 open 必须成功（失败不得污染后续打开）
        port.open(deviceInfo("2"), 57600)
        assertTrue(port.isOpen(), "第一次失败不得污染后续打开")
        assertEquals(SerialPortBase.State.Open(57600), port.state)
        assertEquals(2, factory.createCount.get())
        assertEquals("2", factory.lastDevice?.id)
        assertEquals(57600, factory.lastBaudRate)
        port.close()
    }

    // =========================================================================
    // 2. 写串行化（§6.1 第 1 条）
    // =========================================================================

    @Test
    fun 连续两百次写的落盘顺序与调用顺序逐条一致_且没有并发写() = runBlocking {
        val fake = FakePortConnection("ordered")
        val port = newPort(fake)
        port.open(deviceInfo(), 115200)

        // 先关写门闸：如果写线程跑得比入队还快，队列根本堆不满，FIFO 被改成 LIFO 也看不出来
        fake.holdWrites()
        val expected = mutableListOf<String>()
        repeat(200) { i ->
            val line = "N$i\n"
            expected += line
            if (i % 2 == 0) port.write(line) else port.writeBytes(line.encodeToByteArray())
        }
        assertEquals(0, fake.writes.size, "门闸应挡住第一次落盘（证明队列真的堆满了）")

        fake.releaseWrites()
        fake.awaitWriteCount(200, 5_000)
        assertWritesMatch(expected, fake.writtenTexts())
        assertEquals(1, fake.maxConcurrentWriters, "同一时刻只能有一个调用者在 write() 里")
        assertFalse(fake.concurrentWriteDetected, "检测到并发写（最多 ${fake.maxConcurrentWriters} 个）")
        port.close()
    }

    @Test
    fun 四线程并发写不丢字节不交错_且每线程内保序() = runBlocking {
        val fake = FakePortConnection("threaded")
        val port = newPort(fake)
        port.open(deviceInfo(), 115200)
        fake.holdWrites()

        val threads = (0 until 4).map { t ->
            Thread({
                repeat(50) { i -> runBlocking { port.write("T$t-$i\n") } }
            }, "测试写线程-$t")
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        fake.releaseWrites()

        fake.awaitWriteCount(200, 5_000)
        val actual = fake.writtenTexts()
        assertTrue(actual.size == 200, "落盘条数应为 200，实际 ${actual.size}")

        // 每条都是完整的（没有被拆开、也没有和别的写交错）
        val pattern = Regex("^T[0-3]-\\d+\n$")
        val broken = actual.firstOrNull { !pattern.matches(it) }
        assertNull(broken, "出现被拆分/交错的写：${broken?.replace("\n", "\\n")}")

        // 不丢不重
        val expectedSet = (0 until 4).flatMap { t -> (0 until 50).map { i -> "T$t-$i\n" } }.toSet()
        assertEquals(expectedSet, actual.toSet(), "写出的内容集合与期望不一致（丢写或重写）")

        // 同一个线程内：程序顺序 = 落盘顺序（跨线程的先后本来就不该被定义）
        for (t in 0 until 4) {
            val seq = actual.filter { it.startsWith("T$t-") }.map { it.removePrefix("T$t-").trim().toInt() }
            assertEquals((0 until 50).toList(), seq, "线程 $t 的写顺序被打乱")
        }

        assertEquals(1, fake.maxConcurrentWriters, "同一时刻只能有一个调用者在 write() 里")
        assertFalse(fake.concurrentWriteDetected)
        port.close()
    }

    // =========================================================================
    // 3. 拔出 / 读结束：只通知一次
    // =========================================================================

    @Test
    fun 拔出只通知一次_重复触发不增加_重新open后重置() = runBlocking {
        val first = FakePortConnection("first")
        val second = FakePortConnection("second")
        val port = newPort(first, second)
        val notified = AtomicInteger()
        port.onClose { notified.incrementAndGet() }

        port.open(deviceInfo("1"), 115200)
        assertEquals(0, notified.get(), "刚打开不该有断开通知")

        // 模拟 USB ACTION_USB_DEVICE_DETACHED（广播线程直接调用）
        port.externalDisconnect("USB 拔出")
        assertEquals(1, notified.get(), "拔出应恰好通知一次")
        assertFalse(port.isOpen())
        assertEquals(SerialPortBase.State.Disconnected, port.state)
        assertNull(port.device)
        val err = port.lastError
        assertNotNull(err)
        assertTrue(err.contains("USB 拔出"), "实际错误：$err")

        // 同一状态下再触发一次广播：不得重复通知
        port.externalDisconnect("USB 拔出（重复广播）")
        assertEquals(1, notified.get(), "重复触发不得再次通知")

        // 重新 open 会重置 closedNotified：再拔出应再通知一次
        port.open(deviceInfo("2"), 230400)
        assertTrue(port.isOpen())
        assertEquals(1, first.closeCount.get(), "第二条连接打开前，旧连接已被释放过一次")
        port.externalDisconnect("蓝牙 ACL 断开")
        assertEquals(2, notified.get(), "重新打开后再断开应再次恰好通知一次")
        port.externalDisconnect("蓝牙 ACL 断开（重复广播）")
        assertEquals(2, notified.get())
    }

    @Test
    fun 读失败触发断开时也恰好通知一次() = runBlocking {
        val fake = FakePortConnection("read-fail").apply { readFailure = IOException("模拟读失败") }
        val port = newPort(fake)
        val notified = AtomicInteger()
        port.onClose { notified.incrementAndGet() }

        port.open(deviceInfo(), 115200)
        awaitUntil(2_000, "读线程因读异常自行断开并通知") { notified.get() >= 1 }
        assertEquals(1, notified.get(), "读异常只应通知一次")
        assertFalse(port.isOpen())
        assertEquals(SerialPortBase.State.Disconnected, port.state)

        port.externalDisconnect("再补一刀")
        assertEquals(1, notified.get(), "已经断开后再触发不得重复通知")
    }

    @Test
    fun 读返回EOF时也恰好通知一次() = runBlocking {
        val fake = FakePortConnection("eof").apply { readDefault = FakeReadDefault.RETURN_EOF }
        val port = newPort(fake)
        val notified = AtomicInteger()
        port.onClose { notified.incrementAndGet() }

        port.open(deviceInfo(), 115200)
        awaitUntil(2_000, "读线程因 EOF 自行断开并通知") { notified.get() >= 1 }
        assertEquals(1, notified.get(), "EOF 只应通知一次")
        port.externalDisconnect("再补一刀")
        assertEquals(1, notified.get())
    }

    // =========================================================================
    // 4. close() 幂等 / 手动 close 的通知语义
    // =========================================================================

    @Test
    fun close幂等_连续三次不抛异常且连接只关一次() {
        val fake = FakePortConnection("idem")
        val port = newPort(fake)
        runBlocking {
            port.open(deviceInfo(), 115200)

            port.close()
            port.close()
            port.close()
        }

        assertFalse(port.isOpen())
        assertEquals(SerialPortBase.State.Disconnected, port.state)
        assertNull(port.device)
        assertEquals(1, fake.closeCount.get(), "底层连接的 close 必须恰好一次")

        // 关闭后写入应被丢弃并记录原因，而不是抛异常
        runBlocking { port.write("G1 X0\n") }
        assertEquals(0, fake.writes.size)
        val err = port.lastError
        assertNotNull(err)
        assertTrue(err.contains("未打开"), "实际错误：$err")
    }

    @Test
    fun 手动close不触发closed回调_即使读线程正阻塞在read里() = runBlocking {
        val fake = FakePortConnection("blocked-read")
        val port = newPort(fake)
        val notified = AtomicInteger()
        port.onClose { notified.incrementAndGet() }

        port.open(deviceInfo(), 115200)
        assertTrue(fake.awaitFirstReadEntered(), "读线程应已进入 read()")
        assertEquals(1, fake.readCallCount.get(), "读线程应阻塞在第一次 read() 里（脚本为空 + 默认阻塞）")

        port.close()
        assertEquals(
            0,
            notified.get(),
            "SerialPortBase.close 的 KDoc 承诺「手动 close 不触发 closed 回调」（v2 用 reading 标志位保证同一件事）：" +
                "close 唤醒读线程后，读线程不得把这次 EOF 误判成拔出"
        )
    }

    @Test
    fun 并发close只关一次连接且不重复通知() {
        val fake = FakePortConnection("concurrent-close")
        val port = newPort(fake)
        val notified = AtomicInteger()
        port.onClose { notified.incrementAndGet() }
        runBlocking { port.open(deviceInfo(), 115200) }

        // 四个线程同时 close()：`closing` 幂等闸门必须只让一个跑完 teardown
        val threads = (0 until 4).map { i -> Thread({ runBlocking { port.close() } }, "并发关闭-$i") }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        assertEquals(1, fake.closeCount.get(), "并发 close 只允许关一次底层连接")
        assertFalse(port.isOpen())
        assertEquals(SerialPortBase.State.Disconnected, port.state)
        assertNull(port.device)
        assertEquals(0, notified.get(), "手动 close 不触发 closed 回调（并发也一样）")

        // 关完之后再 close 一次仍然是幂等的
        runBlocking { port.close() }
        assertEquals(1, fake.closeCount.get())
    }

    @Test
    fun 拔出与手动close并发时通知不超过一次() = runBlocking {
        val fake = FakePortConnection("close-vs-detach")
        val port = newPort(fake)
        val notified = AtomicInteger()
        port.onClose { notified.incrementAndGet() }
        port.open(deviceInfo(), 115200)

        // 「用户点断开」与「USB 拔出广播」同时发生：两条路径只能关一次、最多通知一次
        val closer = Thread({ runBlocking { port.close() } }, "并发-手动close")
        val detacher = Thread({ port.externalDisconnect("USB 拔出") }, "并发-拔出广播")
        closer.start()
        detacher.start()
        closer.join(10_000)
        detacher.join(10_000)

        assertEquals(1, fake.closeCount.get(), "两条关闭路径加起来只能关一次底层连接")
        assertFalse(port.isOpen())
        assertEquals(SerialPortBase.State.Disconnected, port.state)
        assertTrue(notified.get() <= 1, "closed 回调最多一次，实际 ${notified.get()} 次")
    }

    // =========================================================================
    // 5. 关闭前排空写队列
    // =========================================================================

    @Test
    fun close前会排空写队列_五百条一条不掉() = runBlocking {
        // 1 ms/条 ⇒ close 被调用时队列里必然还剩大量未落盘字节，这样「排空」才是真的被验证到
        val fake = FakePortConnection("flush").apply { writeDelayMillis = 1 }
        val port = newPort(fake)
        port.open(deviceInfo(), 115200)

        // 先用门闸把队列**堆满**（写线程一条都没落盘），再放行并立刻 close
        fake.holdWrites()
        val expected = (0 until 500).map { "G1 X$it\n" }
        for (line in expected) port.write(line)
        assertEquals(0, fake.writes.size, "门闸应挡住全部落盘（证明队列真的堆满了）")

        fake.releaseWrites()
        port.close()
        assertTrue(
            fake.writes.size == 500,
            "close 前必须排空写队列（不掉尾）：实际只落盘 ${fake.writes.size}/500 条，" +
                "丢弃是静默的（lastError=${port.lastError}）"
        )
        assertWritesMatch(expected, fake.writtenTexts())
        assertNull(port.lastError, "正常排空不该记录任何错误")
    }

    @Test
    fun close会等待慢写落盘_不丢尾() = runBlocking {
        val fake = FakePortConnection("slow-flush").apply { writeDelayMillis = 2 }
        val port = newPort(fake)
        port.open(deviceInfo(), 115200)

        val expected = (0 until 40).map { "M$it\n" }
        for (line in expected) port.write(line)

        port.close()
        assertTrue(
            fake.writes.size == 40,
            "慢写场景下 close 也必须等到全部落盘：实际 ${fake.writes.size}/40 条，lastError=${port.lastError}"
        )
        assertWritesMatch(expected, fake.writtenTexts())
    }

    @Test
    fun 排空超时_丢弃剩余并记录错误() = runBlocking {
        val fake = FakePortConnection("stuck")
        val port = newPortWith(FakePortFactory(fake), flushTimeoutMillis = 200)
        port.open(deviceInfo(), 115200)
        fake.holdWrites()
        repeat(3) { port.write("DROP-$it\n") }

        try {
            port.close()
            assertEquals(0, fake.writes.size, "门闸没放行，一条都不该落盘")
            assertFalse(port.isOpen())
            assertEquals(SerialPortBase.State.Disconnected, port.state)
            val err = port.lastError
            assertNotNull(err, "排空超时必须记录错误（有界排空是有代价的，必须可见）")
            assertTrue(err.contains("丢弃未落盘字节"), "实际错误：$err")
        } finally {
            fake.releaseWrites()
        }
    }

    // =========================================================================
    // 6. 打开新连接前先释放旧连接
    // =========================================================================

    @Test
    fun 连续open会先释放旧连接_写只落到新连接() = runBlocking {
        val first = FakePortConnection("first")
        val second = FakePortConnection("second")
        val factory = FakePortFactory(first, second)
        val port = newPortWith(factory)

        port.open(deviceInfo("1"), 115200)
        port.write("A\n")
        first.awaitWriteCount(1)

        port.open(deviceInfo("2"), 230400)
        assertEquals(2, factory.createCount.get())
        assertEquals(1, first.closeCount.get(), "旧连接必须先被释放")
        assertTrue(first.closed, "旧连接的 closed 标志应为 true")
        assertEquals(230400, factory.lastBaudRate)

        port.write("B\n")
        second.awaitWriteCount(1)
        assertEquals(listOf("A\n"), first.writtenTexts(), "旧连接不该再收到写")
        assertEquals(listOf("B\n"), second.writtenTexts(), "新连接应收到重新打开后的写")
        port.close()
    }

    // =========================================================================
    // 7. 等待语义：read 返回 0 不得忙等
    // =========================================================================

    @Test
    fun read返回0时不忙等() = runBlocking {
        val fake = FakePortConnection("zero").apply { readDefault = FakeReadDefault.RETURN_ZERO }
        val port = newPort(fake)
        port.open(deviceInfo(), 115200)

        awaitUntil(2_000, "读线程进入空转循环") { fake.readCallCount.get() >= 3 }
        val before = fake.readCallCount.get()
        Thread.sleep(50)
        val calls = fake.readCallCount.get() - before

        // 默认退让 2 ms ⇒ 50 ms 内约 25 次；阈值放到 1000 只是防「完全不退让」的忙等
        assertTrue(calls < 1_000, "50 ms 内 read 被调用 $calls 次，疑似忙等烧 CPU")
        assertTrue(calls >= 2, "读线程似乎停了（50 ms 内只调用了 $calls 次）")
        port.close()
    }

    // =========================================================================
    // 8. UTF-8 跨块拼接（端到端：假连接 → 读线程 → 增量解码器 → onData）
    // =========================================================================

    @Test
    fun UTF8跨块拼接_逐字节喂入_中文与度数符号不得出现替换符() = runBlocking {
        val fake = FakePortConnection("utf8")
        val port = newPort(fake)
        val chunks = CopyOnWriteArrayList<String>()
        port.onData { chunks.add(it) }

        port.open(deviceInfo(), 115200)
        val text = "温度: 25°C\n"
        fake.scriptBytesOneByOne(text.encodeToByteArray())
        fake.scriptEof()

        awaitUntil(2_000, "逐字节喂入后收到完整文本") { chunks.joinToString("").length >= text.length }
        assertEquals(text, chunks.joinToString(""), "onData 拼接结果必须逐字符还原")
        assertFalse(chunks.any { it.contains('\uFFFD') }, "跨块拼接不得出现替换符：${chunks.toList()}")
    }

    @Test
    fun 数据回调抛异常不会杀死读线程() = runBlocking {
        val fake = FakePortConnection("callback")
        val port = newPort(fake)
        val received = CopyOnWriteArrayList<String>()
        val first = AtomicInteger()
        port.onData { chunk ->
            if (first.getAndIncrement() == 0) throw IllegalStateException("模拟 UI 回调抛异常")
            received.add(chunk)
        }

        port.open(deviceInfo(), 115200)
        fake.scriptText("ok\n")
        fake.scriptText("ok2\n")

        awaitUntil(2_000, "第二块数据仍被处理") { received.isNotEmpty() }
        assertEquals(listOf("ok2\n"), received.toList(), "回调抛异常不得让读线程退出")
        val err = port.lastError
        assertNotNull(err, "回调异常应被记录")
        assertTrue(err.contains("数据回调抛异常"), "实际错误：$err")
        port.close()
    }

    // =========================================================================
    // 工具
    // =========================================================================

    /** 被测类的测试子类：暴露 protected 扩展点，并把回调计数搬到测试里。 */
    private class TestPort(
        factory: PortConnectionFactory,
        readIdleDelayMillis: Long = 2L,
        flushTimeoutMillis: Long = 2_000L
    ) : SerialPortBase(TransportKind.Usb, factory, readIdleDelayMillis, flushTimeoutMillis) {

        /** `onOpened` 被调用的次数。 */
        val onOpenedCount = AtomicInteger()

        /** `onClosed` 被调用的次数（KDoc 要求子类实现必须幂等，所以只观测不断言恰好一次）。 */
        val onClosedCount = AtomicInteger()

        override suspend fun list(): List<SerialDeviceInfo> = emptyList()

        override fun onOpened(device: SerialDeviceInfo) {
            onOpenedCount.incrementAndGet()
        }

        override fun onClosed() {
            onClosedCount.incrementAndGet()
        }

        /** 模拟 Android 广播线程收到拔出/断开事件（USB 拔出 / 蓝牙 ACL 断开）。 */
        fun externalDisconnect(reason: String = "测试：设备拔出") = handleExternalDisconnect(reason)
    }

    private fun newPort(vararg fakes: FakePortConnection): TestPort =
        newPortWith(FakePortFactory(fakes.toList()))

    private fun newPortWith(
        factory: FakePortFactory,
        readIdleDelayMillis: Long = 2L,
        flushTimeoutMillis: Long = 2_000L
    ): TestPort {
        val port = TestPort(factory, readIdleDelayMillis, flushTimeoutMillis)
        createdPorts.add(port)
        return port
    }

    private fun deviceInfo(id: String = "1"): SerialDeviceInfo = SerialDeviceInfo(
        id = id,
        kind = TransportKind.Usb,
        name = "假激光 $id",
        deviceId = id.toDoubleOrNull(),
        vendor = "QinHeng",
        product = "CH340",
        vendorId = 0x1A86.toDouble(),
        productId = 0x7523.toDouble()
    )

    /**
     * 断言 `open()` 抛异常并把它取出来。
     *
     * 不用 `assertFailsWith`：它的 lambda 参数是非挂起类型，包住 `open()` 这种挂起调用会编译不过，
     * 而这里必须精确拿到异常对象来检查消息。
     */
    private suspend fun openExpectingFailure(
        port: TestPort,
        device: SerialDeviceInfo = deviceInfo(),
        baudRate: Int = 115200
    ): Throwable {
        try {
            port.open(device, baudRate)
        } catch (t: Throwable) {
            return t
        }
        throw AssertionError("期望 open() 抛异常，但它成功了（state=${port.state}）")
    }

    /** 逐条比对落盘内容；失败信息只打第一条不一致的，避免把 500 行刷进日志。 */
    private fun assertWritesMatch(expected: List<String>, actual: List<String>) {
        assertTrue(
            actual.size == expected.size,
            "落盘条数不符：期望 ${expected.size}，实际 ${actual.size}"
        )
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                throw AssertionError(
                    "第 ${i + 1} 条落盘内容不符：期望「${expected[i].trim()}」，实际「${actual[i].trim()}」"
                )
            }
        }
    }

    /** 当前进程里活着的串口读写线程 id（用于断言「打开失败不残留线程」）。 */
    private fun serialThreadIds(): Set<Long> =
        Thread.getAllStackTraces().keys
            .filter { it.name.startsWith("SerialPort-writer-") || it.name.startsWith("SerialPort-reader-") }
            .map { it.id }
            .toSet()
}
