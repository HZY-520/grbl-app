package com.lasergrbl.core.grbl

import kotlin.test.Test
import kotlin.test.assertEquals

/** 对照 v2 `src/core/grbl/SettingsPreset.ts` 的行为。 */
class SettingsPresetTest {

    @Test
    fun parsesPlainLines() {
        val result = parseSettingsPreset(
            """
            ${'$'}100=250.000
            ${'$'}130=50.000
            ${'$'}31=0
            """.trimIndent()
        )
        assertEquals(3, result.entries.size)
        assertEquals(0, result.skipped)
        assertEquals(PresetEntry(100, 250.0, "\$100=250.000"), result.entries[0])
        assertEquals(50.0, result.entries[1].value)
        assertEquals(0.0, result.entries[2].value)
    }

    @Test
    fun stripsSemicolonAndParenCommentsAndSkipsGarbage() {
        val result = parseSettingsPreset(
            """
            ; 这是注释行
            ${'$'}120=1000 (最大速度)
            ${'$'}121=500 ; 行尾注释
            not a setting
            另一行垃圾
            """.trimIndent()
        )
        assertEquals(2, result.entries.size)
        assertEquals(3, result.skipped)
        assertEquals(120, result.entries[0].id)
        assertEquals(1000.0, result.entries[0].value)
        assertEquals("\$120=1000", result.entries[0].raw)
        assertEquals(500.0, result.entries[1].value)
        assertEquals("\$121=500", result.entries[1].raw)
    }

    @Test
    fun duplicateIdKeepsLastValueButFirstPosition() {
        val result = parseSettingsPreset(
            """
            ${'$'}130=50
            ${'$'}131=40
            ${'$'}130=80
            """.trimIndent()
        )
        assertEquals(2, result.entries.size)
        assertEquals(listOf(130, 131), result.entries.map { it.id })
        assertEquals(80.0, result.entries[0].value)
        assertEquals("\$130=80", result.entries[0].raw)
    }

    @Test
    fun acceptsSignsLeadingZerosAndBareDecimalPoint() {
        val result = parseSettingsPreset("\$11=-0.02\n\$12=.5\n\$13=+3.")
        assertEquals(3, result.entries.size)
        assertEquals(-0.02, result.entries[0].value)
        assertEquals(0.5, result.entries[1].value)
        assertEquals(3.0, result.entries[2].value)
    }

    @Test
    fun emptyTextYieldsNothing() {
        val result = parseSettingsPreset("")
        assertEquals(0, result.entries.size)
        assertEquals(0, result.skipped)
    }
}
