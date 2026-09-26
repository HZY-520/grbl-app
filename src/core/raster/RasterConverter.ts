/**
 * 光栅图像 → G 代码转换
 * 移植自 LaserGRBL GrblFile.cs 的 LoadImageL2L / ImageLine2Line / GetSegments / OptimizeLine2Line
 * 以及 RasterConverter/ImageProcessor.cs 的预处理流程。
 */
import { Formula, dither, flipVertical, grayScale, resizeImage, testGrayScale, threshold, whitenize } from './ImageTransform'
import type { DitheringMode } from './dithering'

export type RasterTool = 'Line2Line' | 'Dithering'
export type RasterDirection = 'Horizontal' | 'Vertical' | 'Diagonal'

export const DIRECTION_LABELS: Record<RasterDirection, string> = {
  Horizontal: '水平（横向扫描）',
  Vertical: '垂直（纵向扫描）',
  Diagonal: '对角线（斜向扫描）'
}

export interface RasterOptions {
  tool: RasterTool
  direction: RasterDirection
  /** 分辨率，线/mm */
  quality: number
  /** 是否使用硬件 PWM（S 值渐变） */
  pwm: boolean
  markSpeed: number
  minPower: number
  maxPower: number
  laserOn: string
  laserOff: string
  offsetX: number
  offsetY: number
  // 预处理
  formula: Formula
  red: number
  green: number
  blue: number
  brightness: number
  contrast: number
  whiteClip: number
  useThreshold: boolean
  threshold: number
  dithering: DitheringMode
  interpolation: 'high' | 'low'
  unidirectional: boolean
  /** 是否禁用 G0 快速空移 */
  disableFastSkip: boolean
  /** 自定义文件头/尾 */
  header: string
  footer: string
}

export const DEFAULT_RASTER_OPTIONS: RasterOptions = {
  tool: 'Line2Line',
  direction: 'Horizontal',
  quality: 3,
  pwm: true,
  markSpeed: 1000,
  minPower: 0,
  maxPower: 1000,
  laserOn: 'M3',
  laserOff: 'M5',
  offsetX: 0,
  offsetY: 0,
  formula: Formula.SimpleAverage,
  red: 100,
  green: 100,
  blue: 100,
  brightness: 100,
  contrast: 100,
  whiteClip: 5,
  useThreshold: false,
  threshold: 50,
  dithering: 'FloydSteinberg',
  interpolation: 'high',
  unidirectional: false,
  disableFastSkip: false,
  header: 'G90',
  footer: 'M5\nG0 X0 Y0'
}

/** C# "0.###" 格式化 */
function fmt(v: number): string {
  const r = Math.round(v * 1000) / 1000
  if (Number.isInteger(r)) return String(r)
  return String(r)
}

/** 段类型 */
type SegKind = 'X' | 'Y' | 'D' | 'VSep' | 'HSep'

interface Segment {
  kind: SegKind
  color: number
  /** 像素长度，负值表示反向 */
  pixLen: number
}

interface L2LConf {
  res: number
  oX: number
  oY: number
  markSpeed: number
  minPower: number
  maxPower: number
  lOn: string
  lOff: string
  pwm: boolean
  dir: RasterDirection
  skipcmd: string
}

function isSeparator(s: Segment) {
  return s.kind === 'VSep' || s.kind === 'HSep'
}

function segFast(s: Segment, c: L2LConf) {
  return c.pwm ? s.color === 0 : s.color <= 125
}

function formatNumber(number: number, offset: number, c: L2LConf) {
  return fmt(Math.round((number / c.res + offset) * 1000) / 1000)
}

function formatLaserPower(color: number) {
  return `S${color}`
}

function segToGCodeNumber(s: Segment, cum: { x: number; y: number }, c: L2LConf): string {
  switch (s.kind) {
    case 'X': {
      cum.x += s.pixLen
      return c.pwm
        ? `X${formatNumber(cum.x, c.oX, c)} ${formatLaserPower(s.color)}`
        : `X${formatNumber(cum.x, c.oX, c)} ${segFast(s, c) ? c.lOff : c.lOn}`
    }
    case 'Y': {
      cum.y += s.pixLen
      return c.pwm
        ? `Y${formatNumber(cum.y, c.oY, c)} ${formatLaserPower(s.color)}`
        : `Y${formatNumber(cum.y, c.oY, c)} ${segFast(s, c) ? c.lOff : c.lOn}`
    }
    case 'D': {
      cum.x += s.pixLen
      cum.y -= s.pixLen
      return c.pwm
        ? `X${formatNumber(cum.x, c.oX, c)} Y${formatNumber(cum.y, c.oY, c)} ${formatLaserPower(s.color)}`
        : `X${formatNumber(cum.x, c.oX, c)} Y${formatNumber(cum.y, c.oY, c)} ${segFast(s, c) ? c.lOff : c.lOn}`
    }
    case 'VSep': {
      cum.y += s.pixLen
      return `Y${formatNumber(cum.y, c.oY, c)}`
    }
    case 'HSep': {
      cum.x += s.pixLen
      return `X${formatNumber(cum.x, c.oX, c)}`
    }
  }
}

