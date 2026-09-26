/**
 * 位图 → 线性走线 G 代码（图片转线性 / 中文文字雕刻的公共核心）
 *
 * 与光栅（Line2Line / 抖动）不同，这里生成的是「真正沿图形走线」的路径：
 *  - Outline（轮廓描线）：Potrace 矢量化，沿图形内外轮廓走线，适合实心图案与汉字外轮廓；
 *  - Centerline（中心线走线）：Zhang-Suen 骨架化，沿笔画中心单线走线，最省材料与时间，
 *    适合线稿、汉字笔画、签名等（对应原项目 Centerline 模式）。
 *
 * 另外提供把文本渲染为位图（支持中文等任意字符）的辅助函数，
 * 使文字雕刻不再局限于 Hershey 的 ASCII 字符集。
 */

import { centerlineTrace } from './Centerline'
import { potraceTrace, type PotraceImage } from './Potrace'
import { polylinesToGcode, type Polyline } from './Paths'

/** 走线方式 */
export type VectorTool = 'Outline' | 'Centerline'

export const VECTOR_TOOL_LABELS: Record<VectorTool, string> = {
  Outline: '轮廓描线',
  Centerline: '中心线走线'
}

/** 位图线性转换选项 */
export interface ImageVectorOptions {
  tool: VectorTool
  /** 二值化阈值 0..255，默认 128 */
  threshold: number
  /** 反相（深底浅图时使用） */
  invert: boolean
  /** 目标宽度 (mm) */
  widthMm: number
  /** 目标高度 (mm) */
  heightMm: number
  offsetX: number
  offsetY: number
  markSpeed: number
  /** 空移速度，0 表示使用 G0 快速空移 */
  travelSpeed: number
  minPower: number
  maxPower: number
  /** 走线时使用的 S 值，缺省用 maxPower */
  laserPower?: number
  laserOn: string
  laserOff: string
  pwm: boolean
  header: string
  footer: string
  /** 是否做最近邻排序优化，默认 true */
  optimize: boolean
  // ---- Outline 专用 ----
  turdSize: number
  alphaMax: number
  optTolerance: number
  curveOptimizing: boolean
  flattenTolerance: number
  // ---- Centerline 专用 ----
  minBranchPx: number
  simplifyTolerance: number
}

/** 位图线性转换结果 */
export interface ImageVectorResult {
  lines: string[]
  pathCount: number
  lengthMm: number
  /** 二值化预览（处理分辨率，黑=出光区域） */
  preview: ImageData
}

/** 默认选项 */
export const DEFAULT_IMAGE_VECTOR_OPTIONS: ImageVectorOptions = {
  tool: 'Outline',
  threshold: 128,
  invert: false,
  widthMm: 50,
  heightMm: 50,
  offsetX: 0,
  offsetY: 0,
  markSpeed: 1000,
  travelSpeed: 3000,
  minPower: 0,
  maxPower: 1000,
  pwm: true,
  laserOn: 'M4',
  laserOff: 'M5',
  header: 'G90',
  footer: 'M5\nG0 X0 Y0',
  optimize: true,
  turdSize: 2,
  alphaMax: 1,
  optTolerance: 0.2,
  curveOptimizing: true,
  flattenTolerance: 0.2,
  minBranchPx: 6,
  simplifyTolerance: 1.2
}

/** 二值化预览：白底黑图（与 Potrace 的判定保持一致） */
function buildPreview(image: PotraceImage, threshold: number, invert: boolean): ImageData {
  const w = image.width
  const h = image.height
  const src = image.data
  const out = new Uint8ClampedArray(w * h * 4)
  for (let i = 0, n = w * h; i < n; i++) {
    const a = src[4 * i + 3] / 255
    // 合成到白底后再比较，避免透明区域被判成前景
    const r = src[4 * i] * a + 255 * (1 - a)
    const g = src[4 * i + 1] * a + 255 * (1 - a)
    const b = src[4 * i + 2] * a + 255 * (1 - a)
    let black = r + g + b < threshold * 3
    if (invert) black = !black
    const v = black ? 0 : 255
    out[4 * i] = v
    out[4 * i + 1] = v
    out[4 * i + 2] = v
    out[4 * i + 3] = 255
  }
  return new ImageData(out, w, h)
}

