package com.lasergrbl.core.native

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 保活**节流策略**的单测 —— 逐条对照 v2 `src/core/native/KeepAlive.ts`。
 *
 * 为什么值得测：v2 的节流状态（`active` / `jobName` / `lastPercent`）是模块级变量，
 * 一旦在移植时改成"每次进度都通知原生层"，就会把通知刷爆（Android 会降频甚至丢通知）；
 * 反过来如果漏掉最后一次 100%，进度条会永远停在 99%。
 */
class KeepAliveControllerTest {

    /** 记录所有原生调用，便于断言"节流有没有生效"。 */
    private class FakeNative : NativeKeepAlive {
        data class Call(val kind: String, val title: String, val text: String, val progress: Int)

        val calls = mutableListOf<Call>()
        var failOnStart = false
        var failOnUpdate = false
        var failOnStop = false
        var notification: KeepAliveNotification = KeepAliveNotification.Shown

        override suspend fun start(title: String, text: String, progress: Int): KeepAliveResult {
            if (failOnStart) throw IllegalStateException("前台服务被禁用")
            calls += Call("start", title, text, progress)
            return KeepAliveResult(running = true, notification = notification)
        }

        override suspend fun update(text: String, progress: Int): KeepAliveResult {
            if (failOnUpdate) throw IllegalStateException("通知刷新失败")
            calls += Call("update", "", text, progress)
            return KeepAliveResult(running = true, notification = notification)
        }

        override suspend fun stop(): KeepAliveResult {
            if (failOnStop) throw IllegalStateException("服务已被回收")
            calls += Call("stop", "", "", -1)
            return KeepAliveResult(running = false, notification = notification)
        }
    }

    @Test
    fun startSendsFormattedTextAndMarksActive() = runTest {
        val native = FakeNative()
        val controller = KeepAliveController(native)

        val result = controller.start("job.nc", 0.0)

        assertEquals(1, native.calls.size)
        val call = native.calls.single()
        assertEquals("start", call.kind)
        assertEquals(NOTIFICATION_TITLE, call.title)
        assertEquals("job.nc · 0%", call.text)
        assertEquals(0, call.progress)
        assertTrue(controller.isActive)
        assertEquals(0, controller.lastPercent)
        assertTrue(result.running)
    }

    @Test
    fun startTruncatesLongJobNameTo24CodeUnits() = runTest {
        val native = FakeNative()
        val controller = KeepAliveController(native)
        val long = "abcdefghijklmnopqrstuvwxyz0123456789.nc"

        controller.start(long, 0.0)

        // 前 24 个 UTF-16 code unit + 省略号（v2 truncateName 的语义）
        assertEquals("abcdefghijklmnopqrstuvwx… · 0%", native.calls.single().text)
    }

    @Test
    fun repeatedStartIsIdempotentAndResetsThrottle() = runTest {
        val native = FakeNative()
        val controller = KeepAliveController(native)

        controller.start("a.nc", 0.0)
        controller.updateProgress(50.0, 100.0)
        controller.start("b.nc", 0.0)

        assertEquals(listOf("start", "update", "start"), native.calls.map { it.kind })
        assertEquals("b.nc · 0%", native.calls.last().text)
        assertEquals(0, controller.lastPercent, "重复 start 必须把节流状态重置为初始百分比")
    }

    @Test
    fun updateIsSkippedWhenNotActive() = runTest {
        val native = FakeNative()
        val controller = KeepAliveController(native)

        controller.updateProgress(50.0, 100.0)

        assertTrue(native.calls.isEmpty(), "未保活时不应给原生层发任何调用")
    }

    @Test
    fun updateIsSkippedWhenPercentUnchanged() = runTest {
        val native = FakeNative()
        val controller = KeepAliveController(native)
        controller.start("job.nc", 0.0)

        // 1% 的变化（0 -> 1）会被发出；同为 1% 的后续更新必须被跳过
        controller.updateProgress(1.0, 100.0)
        controller.updateProgress(1.4, 100.0)
        controller.updateProgress(1.0, 100.0)

        assertEquals(1, native.calls.count { it.kind == "update" }, "百分比没变就不该刷通知")
    }

