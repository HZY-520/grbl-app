/**
 * SVG 线性走线（新增路径：中心线描线 / 智能）
 *
 * 背景：原先 SVG 只做「轮廓提取」——直接解析 path 几何。对线条型 SVG
 * （签名、线稿、单线图形，或已转成填充路径的文字），轮廓提取会把每条线
 * 描成外框（双边），也就是用户抱怨的「描边」而不是「描线」。
 *
 * 这里新增一条与图片中心线同源的路径：
 *   1. 把 SVG 光栅化到 canvas（data URL → Image → drawImage），
 *      分辨率可配置（长边像素，默认 1024），保证细线不断裂；
 *   2. ImageData → PotraceImage → centerlineTrace → Polyline[]；
 *   3. 复用 Paths.polylinesToGcode 输出 G 代码（与图片中心线同一套换算）。
 *
 * 智能模式：对光栅化结果做笔画宽度启发式（StrokeAnalysis）后二选一，
 * 判定为轮廓时仍走原有的 convertSvgToGcode（不栅格化，几何最精确）。
 *
 * 本文件依赖浏览器 canvas / DOMParser（光栅化不可避免），算法部分在 Centerline.ts。
 */

import { centerlineTrace } from './Centerline'
import { polylinesToGcode } from './Paths'
import type { PotraceImage } from './Potrace'
import { convertSvgToGcode, parseLength, parseViewBox, type SvgConvertOptions, type SvgConvertResult } from './SvgToGcode'
import { decideVectorMode, type VectorDecision } from './StrokeAnalysis'

/** SVG 走线方式 */
export type SvgVectorMode = 'Auto' | 'Outline' | 'Centerline'

/** 界面展示用标签 */
export const SVG_MODE_LABELS: Record<SvgVectorMode, string> = {
  Auto: '智能',
  Outline: '轮廓提取',
  Centerline: '中心线描线'
}

/** 光栅化结果 */
export interface SvgRasterResult {
  /** 位图（RGBA，ImageData 布局；默认白底） */
  image: PotraceImage
  pixelWidth: number
  pixelHeight: number
  /** SVG 内容尺寸（用户坐标 / viewBox 单位） */
  contentWidth: number
  contentHeight: number
}

/** SVG 光栅化选项 */
export interface SvgRasterOptions {
  /** 光栅化长边像素数，默认 1024（越大越精细，细线越不容易断） */
  maxPixels?: number
  /** 是否填白底，默认 true（透明区域视为背景） */
  whiteBackground?: boolean
}

/** SVG 线性走线选项 */
export interface SvgVectorOptions {
  /** 目标宽度 (mm) */
  targetWidthMm: number
  /** 目标高度 (mm)，缺省按 SVG 比例自动 */
  targetHeightMm?: number
  /** 走线方式，默认 Centerline */
  mode?: SvgVectorMode
  /** 光栅化长边像素，默认 1024（仅中心线 / 智能模式使用） */
  rasterPixels?: number
  /** 二值化阈值 0..255，默认 128（仅中心线 / 智能模式使用） */
  threshold?: number
  /** 反相（深底浅图） */
  invert?: boolean
  /** 去毛刺长度（像素），默认 6 */
  minBranchPx?: number
  /** 简化容差（像素），默认 1.2 */
  simplifyTolerance?: number
  /** 智能识别：笔画宽度阈值（占图像短边百分比），默认 2.5 */
  strokeWidthThresholdPct?: number
  /** 智能识别：墨水覆盖率上限，默认 0.5 */
  maxInkRatio?: number
  /** 轮廓模式曲线离散精度 (mm)，默认 0.1 */
  tolerance?: number
  // ---- 与 SvgConvertOptions 一致的雕刻参数 ----
  markSpeed: number
  travelSpeed?: number
  minPower?: number
  maxPower?: number
  laserOn: string
  laserOff: string
  pwm: boolean
  offsetX?: number
  offsetY?: number
  header?: string
  footer?: string
  optimize?: boolean
}

/** SVG 线性走线结果 */
export interface SvgVectorResult extends SvgConvertResult {
  /** 实际采用的方式 */
  mode: 'Outline' | 'Centerline'
  /** 智能判定明细（仅 mode 为 Auto 时提供） */
  decision?: VectorDecision
  /** 是否做了光栅化（中心线 / 智能模式为 true） */
  rasterized: boolean
  /** 光栅化像素尺寸（未光栅化时为 0） */
  rasterWidth: number
  rasterHeight: number
  /** 二值化预览（未光栅化时为 null） */
  preview: ImageData | null
}