/** 与 LaserGRBL GetColor 完全一致 */
function getColor(px: Uint8ClampedArray, x: number, y: number, width: number, c: L2LConf): number {
  const i = (y * width + x) * 4
  const r = px[i]
  const a = px[i + 3]
  const rv = Math.floor(((255 - r) * a) / 255)
  if (rv === 0) return 0
  if (c.pwm) return Math.floor((rv * (c.maxPower - c.minPower)) / 255) + c.minPower
  return rv
}

function extractSegment(
  px: Uint8ClampedArray,
  width: number,
  x: number,
  y: number,
  reverse: boolean,
  len: { v: number },
  prevCol: { v: number },
  out: Segment[],
  c: L2LConf
) {
  len.v++
  const col = getColor(px, x, y, width, c)
  if (prevCol.v === -1) prevCol.v = col
  if (prevCol.v !== col) {
    const kind: SegKind = c.dir === 'Horizontal' ? 'X' : c.dir === 'Vertical' ? 'Y' : 'D'
    out.push({ kind, color: prevCol.v, pixLen: reverse ? -len.v : len.v })
    len.v = 0
  }
  prevCol.v = col
}

const isEven = (v: number) => v % 2 === 0

function getSegments(px: Uint8ClampedArray, width: number, height: number, c: L2LConf, uni: boolean): Segment[] {
  const rv: Segment[] = []
  if (c.dir === 'Horizontal' || c.dir === 'Vertical') {
    const h = c.dir === 'Horizontal'
    const outer = h ? height : width
    for (let i = 0; i < outer; i++) {
      const d = uni || isEven(i)
      const len = { v: -1 }
      const prevCol = { v: -1 }
      const inner = h ? width : height
      for (let j = d ? 0 : inner - 1; d ? j < inner : j >= 0; j = d ? j + 1 : j - 1) {
        const x = h ? j : i
        const y = h ? i : j
        extractSegment(px, width, x, y, !d, len, prevCol, rv, c)
      }
      rv.push({ kind: h ? 'X' : 'Y', color: prevCol.v, pixLen: !d ? -(len.v + 1) : len.v + 1 })
      if (uni) {
        if (h) rv.push({ kind: 'X', color: 0, pixLen: -(width) })
        else rv.push({ kind: 'Y', color: 0, pixLen: -(height) })
      }
      if (i < outer - 1) rv.push(h ? { kind: 'VSep', color: 0, pixLen: 1 } : { kind: 'HSep', color: 0, pixLen: 1 })
    }
  } else {
    // 对角线
    rv.push({ kind: 'VSep', color: 0, pixLen: 1 })
    const w = width
    const h = height
    for (let slice = 0; slice < w + h - 1; ++slice) {
      const d = uni || isEven(slice)
      const len = { v: -1 }
      const prevCol = { v: -1 }
      const z1 = slice < h ? 0 : slice - h + 1
      const z2 = slice < w ? 0 : slice - w + 1
      for (let j = d ? z1 : slice - z2; d ? j <= slice - z2 : j >= z1; j = d ? j + 1 : j - 1) {
        extractSegment(px, width, j, slice - j, !d, len, prevCol, rv, c)
      }
      rv.push({ kind: 'D', color: prevCol.v, pixLen: !d ? -(len.v + 1) : len.v + 1 })
      if (uni) {
        const slen = slice - z1 - z2 + 1
        rv.push({ kind: 'D', color: 0, pixLen: -slen })
      }
      if (slice < Math.min(w, h) - 1) {
        rv.push(d && !uni ? { kind: 'HSep', color: 0, pixLen: 1 } : { kind: 'VSep', color: 0, pixLen: 1 })
      } else if (slice >= Math.max(w, h) - 1) {
        rv.push(d && !uni ? { kind: 'VSep', color: 0, pixLen: 1 } : { kind: 'HSep', color: 0, pixLen: 1 })
      } else {
        rv.push(w > h ? { kind: 'HSep', color: 0, pixLen: 1 } : { kind: 'VSep', color: 0, pixLen: 1 })
      }
    }
  }
  return rv
}

/** 把命令解析为 X/Y/S/M 值（对应 BuildHelper） */
function parseCmd(line: string) {
  const upper = line.toUpperCase()
  const get = (letter: string): number | null => {
    const m = new RegExp(`${letter}(-?[0-9.]+)`).exec(upper)
    return m ? parseFloat(m[1]) : null
  }
  return {
    X: get('X'),
    Y: get('Y'),
    S: /(^|\s)S(-?[0-9.]+)/.test(` ${upper}`) ? get('S') : null,
    M3: upper.includes('M3') || upper.includes('M4'),
    M5: upper.includes('M5'),
    isMovement: get('X') !== null || get('Y') !== null
  }
}

