package com.lasergrbl.glasskit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * iGRBL v2 内置图标集合（42 个，24x24 视图框）的 Compose Multiplatform 版本。
 *
 * 所有 path 数据均逐字复制自 iGRBL v2 的 `src/ui/icons.ts` 中导出的 `ICON_PATHS`：
 * 不做简化、不重绘、不改名，顺序也与 v2 完全一致。
 * 渲染方式对齐 v2 的 `src/ui/components/AppIcon.vue`：单个 `<svg viewBox="0 0 24 24">`
 * 内只有一条 `<path>`，`fill = none`、`stroke = currentColor`、`stroke-width = 1.8`、
 * 圆头圆角（round cap / round join）。
 *
 * 由于调用方统一用 `tint` 上色，矢量本身固定为 [Color.Black] 描边、无填充：
 * ```
 * Icon(imageVector = LiquidIcons.get("home"), contentDescription = null, tint = ...)
 * ```
 */
// 来源: src/ui/icons.ts / AppIcon.vue（stroke 1.8 / fill none / 24 viewBox）
object LiquidIcons {

    /** 未知图标名时的回退项，对应 v2 AppIcon 的 `ICON_PATHS[name] ?? ICON_PATHS.info` */
    private const val FALLBACK_NAME = "info"

    /** 图标名 -> SVG path 的 d 字符串，顺序与 v2 `ICON_PATHS` 完全一致 */
    private val entries: List<Pair<String, String>> = listOf(
        "home" to "M12 3 L21 11 H18 V20 H14 V15 H10 V20 H6 V11 H3 Z",
        "grid" to "M4 4 H10 V10 H4 Z M14 4 H20 V10 H14 Z M4 14 H10 V20 H4 Z M14 14 H20 V20 H14 Z",
        "image" to "M4 5 H20 V19 H4 Z M4 15 L9 11 L13 15 L16 12 L20 16",
        "text" to "M5 6 H19 M12 6 V19 M9 19 H15",
        "vector" to "M4 18 C4 8 20 16 20 6 M4 18 m -1.5 0 a 1.5 1.5 0 1 0 3 0 a 1.5 1.5 0 1 0 -3 0 M20 6 m -1.5 0 a 1.5 1.5 0 1 0 3 0 a 1.5 1.5 0 1 0 -3 0",
        "file" to "M6 3 H14 L18 7 V21 H6 Z M14 3 V7 H18",
        "folder" to "M3 6 H10 L12 8 H21 V19 H3 Z",
        "move" to "M12 2 V22 M2 12 H22 M12 2 L9 6 M12 2 L15 6 M12 22 L9 18 M12 22 L15 18 M2 12 L6 9 M2 12 L6 15 M22 12 L18 9 M22 12 L18 15",
        "crosshair" to "M12 3 V21 M3 12 H21 M12 5 A7 7 0 1 0 12 19 A7 7 0 1 0 12 5 Z",
        "settings" to "M12 8 A4 4 0 1 0 12 16 A4 4 0 1 0 12 8 Z M12 2 V5 M12 19 V22 M2 12 H5 M19 12 H22 M5 5 L7 7 M17 17 L19 19 M19 5 L17 7 M7 17 L5 19",
        "config" to "M4 7 H20 M4 12 H20 M4 17 H20 M8 5 V9 M15 10 V14 M10 15 V19",
        "terminal" to "M4 5 H20 V19 H4 Z M7 9 L10 12 L7 15 M12 15 H17",
        "usb" to "M8 3 V8 H16 V3 M16 8 V12 A4 4 0 0 1 12 16 M12 16 V21",
        "play" to "M7 4 L19 12 L7 20 Z",
        "pause" to "M8 5 H11 V19 H8 Z M13 5 H16 V19 H13 Z",
        "stop" to "M6 6 H18 V18 H6 Z",
        "refresh" to "M20 12 A8 8 0 1 1 17 6 M20 4 V9 H15",
        "back" to "M15 5 L8 12 L15 19",
        "forward" to "M9 5 L16 12 L9 19",
        "plus" to "M12 5 V19 M5 12 H19",
        "minus" to "M5 12 H19",
        "info" to "M12 3 A9 9 0 1 0 12 21 A9 9 0 1 0 12 3 Z M12 11 V17 M12 7.4 V8.4",
        "eye" to "M2 12 C5 6 19 6 22 12 C19 18 5 18 2 12 Z M12 9 A3 3 0 1 0 12 15 A3 3 0 1 0 12 9 Z",
        "power" to "M12 3 V11 M6.5 7 A8 8 0 1 0 17.5 7",
        "layers" to "M12 3 L21 8 L12 13 L3 8 Z M3 12 L12 17 L21 12 M3 16 L12 21 L21 16",
        "check" to "M5 13 L10 18 L19 6",
        "close" to "M6 6 L18 18 M18 6 L6 18",
        "trash" to "M4 7 H20 M9 7 V4 H15 V7 M6 7 L7 21 H17 L18 7 M10 11 V17 M14 11 V17",
        "target" to "M12 3 A9 9 0 1 0 12 21 A9 9 0 1 0 12 3 Z M12 8 A4 4 0 1 0 12 16 A4 4 0 1 0 12 8 Z",
        "homeZero" to "M12 3 L21 11 H18 V20 H6 V11 H3 Z M10 15 H14",
        "ruler" to "M3 9 H21 V15 H3 Z M7 9 V12 M11 9 V13 M15 9 V12 M19 9 V13",
        "speed" to "M12 20 A9 9 0 1 1 21 11 M12 11 L18 6",
        "flame" to "M12 3 C9 7 8 9 8 12 A4 4 0 0 0 16 12 C16 9 15 7 12 3 Z M12 21 A6 6 0 0 1 6 15",
        "clock" to "M12 3 A9 9 0 1 0 12 21 A9 9 0 1 0 12 3 Z M12 7 V12 L16 14",
        "save" to "M5 3 H16 L20 7 V21 H4 V3 Z M8 3 V9 H15 V3 M8 21 V14 H16 V21",
        "download" to "M12 3 V15 M7 10 L12 15 L17 10 M4 19 H20",
        "edit" to "M4 20 L4 16 L16 4 L20 8 L8 20 Z M14 6 L18 10",
        "link" to "M10 14 A4 4 0 0 1 10 8 L13 5 A4 4 0 0 1 19 11 L17 13 M14 10 A4 4 0 0 1 14 16 L11 19 A4 4 0 0 1 5 13 L7 11",
        "zoomIn" to "M11 4 A7 7 0 1 0 11 18 A7 7 0 1 0 11 4 Z M16 16 L21 21 M11 8 V14 M8 11 H14",
        "zoomOut" to "M11 4 A7 7 0 1 0 11 18 A7 7 0 1 0 11 4 Z M16 16 L21 21 M8 11 H14",
        "list" to "M8 6 H20 M8 12 H20 M8 18 H20 M4 6 H4.5 M4 12 H4.5 M4 18 H4.5",
        "bluetooth" to "M6.5 6.5 L17.5 17.5 L12 23 V1 L17.5 6.5 L6.5 17.5"
    )

