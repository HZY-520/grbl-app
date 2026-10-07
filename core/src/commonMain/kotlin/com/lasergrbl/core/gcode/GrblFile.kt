package com.lasergrbl.core.gcode

import com.lasergrbl.core.grbl.GrblCommand
import com.lasergrbl.core.internal.jsHypot
import com.lasergrbl.core.internal.jsTrim
import kotlin.math.max
import kotlin.math.min

/**
 * G 代码文件加载、分析与预览 —— 逐行移植自 v2 `src/core/gcode/GrblFile.ts`
 * （其本身移植自 LaserGRBL `GrblFile.cs` 的加载/分析/预览部分）。
 */

data class BoundingBox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
    val valid: Boolean
)

data class PreviewMove(
    /** true = 快速空移（不切割）。 */
    val rapid: Boolean,
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double
)

data class GcodeStats(
    val totalLines: Int,
    val motionCommands: Int,
    val pathLengthMm: Double,
    val estimatedSeconds: Double,
    val bbox: BoundingBox
)

data class GcodeFileData(
    val name: String,
    val commands: List<GrblCommand>,
    val stats: GcodeStats,
    val preview: List<PreviewMove>
)

/** 去掉注释（`;` 之后、括号内）。 */
internal fun stripComments(line: String): String {
    val out = StringBuilder()
    var comment = false
    for (c in line) {
        if (c == ';' && !comment) break
        if (c == '(') comment = true
        if (!comment) out.append(c)
        if (c == ')') comment = false
    }
    return jsTrim(out.toString())
}

/** 与 v2 `/([A-Za-z])\s*(-?\d*\.?\d+)/g` 等价。 */
private val ELEMENT_REGEX = Regex("([A-Za-z])\\s*(-?\\d*\\.?\\d+)")

/**
 * 解析一行中的字母/数值对。
 *
 * ⚠️ 与 `GrblCommand.buildHelper()` 相反：这里**首次出现者胜**（v2 用 `if (!map.has(key))`），
 * 所以 `G1 X1 Y2 X3` 在这种解析下 X 仍是 1。
 */
internal fun parseElements(line: String): Map<String, Double> {
    val map = linkedMapOf<String, Double>()
    for (m in ELEMENT_REGEX.findAll(line)) {
        val key = m.groupValues[1].uppercase()
        if (!map.containsKey(key)) {
            map[key] = m.groupValues[2].toDoubleOrNull() ?: Double.NaN
        }
    }
    return map
}

/** 加载并解析 G 代码文本。 */
fun parseGcode(name: String, text: String): GcodeFileData {
    val commands = mutableListOf<GrblCommand>()
    for (raw in LINE_SPLIT.split(text)) {
        val line = jsTrim(stripComments(raw))
        if (line.isEmpty()) continue
        val cmd = GrblCommand(line)
        if (!cmd.isEmpty) commands.add(cmd)
    }
    val analysis = analyze(commands)
    return GcodeFileData(name, commands, analysis.stats, analysis.preview)
}

private val LINE_SPLIT = Regex("\\r?\\n")

/** `analyze` 的返回值（对应 v2 的 `{ stats, preview }`）。 */
data class GcodeAnalysis(val stats: GcodeStats, val preview: List<PreviewMove>)

/** 分析：包围盒、路径长度、预估时间、预览几何。 */
fun analyze(commands: List<GrblCommand>): GcodeAnalysis {
    var x = 0.0
    var y = 0.0
    var absolute = true
    var feed = 1000.0
    var laserOn = false
    var lastS = 0.0
    var total = 0.0
    var motionCommands = 0
    var estimatedSeconds = 0.0
    val rapidRate = 3000.0 // mm/min 默认空移速度

    var minX = 0.0
    var minY = 0.0
    var maxX = 0.0
    var maxY = 0.0
    var bboxValid = false

    val preview = mutableListOf<PreviewMove>()

    fun extend(px: Double, py: Double) {
        if (!bboxValid) {
            minX = px
            maxX = px
            minY = py
            maxY = py
            bboxValid = true
        } else {
            minX = min(minX, px)
            maxX = max(maxX, px)
            minY = min(minY, py)
            maxY = max(maxY, py)
        }
    }

    for (cmd in commands) {
        val e = parseElements(cmd.command)
        val g = e["G"]
        val m = e["M"]

        if (g == 90.0) {
            absolute = true
        } else if (g == 91.0) {
            absolute = false
        } else if (g == 92.0) {
            // 设置坐标偏移：把当前位置设为给定值
            e["X"]?.let { x = it }
            e["Y"]?.let { y = it }
            continue
        }
        e["F"]?.let { feed = it }
        if (m == 3.0 || m == 4.0) laserOn = true else if (m == 5.0) laserOn = false
        e["S"]?.let {
            lastS = it
            laserOn = lastS > 0
        }

        val hasX = e.containsKey("X")
        val hasY = e.containsKey("Y")
        val hasXY = hasX || hasY
        val isMotion = g == 0.0 || g == 1.0 || g == 2.0 || g == 3.0 || (g == null && hasXY)
        if (!isMotion || !hasXY) {
            // 仅 S 指令等：忽略
            continue
        }

        motionCommands++
        var nx = x
        var ny = y
        if (hasX) nx = if (absolute) e.getValue("X") else x + e.getValue("X")
        if (hasY) ny = if (absolute) e.getValue("Y") else y + e.getValue("Y")

        val dist = jsHypot(nx - x, ny - y)
        total += dist

        val rapid = !laserOn || g == 0.0
        val rate = if (rapid) rapidRate else if (feed > 0) feed else 1000.0
        if (rate > 0) {
            // 简易时间估算：距离/速度（换算成分钟）
            estimatedSeconds += (dist / rate) * 60.0
        }

        if (laserOn) {
            extend(x, y)
            extend(nx, ny)
        }

        preview.add(PreviewMove(rapid, x, y, nx, ny))
        x = nx
        y = ny
    }

    return GcodeAnalysis(
        stats = GcodeStats(
            totalLines = commands.size,
            motionCommands = motionCommands,
            pathLengthMm = total,
            estimatedSeconds = estimatedSeconds,
            bbox = BoundingBox(minX, minY, maxX, maxY, bboxValid)
        ),
        preview = preview
    )
}
