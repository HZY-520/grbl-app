package com.lasergrbl.core.raster

import com.lasergrbl.core.internal.nowMillis
import kotlin.math.truncate

/**
 * 图像抖动 —— 逐行移植自 v2 `src/core/raster/dithering.ts`
 * （其本身移植自 LaserGRBL 的 `RasterConverter/Dithering` 目录，基于 Cyotek ColorReduction 的误差扩散实现）。
 *
 * ⚠️ 三个必须原样复刻的 JS 语义（夹具 `dithering.json` 全部钉住）：
 *  1. `>>` 是 **int32 算术右移**：先 ToInt32 再移位、负数向下取整；非移位矩阵则用 `Math.trunc`（朝零）；
 *  2. 写入 `Uint8ClampedArray` 时按 `toByte` 夹到 0..255；
 *  3. Random 模式的 LCG 在 JS 里是 **float64 乘法 + ToInt32**（乘积 ~2.3e18 已超出 2^53，会丢精度），
 *     用 Kotlin 的 Long 精确算术会得到完全不同的序列 —— 必须照样走 Double + ToInt32 模拟。
 */
enum class DitheringMode(val value: String) {
    Atkinson("Atkinson"),
    FloydSteinberg("FloydSteinberg"),
    Burks("Burks"),
    Jarvis("Jarvis"),
    Random("Random"),
    Sierra2("Sierra2"),
    Sierra3("Sierra3"),
    SierraLight("SierraLight"),
    Stucki("Stucki");

    companion object {
        fun fromValue(value: String): DitheringMode? = entries.firstOrNull { it.value == value }
    }
}

/** 与 v2 `DITHERING_MODES` 同序。 */
val DITHERING_MODES: List<DitheringMode> = listOf(
    DitheringMode.Atkinson,
    DitheringMode.FloydSteinberg,
    DitheringMode.Burks,
    DitheringMode.Jarvis,
    DitheringMode.Random,
    DitheringMode.Sierra2,
    DitheringMode.Sierra3,
    DitheringMode.SierraLight,
    DitheringMode.Stucki
)

/** 抖动算法中文名称。 */
val DITHERING_LABELS: Map<DitheringMode, String> = linkedMapOf(
    DitheringMode.Atkinson to "Atkinson（阿特金森）",
    DitheringMode.FloydSteinberg to "Floyd-Steinberg（弗洛伊德）",
    DitheringMode.Burks to "Burks（伯克斯）",
    DitheringMode.Jarvis to "Jarvis-Judice-Ninke（贾维斯）",
    DitheringMode.Random to "Random（随机）",
    DitheringMode.Sierra2 to "Sierra2（塞拉 2 行）",
    DitheringMode.Sierra3 to "Sierra3（塞拉 3 行）",
    DitheringMode.SierraLight to "Sierra Light（塞拉轻量）",
    DitheringMode.Stucki to "Stucki（斯图基）"
)

private class DiffMatrix(
    val matrix: Array<IntArray>,
    val divisor: Int,
    /** true 表示 divisor 是移位位数（2^divisor）。 */
    val useShifting: Boolean
)

private val MATRICES: Map<DitheringMode, DiffMatrix> = linkedMapOf(
    DitheringMode.FloydSteinberg to DiffMatrix(
        matrix = arrayOf(intArrayOf(0, 0, 7), intArrayOf(3, 5, 1)),
        divisor = 4,
        useShifting = true
    ),
    DitheringMode.Atkinson to DiffMatrix(
        matrix = arrayOf(intArrayOf(0, 0, 1, 1), intArrayOf(1, 1, 1, 0), intArrayOf(0, 1, 0, 0)),
        divisor = 3,
        useShifting = true
    ),
    DitheringMode.Burks to DiffMatrix(
        matrix = arrayOf(intArrayOf(0, 0, 0, 8, 4), intArrayOf(2, 4, 8, 4, 2)),
        divisor = 5,
        useShifting = true
    ),
    DitheringMode.Jarvis to DiffMatrix(
        matrix = arrayOf(
            intArrayOf(0, 0, 0, 7, 5),
            intArrayOf(3, 5, 7, 5, 3),
            intArrayOf(1, 3, 5, 3, 1)
        ),
        divisor = 48,
        useShifting = false
    ),
    DitheringMode.Sierra2 to DiffMatrix(
        matrix = arrayOf(intArrayOf(0, 0, 0, 4, 3), intArrayOf(1, 2, 3, 2, 1)),
        divisor = 4,
        useShifting = true
    ),
    DitheringMode.Sierra3 to DiffMatrix(
        matrix = arrayOf(
            intArrayOf(0, 0, 0, 5, 3),
            intArrayOf(2, 4, 5, 4, 2),
            intArrayOf(0, 2, 3, 2, 0)
        ),
        divisor = 5,
        useShifting = true
    ),
    DitheringMode.SierraLight to DiffMatrix(
        matrix = arrayOf(intArrayOf(0, 0, 2), intArrayOf(1, 1, 0)),
        divisor = 2,
        useShifting = true
    ),
    DitheringMode.Stucki to DiffMatrix(
        matrix = arrayOf(
            intArrayOf(0, 0, 0, 8, 4),
            intArrayOf(2, 4, 8, 4, 2),
            intArrayOf(1, 2, 4, 2, 1)
        ),
        divisor = 42,
        useShifting = false
    )
)