/** 对应 OptimizeLine2Line：合并连续的激光关闭空移 */
function optimizeLine2Line(temp: string[], c: L2LConf): string[] {
  const rv: string[] = []
  let curX = c.oX
  let curY = c.oY
  let cumulate = false
  for (const line of temp) {
    const p = parseCmd(line)
    const oldCumulate = cumulate
    if (c.pwm) {
      if (p.S !== null) cumulate = p.S === 0
    } else {
      if (p.M5) cumulate = true
      else if (p.M3) cumulate = false
    }

    if (oldCumulate && !cumulate) {
      if (c.pwm) rv.push(`${c.skipcmd} X${fmt(curX)} Y${fmt(curY)} S0`)
      else rv.push(`${c.skipcmd} X${fmt(curX)} Y${fmt(curY)} ${c.lOff}`)
    }

    if (p.isMovement) {
      if (p.X !== null) curX = p.X
      if (p.Y !== null) curY = p.Y
    }

    if (!p.isMovement || !cumulate) rv.push(line)
  }
  return rv
}

export interface RasterResult {
  lines: string[]
  pixelWidth: number
  pixelHeight: number
  res: number
  /** 预处理后的预览图 */
  preview: ImageData
}

/**
 * 主转换入口。
 * @param source 图片源
 * @param srcW 原始宽(px) @param srcH 原始高(px)
 * @param targetMmW 目标宽(mm) @param targetMmH 目标高(mm)
 */
export async function convertImageToGcode(
  source: CanvasImageSource,
  srcW: number,
  srcH: number,
  targetMmW: number,
  targetMmH: number,
  options: RasterOptions
): Promise<RasterResult> {
  const maxSize = 22000 * 22000
  const filesize = targetMmW * targetMmH
  const maxRes = Math.sqrt(maxSize / Math.max(filesize, 0.0001))
  const res = Math.min(maxRes, options.quality)
  const pixelW = Math.max(1, Math.round(targetMmW * res))
  const pixelH = Math.max(1, Math.round(targetMmH * res))

  const conf: L2LConf = {
    res,
    oX: options.offsetX,
    oY: options.offsetY,
    markSpeed: options.markSpeed,
    minPower: options.minPower,
    maxPower: options.maxPower,
    lOn: options.laserOn,
    lOff: options.laserOff,
    pwm: options.pwm,
    dir: options.direction,
    skipcmd: options.disableFastSkip ? 'G1' : 'G0'
  }

  // ---- 预处理 ----
  const resized = resizeImage(source, srcW, srcH, pixelW, pixelH, false, options.interpolation)
  const isGray = testGrayScale(resized)
  grayScale(
    resized,
    options.red,
    options.green,
    options.blue,
    -((100 - options.brightness) / 100),
    options.contrast / 100,
    isGray ? Formula.SimpleAverage : options.formula
  )
  whitenize(resized, options.whiteClip)
  if (options.tool === 'Dithering') {
    dither(resized, options.dithering)
  } else {
    threshold(resized, options.threshold / 100, options.useThreshold)
  }

  const flipped = flipVertical(resized)

  // ---- 生成 G 代码 ----
  const segments = getSegments(flipped.data, flipped.width, flipped.height, conf, options.unidirectional)
  const temp: string[] = []
  const cum = { x: 0, y: 0 }
  let fast = true

  for (const seg of segments) {
    const changeGMode = fast !== segFast(seg, conf)
    if (isSeparator(seg) && !fast) {
      temp.push(conf.pwm ? 'S0' : conf.lOff)
    }
    fast = segFast(seg, conf)
    const number = segToGCodeNumber(seg, cum, conf)
    temp.push(changeGMode ? `${fast ? conf.skipcmd : 'G1'} ${number}` : number)
  }

  const optimized = optimizeLine2Line(temp, conf)

  const lines: string[] = []
  // 自定义文件头
  if (options.header.trim()) {
    for (const l of options.header.split('\n')) if (l.trim()) lines.push(l.trim())
  }
  // 移动到起始点并设置速度
  lines.push(`${conf.skipcmd} X${fmt(conf.oX)} Y${fmt(conf.oY)} F${conf.markSpeed}`)
  if (conf.pwm) lines.push(`${conf.lOn} S0`)
  else lines.push(`${conf.lOff} S${conf.maxPower}`)

  lines.push(...optimized)

  lines.push(conf.lOff)
  if (options.footer.trim()) {
    for (const l of options.footer.split('\n')) if (l.trim()) lines.push(l.trim())
  }

  return { lines, pixelWidth: pixelW, pixelHeight: pixelH, res, preview: resized }
}