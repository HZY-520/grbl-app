package com.lasergrbl.core.golden

import com.lasergrbl.core.native.NOTIFICATION_TITLE
import com.lasergrbl.core.native.formatText
import com.lasergrbl.core.native.percentOf
import com.lasergrbl.core.native.truncateName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 保活通知格式化（截断 / 百分比 / 正文）对照 v2 的黄金样本。 */
class GoldenKeepAliveTest {

    @Test
    fun notificationTitleMatches() {
        val result = Golden.result("keepalive-format")
        assertEquals(Golden.string(result, "notificationTitle"), NOTIFICATION_TITLE)
    }

    @Test
    fun truncateNameMatchesOracle() {
        val names = Golden.array("keepalive-format", "names")
        assertTrue(names.isNotEmpty(), "样本为空说明夹具没生成")
        for (case in names) {
            val node = case.asObject()
            val input = Golden.string(node, "input")
            val expected = Golden.string(node, "output")
            assertEquals(expected, truncateName(input), "truncateName(${input.length} code units)")
        }
    }

    @Test
    fun percentOfMatchesOracle() {
        val percents = Golden.array("keepalive-format", "percents")
        assertTrue(percents.isNotEmpty())
        for (case in percents) {
            val node = case.asObject()
            val executed = Golden.string(node, "executed").toDouble()
            val total = Golden.string(node, "total").toDouble()
            val expected = Golden.int(node, "output")
            assertEquals(expected, percentOf(executed, total), "percentOf($executed, $total)")
        }
    }

    @Test
    fun formatTextMatchesOracle() {
        val formats = Golden.array("keepalive-format", "formats")
        assertTrue(formats.isNotEmpty())
        for (case in formats) {
            val node = case.asObject()
            val name = Golden.string(node, "name")
            val percent = Golden.string(node, "percent").toDouble()
            val expected = Golden.string(node, "output")
            assertEquals(expected, formatText(name, percent), "formatText(\"$name\", $percent)")
        }
    }
}
