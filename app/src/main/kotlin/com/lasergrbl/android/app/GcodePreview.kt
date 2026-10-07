package com.lasergrbl.android.app

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lasergrbl.core.gcode.BoundingBox
import com.lasergrbl.core.gcode.PreviewMove
import com.lasergrbl.glasskit.theme.LiquidTheme
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * G 代码路径预览画布 —— v2 `src/ui/components/GcodePreview.vue`（462 行）的 Compose 版。
 *
 * ### 逐条对应 v2 的实现要点（见 `docs/V2-UI-INVENTORY.md` §4.3 第 7 条）
 * | v2 | 这里 |
 * | --- | --- |
 * | `devicePixelRatio` 上限 2 | `LocalDensity.density` 由 Compose 自己处理；画布用逻辑像素坐标，**不需要**手写 dpr |
 * | 按 `bw/bh/dpr` 变化才重分配位图 | Compose 每帧重建 `Path` 有开销，所以按 `(size, bbox, preview)` 缓存**归一化后的路径**（[remember]） |
 * | 分段索引 `rapids[]` / `cuts[]` / `bounds` | [buildPreviewGeometry]：一次遍历把移动按 rapid/cut 分成两条 `Path` |
 * | 进度驱动的**部分描边**：走完的实线 + 尾部 alpha 0.18 + 最后一段插值 + 发光激光头 | [drawProgress]：`shown = progress * totalLength` 逐段累积裁剪 |
 * | 指数逼近缓动 `shown += diff * (1 - exp(-dt/120))`，`dt` 钳 ≤100 ms，`|diff|<0.05` 收敛 | [rememberAnimatedProgress]：`withFrameNanos` 自调度，同样的公式与阈值 |
 * | `prefers-reduced-motion` 时直接吸附 | `instant` / `animated = false` / 系统动画缩放为 0 时吸附（[animationsDisabled]） |
 * | 清理两个 rAF 与三个 observer | Compose 的 `LaunchedEffect` 在离开组合时自动取消 |
 *
 * ### 与 v2 的两处有意差异
 * 1. **不做位图缓存**：v2 把画布内容缓存成 bitmap 再局部重绘，是因为 DOM canvas 的
 *    `drawImage` 比重新描 5000 条线段便宜；Compose 的 `Canvas` 直接描 Path 由 GPU 合成，
 *    提前拍成 bitmap 反而多一次上传。这里改为"缓存归一化 Path + 每帧只做一次变换"。
 * 2. **调色板不读 CSS 变量**（那是 DOM 的机制），直接用 `LiquidTheme.colors`。
 *
 * @param preview `:core` 解析出的移动段（`parseGcode` 的 `preview`）
 * @param bbox 包围盒；`valid = false` 或 null 时显示"无路径"
 * @param progress 0..1 的完成比例（来自 `grbl.progress.executed / total`）
 */
@Composable
fun GcodePreview(
    preview: List<PreviewMove>,
    bbox: BoundingBox?,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    animated: Boolean = true,
    height: Dp = 220.dp,
    instant: Boolean = false
) {
    val colors = LiquidTheme.colors
    val context = LocalContext.current
    val reducedMotion = remember(context) { animationsDisabled(context) }

    val geometry = remember(preview, bbox) { buildPreviewGeometry(preview, bbox) }

    // 进度：v2 的 `shown` 是"缓动后的当前进度"，与目标之间指数逼近
    val target = progress.coerceIn(0f, 1f)
    val snap = instant || !animated || reducedMotion
    val shown = rememberAnimatedProgress(target, snap)

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(LiquidTheme.dimens.cardRadius))
            .background(if (colors.isLight) Color(0xFFF2F3F7) else Color(0xFF0B0C10))
    ) {
        if (geometry == null) {
            GlassEmpty("无可预览的路径")
            return@Box
        }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            // 线性映射：把 [minX..maxX] × [minY..maxY] 装进画布（保留 5% 内边距）
            val padX = size.width * 0.05f
            val padY = size.height * 0.05f
            val drawW = max(1f, size.width - padX * 2)
            val drawH = max(1f, size.height - padY * 2)
            val spanX = max(1e-9f, geometry.spanX)
            val spanY = max(1e-9f, geometry.spanY)
            // 等比缩放，保持形状（v2 也是等比）
            val scale = min(drawW / spanX, drawH / spanY)
            val offsetX = padX + (drawW - spanX * scale) / 2f
            val offsetY = padY + (drawH - spanY * scale) / 2f

            fun map(x: Float, y: Float): Offset =
                // SVG/图像 y 向下，G 代码 y 向上 ⇒ 翻转
                Offset(offsetX + (x - geometry.minX) * scale, offsetY + (geometry.maxY - y) * scale)

            val gridColor = if (colors.isLight) Color(0x14000000) else Color(0x14FFFFFF)
            drawGrid(gridColor)

            // 快速空移：细虚线感的浅色（v2 用较低不透明度）
            drawPath(geometry.rapidPath, color = colors.textFaint.copy(alpha = 0.42f), style = Stroke(width = 1f))
            // 切割路径：完整轮廓（未走完的部分用低不透明度）
            drawPath(geometry.cutPath, color = colors.accent.copy(alpha = 0.22f), style = Stroke(width = 1.6f))

            drawProgress(
                geometry = geometry,
                shown = shown,
                map = ::map,
                accent = colors.accent,
                head = colors.accentStrong
            )
        }
    }
}

