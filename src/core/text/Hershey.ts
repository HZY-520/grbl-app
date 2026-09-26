/**
 * Hershey 矢量字体文本 → G 代码
 *
 * 移植自 LaserGRBL 的 Hershey/Hershey.cs，核心对应 CreateString / MeasureString /
 * ApplyOffset 三个方法：
 *  - 字符片段（G0/G1/M3/M5 以及裸 X/Y 续接移动）会按比例缩放并平移到当前位置；
 *  - 相邻字符之间按 HERSHEY_SPACE_BETWEEN 累加间距；
 *  - 横向字体沿 +X 排列、换行到 +Y，纵向字体沿 +Y 排列、换行到 +X。
 */
import { HERSHEY_HORIZONTAL, HERSHEY_VERTICAL, HERSHEY_SPACE_BETWEEN } from './hersheyData'

export type HersheyOrientation = 'horizontal' | 'vertical'

export interface HersheyOptions {
  /** 文字内容，支持 \n 换行 */
  text: string
  /** 字体朝向 */
  orientation: HersheyOrientation
  /** 字符高度（毫米），对应原软件的字号 */
  sizeMm: number
  /** 是否加粗（多次重复雕刻，微偏移） */
  bold: boolean
  /** 行间距倍率，默认 1.5 */
  lineSpacing?: number
  /** 起点 X / Y（毫米） */
  offsetX?: number
  /** 起点 Y */
  offsetY?: number
  /** 雕刻进给速度 mm/min */
  markSpeed: number
  /** 激光开启指令，如 'M3' 或 'M4'；若为空则使用 S 值控制 */
  laserOn: string
  /** 激光关闭指令，如 'M5' */
  laserOff: string
  /** 是否使用 S 值（PWM） */
  pwm: boolean
  /** 最大功率 S 值 */
  maxPower?: number
}

export interface HersheyResult {
  /** 生成的 G 代码行（不含换行符） */
  lines: string[]
  /** 文本包围盒（毫米），用于预览与居中，相对于起点 */
  widthMm: number
  heightMm: number
}

/**
 * 字体基准高度（字体单位）。
 * C# 的 CreateString 不做缩放，字体单位直接当作毫米使用，并把用于居中的
 * 方框尺寸固定为 2（横向 sizeY=2、纵向 sizeX=2）。实测字形高度两向均约 2.0，
 * 因此缩放因子统一取 sizeMm / 2.0。
 */
const BASE_HEIGHT_HORIZONTAL = 2.0
const BASE_HEIGHT_VERTICAL = 2.0

/** 加粗时附加雕刻的微偏移量（取字号的比例，单位毫米） */
const BOLD_OFFSET_RATIO = 0.03

/** 匹配字体片段中的 X/Y 坐标，等价于 C# 的正则 [XY]-?\d+(\.\d+)? */
const COORD_REGEX = /[XY]-?\d+(?:\.\d+)?/g

/** 与 RasterConverter 一致的数字格式化：保留 3 位小数并去掉多余的 0 */
function fmt(v: number): string {
  const r = Math.round(v * 1000) / 1000
  return String(r)
}

/** 单个字形经缩放与平移后的结果 */
interface GlyphPlacement {
  /** 已缩放、平移的 G 代码片段 */
  parts: string[]
  /** 字形横向尺寸（毫米） */
  widthMm: number
  /** 字形纵向尺寸（毫米） */
  heightMm: number
}

/**
 * 对应 C# 的 ApplyOffset：把片段中所有 X/Y 乘以 scale 再平移，
 * 并按原始字体单位统计字形尺寸（尺寸统计发生在平移之前）。
 */