/**
 * 位图 → 线性 G 代码。
 * 输入位图坐标系为图像坐标系（原点左上、y 向下），输出 G 代码坐标系 y 向上。
 */
export function convertImageVector(image: PotraceImage, o: ImageVectorOptions): ImageVectorResult {
  if (!image || image.width <= 0 || image.height <= 0) {
    return { lines: [], pathCount: 0, lengthMm: 0, preview: new ImageData(1, 1) }
  }

  const polys: Polyline[] =
    o.tool === 'Centerline'
      ? centerlineTrace(image, {
          threshold: o.threshold,
          invert: o.invert,
          closed: false,
          minBranchPx: o.minBranchPx,
          simplifyTolerance: o.simplifyTolerance
        })
      : potraceTrace(image, {
          threshold: o.threshold,
          invert: o.invert,
          turdSize: o.turdSize,
          alphaMax: o.alphaMax,
          optTolerance: o.optTolerance,
          curveOptimizing: o.curveOptimizing,
          flattenTolerance: o.flattenTolerance
        })

  const sc = o.widthMm / image.width
  const g = polylinesToGcode(polys, {
    pixelSizeMm: sc,
    offsetX: o.offsetX,
    // 图像 y 向下，G 代码 y 向上：翻转后整体上移一个高度，使输出落在 [0, heightMm]
    offsetY: o.offsetY + o.heightMm,
    markSpeed: o.markSpeed,
    travelSpeed: o.travelSpeed > 0 ? o.travelSpeed : undefined,
    minPower: o.minPower,
    maxPower: o.maxPower,
    laserPower: o.laserPower,
    laserOn: o.laserOn,
    laserOff: o.laserOff,
    pwm: o.pwm,
    flipY: true,
    header: o.header,
    footer: o.footer,
    optimize: o.optimize,
    travelCommand: 'G0'
  })

  return {
    lines: g.lines,
    pathCount: g.pathCount,
    lengthMm: g.lengthMm,
    preview: buildPreview(image, o.threshold, o.invert)
  }
}

// ---------------------------------------------------------------------------
// 文本 → 位图（支持中文）
// ---------------------------------------------------------------------------

/** 文本渲染选项 */
export interface TextRenderOptions {
  text: string
  /** 单字高度 (mm) */
  sizeMm: number
  bold: boolean
  orientation: 'horizontal' | 'vertical'
  /** 行距倍率，默认 1.5 */
  lineSpacing: number
  /** CSS 字体族；缺省使用系统默认中文字体 */
  fontFamily: string
  /** 渲染时的像素字高（越大越精细），默认 180 */
  renderSizePx?: number
}

/** 文本渲染结果 */
export interface TextRenderResult {
  /** 裁剪后的位图（仅含墨迹区域） */
  image: ImageData
  /** 每毫米对应的像素数 */
  pxPerMm: number
  /** 文本实际尺寸 (mm) */
  widthMm: number
  heightMm: number
}

/** 默认中文字体栈（Android WebView 可用的系统字体） */
export const DEFAULT_TEXT_FONT = '"PingFang SC", "Noto Sans CJK SC", "Source Han Sans SC", "Microsoft YaHei", sans-serif'

interface Metrics {
  ascent: number
  descent: number
  width: number
}

/** 测量一行文本的墨迹高度与宽度 */
function measureLine(ctx: CanvasRenderingContext2D, line: string, fallback: number): Metrics {
  const m = ctx.measureText(line)
  let ascent = m.actualBoundingBoxAscent
  let descent = m.actualBoundingBoxDescent
  if (!Number.isFinite(ascent) || !Number.isFinite(descent) || (ascent === 0 && descent === 0)) {
    ascent = fallback * 0.8
    descent = fallback * 0.2
  }
  return { ascent, descent, width: m.width }
}

