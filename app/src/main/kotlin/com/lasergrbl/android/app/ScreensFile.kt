package com.lasergrbl.android.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.theme.LiquidTheme
import java.io.File

/**
 * 雕刻文件 —— v2 `src/ui/views/FileView.vue`（249 行）的 Compose 版（`/file`，tab file）。
 *
 * ### 逐块对应
 * | v2 | 这里 |
 * | --- | --- |
 * | 隐藏 `<input type=file>` 载入本地 G 代码（`utils.ts:14-18`） | [rememberGcodeFilePicker]（`ActivityResultContracts.OpenDocument`） |
 * | 已存文件列表 + 载入/删除 | 「已存文件」卡片（[GcodeFileStore]，应用私有目录） |
 * | 统计（行数 / 路径长度 / 预计时间） | 「文件统计」卡片 |
 * | `GcodePreview` | [GcodePreview] |
 * | 按钮 8 个（载入 / 运行 / 暂停 / 中止 / 预览 / 另存 / 删除 / 清空） | 见下 |
 *
 * ### 与 v2 的三处差异（都是刻意的，都写在这里）
 * 1. **已存文件落在应用私有目录**，不入 SharedPreferences：v2 的 `saveFile` 把整份 G 代码
 *    塞进 localStorage（几 MB 字符串）。语义相同（都是"本地已存"），但不会撑爆设置存储。
 * 2. **「另存」得到的是规范化后的 G 代码**：`:core` 的 `GcodeFileData` 只保留解析结果
 *    （命令列表），没有原始文本，注释与空行在解析时已被剥掉。所以另存不是逐字节原文。
 *    这条与 v2 也不同（v2 存的是 `file.text` 原文）。
 * 3. v2 的 7 处 `toast` → `container.toasts.show(...)`（默认 2.2 s，与 v2 一致）。
 */