    /** 名称到 path 的索引，供 [get] 查找 */
    private val pathByName: Map<String, String> = entries.toMap()

    /** 全部图标名，顺序与 v2 `ICON_PATHS` 一致 */
    val names: List<String> = entries.map { it.first }

    /** 已构建矢量缓存：[get] 命中后不会重复构建 */
    private val cache = mutableMapOf<String, ImageVector>()

    /** 全部图标（名称 -> 矢量）；首次访问时才逐个构建，之后一直复用 */
    val all: Map<String, ImageVector> by lazy { names.associateWith { get(it) } }

    /**
     * 按名称取图标矢量。
     *
     * 未知名字回退到 "info"，与 v2 AppIcon 的 `ICON_PATHS[name] ?? ICON_PATHS.info` 行为一致；
     * 构建结果会被缓存。
     */
    fun get(name: String): ImageVector {
        val key = if (pathByName.containsKey(name)) name else FALLBACK_NAME
        return cache.getOrPut(key) { build(key) }
    }

    /** 按 v2 AppIcon.vue 的参数构建单个矢量（黑色描边、无填充，交由调用方 tint） */
    private fun build(iconName: String): ImageVector = ImageVector.Builder(
        name = "LiquidIcon.$iconName",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = PathParser().parsePathString(pathByName.getValue(iconName)).toNodes(),
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ).build()
}
