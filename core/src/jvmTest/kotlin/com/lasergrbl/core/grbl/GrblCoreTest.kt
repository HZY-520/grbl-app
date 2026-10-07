package com.lasergrbl.core.grbl

import com.lasergrbl.core.golden.Golden
import com.lasergrbl.core.golden.asObject
import com.lasergrbl.core.serial.SerialTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GrblCore` 的行为测试 —— 假串口 + 假时钟 + 手动驱动 `txTick()`，不依赖真实时间。
 *
 * 背景：v2 的 GrblCore 没有黄金样本（`GrblCore.ts` 在被 import 时就会构造 Capacitor 传输层，
 * Node 里跑不起来），所以这条链路的判据只能是「按 TS 源码逐行对齐的行为断言」。本文件覆盖：
 * 行装配（跨分块）、4096 守卫、字节预算（`usedBuffer`/`autoBufferSize`/`[OPT:]`/`Bf:`）、
 * 三种流式模式（含 RepeatOnError 的 3 次上限）、覆盖倍率字节阶梯、v1.1 与 v0.9 两种状态方言、
 * 握手时序与 10 s 连接超时、版本 banner（对接 `grbl-version` 黄金样本）、配置读写、
 * 点动/移动拼装、卡死检测、断开判定。
 *
 * 驱动方式（全部走 `runTest` 的虚拟时间）：
 *  * 握手等待压成 0（[HandshakeDelays.Immediate]），握手在虚拟时间 t=0 完成；
 *  * 关掉自动发送循环（`autoTxLoop = false`），由测试调用 `core.txTick()` ——
 *    这是本次移植**唯一**有意放开的可见性（TS 里 `txTick` 是私有的，只被 `setInterval` 驱动）；
 *  * `FakeTransport.feedSync(...)` 触发 `onData`，模拟串口分块到达；
 *  * `txScope = backgroundScope`（与测试同一个 TestDispatcher），所以 `runCurrent()` 会把
 *    tick 里 fire-and-forget 的 `transport.write(...)` 真正刷出去。
 *
 * 需要**真机**才能验证的东西（这里没有、也不该假装有）：
 *  * 真实串口的时序（4 ms 节拍在 Android 上是否跟得上、蓝牙 SPP 的吞吐）；
 *  * 真机 `[OPT:]` / `Bf:` 的实际取值与缓冲语义；
 *  * 软复位 0x18 之后真实固件的启动时间（v2 固定等 400 ms 是否够）；
 *  * USB/蓝牙的 open/close/list 行为本身。
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy / runCurrent
class GrblCoreTest {

    // =========================================================================
    // 1. 行装配：跨分块的半行 / 半个 "ok"
    // =========================================================================

    @Test
    fun lineAssemblySurvivesChunkBoundaries() = runTest {
        val (core, tr) = openCore()
        core.setStreamingMode(StreamingMode.Synchronous)
        core.enqueueRaw("G1 X0 Y0") // serialData = "G1X0Y0\n"，8 字节
        core.enqueueRaw("G1 X5 Y0") // 8 字节

        core.txTick()
        runCurrent()
        assertContentEquals(listOf("G1X0Y0\n"), tr.writes)
        assertEquals(1, core.pendingCount)

        // "ok\r\n" 被劈成 ["ok\r", "\n"]：应答在第二块才成形
        tr.feedSync(this, "ok\r")
        assertEquals(1, core.pendingCount, "只有 \\r 时不该产生应答")

        tr.feedSync(this, "\n")
        assertEquals(0, core.pendingCount)
        assertEquals(0, core.bufferUsed)
        assertEquals(CommandStatus.ResponseGood, core.lastCommandStatus?.status)
        // 手动 enqueueRaw 的命令不在任务里，v2 只在 inProgram 时累加 executed
        assertEquals(0, core.progress.executed)

        // "ok" 本身被劈在中间
        tr.feedSync(this, "o")
        assertEquals(1, tr.writes.size, "还没 tick，不该发出第二条")
        tr.feedSync(this, "k\r\n")
        assertEquals(0, core.pendingCount, "\"o\" + \"k\\r\\n\" 只算一次应答")

        core.txTick()
        runCurrent()
        assertEquals("G1X5Y0\n", tr.writes.last())
        assertEquals(1, core.pendingCount)

        // 一个块里装配出两行应答
        tr.feedSync(this, "ok\nok\n")
        assertEquals(0, core.pendingCount)
    }

    @Test
    fun multipleLinesInOneChunkAllGetProcessed() = runTest {
        val (core, tr) = openCore()
        core.setStreamingMode(StreamingMode.Buffered)
        core.enqueueRaw("G0") // "G0\n" = 3 字节
        core.enqueueRaw("M5") // 3 字节

        core.txTick()
        runCurrent()
        core.txTick()
        runCurrent()
        assertEquals(2, core.pendingCount, "两条都在等应答")
        assertContentEquals(listOf("G0\n", "M5\n"), tr.writes)

        tr.feedSync(this, "ok\nok\n")
        assertEquals(0, core.pendingCount, "一个块里的两行都被处理")
        assertEquals(0, core.bufferUsed)
    }

    @Test
    fun noNewlineChunkStaysInRxBuffer() = runTest {
        val (core, tr) = openCore()
        core.enqueueRaw("G1 X0 Y0")
        core.txTick()
        runCurrent()
        assertEquals(1, core.pendingCount, "先建立一条等待应答的命令")

        tr.feedSync(this, "B")
        tr.feedSync(this, "usy")
        assertEquals(1, core.pendingCount, "没有换行就不算一行")
        assertEquals("Busy", core.pendingRx(), "半行要留在装配缓冲里")
    }

    @Test
    fun blankLinesAreIgnored() = runTest {
        val (core, tr) = openCore()
        core.enqueueRaw("G1 X0 Y0")
        core.txTick()
        runCurrent()

        tr.feedSync(this, "\r\n   \n\t\n")
        assertEquals(1, core.pendingCount, "空行不该被当成应答")
        assertEquals(0, core.pendingRx().length)
    }

    // =========================================================================
    // 2. 4096 字节守卫：无换行时截断到「最后 1024 字节」
    // =========================================================================

    @Test
    fun rxBufferGuardTruncatesToLast1024Bytes() = runTest {
        val (core, tr) = openCore()
        core.enqueueRaw("G1 X0 Y0")
        core.txTick()
        runCurrent()

        // 4096 字节：v2 是 `if (this.rxBuffer.length > 4096)`，等于 4096 不触发
        tr.feedSync(this, "A".repeat(RX_BUFFER_GUARD))
        assertEquals(RX_BUFFER_GUARD, core.pendingRx().length, "4096 不触发守卫")

        // 再来 1000 → 5096 > 4096 → 只保留最后 1024 字节
        val tail = "B".repeat(1000)
        tr.feedSync(this, tail)
        assertEquals(RX_BUFFER_KEEP, core.pendingRx().length, "超过 4096 后只留最后 1024 字节")

        val kept = core.pendingRx()
        assertEquals("A".repeat(24), kept.substring(0, 24), "保留的是末尾 1024 字节：前面是旧的 A")
        assertEquals(tail, kept.substring(24), "后面 1000 个是新喂进来的 B")
        assertEquals(1, core.pendingCount, "期间没有换行，命令一直挂着")
    }

    @Test
    fun oversizedChunkWithNewlineYieldsItsLines() = runTest {
        val (core, tr) = openCore()
        core.enqueueRaw("G1 X0 Y0")
        core.txTick()
        runCurrent()

        val chunk = buildString {
            repeat(3000) { append('x') }
            append("\nok\n")
        }
        tr.feedSync(this, chunk)
        assertTrue(core.pendingRx().isEmpty(), "含换行的超长块要全部消费掉")
        assertEquals(0, core.pendingCount, "超长行仍然能完成应答")
    }

    // =========================================================================
    // 3. 字节预算：usedBuffer / autoBufferSize / [OPT:] / Bf:
    // =========================================================================

    @Test
    fun commandIsSentOnlyWhenBufferAllows() = runTest {
        val (core, tr) = openCore()
        core.setStreamingMode(StreamingMode.Synchronous)
        core.enqueueRaw("G1 X0 Y0") // "G1X0Y0\n" = 7 字节

        core.txTick()
        runCurrent()
        assertContentEquals(listOf("G1X0Y0\n"), tr.writes)
        assertEquals(1, core.pendingCount)
        assertEquals(7, core.bufferUsed, "发送后按 serialData.length 记账")

        // 等应答回来后再测「放不下」的情况
        tr.feedSync(this, "ok\n")
        assertEquals(0, core.bufferUsed)

        // 130 字节的命令 > 127 → 发不出去，并且**卡住队列**（GRBL 命令是有序的）
        core.enqueueRaw("G1 X0 Y0 " + "1".repeat(121))
        core.enqueueRaw("M5")
        core.txTick()
        runCurrent()
        assertTrue(tr.writes.none { it.startsWith("G1X0Y0" + "1") }, "130 > 127：队首命令发不出去")
        assertEquals(1, tr.writes.size, "整个 tick 什么都没发")
        assertEquals(0, core.pendingCount, "什么都没发出去")
        assertEquals(2, core.queueCount, "两条都还在队列里")
        assertEquals(0, core.bufferUsed, "没有记账")

        // 换一条短命令：7 字节的命令在 127 的缓冲里放得下，之后还能继续塞
        val (coreShort, trShort) = openCore()
        coreShort.enqueueRaw("G1 X0 Y0")
        coreShort.txTick()
        runCurrent()
        assertEquals(7, coreShort.bufferUsed)
        assertEquals("G1X0Y0\n", trShort.writes.last())

        coreShort.txTick()
        runCurrent()
        assertEquals(7, coreShort.bufferUsed, "队列空了，不会重复发")
    }

    @Test
    fun bufferIsCreditedBackOnOkAndOnError() = runTest {
        val (core, tr) = openCore()
        core.enqueueRaw("G1 X0 Y0")
        core.txTick()
        runCurrent()
        assertEquals(7, core.bufferUsed)
        assertEquals(1, core.pendingCount)

        tr.feedSync(this, "ok\n")
        assertEquals(0, core.bufferUsed, "ok 之后按命令长度退回")
        assertEquals(0, core.pendingCount)

        core.enqueueRaw("G1 X5 Y0")
        core.txTick()
        runCurrent()
        assertEquals(7, core.bufferUsed)

        tr.feedSync(this, "error:20\n")
        assertEquals(0, core.bufferUsed, "error 同样退回（否则缓冲会永久泄漏）")
        assertEquals(0, core.pendingCount)
        assertEquals(CommandStatus.ResponseBad, core.lastCommandStatus?.status)
    }

    @Test
    fun defaultBufferIs127UntilOptMessageOverridesIt() = runTest {
        val (core, tr) = openCore()
        assertEquals(DEFAULT_BUFFER_SIZE, core.bufferSize, "默认 127 字节")
        assertEquals(127, DEFAULT_BUFFER_SIZE)

        // 130 字节的命令在默认缓冲里放不下
        core.enqueueRaw("G1 X0 Y0 " + "1".repeat(121))
        core.txTick()
        runCurrent()
        assertTrue(tr.writes.isEmpty(), "130 > 127：默认缓冲下发不出去")

        // [OPT:VZ,15,128] 的第三个字段直接改写缓冲上限（force = true）
        tr.feedSync(this, "[OPT:VZ,15,128]\n")
        assertEquals(128, core.bufferSize, "[OPT:] 无条件采纳第三个字段")

        core.txTick()
        runCurrent()
        assertContentEquals(listOf("G1X0Y0" + "1".repeat(121) + "\n"), tr.writes, "128 字节上限下就能发了")

        // 第二个实例：换成 [OPT:…,129] 也会被采纳（force 不看白名单）
        val (core2, tr2) = openCore()
        tr2.feedSync(this, "[OPT:VZ,15,129]\n")
        assertEquals(129, core2.bufferSize)
    }

    @Test
    fun bfMessageOnlyAdoptsWhitelistedSizes() = runTest {
        val (core, tr) = openCore()
        tr.feedSync(this, "<Run|MPos:0.000,0.000,0.000|Bf:15,128>\n")
        assertEquals(128, core.bufferSize, "Bf:…,128 在白名单里 → 采纳")
        assertEquals(15, core.grblBlocks, "Bf 的第一个字段是规划器块数")

        // autoBufferSize 已经不是默认值了，后面的 Bf 不再改动它
        tr.feedSync(this, "<Run|MPos:0.000,0.000,0.000|Bf:15,254>\n")
        assertEquals(128, core.bufferSize, "autoBufferSize 非默认值时不再自动改")

        // 全新实例：非白名单的值（129）忽略，保持默认 127
        val (core2, tr2) = openCore()
        tr2.feedSync(this, "<Run|MPos:0.000,0.000,0.000|Bf:0,129>\n")
        assertEquals(DEFAULT_BUFFER_SIZE, core2.bufferSize, "129 不在白名单里")

        tr2.feedSync(this, "<Run|MPos:0.000,0.000,0.000|Bf:0,10240>\n")
        assertEquals(10240, core2.bufferSize, "10240 在白名单里")
    }

    // =========================================================================
    // 4. 三种流式模式
    // =========================================================================

    @Test
    fun bufferedKeepsSeveralCommandsInFlight() = runTest {
        val (core, tr) = openCore()
        core.setStreamingMode(StreamingMode.Buffered)
        // v2 的 txTick 是 `if (this.canSend()) this.sendLine()` —— 一次 tick 只调一次 sendLine，
        // 所以「流水线」是跨 tick 体现的：只要缓冲放得下，下一个 4 ms 节拍就继续发。
        core.enqueueRaw("G0") // "G0\n" = 3 字节
        core.enqueueRaw("M5") // 3 字节

        core.txTick()
        runCurrent()
        assertContentEquals(listOf("G0\n"), tr.writes, "第一个 tick 发队首")
        assertEquals(1, core.pendingCount)
        assertEquals(3, core.bufferUsed)

        core.txTick()
        runCurrent()
        assertContentEquals(listOf("G0\n", "M5\n"), tr.writes, "第二个 tick 继续发（3 + 3 ≤ 127）")
        assertEquals(2, core.pendingCount, "两条都在等应答")
        assertEquals(6, core.bufferUsed, "两条都记账")
    }

    @Test
    fun bufferedRespectsBufferBudgetWithinOneTick() = runTest {
        val (core, tr) = openCore()
        core.setStreamingMode(StreamingMode.Buffered)
        core.enqueueRaw("G1 X0 Y0") // "G1X0Y0\n" = 7 字节
        core.enqueueRaw("G1 X5 Y0") // 7 字节

        core.txTick()
        runCurrent()
        assertEquals(1, tr.writes.size, "一个 tick 只发一条（sendLine 只调一次）")
        assertEquals(7, core.bufferUsed)

        core.txTick()
        runCurrent()
        assertEquals(2, tr.writes.size, "下一个 tick 再发第二条（7 + 7 = 14 ≤ 127）")
        assertEquals(14, core.bufferUsed)
    }

    @Test
    fun synchronousSendsOneCommandAtATime() = runTest {
        val (core, tr) = openCore()
        core.setStreamingMode(StreamingMode.Synchronous)
        core.enqueueRaw("G1 X0 Y0")
        core.enqueueRaw("G1 X5 Y0")

        core.txTick()
        runCurrent()
        assertContentEquals(listOf("G1X0Y0\n"), tr.writes, "Synchronous：pending 非空就不再发")
        assertEquals(1, core.pendingCount)
        assertEquals(1, core.queueCount)

        core.txTick()
        runCurrent()
        assertEquals(1, tr.writes.size, "还在等第一条的应答")

        tr.feedSync(this, "ok\n")
        assertEquals(0, core.pendingCount)

        core.txTick()
        runCurrent()
        assertEquals("G1X5Y0\n", tr.writes.last())
        assertEquals(1, core.pendingCount)
    }

    @Test
    fun repeatOnErrorPipelinesLikeBuffered() = runTest {
        val (core, tr) = openCore()
        core.setStreamingMode(StreamingMode.RepeatOnError)
        // 注意 runProgram 会清空队列（resetBufferAccounting 默认 true），所以命令要给它
        core.runProgram(listOf(GrblCommand("G0"), GrblCommand("M5")))

        core.txTick()
        runCurrent()
        assertContentEquals(listOf("G0\n"), tr.writes, "第一个 tick 发队首")
        assertEquals(1, core.pendingCount)

        // ⚠️ 函数名是历史叫法，v2 的实际行为相反：TS 327 的非 Buffered 分支要求
        // `pending.length === 0` 才发下一条 —— RepeatOnError 与 Synchronous 一样是**一条一条来**，
        // 只有 Buffered（TS 326）才能同时压多条。
        core.txTick()
        runCurrent()
        assertEquals(1, tr.writes.size, "还在等 G0 的应答，第二条发不出去（TS 327）")
        assertEquals(1, core.queueCount, "M5 还在队列里")

        tr.feedSync(this, "ok\n")
        core.txTick()
        runCurrent()
        assertEquals("M5\n", tr.writes.last(), "应答回来之后才发下一条")
        assertEquals(1, core.pendingCount)
    }

    @Test
    fun errorResponseRepeatsOnlyInRepeatOnErrorMode() = runTest {
        // ---- Buffered：error 不重发 ----
        val (coreBuffered, trBuffered) = openCore()
        coreBuffered.setStreamingMode(StreamingMode.Buffered)
        coreBuffered.runProgram(listOf(GrblCommand("G1 X0 Y0")))
        coreBuffered.txTick()
        runCurrent()
        assertEquals(1, trBuffered.writes.size)

        trBuffered.feedSync(this, "error:20\n")
        coreBuffered.txTick()
        runCurrent()
        assertEquals(1, trBuffered.writes.size, "Buffered：error 不会重发")
        // v2 只看 repeatCount：首发（repeatCount == 0）无论成败都 +1
        assertEquals(1, coreBuffered.progress.executed, "首发那一条计入了 executed")
        assertEquals(DetectedIssue.MachineAlarm, coreBuffered.lastIssue)
        assertTrue(!coreBuffered.inProgram, "队列空了 → 任务结束")

        // ---- RepeatOnError：error 会重发 ----
        val (coreRepeat, trRepeat) = openCore()
        coreRepeat.setStreamingMode(StreamingMode.RepeatOnError)
        coreRepeat.runProgram(listOf(GrblCommand("G1 X0 Y0")))
        coreRepeat.txTick()
        runCurrent()
        assertEquals(1, trRepeat.writes.size)

        trRepeat.feedSync(this, "error:20\n")
        coreRepeat.txTick()
        runCurrent()
        assertContentEquals(
            listOf("G1X0Y0\n", "G1X0Y0\n"),
            trRepeat.writes,
            "RepeatOnError：error 之后重发同一条命令"
        )
        assertEquals(1, coreRepeat.pendingCount, "重发的那条在等应答")
        assertEquals(DetectedIssue.MachineAlarm, coreRepeat.lastIssue, "任务中的失败命令 → MachineAlarm")
        assertEquals(1, coreRepeat.progress.executed, "首发计入；重试（repeatCount > 0）不计")
    }

    @Test
    fun repeatOnErrorCapsRepeatsAtThree() = runTest {
        val (core, tr) = openCore()
        core.setStreamingMode(StreamingMode.RepeatOnError)
        val repeatCounts = mutableListOf<Int>()
        core.onCommandStatus.on { repeatCounts.add(it.repeatCount) }

        // ⚠️ 队列里必须**留着第二条命令**，否则 TS 403-405 会在排入重试的同一刻就 programEnd
        // （inProgram 变 false → 后续 error 不再排重试）。那种「单命令任务只重发一次」的行为
        // 由 repeatOnErrorProgramEndsAfterFirstRetryRound 覆盖。
        core.runProgram(listOf(GrblCommand("G1 X0 Y0"), GrblCommand("M5")))
        repeat(4) {
            core.txTick()
            runCurrent()
            tr.feedSync(this, "error:20\n")
        }

        // v2 的上限是「repeatCount < 3 才排重试」：首发(0) + 重试(1,2,3) = 4 条
        assertContentEquals(
            listOf("G1X0Y0\n", "G1X0Y0\n", "G1X0Y0\n", "G1X0Y0\n"),
            tr.writes,
            "首发 + 最多 3 次重发（TS 395）"
        )
        assertEquals(listOf(0, 1, 2, 3), repeatCounts, "重试计数 0→1→2→3，之后不再重发")
        assertEquals(0, core.pendingCount)
        // v2：只要 repeatCount == 0 就 +1（**不看成功失败**），重试的那几次不计
        assertEquals(1, core.progress.executed, "首发计一次；3 次重试不计")
        assertTrue(core.inProgram, "队列里还有 M5，任务还没结束")

        core.txTick()
        runCurrent()
        assertEquals("M5\n", tr.writes.last(), "重试上限用尽后才轮到 M5")

        tr.feedSync(this, "ok\n")
        assertTrue(!core.inProgram, "最后一条 ok 之后队列与 in-flight 都空了 → programEnd（TS 403-405）")
        assertEquals(2, core.progress.executed)
    }

    @Test
    fun repeatOnErrorProgramEndsAfterFirstRetryRound() = runTest {
        // ⚠️ v2 的一个真实行为（照抄，没有"修正"）：
        // 重试命令放在 `retryQueue` 里，而 `manageCommandResponse` 判断任务结束看的是
        // `queue` 与 `pending` —— 于是第一条命令失败并排入重试队列的那一刻，
        // `onProgramEnd()` 就会被触发（`retryQueue` 不在判断范围内），inProgram 变 false。
        // 下一次 tick 仍然会把 retryQueue 发出去（peekNext 不看 inProgram），
        // 但那条命令的应答不会再触发重发，随后任务就真的结束了。
        val (core, tr) = openCore()
        core.setStreamingMode(StreamingMode.RepeatOnError)
        core.runProgram(listOf(GrblCommand("G1 X0 Y0")))

        var ended = 0
        core.onProgramEnd.on { ended++ }

        core.txTick()
        runCurrent()
        assertEquals(1, tr.writes.size)

        tr.feedSync(this, "error:20\n")
        assertEquals(1, ended, "排入重试队列的同时就报了 programEnd")
        assertTrue(!core.inProgram)

        core.txTick()
        runCurrent()
        assertEquals(2, tr.writes.size, "重试仍然会被发出去")

        tr.feedSync(this, "error:20\n")
        core.txTick()
        runCurrent()
        assertEquals(2, tr.writes.size, "inProgram 已经是 false → 不再重发")
        assertEquals(1, ended, "不会重复报 programEnd")
    }

    @Test
    fun errorOutsideProgramIsNotRepeated() = runTest {
        val (core, tr) = openCore()
        core.setStreamingMode(StreamingMode.RepeatOnError)
        core.enqueueRaw("G1 X0 Y0") // 手动命令（inProgram = false）

        core.txTick()
        runCurrent()
        tr.feedSync(this, "error:20\n")
        core.txTick()
        runCurrent()

        assertEquals(1, tr.writes.size, "v2 的重发条件要求 inProgram")
    }

    @Test
    fun programEndAfterLastResponse() = runTest {
        val (core, tr) = openCore()
        core.setStreamingMode(StreamingMode.Synchronous)
        core.runProgram(listOf(GrblCommand("G1 X0 Y0"), GrblCommand("M5")))

        var ended = 0
        core.onProgramEnd.on { ended++ }
        assertEquals(2, core.progress.total)
        assertEquals(0, core.progress.executed)

        core.txTick()
        runCurrent()
        core.txTick()
        runCurrent()
        assertEquals(1, tr.writes.size, "Synchronous 下第二条要等第一条的 ok")

        tr.feedSync(this, "ok\n")
        assertEquals(0, ended)
        assertEquals(1, core.progress.executed)

        core.txTick()
        runCurrent()
        tr.feedSync(this, "ok\n")
        assertEquals(1, ended, "队列与 in-flight 都空了 → programEnd")
        assertEquals(2, core.progress.executed)
        assertTrue(!core.inProgram)
    }

    // =========================================================================
    // 5. 实时覆盖倍率的字节阶梯
    // =========================================================================

    @Test
    fun feedOverrideLadderMatchesGrblBytes() = runTest {
        // openCore 之后机器侧与目标侧都是 100；每个用例用 armOverrides 把机器侧摆到指定值，
        // 再把目标复位到 100，验证这一档会发哪个字节。
        // ⚠️ TS 753：目标 === 100 且机器 !== 100 时**第一档就是 0x90（RESET）**，
        // 它优先于「差 ≥10 → 0x91」「差 ≥1 → 0x93」那些档位 —— 所以机器侧 90 / 95 的结果
        // 同样是 0x90，而不是 0x91 / 0x93。
        val cases = listOf(
            100 to 0, // 已经在 100 → 不发（"档位一致"分支）
            90 to Rt.FEED_RESET, // 目标 100 → 0x90（不是 0x91）
            95 to Rt.FEED_RESET, // 目标 100 → 0x90（不是 0x93）
            0 to Rt.FEED_RESET // 从 0 复位到 100 → 0x90
        )
        for ((current, expected) in cases) {
            val (core, tr) = openCore()
            armOverrides(core, tr, feed = current)

            core.setFeedOverride(100)
            assertEquals(100, core.targetOverrides.feed, "目标 = 100")
            core.txTick()
            flushWrites(this)
            if (expected == 0) {
                assertTrue(tr.bytes.isEmpty(), "目标 100 且机器侧就是 100 → 不发字节")
            } else {
                assertContentEquals(listOf(expected), tr.bytes, "机器侧 $current → 目标 100")
            }
        }
    }

    @Test
    fun feedOverrideTargetAboveMachineSendsUpBytes() = runTest {
        val cases = listOf(
            150 to Rt.FEED_UP_10, // 差 50 → 升 10 档
            120 to Rt.FEED_UP_10, // 差 20 → 升 10 档
            105 to Rt.FEED_UP_1 // 差 5 → 升 1 档
        )
        for ((target, expected) in cases) {
            val (core, tr) = openCore()
            armOverrides(core, tr, feed = 100)

            core.setFeedOverride(target)
            core.txTick()
            flushWrites(this)
            assertContentEquals(
                listOf(expected),
                tr.bytes,
                "进给 100 → $target（机器侧=${core.overrides} 目标=${core.targetOverrides}）"
            )
        }
    }

    @Test
    fun feedOverrideTargetBelowMachineSendsDownBytes() = runTest {
        val cases = listOf(
            50 to Rt.FEED_DOWN_10, // 差 50 → 降 10 档
            80 to Rt.FEED_DOWN_10, // 差 20 → 降 10 档
            95 to Rt.FEED_DOWN_1 // 差 5 → 降 1 档
        )
        for ((target, expected) in cases) {
            val (core, tr) = openCore()
            armOverrides(core, tr, feed = 100)

            core.setFeedOverride(target)
            core.txTick()
            flushWrites(this)
            assertContentEquals(listOf(expected), tr.bytes, "进给 100 → $target")
        }
    }

    @Test
    fun powerOverrideLadderMatchesGrblBytes() = runTest {
        // 与进给倍率同一套逻辑，只是字节换成 0x99..0x9d（`Ov:` 的第三个字段）
        val upCases = listOf(
            150 to Rt.POWER_UP_10,
            105 to Rt.POWER_UP_1
        )
        for ((target, expected) in upCases) {
            val (core, tr) = openCore()
            armOverrides(core, tr, power = 100)

            core.setPowerOverride(target)
            core.txTick()
            flushWrites(this)
            assertContentEquals(listOf(expected), tr.bytes, "功率 100 → $target")
        }

        val downCases = listOf(
            50 to Rt.POWER_DOWN_10,
            95 to Rt.POWER_DOWN_1
        )
        for ((target, expected) in downCases) {
            val (core, tr) = openCore()
            armOverrides(core, tr, power = 100)

            core.setPowerOverride(target)
            core.txTick()
            flushWrites(this)
            assertContentEquals(listOf(expected), tr.bytes, "功率 100 → $target")
        }
    }

    @Test
    fun powerOverrideResetAndUpBytes() = runTest {
        // 机器侧 0、目标 100 → 复位字节 0x99
        val (coreZero, trZero) = openCore()
        armOverrides(coreZero, trZero, power = 0)
        coreZero.setPowerOverride(100)
        flushWrites(this)
        trZero.resetWrites(this)
        coreZero.txTick()
        flushWrites(this)
        assertContentEquals(listOf(Rt.POWER_RESET), trZero.bytes, "机器侧 0、目标 100 → 0x99")

        // 机器侧 90、目标 100 → 0x99（TS 759：目标是 100 时第一档就是 RESET，不是 0x9a）
        val (coreUp, trUp) = openCore()
        armOverrides(coreUp, trUp, power = 90)
        coreUp.setPowerOverride(100)
        coreUp.txTick()
        flushWrites(this)
        assertContentEquals(listOf(Rt.POWER_RESET), trUp.bytes, "90 → 100 走 RESET 档 0x99")

        // 机器侧 95、目标 100 → 0x99（同上，不是 0x9c）
        val (coreOne, trOne) = openCore()
        armOverrides(coreOne, trOne, power = 95)
        coreOne.setPowerOverride(100)
        coreOne.txTick()
        flushWrites(this)
        assertContentEquals(listOf(Rt.POWER_RESET), trOne.bytes, "95 → 100 走 RESET 档 0x99")
    }

    @Test
    fun rapidOverrideSendsFixedLadder() = runTest {
        // rapids 没有"差多少"的档位，只有 100 / 50 / 25 三个固定目标值
        val (reset, trReset) = openCore()
        armOverrides(reset, trReset, rapids = 25)
        reset.setRapidOverride(100)
        reset.txTick()
        flushWrites(this)
        assertContentEquals(listOf(Rt.RAPID_RESET), trReset.bytes, "目标 100 且机器不是 100 → 0x95")

        val (core, tr) = openCore()
        armOverrides(core, tr, rapids = 100)
        core.setRapidOverride(50)
        core.txTick()
        flushWrites(this)
        assertContentEquals(listOf(Rt.RAPID_50), tr.bytes, "rapids 100 → 50 用 0x96")

        // 机器回报 50 → 目标与机器一致，不再发
        armOverrides(core, tr, rapids = 50, feed = 100, power = 100)
        core.txTick()
        flushWrites(this)
        assertTrue(tr.bytes.isEmpty(), "机器已经是 50，目标也是 50")

        core.setRapidOverride(25)
        core.txTick()
        flushWrites(this)
        assertContentEquals(listOf(Rt.RAPID_25), tr.bytes, "rapids 50 → 25 用 0x97")

        // 目标 = 25 且机器已经是 25 → 不发
        armOverrides(core, tr, rapids = 25)
        core.txTick()
        flushWrites(this)
        assertTrue(tr.bytes.isEmpty(), "已经在 25 了")
    }

    @Test
    fun overrideLadderOrderIsFeedThenPowerThenRapids() = runTest {
        val (core, tr) = openCore()
        tr.resetWrites(this)

        core.setFeedOverride(150)
        core.setPowerOverride(150)
        core.setRapidOverride(25)
        core.txTick()
        flushWrites(this)

        assertContentEquals(
            listOf(Rt.FEED_UP_10, Rt.POWER_UP_10, Rt.RAPID_25),
            tr.bytes,
            "同一 tick 里按 feed → power → rapids 的顺序发（机器侧=${core.overrides} 目标=${core.targetOverrides}）"
        )
    }

    @Test
    fun overrideSettersClampLikeTsMath() = runTest {
        val (core, _) = openCore()
        core.setFeedOverride(5)
        assertEquals(10, core.targetOverrides.feed, "Math.max(10, …)")
        core.setFeedOverride(500)
        assertEquals(200, core.targetOverrides.feed, "Math.min(200, …)")

        core.setPowerOverride(0)
        assertEquals(10, core.targetOverrides.power)
        core.setPowerOverride(999)
        assertEquals(200, core.targetOverrides.power)

        // v2 的 rapid 没有任何钳制
        core.setRapidOverride(999)
        assertEquals(999, core.targetOverrides.rapids)
    }

    @Test
    fun overrideReflectsStatusAndEmitsOnlyOnChange() = runTest {
        val (core, tr) = openCore()
        val emitted = mutableListOf<GrblCore.Overrides>()
        core.onOverride.on { emitted.add(it) }
        assertEquals(GrblCore.Overrides(100, 100, 100), core.overrides, "openCore 补喂的状态不带 Ov: → 解析成 0，再被同步会 100")

        tr.feedSync(this, "<Run|MPos:0.000,0.000,0.000|Ov:100,100,100>\n")
        assertEquals(0, emitted.size, "与当前值相同 → 不发事件")

        tr.feedSync(this, "<Run|MPos:0.000,0.000,0.000|Ov:120,100,100>\n")
        assertEquals(1, emitted.size)
        assertEquals(GrblCore.Overrides(120, 100, 100), emitted[0])
        assertEquals(GrblCore.Overrides(120, 100, 100), core.overrides)

        // 残缺的 Ov: 段：v2 是 `parseInt(undefined, 10)` = NaN，比较结果永远不等 → 会再发一次事件，
        // 并且字段被写成 NaN（Kotlin 侧存成 Int，与 JS 的 `NaN | 0` 一样落到 0）
        tr.feedSync(this, "<Run|MPos:0.000,0.000,0.000|Ov:>\n")
        assertEquals(2, emitted.size, "NaN 与任何值都不相等 → 仍然算变化")
        assertEquals(GrblCore.Overrides(0, 0, 0), core.overrides)
    }

    // =========================================================================
    // 6. 状态解析（v1.1 与 v0.9 两种方言）
    // =========================================================================

    @Test
    fun realtimeStatusV11ParsesEveryField() = runTest {
        val tr = FakeTransport()
        val core = newCore(tr, handshakeDelays = HandshakeDelays.Immediate)
        // 监听器要在 openCore 之前挂上：握手函数内部补喂的那条 Idle 才是「连上」事件
        val statuses = mutableListOf<MacStatus>()
        core.onStatus.on { statuses.add(it) }
        val positions = mutableListOf<GPoint>()
        core.onPosition.on { positions.add(it) }
        val fss = mutableListOf<GrblCore.FeedSpindle>()
        core.onFss.on { fss.add(it) }

        backgroundScope.launch { core.open(fakeDevice(), 115200) }
        runCurrent()
        drainHandshake(core, tr)
        tr.resetWrites(this)
        assertEquals(listOf(MacStatus.Connecting), statuses, "只有 Connecting 一次")

        tr.feedSync(this, "<Idle|MPos:0.000,0.000,0.000|FS:0,0>\n")
        assertEquals(MacStatus.Idle, core.machineStatus)
        assertEquals(GPoint(0.0, 0.0, 0.0), core.position)
        assertEquals(GPoint(0.0, 0.0, 0.0), core.workPosition)
        assertEquals(listOf(MacStatus.Connecting, MacStatus.Idle), statuses, "Connecting → Idle 各一次")
        assertEquals(1, positions.size, "MPos 变化 → position 事件")
        // 初值就是 f=0,s=0，FS:0,0 不构成变化 → 不发 fss 事件（v2 的 `changed` 判断）
        assertEquals(0, fss.size)

        tr.feedSync(this, "<Run|MPos:1.500,2.250,-0.125|FS:600,255|Ov:120,50,150|Bf:15,128|WCO:10.000,20.000,0.500>\n")
        assertEquals(MacStatus.Run, core.machineStatus)
        assertEquals(GPoint(1.5, 2.25, -0.125), core.position)
        assertEquals(GPoint(10.0, 20.0, 0.5), core.wco)
        assertEquals(GPoint(-8.5, -17.75, -0.625), core.workPosition, "工作坐标 = MPos − WCO")
        assertEquals(600.0, core.feed)
        assertEquals(255.0, core.spindle)
        assertEquals(GrblCore.Overrides(120, 50, 150), core.overrides)
        assertEquals(128, core.bufferSize, "Bf:15,128 → 128 在白名单里")
        assertEquals(15, core.grblBlocks)
        assertEquals(GrblCore.FeedSpindle(600.0, 255.0), fss.last())
    }

    @Test
    fun realtimeStatusV11WPosWinsInPipeOrder() = runTest {
        // v2 按 | 分段的**顺序**处理，每个段各自更新位置：
        //  · WPos 段：MPos = WPos + 当时的 WCO
        //  · WCO 段：只改偏移，不重算 MPos（位置事件也不发）
        val (wcoFirst, trWcoFirst) = openCore()
        trWcoFirst.feedSync(this, "<Idle|WCO:10.000,20.000,0.500|WPos:100.000,200.000,5.000>\n")
        assertEquals(GPoint(110.0, 220.0, 5.5), wcoFirst.position, "先 WCO 后 WPos：MPos = WPos + WCO")

        val (wposFirst, trWposFirst) = openCore()
        trWposFirst.feedSync(this, "<Idle|WPos:100.000,200.000,5.000|WCO:10.000,20.000,0.500>\n")
        assertEquals(GPoint(100.0, 200.0, 5.0), wposFirst.position, "先 WPos 时 WCO 还是 0 → MPos 就是 WPos")
        assertEquals(GPoint(10.0, 20.0, 0.5), wposFirst.wco, "随后的 WCO 段只更新偏移")
        assertEquals(GPoint(90.0, 180.0, 4.5), wposFirst.workPosition, "工作坐标 = MPos − WCO")

        // 只有 WCO：只更新偏移，MPos 不动（工作坐标随之变化）
        val (wcoOnly, trWcoOnly) = openCore()
        trWcoOnly.feedSync(this, "<Idle|WCO:1.000,2.000,3.000>\n")
        assertEquals(GPoint(0.0, 0.0, 0.0), wcoOnly.position, "WCO 段不会改 MPos")
        assertEquals(GPoint(-1.0, -2.0, -3.0), wcoOnly.workPosition)
        assertEquals(GPoint(1.0, 2.0, 3.0), wcoOnly.wco)
    }

    @Test
    fun realtimeStatusV11SingleAxisFWithoutSpindle() = runTest {
        val (core, tr) = openCore()
        // GRBL 1.1 的 "F:" 段（不带 S）：v2 走 `setFS(parseFloat(...), 0)`
        tr.feedSync(this, "<Run|MPos:0.000,0.000,0.000|F:300>\n")
        assertEquals(300.0, core.feed)
        assertEquals(0.0, core.spindle)

        // "F:" 后面没有数字 → JS 是 parseFloat(undefined) = NaN（Kotlin 侧保持一致）
        tr.feedSync(this, "<Run|MPos:0.000,0.000,0.000|F:>\n")
        assertTrue(core.feed.isNaN(), "F: 空值时 feed 是 NaN（与 parseFloat(undefined) 一致）")
    }

    @Test
    fun realtimeStatusMachineStateWithColonIsTrimmed() = runTest {
        val (core, tr) = openCore()
        tr.feedSync(this, "<Hold:1|MPos:0.000,0.000,0.000|FS:0,0>\n")
        assertEquals(MacStatus.Hold, core.machineStatus, "状态字取 ':' 之前的部分")
    }

    @Test
    fun realtimeStatusUnknownStateIsIgnored() = runTest {
        val (core, tr) = openCore()
        tr.feedSync(this, "<Bogus|MPos:0.000,0.000,0.000|FS:0,0>\n")
        assertEquals(MacStatus.Idle, core.machineStatus, "未登记的状态字不改变机器状态")
    }

    @Test
    fun realtimeStatusV09CommaDialect() = runTest {
        val (core, tr) = openCore()

        // v0.9 的实时状态在 v2 里也是**尖括号包着**的（`<Idle,MPos:…,WPos:…>`），
        // 只是内部分段用逗号而不是竖线。真实 0.9 报文里 MPos 后面带一个空格，
        // 而 v2 的 `arr[1].substring(5)` 硬编码了 "MPos:" 的 5 个字符 —— 所以这里照抄原样。
        tr.feedSync(this, "<Idle,MPos: 10.000,20.000,30.000,WPos: 1.000,2.000,3.000>\n")

        assertEquals(MacStatus.Idle, core.machineStatus, "v0.9 方言里状态是第一段")
        assertEquals(GPoint(10.0, 20.0, 30.0), core.position, "arr[1..3] 从第 5 个字符起 parseFloat")
        assertEquals(GPoint(1.0, 2.0, 3.0), core.workPosition, "arr[4..6] 给出 WPos")
        assertEquals(GPoint(9.0, 18.0, 27.0), core.wco, "WCO = MPos − WPos")

        // 只有 MPos 时只更新机械坐标
        tr.feedSync(this, "<Run,MPos: 5.000,6.000,7.000>\n")
        assertEquals(MacStatus.Run, core.machineStatus)
        assertEquals(GPoint(5.0, 6.0, 7.0), core.position)
        assertEquals(GPoint(9.0, 18.0, 27.0), core.wco, "WCO 保持不变")

        // 只有状态字
        tr.feedSync(this, "<Hold>\n")
        assertEquals(MacStatus.Hold, core.machineStatus)
    }

    @Test
    fun statusVersionFallbacksFollowPinMarker() = runTest {
        // 没有版本上下文时 statusReportVersion 用 '|' 与 'Pin:' 猜方言：
        // 有 | 无 Pin: → 1.1；有 | 有 Pin: → 1.0c；无 | → 0.9（逗号方言）
        val (noPin, trNoPin) = openCore()
        trNoPin.feedSync(this, "<Idle|MPos:1.000,2.000,3.000|FS:0,0>\n")
        assertEquals(GPoint(1.0, 2.0, 3.0), noPin.position, "1.1 方言：按 | 分段解析")

        val (withPin, trWithPin) = openCore()
        trWithPin.feedSync(this, "<Idle|MPos:1.000,2.000,3.000|FS:0,0|Pin:XYZ>\n")
        // 1.0c < 1.1，所以落到**逗号**分支：整行按 ',' 切开，
        // "MPos:1.000" 不是第 5 个字符起才开始的 → JS 的 substring(5) 得到 ".000" → NaN。
        // 这是 v2 的实际行为（黄金样本 grbl-version.json 的 statusReportFallbacks 也钉着这个回退），
        // 照抄：1.0c 的 Pin: 报文会被错解。
        assertEquals(MacStatus.Idle, withPin.machineStatus)
        assertEquals(GPoint(Double.NaN, 3.0, 0.0), withPin.position, "1.0c 落到逗号方言（v2 的行为）")
        assertTrue(withPin.position.X.isNaN(), "X 是 NaN —— v2 的 substring(5) 缺陷")

        val (v09, trV09) = openCore()
        trV09.feedSync(this, "<Idle,MPos: 1.000,2.000,3.000>\n")
        assertEquals(GPoint(1.0, 2.0, 3.0), v09.position, "没有 | → 逗号方言，MPos 段按第 5 个字符起算")
        assertEquals(GPoint(0.0, 0.0, 0.0), v09.wco, "逗号方言里没有 WPos 段就不动 WCO")

        // 版本上下文一旦确定，就压过报文外形
        trNoPin.feedSync(this, "[VER:0.9j:]\n")
        trNoPin.feedSync(this, "<Idle,MPos: 4.000,5.000,6.000>\n")
        assertEquals(GPoint(4.0, 5.0, 6.0), noPin.position, "版本上下文优先于报文外形")
    }

    // =========================================================================
    // 7. 握手 + 10 s 连接超时
    // =========================================================================

    @Test
    fun handshakeBytesGoOutInDocumentedOrder() = runTest {
        // 关掉自动发送循环时，握手入队的 $I/$$/$# 不会自己飞出去 ——
        // 所以这里逐段推进虚拟时间，用 txTick() 手动把队列顶出去（等价于 v2 的 4 ms 定时器）
        val tr = FakeTransport()
        val core = newCore(
            tr,
            handshakeDelays = HandshakeDelays(10, 10, 10, 10)
        )

        val openJob = backgroundScope.launch { core.open(fakeDevice(), 115200) }
        runCurrent()
        assertContentEquals(listOf(0x18), tr.bytes, "先发软复位 0x18")
        assertEquals(1, tr.openCount)
        assertEquals(115200, tr.lastBaudRate)
        assertEquals(MacStatus.Connecting, core.machineStatus)
        assertEquals(0, core.queueCount, "复位等待期间还没入队任何东西")

        advanceTimeBy(10) // 复位等待结束 → "\r\n" + 入队 $I
        runCurrent()
        // ⚠️ 这里断言的是「\r\n 已经写出去了」而不是 `writes == ["\r\n"]`：
        // `queryPosition()` 是 fire-and-forget 的（v2 的 `void transport.writeBytes(...)`），
        // 而它调度的写任务与本协程的后续步骤在同一个 TestDispatcher 队列里，
        // 所以 `?` 有可能出现在 `\r\n` 之前（真实设备上两者相差 1.1 秒，不会发生）。
        assertTrue(tr.writes.contains("\r\n"), "握手要敲回车换行：${tr.writes}")
        assertEquals(1, core.queueCount, "\$I 已入队")

        advanceTimeBy(10) // $I 的等待结束 → 入队 $$
        runCurrent()
        assertEquals(2, core.queueCount)

        advanceTimeBy(10) // $$ 的等待结束 → 入队 $#
        runCurrent()
        assertEquals(3, core.queueCount, "\$I / \$\$ / \$# 都在队列里")

        advanceTimeBy(10) // $# 的等待结束 → queryPosition()
        runCurrent()
        openJob.join()
        assertEquals(MacStatus.Connecting, core.machineStatus, "发完 ? 也还没离开 Connecting")

        // 队列按 FIFO 发出去
        drainHandshake(core, tr)
        core.txTick()
        flushWrites(this)

        val lines = tr.writes.filter { it.contains('\n') }.map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("\$I", "\$\$", "\$#"), lines, "三条握手命令按顺序发出")
        assertEquals(Rt.STATUS_QUERY, tr.bytes.last(), "握手最后是 ?")
        assertTrue(tr.bytes.count { it == Rt.STATUS_QUERY } >= 1, "握手发过 ?：${tr.bytes}")
    }

    @Test
    fun resetOnConnectCanBeDisabledBySettings() = runTest {
        // 默认是 true（发 0x18 + 等 400 ms）；关掉之后第一阶段就是 "\r\n"
        val tr = FakeTransport()
        val core = newCore(
            tr,
            settings = FakeSettings(mapOf("Reset Grbl On Connect" to false)),
            handshakeDelays = HandshakeDelays.Immediate
        )
        backgroundScope.launch { core.open(fakeDevice(), 115200) }
        runCurrent()
        flushWrites(this)
        // ⚠️ 不是「一个字节都不发」：握手末尾一定会发一个 `?`（TS 240）。这里要断言的是没有 0x18。
        assertTrue(!tr.bytes.contains(Rt.SOFT_RESET), "关了复位就不发 0x18：${tr.bytes}")
        assertTrue(tr.bytes.all { it == Rt.STATUS_QUERY }, "只剩握手末尾那个 ?：${tr.bytes}")
        assertEquals("\r\n", tr.writes.first())
        assertEquals(3, core.queueCount)

        // 固件是 Smoothie 时同样跳过复位
        val trSmoothie = FakeTransport()
        val coreSmoothie = newCore(
            trSmoothie,
            settings = FakeSettings(mapOf("Firmware Type" to "Smoothie")),
            handshakeDelays = HandshakeDelays.Immediate
        )
        assertEquals(Firmware.Smoothie, coreSmoothie.firmware)
        backgroundScope.launch { coreSmoothie.open(fakeDevice(), 115200) }
        runCurrent()
        flushWrites(this)
        assertTrue(!trSmoothie.bytes.contains(Rt.SOFT_RESET), "Smoothie 不软复位：${trSmoothie.bytes}")
    }

    @Test
    fun settingsDriveThreadingModeFirmwareAndJog() = runTest {
        val core = newCore(
            FakeTransport(),
            settings = FakeSettings(
                mapOf(
                    "Threading Mode" to "Slow",
                    "Firmware Type" to "Marlin",
                    "Jog Speed" to 250,
                    "Jog Step" to 5
                )
            )
        )
        assertEquals(ThreadingMode.Slow, core.threadingMode)
        assertEquals(Firmware.Marlin, core.firmware)
        assertEquals(250, core.jogSpeed)
        assertEquals(5, core.jogStep)

        // 未登记的名字回退到 **Fast**：TS 148 是 `ThreadingMode.all().find(...) ?? ThreadingMode.Fast`
        // （列表首项确实是 Slow，但 `??` 用的是 Fast —— statusQuery 500 而不是 2000）
        val unknown = newCore(FakeTransport(), settings = FakeSettings(mapOf("Threading Mode" to "Bogus")))
        assertEquals(ThreadingMode.Fast, unknown.threadingMode)
    }

    @Test
    fun tenSecondSilenceRaisesConnectTimeout() = runTest {
        val tr = FakeTransport()
        val core = newCore(tr, autoTxLoop = true, handshakeDelays = HandshakeDelays.Immediate)
        var timeouts = 0
        core.onConnectTimeout.on { timeouts++ }

        // 全程没有任何应答：10 s 之后要报连接超时并自动断开
        backgroundScope.launch { core.open(fakeDevice(), 115200) }
        runCurrent()
        assertEquals(0, timeouts, "握手刚结束还不该超时")
        assertEquals(MacStatus.Connecting, core.machineStatus)

        advanceTimeBy(CONNECT_TIMEOUT_MILLIS + 2 * DEFAULT_TX_PERIOD_MILLIS)
        runCurrent()

        assertEquals(1, timeouts)
        assertEquals(MacStatus.Disconnected, core.machineStatus)
        assertEquals(DetectedIssue.Unknown, core.lastIssue, "内部关闭不记 ManualDisconnect")
        assertEquals(1, tr.closeCount, "超时会关掉串口")
        assertEquals(0, core.queueCount)
    }

    @Test
    fun responsesCancelConnectTimeout() = runTest {
        val tr = FakeTransport()
        val core = newCore(tr, autoTxLoop = true, handshakeDelays = HandshakeDelays.Immediate)
        var timeouts = 0
        core.onConnectTimeout.on { timeouts++ }
        var connected = 0
        core.onConnected.on { connected++ }

        backgroundScope.launch { core.open(fakeDevice(), 115200) }
        runCurrent()
        tr.feedSync(this, "Grbl 1.1h ['$' for help]\n")
        tr.feedSync(this, "<Idle|MPos:0.000,0.000,0.000|FS:0,0>\n")

        assertEquals(MacStatus.Idle, core.machineStatus)
        assertTrue(core.isConnected)
        assertEquals(1, connected, "Connecting → Idle 时发 connected 一次")
        assertEquals("1.1h", core.version?.toString())

        advanceTimeBy(3 * CONNECT_TIMEOUT_MILLIS)
        runCurrent()
        assertEquals(0, timeouts, "已连上（Idle）就不再走连接超时分支")
        assertEquals(MacStatus.Idle, core.machineStatus)
    }

    @Test
    fun openIsNoOpWhenAlreadyConnected() = runTest {
        val (core, tr) = openCore()
        // 已经连上：v2 的 isConnected 早退，不会再 open 一次
        runCurrent()
        assertTrue(core.isConnected)
        assertEquals(1, tr.openCount)
    }

    // =========================================================================
    // 8. 版本 / banner（对接 grbl-version 黄金样本）
    // =========================================================================

    @Test
    fun versionBannersMatchGrblVersionFixture() = runTest {
        val banners = Golden.array("grbl-version", "banners")
        assertTrue(banners.isNotEmpty())

        var checked = 0
        for (element in banners) {
            val node = element.asObject()
            val line = Golden.string(node, "line")
            val expected = node["constructorArgs"]?.asObject() ?: continue
            val parsedFrom = node["parsedFrom"]?.jsonPrimitive?.content

            val (core, tr) = openCore()
            tr.feedSync(this, line + "\n")

            val version = assertNotNull(core.version, "[$line] 应当解析出版本")
            assertEquals(Golden.int(expected, "major"), version.major, "[$line] major")
            assertEquals(Golden.int(expected, "minor"), version.minor, "[$line] minor")
            assertEquals(Golden.stringOrNull(expected, "build") ?: "", version.build, "[$line] build")

            val vendorPath = parsedFrom != "parseVersionBanner"
            assertEquals(
                if (vendorPath) Golden.stringOrNull(expected, "vendorInfo") else null,
                version.vendorInfo,
                "[$line] vendorInfo（banner 路径不填厂商）"
            )
            assertEquals(
                if (vendorPath) Golden.stringOrNull(expected, "vendorVersion") else null,
                version.vendorVersion,
                "[$line] vendorVersion（banner 路径不填厂商）"
            )

            // 夹具里的 lookupGroup 是 generate.ts 用同一个 lookupGroupName 算出来的 ——
            // 这里验证「GrblCore 解析出来后放进版本上下文的那个版本」与夹具的分组一致
            assertEquals(
                Golden.string(node, "lookupGroup"),
                lookupGroupName(core.config.getVersion()),
                "[$line] 解码组"
            )
            checked++
        }
        assertTrue(checked > 0, "没有可校验的 banner")
    }

    @Test
    fun versionUpgradeIsMonotonicAndResetsConfig() = runTest {
        val (core, tr) = openCore()

        tr.feedSync(this, "Grbl 1.1h ['$' for help]\n")
        assertEquals("1.1h", core.version?.toString())

        tr.feedSync(this, "\$30=1000\n")
        assertEquals(1000.0, core.config.get(30, -1))
        assertEquals(1, core.config.entries().size)

        // 更旧的 banner 不覆盖当前版本，也不清空设置
        tr.feedSync(this, "Grbl 1.0c ['$' for help]\n")
        assertEquals("1.1h", core.version?.toString(), "banner 版本只升不降")
        assertEquals(1000.0, core.config.get(30, -1))

        // 更新的 [VER:] 直接覆盖（v2 的 parseVerMessage 不做比较）
        tr.feedSync(this, "[VER:1.1h.20190825:Ortur Laser Master 3:1.7]\n")
        assertEquals("1.120190825", core.version?.toString())
        assertEquals(GrblVersionInfo(1, 1, "20190825"), core.version)
        assertEquals(GrblVersionInfo(1, 1, "20190825"), core.config.getVersion())
        assertTrue(core.config.entries().isEmpty(), "parseVerMessage 会 reset 设置集合")
    }

    @Test
    fun verMessageFillsVendorAndLookupGroup() = runTest {
        val (core, tr) = openCore()
        tr.feedSync(this, "[VER:1.1h.20190825:NanoDuo:1.1]\n")
        assertEquals("NanoDuo", core.version?.vendorInfo)
        assertEquals("1.1", core.version?.vendorVersion)
        assertEquals("longer.nanoduo", lookupGroupName(core.config.getVersion()))

        val (core2, tr2) = openCore()
        tr2.feedSync(this, "[VER:1.1h.20190825:]\n")
        assertNull(core2.version?.vendorInfo, "空的厂商段 → null")
        assertEquals("v1.1", lookupGroupName(core2.config.getVersion()))
    }

    @Test
    fun versionBannerAndVerMessageEmitStartupMessages() = runTest {
        val (core, tr) = openCore()
        val messages = mutableListOf<GrblMessage>()
        core.onMessage.on { messages.add(it) }

        tr.feedSync(this, "Grbl 1.1h ['$' for help]\n")
        tr.feedSync(this, "[VER:1.1h.20190825:]\n")
        tr.feedSync(this, "[OPT:VZ,15,128]\n")
        tr.feedSync(this, "[MSG:Welcome]\n")

        assertEquals(
            listOf(MessageType.Startup, MessageType.Startup, MessageType.Feedback, MessageType.Feedback),
            messages.map { it.type }
        )
        assertEquals("Grbl 1.1h ['$' for help]", messages[0].nativeMessage)
    }

    // =========================================================================
    // 9. 配置行的两条路径（$ 设置项 / 写 EEPROM）
    // =========================================================================

    @Test
    fun writeEepromCommandUpdatesConfigOnOk() = runTest {
        val (core, tr) = openCore()
        core.enqueueRaw("\$30=1000") // isWriteEEPROM = true
        core.txTick()
        runCurrent()
        assertContentEquals(listOf("\$30=1000\n"), tr.writes, "GRBL 命令不压缩空格")

        tr.feedSync(this, "ok\n")
        assertEquals(1000.0, core.config.get(30, -1), "ok 之后写入本地设置副本")

        // 写失败就不该记
        core.enqueueRaw("\$31=1")
        core.txTick()
        runCurrent()
        tr.feedSync(this, "error:3\n")
        assertEquals(false, core.config.has(31), "error 之后不更新设置")
    }

    @Test
    fun configLinesAccumulateAndRoundTrip() = runTest {
        val (core, tr) = openCore()
        tr.feedSync(this, "\$0=10\n\$1=25\n\$30=1000\n\$32=0.5\n")
        assertEquals(10.0, core.config.get(0))
        assertEquals(25.0, core.config.get(1))
        assertEquals(1000.0, core.config.get(30))
        assertEquals(0.5, core.config.get(32))
        assertEquals(0.0, core.config.get(999), "缺省值是 0")
        assertEquals(false, core.config.has(999))

        assertEquals(listOf(0, 1, 30, 32), core.config.entries().map { it.first }, "entries 按编号升序")
        assertEquals(
            listOf("\$0=10", "\$1=25", "\$30=1000", "\$32=0.5"),
            core.config.toCommands(),
            "写回命令用 String(v) 语义（整数不带 .0）"
        )
    }

    @Test
    fun configAndAlarmLinesAreDecodedIntoMessages() = runTest {
        val (core, tr) = openCore()
        core.setStreamingMode(StreamingMode.Synchronous)
        val messages = mutableListOf<GrblMessage>()
        core.onMessage.on { messages.add(it) }

        tr.feedSync(this, "\$30=1000\n")
        assertEquals(MessageType.Config, messages.last().type)
        assertEquals(1000.0, core.config.get(30, -1))

        // ALARM 既当作命令应答（清 in-flight），也当报警消息。
        // 注意 v2 只在**任务中**才把它记成 MachineAlarm（if (this.inProgram)），
        // 所以这里必须走 runProgram 而不是 enqueueRaw。
        core.runProgram(listOf(GrblCommand("G1 X0 Y0")))
        core.txTick()
        runCurrent()
        assertEquals(1, core.pendingCount)

        tr.feedSync(this, "ALARM:1\n")
        assertEquals(MessageType.Alarm, messages.last().type, "ALARM 被归到 Alarm 类")
        assertTrue(messages.last().message.isNotEmpty(), "报警正文会被解码表替换/补充")
        assertEquals(0, core.pendingCount, "ALARM 同样是一条应答")
        // ⚠️ 即使在任务里，`ALARM:1` 也**不会**置 MachineAlarm：TS 383 只认
        // `p.status === CommandStatus.ResponseBad`，而 GrblCommand.status 把 "ERROR…" 判成 ResponseBad、
        // 把 "ALARM…" 判成 InvalidResponse。真机进 Alarm 是靠 `<Alarm|…>` 状态报文（parseMachineStatus）。
        // MachineAlarm 的正例见 errorResponseRepeatsOnlyInRepeatOnErrorMode（error:20）。
        assertEquals(DetectedIssue.Unknown, core.lastIssue, "ALARM 文本行不是 ResponseBad → 不置 MachineAlarm")
        assertEquals(CommandStatus.InvalidResponse, core.lastCommandStatus?.status)
        assertTrue(!core.inProgram, "in-flight 与队列都空了 → 任务结束")

        // 任务之外的 ALARM 只记消息，不升级成 issue
        val (coreIdle, trIdle) = openCore()
        val idleMessages = mutableListOf<GrblMessage>()
        coreIdle.onMessage.on { idleMessages.add(it) }
        assertEquals(DetectedIssue.Unknown, coreIdle.lastIssue, "新实例的 issue 初值")
        coreIdle.enqueueRaw("G1 X0 Y0")
        coreIdle.txTick()
        runCurrent()
        trIdle.feedSync(this, "ALARM:1\n")
        assertEquals(MessageType.Alarm, idleMessages.last().type, "任务外的 ALARM 也是 Alarm 消息")
        assertEquals(DetectedIssue.Unknown, coreIdle.lastIssue, "不在任务中就不算 MachineAlarm")

        // 其它行按 Others 走
        trIdle.feedSync(this, "some random chatter\n")
        assertEquals(MessageType.Others, idleMessages.last().type)
    }

    @Test
    fun lookupHelpersUseVersionContext() = runTest {
        val (core, tr) = openCore()
        tr.feedSync(this, "[VER:0.9j:]\n")
        assertEquals(GrblVersionInfo(0, 9, "j"), core.config.getVersion(), "查表跟着版本上下文走")

        val info = core.settingInfo(30)
        assertEquals(3, info.size, "settingInfo 固定返回 [名称, 单位, 说明]")
        assertEquals("", info[2], "v0.9 分组里查不到 $30 的说明")

        // 未知设置项 → 空串（v2 的 `?? ''`）
        assertEquals(listOf("", "", ""), core.settingInfo(999999))
        assertEquals(listOf("", ""), core.alarmInfo("999999"))
        assertNull(core.errorDescription("999999"), "未知错误码返回 null")
    }

    // =========================================================================
    // 10. 点动 / 移动命令拼装
    // =========================================================================

    @Test
    fun jogUsesDollarJOnV11AndRelativeMovesOnV09() = runTest {
        val (v11, tr11) = openCore()
        tr11.feedSync(this, "[VER:1.1h:]\n")
        v11.jog(JogDirection.NE, step = 1, speed = 1000)
        v11.jog(JogDirection.Home, step = 1, speed = 1000)
        // 步长走 JS `toFixed(1)`：1 → "1.0"（不是 Kotlin 的 "1"）
        assertEquals("\$J=G91X1.0Y1.0F1000", v11.queuedTexts()[0])
        assertEquals("\$J=G90X0Y0F1000", v11.queuedTexts()[1], "Home 用 G90 回原点")

        val (v09, tr09) = openCore()
        tr09.feedSync(this, "[VER:0.9j:]\n")
        v09.jog(JogDirection.SW, step = 1, speed = 500)
        assertEquals(
            listOf("G91 G1X-1.0Y-1.0F500", "G90"),
            v09.queuedTexts(),
            "v0.9 用相对移动模拟，点动之后要回到绝对坐标"
        )
    }

    @Test
    fun jogIsIgnoredWhenNotConnected() = runTest {
        val core = newCore(FakeTransport(), handshakeDelays = HandshakeDelays.Immediate)
        assertEquals(false, core.isConnected)
        core.jog(JogDirection.N, step = 1, speed = 1000)
        assertEquals(0, core.queueCount, "没连上时不入队")
    }

    @Test
    fun moveToAndSetZeroHereUseJsToFixed() = runTest {
        val core = newCore(FakeTransport(), handshakeDelays = HandshakeDelays.Immediate)
        core.moveTo(10.5, -3.25, speed = 800)
        core.setZeroHere(1.5, 2.0)
        core.writeSetting(30, 1000.0)
        assertEquals(
            listOf("G90 G1 X10.500 Y-3.250 F800", "G92 X1.5 Y2", "\$30=1000"),
            core.queuedTexts(),
            "toFixed(3) / formatDecimal（String(v)，整数不带 .0）"
        )
    }

    @Test
    fun homingRequiresIdleAndUnlocked() = runTest {
        val (core, tr) = openCore()
        assertEquals(true, core.canDoHoming, "Idle 时可以回零")
        assertEquals(true, core.canReset)
        assertEquals(false, core.canFeedHold, "Idle 不能进给保持")

        tr.feedSync(this, "<Run|MPos:0.000,0.000,0.000|FS:0,0>\n")
        assertEquals(false, core.canDoHoming, "Run 中不能回零")
        core.homing()
        assertEquals(0, core.queueCount)
        assertEquals(true, core.canFeedHold)

        tr.feedSync(this, "<Alarm|MPos:0.000,0.000,0.000|FS:0,0>\n")
        assertEquals(false, core.canDoHoming, "Alarm 中不能回零")

        tr.feedSync(this, "<Idle|MPos:0.000,0.000,0.000|FS:0,0>\n")
        core.homing()
        assertEquals(listOf("\$H"), core.queuedTexts())

        tr.feedSync(this, "<Jog|MPos:0.000,0.000,0.000|FS:0,0>\n")
        assertEquals(true, core.canFeedHold)
    }

    // =========================================================================
    // 11. 卡死检测 / 队列清理 / 断开
    // =========================================================================

    @Test
    fun hangDetectionFiresAfterStatusQueryTimeout() = runTest {
        var now = 0L
        val tr = FakeTransport()
        val core = newCore(
            tr,
            autoTxLoop = true,
            handshakeDelays = HandshakeDelays.Immediate,
            clock = { now }
        )
        val issues = mutableListOf<DetectedIssue>()
        core.onIssue.on { issues.add(it) }

        backgroundScope.launch { core.open(fakeDevice(), 115200) }
        runCurrent()
        tr.feedSync(this, "<Run|MPos:0.000,0.000,0.000|FS:0,0>\n")
        assertEquals(MacStatus.Run, core.machineStatus, "先把机器置成 Run")

        core.runProgram(listOf(GrblCommand("G1 X0 Y0")))
        core.txTick()
        runCurrent()
        assertEquals(0, issues.size, "刚开始跑还不算卡死")

        // ThreadingMode.Fast：max(500 * 10, 5000) = 5000 ms
        now += 6000
        advanceTimeBy(3 * DEFAULT_TX_PERIOD_MILLIS)
        runCurrent()
        assertEquals(DetectedIssue.StopResponding, core.lastIssue)
        assertEquals(1, issues.size, "lastIssue 一旦不再是 Unknown 就不再重复报")

        now += 6000
        advanceTimeBy(3 * DEFAULT_TX_PERIOD_MILLIS)
        runCurrent()
        assertEquals(1, issues.size, "同一次卡死只报一次")
    }

    @Test
    fun hangDetectionNeedsProgramAndRunState() = runTest {
        var now = 0L
        val tr = FakeTransport()
        val core = newCore(tr, autoTxLoop = true, handshakeDelays = HandshakeDelays.Immediate, clock = { now })
        backgroundScope.launch { core.open(fakeDevice(), 115200) }
        runCurrent()

        // Idle + 没有任务 → 不该报卡死
        now += 60_000
        core.txTick()
        runCurrent()
        assertEquals(DetectedIssue.Unknown, core.lastIssue)

        // Run 但没有任务 → 也不报
        tr.feedSync(this, "<Run|MPos:0.000,0.000,0.000|FS:0,0>\n")
        now += 60_000
        core.txTick()
        runCurrent()
        assertEquals(DetectedIssue.Unknown, core.lastIssue)
    }

    @Test
    fun softResetAndAbortClearQueues() = runTest {
        val (core, tr) = openCore()
        core.enqueueRaw("G1 X0 Y0")
        core.enqueueRaw("G1 X5 Y0")
        assertEquals(2, core.queueCount)

        core.abortProgram()
        assertEquals(0, core.queueCount)
        assertEquals(DetectedIssue.ManualAbort, core.lastIssue)

        core.enqueueRaw("G1 X0 Y0")
        tr.resetWrites(this)
        core.softReset()
        runCurrent()
        assertEquals(0, core.queueCount)
        assertEquals(0, core.bufferUsed)
        assertContentEquals(listOf(Rt.SOFT_RESET), tr.bytes)
        assertEquals(MacStatus.Idle, core.machineStatus, "软复位只清队列，不改状态（等机器回报）")
    }

    @Test
    fun programRunningSoftResetRaisesManualReset() = runTest {
        val (core, tr) = openCore()
        core.runProgram(listOf(GrblCommand("G1 X0 Y0")))
        core.txTick()
        runCurrent()
        assertEquals(7, tr.writtenBytes().size) // "G1X0Y0\n"
        assertEquals(1, core.pendingCount)

        core.softReset()
        assertEquals(DetectedIssue.ManualReset, core.lastIssue, "任务中软复位 → ManualReset")
        assertTrue(!core.inProgram)
        assertEquals(0, core.pendingCount, "in-flight 也被丢掉")
        assertEquals(0, core.bufferUsed, "缓冲记账归零")
        assertTrue(core.progress.sent == 1, "jobSent 不清零（v2 也一样）")
    }

    @Test
    fun realtimeCommandsGoOutAsSingleBytes() = runTest {
        val (core, tr) = openCore()
        core.queryPosition()
        core.feedHold()
        core.cycleStart()
        runCurrent()
        assertContentEquals(listOf(Rt.STATUS_QUERY, Rt.FEED_HOLD, Rt.CYCLE_START), tr.bytes)
    }

    @Test
    fun manualAndUnexpectedDisconnectsAreDistinguished() = runTest {
        // 手动关闭 → ManualDisconnect
        val (core, tr) = openCore()
        var disconnected = 0
        core.onDisconnected.on { disconnected++ }
        core.close()
        assertEquals(DetectedIssue.ManualDisconnect, core.lastIssue)
        assertEquals(MacStatus.Disconnected, core.machineStatus)
        assertEquals(1, disconnected)
        assertEquals(1, tr.closeCount)

        // 设备掉线 → UnexpectedDisconnect
        val (core2, tr2) = openCore()
        var disconnected2 = 0
        core2.onDisconnected.on { disconnected2++ }
        tr2.simulateClose()
        runCurrent()
        assertEquals(DetectedIssue.UnexpectedDisconnect, core2.lastIssue)
        assertEquals(MacStatus.Disconnected, core2.machineStatus)
        assertEquals(1, disconnected2)
    }

    @Test
    fun transportCloseFailureIsSwallowed() = runTest {
        val tr = object : SerialTransport by FakeTransport() {
            override suspend fun close() {
                throw IllegalStateException("设备已经拔了")
            }
        }
        val core = newCore(tr, handshakeDelays = HandshakeDelays.Immediate)
        backgroundScope.launch { core.open(fakeDevice(), 115200) }
        runCurrent()

        core.close() // v2 用 try/catch 吞掉 close 的异常
        assertEquals(MacStatus.Disconnected, core.machineStatus)
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    /**
     * 造一个已经「连上」的 core：假串口 + `autoTxLoop = false` + 握手等待为 0。
     *
     * 三件必须做对的事（否则后续断言会被握手的残留污染）：
     *  1. `open()` 的挂起只发生在 `yield()` 上，`runCurrent()` 能一路跑到 `?` 发完；
     *  2. 握手入队的 `$I` / `$$` / `$#` 要**像真实机器那样**被 tick 发出去并回收 ——
     *     因为这里关掉了自动发送循环（v2 那边是 4 ms 定时器在握手等待期间顺手发掉的）；
     *  3. 握手本身不会让状态离开 Connecting（v2 只在这里发 `?`，状态要等机器回
     *     `<Idle|…>` 才会变），所以补喂一条 Idle 状态，否则 `isConnected` 永远是 false。
     *
     * 返回时清空写记录，这样每条用例的断言都只针对自己那一段。
     */
    private suspend fun TestScope.openCore(
        settings: FakeSettings = FakeSettings(),
        transport: FakeTransport = FakeTransport()
    ): Pair<GrblCore, FakeTransport> {
        val core = newCore(transport, settings = settings, handshakeDelays = HandshakeDelays.Immediate)
        backgroundScope.launch { core.open(fakeDevice(transport.kind), 115200) }
        runCurrent()
        assertEquals(MacStatus.Connecting, core.machineStatus, "握手结束时还在 Connecting")
        assertEquals(Rt.STATUS_QUERY, transport.bytes.last(), "握手最后发 ?")

        drainHandshake(core, transport)
        transport.resetWrites(this)

        // 补喂一条 Idle 状态让状态机离开 Connecting，并把**期望倍率同步到机器当前值** ——
        // v2 里 `Ov:` 段缺失时倍率会被解析成 0，若不显式同步，manageOverrides() 会在
        // 「目标 100 / 机器 0」之间不停发复位字节，污染后续字节级断言。
        transport.feedSync(this, "<Idle|MPos:0.000,0.000,0.000|FS:0,0>\n")
        core.syncTargetOverridesToMachine()
        flushWrites(this)
        assertTrue(core.isConnected, "机器回了 Idle 才算连上")
        assertEquals(0, core.bufferUsed, "握手命令都已回收")

        // 自检：同步之后一个 tick 不该再发任何字节
        transport.resetWrites(this)
        core.txTick()
        flushWrites(this)
        assertTrue(transport.bytes.isEmpty(), "openCore 之后倍率已同步，不该再发字节：${transport.bytes}")
        return core to transport
    }

    /**
     * 把一个覆盖倍率测试用例的起点摆好：喂一条带 `Ov:` 的状态报文，再把**期望倍率同步到该值**、
     * 清空写记录。这样后续的字节断言只反映「测试自己改目标值」引起的那一次逼近。
     */
    private suspend fun TestScope.armOverrides(
        core: GrblCore,
        transport: FakeTransport,
        feed: Int = 100,
        rapids: Int = 100,
        power: Int = 100
    ) {
        transport.feedSync(this, "<Idle|MPos:0.000,0.000,0.000|Ov:$feed,$rapids,$power>\n")
        assertEquals(feed, core.overrides.feed, "机器侧进给倍率已就位")
        assertEquals(rapids, core.overrides.rapids, "机器侧快速倍率已就位")
        assertEquals(power, core.overrides.power, "机器侧功率倍率已就位")
        core.syncTargetOverridesToMachine()
        assertEquals(feed, core.targetOverrides.feed, "期望进给倍率已同步")
        assertEquals(rapids, core.targetOverrides.rapids, "期望快速倍率已同步")
        assertEquals(power, core.targetOverrides.power, "期望功率倍率已同步")
        transport.resetWrites(this)
    }

    /**
     * 把握手入队的 `$I` / `$$` / `$#` 像真实机器那样走完一遍：
     * 每个 tick 发一条（默认为 Buffered 模式），然后机器一次性回三个 `ok`。
     */
    private suspend fun TestScope.drainHandshake(core: GrblCore, transport: FakeTransport) {
        val queued = core.queueCount
        assertEquals(3, queued, "握手恰好入队 \$I / \$\$ / \$# 三条")
        repeat(queued) {
            core.txTick()
            runCurrent()
        }
        assertEquals(0, core.queueCount, "三条都发出去了")
        assertEquals(queued, core.pendingCount, "三条都在等应答")
        transport.feedSync(this, "ok\n".repeat(queued))
        assertEquals(0, core.pendingCount, "应答都收回来了")
        assertEquals(0, core.bufferUsed)
    }

    private fun TestScope.newCore(
        transport: SerialTransport,
        settings: FakeSettings = FakeSettings(),
        autoTxLoop: Boolean = false,
        handshakeDelays: HandshakeDelays = HandshakeDelays.Immediate,
        clock: () -> Long = { testScheduler.currentTime }
    ): GrblCore = GrblCore(
        injectedTransport = transport,
        settingsSource = settings,
        txScope = backgroundScope,
        autoTxLoop = autoTxLoop,
        clock = clock,
        handshakeDelays = handshakeDelays
    )
}