// ===================== 几何归一化（一次遍历） =====================

/**
 * 预览几何：把移动段拆成"空移 Path"与"切割段列表"。
 *
 * v2 用 `computed` 建 `rapids[]` / `cuts[]` / `bounds` 的索引，避免绘制时重走数组；
 * 这里同理：切段保留为浮点数组（要按进度逐段裁剪），空移直接合成一条 [Path]。
 */
private class PreviewGeometry(
    val rapidPath: Path,
    /** 完整切割轮廓（未走完的部分用它画低不透明度底），与 [cutSegments] 同一份数据。 */
    val cutPath: Path,
    val cutSegments: FloatArray, // 每段 4 个浮点：x1, y1, x2, y2
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float
) {
    val spanX: Float get() = maxX - minX
    val spanY: Float get() = maxY - minY

    /** 切割段总长（用于把归一化进度换算成长度）。 */
    val totalCutLength: Float

    init {
        var sum = 0f
        var i = 0
        while (i + 3 < cutSegments.size) {
            val dx = cutSegments[i + 2] - cutSegments[i]
            val dy = cutSegments[i + 3] - cutSegments[i + 1]
            sum += kotlin.math.sqrt(dx * dx + dy * dy)
            i += 4
        }
        totalCutLength = sum
    }
}

private fun buildPreviewGeometry(preview: List<PreviewMove>, bbox: BoundingBox?): PreviewGeometry? {
    if (preview.isEmpty()) return null
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE

    val rapid = Path()
    var firstRapid = true
    val cuts = ArrayList<Float>(preview.size * 2)

    for (m in preview) {
        val x1 = m.x1.toFloat()
        val y1 = m.y1.toFloat()
        val x2 = m.x2.toFloat()
        val y2 = m.y2.toFloat()
        if (x1 < minX) minX = x1
        if (x2 < minX) minX = x2
        if (y1 < minY) minY = y1
        if (y2 < minY) minY = y2
        if (x1 > maxX) maxX = x1
        if (x2 > maxX) maxX = x2
        if (y1 > maxY) maxY = y1
        if (y2 > maxY) maxY = y2

        if (m.rapid) {
            if (firstRapid) {
                rapid.moveTo(x1, y1)
                firstRapid = false
            } else {
                rapid.lineTo(x1, y1)
            }
            rapid.lineTo(x2, y2)
        } else {
            cuts.add(x1)
            cuts.add(y1)
            cuts.add(x2)
            cuts.add(y2)
        }
    }

    // bbox 有效时以它为准（v2 的 bounds 来自 GrblFile 的分析，比逐段极值更权威）
    if (bbox != null && bbox.valid) {
        minX = bbox.minX.toFloat()
        maxX = bbox.maxX.toFloat()
        minY = bbox.minY.toFloat()
        maxY = bbox.maxY.toFloat()
    }
    if (minX > maxX || minY > maxY) return null

    // 完整切割轮廓（每条段单独 moveTo，避免把不连续的段连起来）
    val cutPath = Path()
    var i = 0
    while (i + 3 < cuts.size) {
        cutPath.moveTo(cuts[i], cuts[i + 1])
        cutPath.lineTo(cuts[i + 2], cuts[i + 3])
        i += 4
    }

    return PreviewGeometry(rapid, cutPath, cuts.toFloatArray(), minX, maxX, minY, maxY)
}

