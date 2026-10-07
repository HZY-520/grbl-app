package com.lasergrbl.core.golden

import com.lasergrbl.core.grbl.CommandStatus
import com.lasergrbl.core.grbl.Element
import com.lasergrbl.core.grbl.GrblCommand
import com.lasergrbl.core.grbl.GrblConfSTIsSetConf
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GrblCommand` / `Element` 对照 v2 的黄金样本（`gcode-analysis.json` 的命令相关小节）。
 *
 * 覆盖：全部谓词、`serialData` 的空格压缩规则、`buildHelper()` 的重写与"重复地址字后者胜"、
 * `Element` 的 NaN 语义、`GrblConfSTIsSetConf`、以及发送/响应状态机。
 * （`stats` / `preview` / `reanalyze` 属于 `GrblFile`，在另一个测试里验。）
 */
class GoldenGrblCommandTest {

    @Test
    fun commandPredicatesMatchOracle() {
        val commands = Golden.array("gcode-analysis", "commands")
        assertTrue(commands.isNotEmpty())
        for (element in commands) {
            val node = element.asObject()
            val raw = Golden.string(node, "commandRaw")
            val cmd = GrblCommand(raw)

            val serialBefore = Golden.string(node, "serialDataBeforeBuildHelper")
            assertEquals(serialBefore, cmd.serialData, "[$raw] buildHelper 之前的 serialData")
            assertEquals(false, cmd.justBuilt, "[$raw] 还没 buildHelper 时 justBuilt 应为 false")

            cmd.buildHelper()
            assertCommandDetail(node, cmd, label = raw)
        }
    }

    @Test
    fun rawCommandSweepMatchesOracle() {
        val cases = Golden.array("gcode-analysis", "rawCommandSweep")
        assertTrue(cases.isNotEmpty())
        for (element in cases) {
            val node = element.asObject()
            val input = Golden.string(node, "input")
            val detail = Golden.objectAt(node, "detail")
            val cmd = GrblCommand(input)
            cmd.buildHelper()
            assertCommandDetail(detail, cmd, label = input)
        }
    }

    @Test
    fun lifecycleMatchesOracle() {
        val cases = Golden.array("gcode-analysis", "lifecycle")
        assertTrue(cases.isNotEmpty())
        for (element in cases) {
            val node = element.asObject()
            val setResult = Golden.string(node, "setResult")
            val stored = Golden.string(node, "commandStored")

            if (setResult == "(cleared)") {
                // 生成器对这一条走的是 setResult('ok') → clearResult()，没有 setSending 步骤
                val cmd = GrblCommand(stored)
                cmd.setResult("ok")
                cmd.clearResult()
                assertEquals(
                    Golden.string(node, "initialStatus"),
                    cmd.status.value,
                    "(cleared) 的最终状态"
                )
                assertEquals(Golden.stringOrNull(node, "codedResult"), cmd.codedResult, "(cleared) 的 codedResult")
                continue
            }

            val cmd = GrblCommand(stored)
            assertEquals(Golden.string(node, "initialStatus"), cmd.status.value, "[$setResult] 初始状态")

            cmd.setSending()
            assertEquals(Golden.string(node, "sendingStatus"), cmd.status.value, "[$setResult] setSending 之后")

            cmd.setResult(setResult)
            assertEquals(Golden.string(node, "statusAfter"), cmd.status.value, "[$setResult] setResult 之后")
            assertEquals(
                Golden.stringOrNull(node, "codedResult"),
                cmd.codedResult,
                "[$setResult] codedResult 的大小写/裁剪"
            )
        }
    }

    @Test
    fun elementSweepMatchesOracle() {
        val cases = Golden.array("gcode-analysis", "elementSweep")
        assertTrue(cases.isNotEmpty())
        for (element in cases) {
            val node = element.asObject()
            val input = Golden.string(node, "input")
            val e = Element.parse(input)
            assertEquals(Golden.string(node, "command"), e.command, "Element.parse(\"$input\").command")
            assertEquals(Golden.double(node, "number"), e.number, "Element.parse(\"$input\").number")
            assertEquals(Golden.string(node, "toString"), e.toString(), "Element.parse(\"$input\").toString()")
            assertEquals(Golden.boolean(node, "equalsSelf"), e == Element.parse(input), "自反相等")
        }
    }

    @Test
    fun elementEdgeCasesMatchOracle() {
        val node = Golden.objectAt(Golden.result("gcode-analysis"), "elementEdge")

        val nan = Element.parse("Y")
        assertEquals(Golden.string(node, "nanToString"), nan.toString(), "NaN 的 toString")
        assertEquals(Golden.boolean(node, "nanEqualsSelf"), nan == Element.parse("Y"), "NaN 不等于自身")
        assertEquals(Golden.boolean(node, "nanEqualsNull"), nan.equals(null), "NaN 不等于 null")

        val fromElements = GrblCommand.fromElements(listOf(Element.parse("G1"), Element.parse("X1.5")))
        assertEquals(Golden.string(node, "fromElements"), fromElements.command, "fromElements")

        val combined = GrblCommand.combine(Element.parse("G1"), GrblCommand("X2 Y3"))
        assertEquals(Golden.string(node, "combine"), combined.command, "combine")

        val cloneNode = Golden.objectAt(node, "clonePreservesResult")
        val original = GrblCommand(Golden.string(cloneNode, "command"), Golden.int(cloneNode, "repeatCount"))
        original.setResult(Golden.string(cloneNode, "codedResult"))
        val copy = original.clone()
        assertEquals(Golden.string(cloneNode, "command"), copy.command, "clone 的命令")
        assertEquals(Golden.int(cloneNode, "repeatCount"), copy.repeatCount, "clone 的 repeatCount")
        assertEquals(Golden.string(cloneNode, "codedResult"), copy.codedResult, "clone 保留 codedResult")
        assertEquals(Golden.string(cloneNode, "status"), copy.status.value, "clone 的状态")
    }

    @Test
    fun configLineDetectionMatchesOracle() {
        val cases = Golden.array("gcode-analysis", "confSweep")
        assertTrue(cases.isNotEmpty())
        for (element in cases) {
            val node = element.asObject()
            val input = Golden.string(node, "input")
            assertEquals(Golden.boolean(node, "isSetConf"), GrblConfSTIsSetConf(input), "[$input] isSetConf")
            assertEquals(Golden.boolean(node, "isGrblCommand"), GrblCommand(input).isGrblCommand, "[$input] isGrblCommand")
        }
    }

    /** `commands[]` 与 `rawCommandSweep[].detail` 共用同一套字段。 */
    private fun assertCommandDetail(node: JsonObject, cmd: GrblCommand, label: String) {
        fun check(key: String, actual: Boolean) {
            val expected = node[key] ?: return
            assertEquals(expected.toString().toBooleanStrict(), actual, "[$label] $key")
        }

        assertEquals(Golden.boolean(node, "isEmpty"), cmd.isEmpty, "[$label] isEmpty")
        check("isGrblCommand", cmd.isGrblCommand)
        check("isWriteEEPROM", cmd.isWriteEEPROM)
        check("isSetWCO", cmd.isSetWCO)
        check("isMovement", cmd.isMovement)
        check("isLinearMovement", cmd.isLinearMovement)
        check("isArcMovement", cmd.isArcMovement)
        check("isPause", cmd.isPause)
        check("isAbsoluteCoord", cmd.isAbsoluteCoord)
        check("isRelativeCoord", cmd.isRelativeCoord)
        check("isLaserON", cmd.isLaserON)
        check("isM3", cmd.isM3)
        check("isM4", cmd.isM4)
        check("isLaserOFF", cmd.isLaserOFF)
        check("isM5", cmd.isM5)
        check("isCW_withTrue", cmd.isCW(true))
        check("isCW_withFalse", cmd.isCW(false))

        Golden.intOrNull(node, "repeatCount")?.let { assertEquals(it, cmd.repeatCount, "[$label] repeatCount") }
        Golden.stringOrNull(node, "getDecodedMessage")?.let {
            assertEquals(it, cmd.getDecodedMessage(), "[$label] getDecodedMessage")
        }
        Golden.stringOrNull(node, "status")?.let {
            assertEquals(it, cmd.status.value, "[$label] status")
        }
        if (node.containsKey("codedResult")) {
            assertEquals(Golden.stringOrNull(node, "codedResult"), cmd.codedResult, "[$label] codedResult")
        }
        Golden.stringOrNull(node, "serialDataAfterBuildHelper")?.let {
            assertEquals(it, cmd.serialData, "[$label] buildHelper 之后的 serialData")
        }
        Golden.booleanOrNull(node, "justBuilt")?.let {
            assertEquals(it, cmd.justBuilt, "[$label] justBuilt")
        }
        Golden.stringOrNull(node, "commandAfterBuildHelper")?.let {
            assertEquals(it, cmd.command, "[$label] buildHelper 之后的 command")
        }
        // rawCommandSweep 里的 commandRaw 是 buildHelper 之后的值
        Golden.stringOrNull(node, "commandRaw")?.let {
            assertEquals(it, cmd.command, "[$label] command")
        }

        val elements = node["elements"]?.asObject() ?: return
        for (addr in ELEMENT_KEYS) {
            val expected = elements[addr] ?: continue
            val actual = when (addr) {
                "G" -> cmd.G
                "M" -> cmd.M
                "T" -> cmd.T
                "S" -> cmd.S
                "P" -> cmd.P
                "X" -> cmd.X
                "Y" -> cmd.Y
                "Z" -> cmd.Z
                "I" -> cmd.I
                "J" -> cmd.J
                "F" -> cmd.F
                "R" -> cmd.R
                else -> error("未知地址字 $addr")
            }
            if (expected.toString() == "null") {
                assertEquals(null, actual, "[$label] 地址字 $addr 应为 null")
            } else {
                val e = expected.asObject()
                val real = actual ?: error("[$label] 地址字 $addr 不应为 null")
                assertEquals(Golden.string(e, "command"), real.command, "[$label] $addr.command")
                assertEquals(Golden.double(e, "number"), real.number, "[$label] $addr.number")
                assertEquals(Golden.string(e, "toString"), real.toString(), "[$label] $addr.toString()")
            }
        }
    }

    private companion object {
        val ELEMENT_KEYS = listOf("G", "M", "T", "S", "P", "X", "Y", "Z", "I", "J", "F", "R")
    }
}
