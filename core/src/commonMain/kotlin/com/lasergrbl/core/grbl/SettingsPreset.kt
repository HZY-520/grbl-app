package com.lasergrbl.core.grbl

/**
 * GRBL 机器参数预设（.nc）解析 —— 移植自 v2 `src/core/grbl/SettingsPreset.ts`。
 *
 * 预设文件就是 GRBL `$$` 的导出格式，每行形如 `$130=50.000`；
 * 解析容忍空行、分号注释（`; ...`）、圆括号注释（`(...)`）与前后空白。
 */
data class PresetEntry(
    /** 参数编号，如 130 对应 $130 */
    val id: Int,
    /** 参数值 */
    val value: Double,
    /** 规范化后的原始文本行 */
    val raw: String
)

data class PresetParseResult(
    val entries: List<PresetEntry>,
    /** 被忽略的行数（空行之外的非法 / 注释行） */
    val skipped: Int
)

/** 与 v2 的 `LINE_RE` 等价：`/^\$(\d+)\s*=\s*([-+]?(?:\d+\.?\d*|\.\d+))/` */
private val LINE_RE = Regex("^\\$(\\d+)\\s*=\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+))")

/** 与 v2 的 `\([^)]*\)` 等价（去掉圆括号注释）。 */
private val PAREN_COMMENT_RE = Regex("\\([^)]*\\)")

private val LINE_SPLIT_RE = Regex("\\r?\\n")

/**
 * 解析 GRBL 设置预设文本；**同一参数重复出现时以最后一次为准**（但位置保持首次出现的位置，与 v2 一致）。
 */
fun parseSettingsPreset(text: String): PresetParseResult {
    val entries = mutableListOf<PresetEntry>()
    val index = mutableMapOf<Int, Int>()
    var skipped = 0

    for (rawLine in LINE_SPLIT_RE.split(text)) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue

        // 去掉分号注释与圆括号注释，避免注释内容干扰解析
        val line = trimmed.split(';')[0].replace(PAREN_COMMENT_RE, " ").trim()
        val m = LINE_RE.find(line)
        if (m == null) {
            skipped++
            continue
        }

        val id = m.groupValues[1].toIntOrNull()
        val value = m.groupValues[2].toDoubleOrNull()
        if (id == null || value == null || !value.isFinite()) {
            skipped++
            continue
        }

        val existing = index[id]
        if (existing != null) {
            entries[existing] = PresetEntry(id, value, line)
        } else {
            index[id] = entries.size
            entries.add(PresetEntry(id, value, line))
        }
    }

    return PresetParseResult(entries, skipped)
}
