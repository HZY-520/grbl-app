/** 应用内 G 代码文件仓库（基于 localStorage） */

export interface SavedFileMeta {
  /** 生成来源：image / text / svg / gcode */
  kind?: string
  widthMm?: number
  heightMm?: number
  note?: string
}

export interface SavedFile {
  id: string
  name: string
  lines: string[]
  createdAt: number
  meta?: SavedFileMeta
}

const KEY = 'lasergrbl.files'

export function listSavedFiles(): SavedFile[] {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return []
    const arr = JSON.parse(raw) as SavedFile[]
    if (!Array.isArray(arr)) return []
    return arr.sort((a, b) => b.createdAt - a.createdAt)
  } catch {
    return []
  }
}

function persist(files: SavedFile[]) {
  try {
    localStorage.setItem(KEY, JSON.stringify(files))
  } catch {
    /* 超出配额时静默忽略 */
  }
}

export function saveGcodeFile(name: string, lines: string[], meta?: SavedFileMeta): SavedFile {
  const files = listSavedFiles()
  const file: SavedFile = {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    name,
    lines,
    createdAt: Date.now(),
    meta
  }
  files.unshift(file)
  // 最多保留 50 个文件，避免占满存储
  persist(files.slice(0, 50))
  return file
}

export function getSavedFile(id: string): SavedFile | null {
  return listSavedFiles().find((f) => f.id === id) ?? null
}

export function deleteSavedFile(id: string) {
  persist(listSavedFiles().filter((f) => f.id !== id))
}