/** 读取 SVG 内容尺寸（viewBox 优先，其次 width/height），无效返回 null */
export function readSvgContentSize(svgText: string): { width: number; height: number } | null {
  let root: Element
  try {
    const doc = new DOMParser().parseFromString(svgText, 'image/svg+xml')
    if (doc.querySelector('parsererror')) return null
    root = doc.documentElement
  } catch {
    return null
  }
  if (!root || root.tagName.toLowerCase() !== 'svg') return null

  const vb = parseViewBox(root.getAttribute('viewBox'))
  if (vb) return { width: vb[2], height: vb[3] }

  let w = parseLength(root.getAttribute('width'))
  let h = parseLength(root.getAttribute('height'))
  if (w <= 0 && h > 0) w = h
  if (h <= 0 && w > 0) h = w
  if (!(w > 0) || !(h > 0)) return null
  return { width: w, height: h }
}

/**
 * 规范化 SVG：补全 xmlns，并把根元素的 width/height 固定为内容尺寸（px）。
 * 原因：
 *  - `width="100%"` 之类的百分比尺寸无法被 <img> 解码出固有尺寸；
 *  - width/height 与 viewBox 比例不一致时浏览器会 letterbox（preserveAspectRatio），
 *    会让光栅化结果与轮廓模式的几何对不上。
 */
function normalizeSvgForRaster(svgText: string, size: { width: number; height: number }): string {
  const doc = new DOMParser().parseFromString(svgText, 'image/svg+xml')
  if (doc.querySelector('parsererror')) throw new Error('SVG 解析失败：文件格式无效')
  const root = doc.documentElement
  if (!root || root.tagName.toLowerCase() !== 'svg') throw new Error('SVG 解析失败：缺少 <svg> 根元素')
  if (!root.getAttribute('xmlns')) root.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  root.setAttribute('width', String(size.width))
  root.setAttribute('height', String(size.height))
  return new XMLSerializer().serializeToString(root)
}

/** data URL → Image */
function loadSvgImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('SVG 光栅化失败：图片无法解码（可能含外部引用或非法内容）'))
    img.src = url
  })
}

