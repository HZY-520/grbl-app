/**
 * GRBL 机器参数预设（.nc）解析
 * 预设文件即 GRBL 的 `$$` 设置导出格式，每行形如 `$130=50.000`。
 * 解析时容忍空行、分号注释（; ...）与圆括号注释（(...)）及前后空白。
 */

export interface PresetEntry {
  /** 参数编号，如 130 对应 $130 */
  id: number
  /** 参数值 */
  value: number
  /** 规范化后的原始文本行 */
  raw: string
}

export interface ParseResult {
  entries: PresetEntry[]
  /** 被忽略的行数（空行之外的非法 / 注释行） */
  skipped: number
}

const LINE_RE = /^\$(\d+)\s*=\s*([-+]?(?:\d+\.?\d*|\.\d+))/

/** 解析 GRBL 设置预设文本；同一参数重复出现时以最后一次为准 */
export function parseSettingsPreset(text: string): ParseResult {
  const entries: PresetEntry[] = []
  const index = new Map<number, number>()
  let skipped = 0

  for (const rawLine of text.split(/\r?\n/)) {
    const trimmed = rawLine.trim()
    if (!trimmed) continue

    // 去掉分号注释与圆括号注释，避免注释内容干扰解析
    const line = trimmed.split(';')[0].replace(/\([^)]*\)/g, ' ').trim()
    const m = LINE_RE.exec(line)
    if (!m) {
      skipped++
      continue
    }

    const id = Number.parseInt(m[1], 10)
    const value = Number.parseFloat(m[2])
    if (!Number.isFinite(id) || !Number.isFinite(value)) {
      skipped++
      continue
    }

    const existing = index.get(id)
    if (existing !== undefined) {
      entries[existing] = { id, value, raw: line }
    } else {
      index.set(id, entries.length)
      entries.push({ id, value, raw: line })
    }
  }

  return { entries, skipped }
}