    @Test
    fun updateSendsWhenPercentChangesAndKeepsLastValue() = runTest {
        val native = FakeNative()
        val controller = KeepAliveController(native)
        controller.start("job.nc", 0.0)

        controller.updateProgress(37.0, 100.0)
        controller.updateProgress(38.0, 100.0)

        val updates = native.calls.filter { it.kind == "update" }
        assertEquals(listOf("job.nc · 37%", "job.nc · 38%"), updates.map { it.text })
        assertEquals(listOf(37, 38), updates.map { it.progress })
    }

    @Test
    fun finalHundredPercentIsNotDropped() = runTest {
        val native = FakeNative()
        val controller = KeepAliveController(native)
        controller.start("job.nc", 0.0)

        controller.updateProgress(99.0, 100.0)
        controller.updateProgress(100.0, 100.0)

        assertEquals("job.nc · 100%", native.calls.last().text, "最后一个进度不能丢（v2 注释明确要求）")
    }

    @Test
    fun updateFailureIsSwallowed() = runTest {
        val native = FakeNative()
        val controller = KeepAliveController(native)
        controller.start("job.nc", 0.0)
        native.failOnUpdate = true

        // 不应抛异常：保活本身不依赖通知刷新
        controller.updateProgress(50.0, 100.0)

        assertEquals(50, controller.lastPercent, "失败也必须记住已发送的百分比，避免每次 tick 重试")
    }

    @Test
    fun stopAlwaysCallsNativeAndResetsState() = runTest {
        val native = FakeNative()
        val controller = KeepAliveController(native)

        // 未 start 过也调用一次：清理上一次崩溃/刷新遗留的僵尸通知（v2 注释）
        controller.stop()
        assertEquals(listOf("stop"), native.calls.map { it.kind })

        controller.start("job.nc", 10.0)
        controller.stop()

        assertEquals(listOf("stop", "start", "stop"), native.calls.map { it.kind })
        assertFalse(controller.isActive)
        assertEquals(KeepAliveController.NOT_SENT, controller.lastPercent)
        assertEquals("", controller.jobName)
    }

    @Test
    fun stopFailureIsSwallowedAndStateStillResets() = runTest {
        val native = FakeNative()
        val controller = KeepAliveController(native)
        controller.start("job.nc", 0.0)
        native.failOnStop = true

        controller.stop()

        assertFalse(controller.isActive)
        assertEquals(KeepAliveController.NOT_SENT, controller.lastPercent)
    }

    @Test
    fun startFailurePropagatesButLeavesControllerInactive() = runTest {
        val native = FakeNative()
        val controller = KeepAliveController(native)
        native.failOnStart = true

        assertFailsWith<IllegalStateException> { controller.start("job.nc", 0.0) }

        assertFalse(controller.isActive)
        assertEquals(KeepAliveController.NOT_SENT, controller.lastPercent)
    }

    @Test
    fun withoutNativeImplementationEverythingIsNoOp() = runTest {
        val controller = KeepAliveController(native = null)

        val result = controller.start("job.nc", 0.0)
        assertEquals(KeepAliveController.NOOP_RESULT, result)
        assertFalse(controller.isActive, "没有原生实现时不得进入 active 状态")

        controller.updateProgress(50.0, 100.0)
        controller.stop()

        assertFalse(controller.isActive)
        assertNull(controller.lastResult)
    }

    @Test
    fun notificationStateNamesMatchV2Strings() {
        // v2 的 type 是 'shown' | 'denied' | 'unsupported'，Phase 4 的 UI 可能按名字解析
        assertEquals(KeepAliveNotification.Shown, KeepAliveNotification.fromName("shown"))
        assertEquals(KeepAliveNotification.Denied, KeepAliveNotification.fromName("denied"))
        assertEquals(KeepAliveNotification.Unsupported, KeepAliveNotification.fromName("unsupported"))
        assertNull(KeepAliveNotification.fromName("nope"))
    }
}