/** 垂直翻转 90°（顺时针）：水平文本 → 自上而下的竖排 */
function rotate90CW(src: ImageData): ImageData {
  const { width: w, height: h, data } = src
  const out = new Uint8ClampedArray(w * h * 4)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const si = (y * w + x) * 4
      // (x,y) → (h-1-y, x)
      const nx = h - 1 - y
      const ny = x
      const di = (ny * h + nx) * 4
      out[di] = data[si]
      out[di + 1] = data[si + 1]
      out[di + 2] = data[si + 2]
      out[di + 3] = data[si + 3]
    }
  }
  return new ImageData(out, h, w)
}

/** 计算墨迹包围盒（非白像素范围），无墨迹时返回 null */
function inkBox(img: ImageData): { x0: number; y0: number; x1: number; y1: number } | null {
  const { width: w, height: h, data } = img
  let x0 = w
  let y0 = h
  let x1 = -1
  let y1 = -1
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4 + 3
      if (data[i] < 128) continue
      const r = data[i - 3]
      const g = data[i - 2]
      const b = data[i - 1]
      if (r > 200 && g > 200 && b > 200) continue
      if (x < x0) x0 = x
      if (x > x1) x1 = x
      if (y < y0) y0 = y
      if (y > y1) y1 = y
    }
  }
  if (x1 < 0) return null
  return { x0, y0, x1, y1 }
}

/** 按包围盒裁剪位图（含 1px 边距） */
function cropImageData(img: ImageData, box: { x0: number; y0: number; x1: number; y1: number }): ImageData {
  const pad = 1
  const x0 = Math.max(0, box.x0 - pad)
  const y0 = Math.max(0, box.y0 - pad)
  const x1 = Math.min(img.width - 1, box.x1 + pad)
  const y1 = Math.min(img.height - 1, box.y1 + pad)
  const w = x1 - x0 + 1
  const h = y1 - y0 + 1
  const out = new Uint8ClampedArray(w * h * 4)
  for (let y = 0; y < h; y++) {
    const srcStart = ((y0 + y) * img.width + x0) * 4
    out.set(img.data.subarray(srcStart, srcStart + w * 4), y * w * 4)
  }
  return new ImageData(out, w, h)
}

/**
 * 把文本渲染为位图（白底黑字），支持中文等任意字符。
 * 输出的 image 已裁掉四周空白，pxPerMm 用于把像素坐标换算为毫米。
 */
