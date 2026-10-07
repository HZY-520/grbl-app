package com.lasergrbl.core.golden

import com.lasergrbl.core.grbl.parseSettingsPreset
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

/** `.nc` 参数预设解析对照 v2 的黄金样本。 */
class GoldenSettingsPresetTest {

    @Test
    fun parseMatchesOracle() {
        val result = Golden.result("settings-preset")
        val text = Golden.string(result, "text")
        val parse = Golden.objectAt(result, "parse")

        val actual = parseSettingsPreset(text)
        val oracleEntries = parse["entries"]?.jsonArray ?: error("样本缺少 parse.entries")

        assertEquals(oracleEntries.size, actual.entries.size, "条目数不一致")
        oracleEntries.forEachIndexed { index, element ->
            val node = element.asObject()
            val id = Golden.int(node, "id")
            assertEquals(id, actual.entries[index].id, "第 $index 条的 id")
            assertEquals(Golden.double(node, "value"), actual.entries[index].value, "id=$id 的值")
            assertEquals(Golden.string(node, "raw"), actual.entries[index].raw, "id=$id 的 raw")
        }
        assertEquals(Golden.int(parse, "skipped"), actual.skipped, "skipped 计数（覆盖注释/非法行）")
    }

    @Test
    fun emptyTextYieldsNothing() {
        val empty = Golden.objectAt(Golden.result("settings-preset"), "emptyText")
        val actual = parseSettingsPreset("")
        assertEquals(0, actual.entries.size)
        assertEquals(Golden.int(empty, "skipped"), actual.skipped)
    }
}
