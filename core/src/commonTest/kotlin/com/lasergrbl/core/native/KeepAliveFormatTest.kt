package com.lasergrbl.core.native

import kotlin.test.Test
import kotlin.test.assertEquals

/** 对照 v2 `src/core/native/KeepAlive.ts` 的字符串 / 百分比规则。 */
class KeepAliveFormatTest {

    @Test
    fun truncateNameTrimsAndCapsAt24CodeUnits() {
        assertEquals("", truncateName(null))
        assertEquals("", truncateName("   "))
        assertEquals("abc", truncateName("  abc  "))
        // 恰好 24 个字符不截断
        val exactly24 = "a".repeat(24)
        assertEquals(exactly24, truncateName(exactly24))
        // 25 个字符 → 前 24 + 省略号
        assertEquals("a".repeat(24) + "…", truncateName("a".repeat(25)))

        // 中文同样按 UTF-16 code unit 计数（与 JS 一致）
        val cjk30 = "激光雕刻任务名称测试".repeat(3) // 30 个汉字
        assertEquals(30, cjk30.length)
        assertEquals(cjk30.substring(0, 24) + "…", truncateName(cjk30))
    }

    @Test
    fun percentOfClampsAndGuardsDivideByZero() {
        assertEquals(0, percentOf(0.0, 0.0))
        assertEquals(0, percentOf(10.0, -5.0))
        assertEquals(0, percentOf(Double.NaN, 100.0))
        assertEquals(0, percentOf(10.0, Double.NaN))
        assertEquals(50, percentOf(50.0, 100.0))
        assertEquals(33, percentOf(1.0, 3.0))
        assertEquals(100, percentOf(300.0, 100.0))
        assertEquals(0, percentOf(-10.0, 100.0))
        // Math.round 语义：.5 向 +∞
        assertEquals(3, percentOf(2.5, 100.0))
        assertEquals(70, percentOf(0.7, 1.0))
    }

    @Test
    fun formatTextMatchesV2Shape() {
        assertEquals("50%", formatText("", 50.0))
        assertEquals("50%", formatText(null, 50.0))
        assertEquals("50%", formatText("   ", 50.0))
        assertEquals("图案.nc · 50%", formatText("图案.nc", 50.0))
        assertEquals("图案.nc · 0%", formatText("图案.nc", -3.0))
        assertEquals("图案.nc · 100%", formatText("图案.nc", 999.0))
        assertEquals("图案.nc · 0%", formatText("图案.nc", Double.NaN))
        assertEquals("iGRBL 正在雕刻", NOTIFICATION_TITLE)
        assertEquals(24, MAX_NAME_LENGTH)
        assertEquals(1, PROGRESS_STEP)
    }
}
