package com.lasergrbl.core.grbl

import com.lasergrbl.core.Emitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 基础类型的一致性测试 —— 对照 v2 `src/core/grbl/types.ts` 的行为。
 * 这些断言不是「自选动作」，而是从 TS 代码逐条读出来的语义。
 */
class TypesTest {

    @Test
    fun threadingModesMatchV2OrderAndValues() {
        val all = ThreadingMode.all
        assertEquals(listOf("Slow", "Quiet", "Fast", "UltraFast", "Insane"), all.map { it.name })
        assertEquals(2000, ThreadingMode.Slow.statusQuery)
        assertEquals(15, ThreadingMode.Slow.txLong)
        assertEquals(4, ThreadingMode.Slow.txShort)
        assertEquals(2, ThreadingMode.Slow.rxLong)
        assertEquals(1, ThreadingMode.Slow.rxShort)
        assertEquals(200, ThreadingMode.Insane.statusQuery)
        assertEquals(0, ThreadingMode.Insane.txShort)
    }

    @Test
    fun gPointArithmeticAndStringMatchJsNumberFormat() {
        val a = GPoint(1.5, -2.0, 0.0)
        val b = GPoint(0.5, 2.0, 3.0)
        assertEquals(GPoint(2.0, 0.0, 3.0), a + b)
        assertEquals(GPoint(1.0, -4.0, -3.0), a - b)
        // JS 的 `${0}` 是 "0" 而不是 "0.0"
        assertEquals("X1.5 Y-2 Z0", GPoint(1.5, -2.0, 0.0).toString())
        assertEquals(GPoint.Zero, GPoint(0.0, 0.0, 0.0))
    }

    @Test
    fun macStatusLookupIsExactMatchOnly() {
        assertEquals(MacStatus.Idle, MacStatus.fromValue("Idle"))
        assertEquals(MacStatus.AutoHold, MacStatus.fromValue("AutoHold"))
        assertNull(MacStatus.fromValue("idle"))
        assertNull(MacStatus.fromValue(""))
    }

    @Test
    fun detectedIssueKeepsNegativeValues() {
        assertEquals(-1, DetectedIssue.ManualReset.value)
        assertEquals(-3, DetectedIssue.ManualAbort.value)
        assertEquals(5, DetectedIssue.MachineAlarm.value)
        assertEquals(DetectedIssue.Unknown, DetectedIssue.fromValue(999))
    }

    @Test
    fun versionCompareIsMajorThenMinorThenBuildLexicographic() {
        val v11 = GrblVersionInfo(1, 1)
        val v11f = GrblVersionInfo(1, 1, "f")
        val v09 = GrblVersionInfo(0, 9)
        val v12 = GrblVersionInfo(1, 2)

        assertEquals(0, v11.compareTo(GrblVersionInfo(1, 1)))
        assertTrue(v11f.compareTo(v11) > 0, "build 'f' 应大于空 build")
        assertTrue(v12.compareTo(v11f) > 0, "minor 优先于 build")
        assertTrue(v09.lt(v11))
        assertTrue(v12.gte(v11f))
        assertTrue(v11.compareTo(null) > 0)
        assertEquals("1.1f", v11f.toString())
        assertEquals("1.1", v11.toString())
    }

    @Test
    fun vendorFlagsAndOrturFirmwareNumber() {
        val ortur = GrblVersionInfo(1, 1, "f", "Ortur Laser Master 3", "1.7")
        assertTrue(ortur.isOrtur)
        assertFalse(ortur.isLonger)
        assertEquals(170, ortur.orturFWVersionNumber)
        assertTrue(ortur.isLuckyWiFi)
        assertEquals("Ortur Laser Master 3", ortur.machineName)

        val aufero = GrblVersionInfo(1, 1, "", "Aufero Laser 2", "1.5")
        assertTrue(aufero.isOrtur)
        assertEquals(150, aufero.orturFWVersionNumber)
        assertFalse(aufero.isLuckyWiFi)

        val longer = GrblVersionInfo(1, 1, "", "Longer Nano", "2.0")
        assertTrue(longer.isLonger)
        assertTrue(longer.isLuckyWiFi)

        val plain = GrblVersionInfo(1, 1)
        assertFalse(plain.isOrtur)
        assertFalse(plain.isLonger)
        assertEquals(0, plain.orturFWVersionNumber)
        assertNull(plain.machineName)
    }

    @Test
    fun emitterKeepsInsertionOrderAndUnsubscribes() {
        val emitter = Emitter<Int>()
        val seen = mutableListOf<Int>()
        val un1 = emitter.on { seen.add(it * 1) }
        emitter.on { seen.add(it * 10) }

        emitter.emit(1)
        assertEquals(listOf(1, 10), seen)

        un1()
        seen.clear()
        emitter.emit(2)
        assertEquals(listOf(20), seen)
        assertEquals(1, emitter.listenerCount)
    }

    @Test
    fun emitterIsolatesListenerFailures() {
        val emitter = Emitter<String>()
        val seen = mutableListOf<String>()
        emitter.errorHandler = { seen.add("error:" + it.message) }
        emitter.on { throw IllegalStateException("boom") }
        emitter.on { seen.add(it) }

        emitter.emit("payload")
        assertEquals(listOf("error:boom", "payload"), seen)
    }

    @Test
    fun emitterDispatchSurvivesListenerMutation() {
        val emitter = Emitter<Int>()
        val seen = mutableListOf<String>()
        lateinit var un2: () -> Unit
        emitter.on {
            seen.add("first")
            un2() // 派发过程中取消后面那个监听器：本次仍然要回调（v2 先拷贝数组）
        }
        un2 = emitter.on { seen.add("second") }

        emitter.emit(0)
        assertEquals(listOf("first", "second"), seen)
        assertNotNull(emitter)
    }
}
