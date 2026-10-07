package com.lasergrbl.core.golden

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 黄金样本里的位图解码。
 *
 * 生成器为了控制体积，把 RGBA 位图编码成 `palette-rle`：
 * ```
 * { width, height, layout: "RGBA", palette: [[r,g,b,a], ...], runs: [[count, paletteIndex], ...] }
 * ```
 * ⚠️ `runs` 的顺序是 **[重复次数, 调色板下标]**（见 `tools/golden/generate.ts` 的 `encodeImage`），别写反。
 * 展开后就是 v2 `ImageData` 的 `data`（长度 `w*h*4`，RGBA 顺序，每个 0..255）。
 */
object GoldenBitmap {

    fun decode(node: JsonObject): IntArray {
        val width = Golden.int(node, "width")
        val height = Golden.int(node, "height")
        val palette = node["palette"]?.jsonArray?.map { entry ->
            entry.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
        } ?: error("位图缺少 palette")
        val runs = node["runs"]?.jsonArray?.map { run ->
            val pair = run.jsonArray
            pair[0].jsonPrimitive.content.toInt() to pair[1].jsonPrimitive.content.toInt()
        } ?: error("位图缺少 runs")

        val out = IntArray(width * height * 4)
        var pixel = 0
        for ((count, paletteIndex) in runs) {
            val rgba = palette[paletteIndex]
            repeat(count) {
                val base = pixel * 4
                out[base] = rgba[0]
                out[base + 1] = rgba[1]
                out[base + 2] = rgba[2]
                out[base + 3] = rgba[3]
                pixel++
            }
        }
        check(pixel == width * height) {
            "palette-rle 展开后像素数不符：$pixel != ${width * height}"
        }
        return out
    }

    /** 把 RGBA 数据渲染成样本里的 `#`/`.` 行（`#` = 255 白）。 */
    fun toRows(data: IntArray, width: Int, height: Int): List<String> {
        val rows = ArrayList<String>(height)
        for (y in 0 until height) {
            val sb = StringBuilder(width)
            for (x in 0 until width) {
                sb.append(if (data[(y * width + x) * 4] == 255) '#' else '.')
            }
            rows.add(sb.toString())
        }
        return rows
    }

    fun sha256Rgba(data: IntArray): String {
        val bytes = ByteArray(data.size)
        for (i in data.indices) bytes[i] = data[i].toByte()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