export function renderTextToImage(o: TextRenderOptions): TextRenderResult {
  const renderSizePx = Math.max(32, Math.round(o.renderSizePx ?? 180))
  const bold = o.bold
  const font = `${bold ? '700 ' : '400 '}${renderSizePx}px ${o.fontFamily || DEFAULT_TEXT_FONT}`

  // 先在一张临时画布上测量，确定布局尺寸
  const probe = document.createElement('canvas')
  const pctx = probe.getContext('2d', { willReadFrequently: true }) as CanvasRenderingContext2D
  pctx.font = font

  const lines = o.text.split(/\r?\n/)
  const metrics = lines.map((l) => measureLine(pctx, l, renderSizePx))
  const maxInk = Math.max(renderSizePx, ...metrics.map((m) => m.ascent + m.descent))
  const lineStep = maxInk * Math.max(1, o.lineSpacing)
  const maxWidth = Math.max(renderSizePx, ...metrics.map((m) => m.width))
  const pad = Math.ceil(renderSizePx * 0.25)

  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.ceil(maxWidth + pad * 2))
  canvas.height = Math.max(1, Math.ceil(lineStep * (lines.length - 1) + maxInk + pad * 2))
  const ctx = canvas.getContext('2d', { willReadFrequently: true }) as CanvasRenderingContext2D

  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.font = font
  ctx.fillStyle = '#000000'
  ctx.textBaseline = 'alphabetic'
  ctx.textAlign = 'left'
  if (bold) {
    ctx.strokeStyle = '#000000'
    ctx.lineWidth = Math.max(1, renderSizePx * 0.03)
    ctx.lineJoin = 'round'
  }

  for (let i = 0; i < lines.length; i++) {
    if (!lines[i]) continue
    const m = metrics[i]
    const baseline = pad + i * lineStep + m.ascent
    if (bold) ctx.strokeText(lines[i], pad, baseline)
    ctx.fillText(lines[i], pad, baseline)
  }

  let img = ctx.getImageData(0, 0, canvas.width, canvas.height)
  if (o.orientation === 'vertical') img = rotate90CW(img)

  const box = inkBox(img)
  if (!box) {
    // 无墨迹（例如仅空格）：返回 1×1 空图，交由调用方提示
    return { image: new ImageData(1, 1), pxPerMm: 1, widthMm: 0, heightMm: 0 }
  }
  const cropped = cropImageData(img, box)

  // 以「单字墨迹高度」对齐 sizeMm，保证字号直观
  const scaleDenom = o.orientation === 'vertical' ? maxWidth : maxInk
  const pxPerMm = scaleDenom > 0 ? scaleDenom / o.sizeMm : 1

  return {
    image: cropped,
    pxPerMm,
    widthMm: cropped.width / pxPerMm,
    heightMm: cropped.height / pxPerMm
  }
}

/** 文本渲染 + 线性转换的合并选项 */
export interface TextVectorOptions extends TextRenderOptions {
  threshold: number
  invert: boolean
  offsetX: number
  offsetY: number
  markSpeed: number
  travelSpeed: number
  minPower: number
  maxPower: number
  laserPower?: number
  laserOn: string
  laserOff: string
  pwm: boolean
  header: string
  footer: string
  optimize: boolean
  tool: VectorTool
  turdSize: number
  alphaMax: number
  optTolerance: number
  curveOptimizing: boolean
  flattenTolerance: number
  minBranchPx: number
  simplifyTolerance: number
}

export interface TextVectorResult extends ImageVectorResult {
  /** 文本实际尺寸 (mm) */
  widthMm: number
  heightMm: number
}

/** 文本 → 线性走线 G 代码（支持中文） */
export function convertTextVector(o: TextVectorOptions): TextVectorResult {
  const r = renderTextToImage(o)
  if (r.widthMm <= 0 || r.heightMm <= 0) {
    return { lines: [], pathCount: 0, lengthMm: 0, preview: new ImageData(1, 1), widthMm: 0, heightMm: 0 }
  }
  const res = convertImageVector(
    { data: r.image.data, width: r.image.width, height: r.image.height },
    {
      ...DEFAULT_IMAGE_VECTOR_OPTIONS,
      tool: o.tool,
      threshold: o.threshold,
      invert: o.invert,
      widthMm: r.widthMm,
      heightMm: r.heightMm,
      offsetX: o.offsetX,
      offsetY: o.offsetY,
      markSpeed: o.markSpeed,
      travelSpeed: o.travelSpeed,
      minPower: o.minPower,
      maxPower: o.maxPower,
      laserPower: o.laserPower,
      laserOn: o.laserOn,
      laserOff: o.laserOff,
      pwm: o.pwm,
      header: o.header,
      footer: o.footer,
      optimize: o.optimize,
      turdSize: o.turdSize,
      alphaMax: o.alphaMax,
      optTolerance: o.optTolerance,
      curveOptimizing: o.curveOptimizing,
      flattenTolerance: o.flattenTolerance,
      minBranchPx: o.minBranchPx,
      simplifyTolerance: o.simplifyTolerance
    }
  )
  return { ...res, widthMm: r.widthMm, heightMm: r.heightMm }
}

/** 是否包含非 ASCII 字符（中文等需走位图渲染） */
export function hasNonAscii(text: string): boolean {
  return /[^\x20-\x7E]/.test(text)
}