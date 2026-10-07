package com.lasergrbl.core.golden

import com.lasergrbl.core.gcode.BoundingBox
import com.lasergrbl.core.gcode.GcodeStats
import com.lasergrbl.core.gcode.PreviewMove
import com.lasergrbl.core.gcode.analyze
import com.lasergrbl.core.gcode.parseGcode
import com.lasergrbl.core.grbl.GrblCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `parseGcode` / `analyze` 对照 v2 的黄金样本。
 *
 * 样本没有保存那份 23 行的完整 G 代码文本，但保存了**每条命令的原文**（`commands[].commandRaw`），
 * 因此 `analyze` 可以完整复现；`parseGcode` 的逐行切分与注释剥离则用 `commentEdgeCases.text` 验。
 */
class GoldenGrblFileTest {

    @Test
    fun analyzeMatchesOracle() {
        val result = Golden.result("gcode-analysis")
        val commands = Golden.array("gcode-analysis", "commands").map { element ->
            GrblCommand(Golden.string(element.asObject(), "commandRaw"))
        }
        assertTrue(commands.isNotEmpty())

        val actual = analyze(commands)
        assertStats(Golden.objectAt(result, "stats"), actual.stats, "主样本")
        assertPreview(
            result["preview"]?.jsonArray ?: error("样本缺少 preview"),
            actual.preview,
            "主样本"
        )

        // 再分析一次应当完全一致（v2 的 reanalyze）
        val again = analyze(commands)
        val reanalyze = Golden.objectAt(result, "reanalyze")
        assertStats(Golden.objectAt(reanalyze, "stats"), again.stats, "reanalyze")
        assertPreview(
            reanalyze["preview"]?.jsonArray ?: error("样本缺少 reanalyze.preview"),
            again.preview,
            "reanalyze"
        )
    }

    @Test
    fun parseGcodeCommentStrippingMatchesOracle() {
        val node = Golden.objectAt(Golden.result("gcode-analysis"), "commentEdgeCases")
        val text = Golden.string(node, "text")
        val parsed = parseGcode("edge.nc", text)

        val expectedCommands = Golden.strings(node, "commands")
        assertEquals(
            expectedCommands,
            parsed.commands.map { it.command },
            "注释剥离后的命令行"
        )
        assertStats(Golden.objectAt(node, "stats"), parsed.stats, "commentEdgeCases")
    }

    @Test
    fun duplicateWordSemanticsAreFirstWinsInFileAnalyze() {
        val dup = Golden.objectAt(Golden.result("gcode-analysis"), "duplicateWordSemantics")
        val fileParse = Golden.objectAt(dup, "fileParse")
        val fileAnalyze = Golden.objectAt(dup, "fileAnalyze")

        // parseGcode 出来的命令原文
        val parsedCommands = fileParse["commands"]?.let { array ->
            (array as JsonArray).map { GrblCommand(Golden.string(it.asObject(), "mLine")) }
        } ?: error("样本缺少 fileParse.commands")

        val actual = analyze(parsedCommands)
        assertStats(Golden.objectAt(fileAnalyze, "stats"), actual.stats, "fileAnalyze")
        assertPreview(
            fileAnalyze["preview"]?.jsonArray ?: error("样本缺少 fileAnalyze.preview"),
            actual.preview,
            "fileAnalyze"
        )

        // 这条命令里 X 出现两次（X1 … X3）：GrblFile 的解析是"首次出现者胜" → X=1，而不是 3
        assertEquals(1.0, actual.preview[0].x2, "重复地址字应当取第一次出现的 X1")
    }

    private fun assertStats(node: JsonObject, actual: GcodeStats, label: String) {
        assertEquals(Golden.int(node, "totalLines"), actual.totalLines, "[$label] totalLines")
        assertEquals(Golden.int(node, "motionCommands"), actual.motionCommands, "[$label] motionCommands")
        assertEquals(Golden.double(node, "pathLengthMm"), actual.pathLengthMm, 0.0, "[$label] pathLengthMm")
        assertEquals(Golden.double(node, "estimatedSeconds"), actual.estimatedSeconds, 0.0, "[$label] estimatedSeconds")

        val bbox = Golden.objectAt(node, "bbox")
        val expectedBBox = BoundingBox(
            minX = Golden.double(bbox, "minX"),
            minY = Golden.double(bbox, "minY"),
            maxX = Golden.double(bbox, "maxX"),
            maxY = Golden.double(bbox, "maxY"),
            valid = Golden.boolean(bbox, "valid")
        )
        assertEquals(expectedBBox, actual.bbox, "[$label] bbox")
    }

    private fun assertPreview(array: JsonArray, actual: List<PreviewMove>, label: String) {
        assertEquals(array.size, actual.size, "[$label] preview 条数")
        array.forEachIndexed { index, element ->
            val node = element.asObject()
            val expected = PreviewMove(
                rapid = Golden.boolean(node, "rapid"),
                x1 = Golden.double(node, "x1"),
                y1 = Golden.double(node, "y1"),
                x2 = Golden.double(node, "x2"),
                y2 = Golden.double(node, "y2")
            )
            assertEquals(expected, actual[index], "[$label] preview[$index]")
        }
    }
}