@Composable
fun FileScreen(container: AppContainer, backdrop: Backdrop) {
    val grbl = container.grbl
    val navigator = container.navigator
    val colors = LiquidTheme.colors
    val context = container.context

    var savedNames by remember { mutableStateOf(GcodeFileStore.list(context)) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    val picker = rememberGcodeFilePicker(context) { name, text ->
        grbl.loadGcodeText(name, text)
        container.toasts.show("已载入 $name")
    }

    GlassScreenBody {
        // ---- 载入 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSectionHeader("载入 G 代码")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "folder",
                        label = "打开文件",
                        onClick = { picker.launch() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        accent = true
                    )
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = "close",
                        label = "卸载",
                        onClick = { grbl.clearFile() },
                        modifier = Modifier.weight(1f),
                        size = GlassButtonSize.Small,
                        plain = true,
                        enabled = grbl.file != null
                    )
                }
                GlassText(
                    text = "支持 .gcode / .nc / .txt",
                    color = colors.textFaint,
                    fontSize = 11.5.sp
                )
            }
        }

        // ---- 当前文件 ----
        val file = grbl.file
        if (file == null) {
            GlassCard(backdrop) { GlassEmpty("尚未载入任何文件") }
        } else {
            GlassCard(backdrop, padding = PaddingValues(10.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            GlassText(file.name, color = colors.text, fontSize = 15.sp)
                            GlassText(
                                "${file.stats.totalLines} 行 · ${file.stats.motionCommands} 条移动",
                                color = colors.textFaint,
                                fontSize = 11.5.sp
                            )
                        }
                        GlassBadge(
                            text = if (grbl.running) "运行中" else "就绪",
                            kind = if (grbl.running) BadgeKind.Ok else BadgeKind.Idle
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    GcodePreview(
                        preview = file.preview,
                        bbox = file.stats.bbox,
                        progress = progressFraction(grbl.progress),
                        height = 200.dp
                    )
                }
            }

            // ---- 统计 ----
            GlassCard(backdrop) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassSectionHeader("文件统计")
                    GlassKeyValue("总行数", file.stats.totalLines.toString(), mono = true)
                    GlassKeyValue("移动指令", file.stats.motionCommands.toString(), mono = true)
                    GlassKeyValue("路径长度", "${formatOne(file.stats.pathLengthMm)} mm", mono = true)
                    GlassKeyValue("预计时间", formatDuration(file.stats.estimatedSeconds), mono = true)
                    GlassKeyValue(
                        "包围盒",
                        if (file.stats.bbox.valid) {
                            "${file.stats.bbox.minX.toInt()} × ${file.stats.bbox.minY.toInt()} → " +
                                "${file.stats.bbox.maxX.toInt()} × ${file.stats.bbox.maxY.toInt()}"
                        } else {
                            "无"
                        },
                        mono = true
                    )
                }
            }

            // ---- 任务与文件操作 ----
            GlassCard(backdrop) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassSectionHeader("任务")
                    GlassProgressLine(progressFraction(grbl.progress), showLabel = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassIconButton(
                            backdrop = backdrop,
                            icon = "play",
                            label = "运行",
                            onClick = { grbl.runFile() },
                            modifier = Modifier.weight(1f),
                            size = GlassButtonSize.Small,
                            accent = true,
                            enabled = grbl.connected && !grbl.running
                        )
                        GlassIconButton(
                            backdrop = backdrop,
                            icon = "pause",
                            label = "暂停",
                            onClick = { grbl.feedHold() },
                            modifier = Modifier.weight(1f),
                            size = GlassButtonSize.Small,
                            enabled = grbl.connected && grbl.running
                        )
                        GlassIconButton(
                            backdrop = backdrop,
                            icon = "stop",
                            label = "中止",
                            onClick = { grbl.abortProgram() },
                            modifier = Modifier.weight(1f),
                            size = GlassButtonSize.Small,
                            enabled = grbl.running
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassIconButton(
                            backdrop = backdrop,
                            icon = "eye",
                            label = "预览",
                            onClick = { navigator.push(Screen.Preview()) },
                            modifier = Modifier.weight(1f),
                            size = GlassButtonSize.Small,
                            plain = true
                        )
                        GlassIconButton(
                            backdrop = backdrop,
                            icon = "save",
                            label = "另存",
                            onClick = {
                                val saved = GcodeFileStore.save(context, file.name, normalizedText(grbl))
                                savedNames = GcodeFileStore.list(context)
                                container.toasts.show(if (saved != null) "已保存 $saved" else "保存失败")
                            },
                            modifier = Modifier.weight(1f),
                            size = GlassButtonSize.Small,
                            plain = true
                        )
                    }
                }
            }
        }

        // ---- 已存文件 ----
        GlassCard(backdrop) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSectionHeader("已存文件（${savedNames.size}）")
                if (savedNames.isEmpty()) {
                    GlassEmpty("还没有保存过文件")
                } else {
                    savedNames.forEach { saved ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            GlassText(
                                saved,
                                color = colors.text,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            GlassIconButton(
                                backdrop = backdrop,
                                icon = "download",
                                label = "载入",
                                onClick = {
                                    val text = GcodeFileStore.read(context, saved)
                                    if (text != null) {
                                        grbl.loadGcodeText(saved, text)
                                        container.toasts.show("已载入 $saved")
                                    } else {
                                        container.toasts.show("读取失败：$saved")
                                    }
                                },
                                size = GlassButtonSize.Mini,
                                plain = true
                            )
                            GlassIconButton(
                                backdrop = backdrop,
                                icon = "trash",
                                label = "删除",
                                onClick = { pendingDelete = saved },
                                size = GlassButtonSize.Mini,
                                plain = true
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认走 overlay 槽（Phase 1 结论：不能用 Dialog）
    val target = pendingDelete
    if (target != null) {
        LaunchedEffect(target) {
            container.confirm = GlassConfirmState(
                title = "删除已存文件",
                message = "确定删除「$target」吗？该操作不可撤销。",
                confirmText = "删除",
                destructive = true,
                onConfirm = {
                    GcodeFileStore.delete(context, target)
                    savedNames = GcodeFileStore.list(context)
                    if (grbl.file?.name == target) grbl.clearFile()
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null }
            )
        }
    }
}

/** 预计时间显示（秒 → `1h 02m` / `3m 05s` / `12s`）。 */
fun formatDuration(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0) return "0s"
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> String.format(java.util.Locale.US, "%dh %02dm", h, m)
        m > 0 -> String.format(java.util.Locale.US, "%dm %02ds", m, s)
        else -> "${s}s"
    }
}

/** 一位小数（统计显示）。 */
fun formatOne(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

/**
 * 当前文件的**规范化** G 代码文本（用于另存）。
 *
 * ⚠️ `GcodeFileData` 只保留解析结果，没有原始文本；注释与空行在解析时已被剥掉，
 * 所以这里拼回的是"规范化后的 G 代码"，**不是**逐字节原文。
 * v2 的另存存的是 `file.text` 原文 —— 这条差异登记在 `docs/PHASE4-PROGRESS.md`。
 */
private fun normalizedText(grbl: GrblController): String =
    grbl.file?.commands?.joinToString("\n") { it.serialData.trimEnd('\n') } ?: ""

/**
 * 已存 G 代码的本地存储 —— v2 `saveFile`/`loadSavedFile`/`deleteSavedFile` 的 3.0 实现。
 *
 * 落在 `context.filesDir/gcode/` 下；`list()` 只返回文件名（`.gcode` 后缀）。
 */
object GcodeFileStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "gcode").apply { if (!exists()) mkdirs() }

    fun list(context: Context): List<String> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
            ?.map { it.name.removeSuffix(SUFFIX) }
            ?.sorted()
            ?: emptyList()

    /** 写入并返回最终文件名（失败返回 null）。 */
    fun save(context: Context, name: String, text: String): String? {
        val safe = sanitize(name)
        return try {
            File(dir(context), "$safe$SUFFIX").writeText(text, Charsets.UTF_8)
            safe
        } catch (_: Throwable) {
            null
        }
    }

    fun read(context: Context, name: String): String? =
        try {
            val f = File(dir(context), "${sanitize(name)}$SUFFIX")
            if (f.isFile) f.readText(Charsets.UTF_8) else null
        } catch (_: Throwable) {
            null
        }

    fun delete(context: Context, name: String) {
        runCatching { File(dir(context), "${sanitize(name)}$SUFFIX").delete() }
    }

    /** 去掉路径分隔符与危险字符，保留中文。 */
    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').removeSuffix(SUFFIX)
        val cleaned = base.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c.code > 127) c else '_'
        }.joinToString("")
        return cleaned.ifBlank { "untitled" }.take(80)
    }

    private const val SUFFIX = ".gcode"
}