/** 二值化预览（白底黑图，与 Centerline 的判定一致） */
function buildPreview(image: PotraceImage, threshold: number, invert: boolean): ImageData {
  const w = image.width
  const h = image.height
  const src = image.data
  const out = new Uint8ClampedArray(w * h * 4)
  for (let i = 0, n = w * h; i < n; i++) {
    const a = src[4 * i + 3]
    let black = a >= 128 && src[4 * i] + src[4 * i + 1] + src[4 * i + 2] < threshold * 3
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
 * 把 SVG 光栅化为位图（白底），长边缩放到 maxPixels。
 * 抛错信息为中文，调用方可直接提示用户。
 */
export async function rasterizeSvg(svgText: string, o: SvgRasterOptions = {}): Promise<SvgRasterResult> {
  const size = readSvgContentSize(svgText)
  if (!size) throw new Error('SVG 尺寸无效：缺少 viewBox 或 width/height，请改用轮廓提取')

  const maxPixels = Math.max(64, Math.min(8192, Math.round(o.maxPixels ?? 1024)))
  const scale = maxPixels / Math.max(size.width, size.height)
  const pw = Math.max(1, Math.round(size.width * scale))
  const ph = Math.max(1, Math.round(size.height * scale))

  const normalized = normalizeSvgForRaster(svgText, size)
  const url = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(normalized)}`
  const img = await loadSvgImage(url)

  const canvas = document.createElement('canvas')
  canvas.width = pw
  canvas.height = ph
  const ctx = canvas.getContext('2d', { willReadFrequently: true }) as CanvasRenderingContext2D
  if (o.whiteBackground !== false) {
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, pw, ph)
  }
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(img, 0, 0, pw, ph)
  const id = ctx.getImageData(0, 0, pw, ph)

  return {
    image: { data: id.data, width: pw, height: ph },
    pixelWidth: pw,
    pixelHeight: ph,
    contentWidth: size.width,
    contentHeight: size.height
  }
}

/** 组装原有轮廓转换参数 */
function toOutlineOptions(o: SvgVectorOptions, heightMm?: number): SvgConvertOptions {
  return {
    targetWidthMm: o.targetWidthMm,
    targetHeightMm: heightMm,
    tolerance: o.tolerance,
    markSpeed: o.markSpeed,
    travelSpeed: o.travelSpeed,
    laserOn: o.laserOn,
    laserOff: o.laserOff,
    pwm: o.pwm,
    maxPower: o.maxPower,
    offsetX: o.offsetX,
    offsetY: o.offsetY,
    header: o.header,
    footer: o.footer
  }
}

/**
 * SVG → 线性走线 G 代码。
 *  - Outline：直接调用原有 convertSvgToGcode（不栅格化，结果与旧版完全一致）；
 *  - Centerline：光栅化 → 骨架化 → 折线 → G 代码；
 *  - Auto：先光栅化做笔画宽度判定，再走上面两条之一。
 *
 * 三种方式使用同一套几何换算：SVG 内容框 → [offsetX, offsetX+widthMm] ×
 * [offsetY, offsetY+heightMm]（y 轴翻转），因此模式之间可以无缝切换比较。
 */
export async function convertSvgVector(svgText: string, o: SvgVectorOptions): Promise<SvgVectorResult> {
  const mode = o.mode ?? 'Centerline'
  const widthMm = o.targetWidthMm

  // ---- 轮廓提取：保持原有实现（不做任何光栅化） ----
  if (mode === 'Outline') {
    const r = convertSvgToGcode(svgText, toOutlineOptions(o, o.targetHeightMm))
    return { ...r, mode: 'Outline', rasterized: false, rasterWidth: 0, rasterHeight: 0, preview: null }
  }

  const threshold = o.threshold ?? 128
  const invert = o.invert ?? false

  // ---- 中心线 / 智能：先光栅化 ----
  const raster = await rasterizeSvg(svgText, { maxPixels: o.rasterPixels })
  const heightMm = o.targetHeightMm ?? (widthMm * raster.contentHeight) / raster.contentWidth
  const preview = buildPreview(raster.image, threshold, invert)

  const travelSpeed = o.travelSpeed ?? 0
  /** 中心线分支：骨架 → 单线折线 → G 代码 */
  const runCenterline = (decision?: VectorDecision): SvgVectorResult => {
    const polys = centerlineTrace(raster.image, {
      threshold,
      invert,
      closed: false,
      minBranchPx: o.minBranchPx,
      simplifyTolerance: o.simplifyTolerance
    })
    const g = polylinesToGcode(polys, {
      pixelSizeMm: widthMm / raster.pixelWidth,
      offsetX: o.offsetX ?? 0,
      // 位图 y 向下、G 代码 y 向上：翻转后整体上移一个高度
      offsetY: (o.offsetY ?? 0) + heightMm,
      markSpeed: o.markSpeed,
      travelSpeed: travelSpeed > 0 ? travelSpeed : undefined,
      minPower: o.minPower ?? 0,
      maxPower: o.maxPower ?? 1000,
      laserPower: o.maxPower ?? 1000,
      laserOn: o.laserOn,
      laserOff: o.laserOff,
      pwm: o.pwm,
      flipY: true,
      header: o.header,
      footer: o.footer,
      optimize: o.optimize ?? true,
      travelCommand: travelSpeed > 0 ? 'G1' : 'G0'
    })
    return {
      lines: g.lines,
      widthMm,
      heightMm,
      pathCount: g.pathCount,
      pathLengthMm: g.lengthMm,
      mode: 'Centerline',
      decision,
      rasterized: true,
      rasterWidth: raster.pixelWidth,
      rasterHeight: raster.pixelHeight,
      preview
    }
  }

  let decision: VectorDecision | undefined
  let useCenterline = mode === 'Centerline'
  if (mode === 'Auto') {
    decision = decideVectorMode(raster.image, {
      threshold,
      invert,
      strokeWidthThresholdPct: o.strokeWidthThresholdPct,
      maxInkRatio: o.maxInkRatio
    })
    useCenterline = decision.mode === 'Centerline'
  }

  // ---- 判定为轮廓：回到原有轮廓实现（几何更精确，不受栅格化精度影响） ----
  if (!useCenterline) {
    const r = convertSvgToGcode(svgText, toOutlineOptions(o, o.targetHeightMm))
    // 兜底：轮廓提取解析不出任何几何（例如 SVG 只含 <text> 等未支持元素），
    // 但光栅化后明显有墨迹 → 改用中心线，避免「智能」生成空文件
    if (mode === 'Auto' && decision && r.pathCount === 0 && decision.analysis.inkArea > 0) {
      return runCenterline({
        ...decision,
        mode: 'Centerline',
        fallback: true,
        summary: `智能识别：轮廓提取未解析出任何几何（SVG 可能只含 <text> 等未支持元素，光栅化后有 ${decision.analysis.inkArea} px 墨迹）→ 回退中心线描线`
      })
    }
    return {
      ...r,
      mode: 'Outline',
      decision,
      rasterized: true,
      rasterWidth: raster.pixelWidth,
      rasterHeight: raster.pixelHeight,
      preview
    }
  }

  return runCenterline(decision)
}