private fun toByte(v: Int): Int = when {
    v < 0 -> 0
    v > 255 -> 255
    else -> v
}

/** 抖动前把像素转为纯黑白（返回 0 或 255）。 */
private fun luminanceIsWhite(r: Int, g: Int, b: Int): Boolean =
    0.299 * r + 0.587 * g + 0.114 * b >= 128.0

/**
 * 对 RGBA 像素数组执行抖动。
 *
 * [data] 长度为 `width * height * 4`，RGBA 顺序，每个元素 0..255（对应 v2 的 `Uint8ClampedArray`）。
 * [nowMillis] 只在 [DitheringMode.Random] 时作为随机种子（对应 v2 的 `Date.now()`），测试里显式传入以保证确定性。
 */
fun ditherImage(
    data: IntArray,
    width: Int,
    height: Int,
    mode: DitheringMode,
    nowMillis: Long = nowMillis()
) {
    if (mode == DitheringMode.Random) {
        randomDither(data, width, height, nowMillis)
        return
    }

    val dm = MATRICES[mode] ?: MATRICES.getValue(DitheringMode.FloydSteinberg)
    val matrix = dm.matrix
    val divisor = dm.divisor
    val matrixHeight = matrix.size
    val matrixWidth = matrix[0].size

    // 计算起始偏移
    var startingOffset = 0
    for (i in 0 until matrixWidth) {
        if (matrix[0][i] != 0) {
            startingOffset = i - 1
            break
        }
    }

    for (row in 0 until height) {
        for (col in 0 until width) {
            val index = (row * width + col) * 4
            val origR = data[index]
            val origG = data[index + 1]
            val origB = data[index + 2]

            val white = luminanceIsWhite(origR, origG, origB)
            val tr = if (white) 255 else 0
            data[index] = tr
            data[index + 1] = tr
            data[index + 2] = tr

            // 误差（与 LaserGRBL 一致：redError 用 R、green 用 G、blue 用 B）
            val redError = origR - tr
            val greenError = origG - tr
            val blueError = origB - tr

            for (mrow in 0 until matrixHeight) {
                val offsetY = row + mrow
                for (mcol in 0 until matrixWidth) {
                    val coefficient = matrix[mrow][mcol]
                    val offsetX = col + (mcol - startingOffset)
                    if (coefficient != 0 && offsetX > 0 && offsetX < width && offsetY > 0 && offsetY < height) {
                        val offsetIndex = (offsetY * width + offsetX) * 4
                        val newR: Int
                        val newG: Int
                        val newB: Int
                        if (dm.useShifting) {
                            newR = (redError * coefficient) shr divisor
                            newG = (greenError * coefficient) shr divisor
                            newB = (blueError * coefficient) shr divisor
                        } else {
                            newR = truncate((redError * coefficient).toDouble() / divisor).toInt()
                            newG = truncate((greenError * coefficient).toDouble() / divisor).toInt()
                            newB = truncate((blueError * coefficient).toDouble() / divisor).toInt()
                        }
                        data[offsetIndex] = toByte(data[offsetIndex] + newR)
                        data[offsetIndex + 1] = toByte(data[offsetIndex + 1] + newG)
                        data[offsetIndex + 2] = toByte(data[offsetIndex + 2] + newB)
                    }
                }
            }
        }
    }
}

/**
 * 随机抖动（对应 v2 `randomDither`）。
 *
 * LCG 的每一步在 JS 里是：`seed = (seed * 1103515245 + 12345) & 0x7fffffff`。
 * 乘积累积到 ~2.3e18，**超过 float64 的 2^53 精度**，JS 会先丢精度再做 ToInt32；
 * 因此这里用 Double 运算 + [toInt32Js] 精确复刻，而不是用 Long。
 */
private fun randomDither(data: IntArray, width: Int, height: Int, nowMillis: Long) {
    var seed = toInt32Js(nowMillis.toDouble()) and 0x7fffffff

    fun next(): Int {
        val t = seed.toDouble() * 1103515245.0 + 12345.0
        seed = toInt32Js(t) and 0x7fffffff
        return seed % 255
    }

    for (i in 0 until width * height) {
        val idx = i * 4
        val gray = 0.299 * data[idx] + 0.587 * data[idx + 1] + 0.114 * data[idx + 2]
        if (gray > next()) {
            data[idx] = 255
            data[idx + 1] = 255
            data[idx + 2] = 255
        } else {
            data[idx] = 0
            data[idx + 1] = 0
            data[idx + 2] = 0
        }
    }
}

/**
 * 复刻 JS 的 `ToInt32`：先朝零截断，再对 2^32 取模，最后映射到 int32 区间。
 * （`NaN`/`±Infinity` → 0，与规范一致。）
 */
internal fun toInt32Js(value: Double): Int {
    if (value.isNaN() || value.isInfinite()) return 0
    val truncated = truncate(value)
    if (truncated == 0.0) return 0
    var m = truncated % 4294967296.0
    if (m < 0) m += 4294967296.0
    return if (m >= 2147483648.0) (m - 4294967296.0).toInt() else m.toInt()
}
