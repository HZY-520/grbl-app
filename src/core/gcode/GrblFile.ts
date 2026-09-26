/**
 * G 代码文件加载、分析与预览
 * 移植自 LaserGRBL GrblFile.cs 的加载/分析/预览部分（激光雕刻相关）。
 */
import { GrblCommand } from '../grbl/GrblCommand'

export interface BoundingBox {
  minX: number
  minY: number
  maxX: number
  maxY: number
  valid: boolean
}

export interface PreviewMove {
  /** true = 快速空移（不切割） */
  rapid: boolean
  x1: number
  y1: number
  x2: number
  y2: number
}

export interface GcodeStats {
  totalLines: number
  motionCommands: number
  pathLengthMm: number
  estimatedSeconds: number
  bbox: BoundingBox
}

export interface GcodeFileData {
  name: string
  commands: GrblCommand[]
  stats: GcodeStats
  preview: PreviewMove[]
}

/** 去掉注释（; 之后、括号内） */
function stripComments(line: string): string {
  let out = ''
  let comment = false
  for (const c of line) {
    if (c === ';' && !comment) break
    if (c === '(') comment = true
    if (!comment) out += c
    if (c === ')') comment = false
  }
  return out.trim()
}

/** 解析一行中的字母/数值对 */
function parseElements(line: string): Map<string, number> {
  const map = new Map<string, number>()
  const re = /([A-Za-z])\s*(-?\d*\.?\d+)/g
  let m: RegExpExecArray | null
  while ((m = re.exec(line)) !== null) {
    const key = m[1].toUpperCase()
    if (!map.has(key)) map.set(key, parseFloat(m[2]))
  }
  return map
}

/** 加载并解析 G 代码文本 */
export function parseGcode(name: string, text: string): GcodeFileData {
  const commands: GrblCommand[] = []
  for (const raw of text.split(/\r?\n/)) {
    const line = stripComments(raw).trim()
    if (!line) continue
    const cmd = new GrblCommand(line)
    if (!cmd.isEmpty) commands.push(cmd)
  }
  const { stats, preview } = analyze(commands)
  return { name, commands, stats, preview }
}

/** 分析：包围盒、路径长度、预估时间、预览几何 */
export function analyze(commands: GrblCommand[]): { stats: GcodeStats; preview: PreviewMove[] } {
  let x = 0
  let y = 0
  let absolute = true
  let feed = 1000
  let laserOn = false
  let lastS = 0
  let total = 0
  let motionCommands = 0
  let estimatedSeconds = 0
  const rapidRate = 3000 // mm/min 默认空移速度

  const bbox: BoundingBox = { minX: 0, minY: 0, maxX: 0, maxY: 0, valid: false }
  const preview: PreviewMove[] = []

  const extend = (px: number, py: number) => {
    if (!bbox.valid) {
      bbox.minX = bbox.maxX = px
      bbox.minY = bbox.maxY = py
      bbox.valid = true
    } else {
      bbox.minX = Math.min(bbox.minX, px)
      bbox.maxX = Math.max(bbox.maxX, px)
      bbox.minY = Math.min(bbox.minY, py)
      bbox.maxY = Math.max(bbox.maxY, py)
    }
  }

  for (const cmd of commands) {
    const e = parseElements(cmd.command)
    const g = e.has('G') ? (e.get('G') as number) : null
    const m = e.has('M') ? (e.get('M') as number) : null

    if (g === 90) absolute = true
    else if (g === 91) absolute = false
    else if (g === 92) {
      // 设置坐标偏移：把当前位置设为给定值
      if (e.has('X')) x = e.get('X') as number
      if (e.has('Y')) y = e.get('Y') as number
      continue
    }
    if (e.has('F')) feed = e.get('F') as number
    if (m === 3 || m === 4) laserOn = true
    else if (m === 5) laserOn = false
    if (e.has('S')) {
      lastS = e.get('S') as number
      laserOn = lastS > 0
    }

    const hasXY = e.has('X') || e.has('Y')
    const isMotion = g === 0 || g === 1 || g === 2 || g === 3 || (g === null && hasXY)
    if (!isMotion || !hasXY) {
      if (isMotion && !hasXY) {
        // 仅 S 指令，忽略
      }
      continue
    }

    motionCommands++
    let nx = x
    let ny = y
    if (e.has('X')) nx = absolute ? (e.get('X') as number) : x + (e.get('X') as number)
    if (e.has('Y')) ny = absolute ? (e.get('Y') as number) : y + (e.get('Y') as number)

    const dist = Math.hypot(nx - x, ny - y)
    total += dist

    const rapid = !laserOn || g === 0
    const rate = rapid ? rapidRate : feed > 0 ? feed : 1000
    if (rate > 0) {
      // 简易时间估算：距离/速度 + 每段固定加速开销
      const seconds = (dist / rate) * 60
      estimatedSeconds += seconds
    }

    if (laserOn) {
      extend(x, y)
      extend(nx, ny)
    }

    preview.push({ rapid, x1: x, y1: y, x2: nx, y2: ny })
    x = nx
    y = ny
  }

  return {
    stats: {
      totalLines: commands.length,
      motionCommands,
      pathLengthMm: total,
      estimatedSeconds,
      bbox
    },
    preview
  }
}

/** 生成用于发送的命令列表（保持原顺序，含注释去除） */
export function toGcodeText(commands: GrblCommand[]): string {
  return commands.map((c) => c.command).join('\n')
}