function placeGlyph(parts: string[], xOffsetMm: number, yOffsetMm: number, scale: number): GlyphPlacement {
  let maxX = 0
  let minX = 0
  let maxY = 0
  let minY = 0

  const out = parts.map((part) =>
    part.replace(COORD_REGEX, (matched) => {
      const axis = matched[0]
      const raw = parseFloat(matched.slice(1))
      if (axis === 'X') {
        if (raw > maxX) maxX = raw
        if (raw < minX) minX = raw
        return `X${fmt(raw * scale + xOffsetMm)}`
      }
      if (raw > maxY) maxY = raw
      if (raw < minY) minY = raw
      return `Y${fmt(raw * scale + yOffsetMm)}`
    })
  )

  return { parts: out, widthMm: (maxX - minX) * scale, heightMm: (maxY - minY) * scale }
}

/** 把文本转成 G 代码行 */
export function textToGcode(options: HersheyOptions): HersheyResult {
  const horizontal = options.orientation !== 'vertical'
  const font = horizontal ? HERSHEY_HORIZONTAL : HERSHEY_VERTICAL
  const baseHeight = horizontal ? BASE_HEIGHT_HORIZONTAL : BASE_HEIGHT_VERTICAL
  const scale = options.sizeMm > 0 ? options.sizeMm / baseHeight : 0
  const spaceMm = HERSHEY_SPACE_BETWEEN * scale

  const lineSpacing = options.lineSpacing ?? 1.5
  const lineStepMm = options.sizeMm * lineSpacing
  const originX = options.offsetX ?? 0
  const originY = options.offsetY ?? 0

  const power = options.maxPower ?? 1000
  const laserOnRaw = options.laserOn.trim()
  const laserOff = options.laserOff.trim() || 'M5'
  // 开启指令：未指定时用 S 值控制；启用 PWM 时把 S 值跟在开启指令之后
  const onToken = laserOnRaw ? (options.pwm ? `${laserOnRaw} S${power}` : laserOnRaw) : `S${power}`

  // 加粗：除原位置外，再沿 +X、+Y 各做一次微偏移雕刻
  const boldDx = options.sizeMm * BOLD_OFFSET_RATIO
  const boldOffsets: Array<[number, number]> = options.bold
    ? [
        [0, 0],
        [boldDx, 0],
        [0, boldDx]
      ]
    : [[0, 0]]

  const lines: string[] = []
  // 与 C# 一致：先设置功率与进给、保持激光关闭
  lines.push(`${laserOff} S${power} F${options.markSpeed}`)

  const textLines = options.text.split(/\r?\n/)
  let flowMm = 0

  for (let li = 0; li < textLines.length; li++) {
    const chars = Array.from(textLines[li])
    let oX = horizontal ? originX : originX + li * lineStepMm
    let oY = horizontal ? originY + li * lineStepMm : originY
    let lineFlowMm = 0
    let rendered = 0

    for (const ch of chars) {
      const code = ch.charCodeAt(0)
      if (code < 32 || code > 126) continue
      const glyph = font[code - 32]
      if (!glyph) continue

      const placed = placeGlyph(glyph, oX, oY, scale)

      // 行内尺寸统计（对应 MeasureString：字符尺寸 + 字符间 spbwl）
      if (rendered > 0) lineFlowMm += spaceMm
      lineFlowMm += horizontal ? placed.widthMm : placed.heightMm
      rendered++

      // 每个加粗 pass 单独输出一遍字形（含激光开/关）
      for (const [bx, by] of boldOffsets) {
        const pass = bx === 0 && by === 0 ? placed : placeGlyph(glyph, oX + bx, oY + by, scale)
        for (const part of pass.parts) {
          lines.push(part.replace('M3', onToken).replace('M5', laserOff))
        }
        lines.push(laserOff)
      }

      // 前进：横向累加字符宽度，纵向累加字符高度（C# 的 oX/oY += maxX/maxY + spbwl）
      if (horizontal) oX += placed.widthMm + spaceMm
      else oY += placed.heightMm + spaceMm
    }

    if (lineFlowMm > flowMm) flowMm = lineFlowMm
  }

  const crossMm = (textLines.length - 1) * lineStepMm + options.sizeMm

  return horizontal
    ? { lines, widthMm: flowMm, heightMm: crossMm }
    : { lines, widthMm: crossMm, heightMm: flowMm }
}