// ===================== 绘制 =====================

private fun DrawScope.drawGrid(color: Color) {
    val step = size.minDimension / 6f
    if (step <= 0f) return
    var x = step
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = step
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

/**
 * 进度驱动的部分描边。
 *
 * 对应 v2：已走完的实线 + 尾部低不透明度 + 最后一段插值到点 + 发光激光头。
 * `shown` 是 0..1 的缓动进度，按**切割段累计长度**逐段吃掉。
 */
private fun DrawScope.drawProgress(
    geometry: PreviewGeometry,
    shown: Float,
    map: (Float, Float) -> Offset,
    accent: Color,
    head: Color
) {
    val total = geometry.totalCutLength
    if (total <= 0f || shown <= 0f) return
    val targetLength = total * shown.coerceIn(0f, 1f)

    var walked = 0f
    val segs = geometry.cutSegments
    var i = 0
    var lastPoint: Offset? = null
    while (i + 3 < segs.size) {
        val x1 = segs[i]
        val y1 = segs[i + 1]
        val x2 = segs[i + 2]
        val y2 = segs[i + 3]
        val dx = x2 - x1
        val dy = y2 - y1
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len > 0f) {
            val start = map(x1, y1)
            if (walked + len <= targetLength) {
                // 整段走完
                drawLine(accent, start, map(x2, y2), strokeWidth = 2.2f, cap = StrokeCap.Round)
                lastPoint = map(x2, y2)
                walked += len
            } else {
                // 最后一段：插值到当前点
                val t = ((targetLength - walked) / len).coerceIn(0f, 1f)
                val mid = map(x1 + dx * t, y1 + dy * t)
                drawLine(accent, start, mid, strokeWidth = 2.2f, cap = StrokeCap.Round)
                lastPoint = mid
                break
            }
        }
        i += 4
    }

    // 发光激光头（v2 的 drawHead 最后画，保证在最上层）
    val p = lastPoint ?: return
    drawCircle(head.copy(alpha = 0.18f), radius = 9f, center = p)
    drawCircle(head.copy(alpha = 0.35f), radius = 5f, center = p)
    drawCircle(Color.White, radius = 2.4f, center = p)
}

// ===================== 缓动 =====================

/**
 * 指数逼近的进度（v2 的 `shown += diff * (1 - Math.exp(-dt/120))`）。
 *
 * * 自调度 `withFrameNanos`；`dt` 钳到 ≤100 ms（避免后台回来时一跳到底）；
 * * `|diff| < 0.05`（百分比 → 这里是 0.0005）视为收敛，停下不再空转；
 * * [snap] 为真时直接吸附（减少动画 / `animated = false` / `instant`）。
 */
@Composable
private fun rememberAnimatedProgress(target: Float, snap: Boolean): Float {
    var shown by remember { mutableFloatStateOf(target) }
    LaunchedEffect(target, snap) {
        if (snap || abs(target - shown) < CONVERGE_EPSILON) {
            shown = target
            return@LaunchedEffect
        }
        var lastNanos = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dtMs = ((now - lastNanos) / 1_000_000.0).coerceAtMost(100.0)
            lastNanos = now
            val diff = target - shown
            if (abs(diff) < CONVERGE_EPSILON) {
                shown = target
                break
            }
            shown += (diff * (1.0 - exp(-dtMs / 120.0))).toFloat()
        }
    }
    return shown
}

/** 收敛阈值：v2 是 0.05（百分比单位），这里进度是 0..1，所以换算成 0.0005。 */
private const val CONVERGE_EPSILON = 0.0005f

/**
 * 系统是否关闭了动画（对应 v2 的 `prefers-reduced-motion`）。
 *
 * Android 的对应物是「开发者选项 → 动画时长缩放 = 关闭」（`ANIMATOR_DURATION_SCALE = 0`）。
 */
private fun animationsDisabled(context: android.content.Context): Boolean {
    return try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    } catch (_: Throwable) {
        false
    }
}
