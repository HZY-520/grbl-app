/**
 * 笔画宽度分析 —— 「智能识别」判定线稿 / 实心图案
 *
 * 思路（纯几何统计，与分辨率无关）：
 *   1. 二值化 → 统计墨水（前景）面积 inkArea；
 *   2. Zhang-Suen 细化得到骨架 → 统计骨架总长 skeletonLength
 *      （四邻接按 1 计、对角邻接按 √2 计，只统计右下四个方向避免重复计数）；
 *   3. 平均笔画宽度 ≈ 墨水面积 / 骨架长度（同一条笔画：面积 ≈ 宽 × 长）；
 *   4. 归一化到图像短边：strokeWidthRatio = avgStrokeWidthPx / min(w, h)。
 *
 * ⚠️ 第 3 步只在骨架「真的收敛成 1 像素宽」时成立：粗图形（笔画宽 w）需要约 w/2
 * 次细化迭代，迭代不够时剩下的是一坨肉而不是骨架，面积 / 长度会恒等于 1 左右，
 * 会把实心块误判成线稿。所以这里按阈值反推迭代上限
 * （maxIter ≈ 阈值宽度 / 2 + 余量）：
 *   - 细线稿会在上限内收敛 → 面积 / 长度有效 → 按阈值判定；
 *   - 粗图形在细化过程中就用光迭代 → 判定为「笔画粗于阈值」→ 轮廓提取。
 * 这同时让分析保持廉价（细线稿几次迭代就结束，实心图不会跑成百上千次）。
 *
 * 判定：收敛且宽度比例 ≤ 阈值（默认 2.5%）→ 线稿 → 中心线描线；否则 → 轮廓提取。
 * 兜底：无墨迹 / 墨水覆盖率过高（大片实心）/ 细化不收敛 / 骨架为空或退化
 *       → 一律回退轮廓提取。
 *
 * 与 centerlineTrace 共用同一份二值化与细化实现（见 Centerline.ts 的 skeletonize）。
 *
 * 本文件为纯 TypeScript，无 DOM 依赖。
 */

import { skeletonDegree, skeletonize } from './Centerline'
import type { PotraceImage } from './Potrace'

/** 走线方式（与 ImageVector 的 VectorTool 同构，此处独立声明避免循环依赖） */
export type VectorMode = 'Outline' | 'Centerline'

const SQRT2 = Math.SQRT2

/** 细化迭代上限的硬上限：约能分辨 2 × 48 = 96 px 宽的笔画 */
const MAX_THIN_ITERATIONS = 48

/** 笔画分析选项 */
export interface StrokeAnalysisOptions {
  /** 二值化阈值 0..255，默认 128（与 centerlineTrace 一致） */
  threshold?: number
  /** 反相，默认 false */
  invert?: boolean
  /**
   * 笔画宽度阈值：占图像短边的百分比，默认 2.5 (%)。
   * 分析用的细化迭代上限由它反推（阈值宽 / 2 + 4），因此它会影响 converged 的判定。
   */
  strokeWidthThresholdPct?: number
  /** 手动指定细化迭代上限（不传则按阈值反推） */
  maxIterations?: number
}

/** 笔画分析结果 */
export interface StrokeAnalysis {
  width: number
  height: number
  /** 图像短边（像素），用于归一化笔画宽度 */
  shortSide: number
  /** 墨水（前景）像素数 */
  inkArea: number
  /** 墨水覆盖率 0..1 */
  inkRatio: number
  /** 骨架像素数 */
  skeletonPixels: number
  /** 骨架总长度（像素，对角连接按 √2 计） */
  skeletonLength: number
  /** 端点数量（8 邻域度 == 1） */
  endpoints: number
  /** 分支点数量（8 邻域度 >= 3；对角阶梯也会产生，仅供参考） */
  junctions: number
  /** 本次分析使用的细化迭代上限 */
  iterations: number
  /** 骨架是否收敛（false = 笔画比迭代上限对应的宽度还粗，宽度估算无效） */
  converged: boolean
  /** 平均笔画宽度（像素）= 墨水面积 / 骨架长度（仅 converged 时有效） */
  avgStrokeWidthPx: number
  /** 平均笔画宽度 / 图像短边，线稿通常 < 0.03（仅 converged 时有效） */
  strokeWidthRatio: number
  /** 骨架是否为空或退化（无法据其判断笔画宽度） */
  degenerate: boolean
}

/** 空结果 */
function emptyAnalysis(
  width: number,
  height: number,
  inkArea = 0,
  iterations = 0,
  converged = true
): StrokeAnalysis {
  return {
    width,
    height,
    shortSide: Math.max(1, Math.min(width, height)),
    inkArea,
    inkRatio: width > 0 && height > 0 ? inkArea / (width * height) : 0,
    skeletonPixels: 0,
    skeletonLength: 0,
    endpoints: 0,
    junctions: 0,
    iterations,
    converged,
    avgStrokeWidthPx: 0,
    strokeWidthRatio: 0,
    degenerate: true
  }
}

/**
 * 统计墨水面积与骨架长度，估算平均笔画宽度。
 * 输入位图坐标系与 centerlineTrace 一致（原点左上、y 向下）。
 */
export function analyzeStrokes(image: PotraceImage, options: StrokeAnalysisOptions = {}): StrokeAnalysis {
  if (!image || image.width <= 0 || image.height <= 0) return emptyAnalysis(0, 0)
  if (image.width < 3 || image.height < 3) return emptyAnalysis(image.width, image.height)

  const shortSide = Math.max(1, Math.min(image.width, image.height))
  const thresholdPct = options.strokeWidthThresholdPct ?? 2.5
  // 阈值宽度（像素）的一半 + 余量：够细线稿收敛，又让粗图形尽早出局
  const maxIterations =
    options.maxIterations ??
    Math.max(6, Math.min(MAX_THIN_ITERATIONS, Math.ceil(((thresholdPct / 100) * shortSide) / 2) + 4))

  const sk = skeletonize(image, {
    threshold: options.threshold,
    invert: options.invert,
    maxIterations
  })
  const { data, width: w, height: h, inkArea, converged } = sk
  const inkRatio = inkArea / (image.width * image.height)
  if (inkArea === 0 || w < 3 || h < 3) {
    return emptyAnalysis(image.width, image.height, inkArea, maxIterations, true)
  }

  const deg = skeletonDegree(data, w, h)

  let skeletonPixels = 0
  let endpoints = 0
  let junctions = 0
  // 只统计「右 / 下 / 右下 / 左下」四个方向，每条连接只数一次
  let step4 = 0
  let step8 = 0
  for (let y = 1; y < h - 1; y++) {
    const row = y * w
    for (let x = 1; x < w - 1; x++) {
      const i = row + x
      if (!data[i]) continue
      skeletonPixels++
      const d = deg[i]
      if (d === 1) endpoints++
      else if (d >= 3) junctions++
      if (data[i + 1]) step4++
      if (data[i + w]) step4++
      if (data[i + w + 1]) step8++
      if (data[i + w - 1]) step8++
    }
  }

  const skeletonLength = step4 + step8 * SQRT2
  const degenerate = skeletonPixels === 0 || skeletonLength < 1e-6
  const usable = converged && !degenerate
  const avgStrokeWidthPx = usable ? inkArea / skeletonLength : 0

  return {
    width: image.width,
    height: image.height,
    shortSide,
    inkArea,
    inkRatio,
    skeletonPixels,
    skeletonLength,
    endpoints,
    junctions,
    iterations: maxIterations,
    converged,
    avgStrokeWidthPx,
    strokeWidthRatio: usable ? avgStrokeWidthPx / shortSide : 0,
    degenerate
  }
}

/** 智能判定选项 */
export interface VectorDecisionOptions extends StrokeAnalysisOptions {
  /** 笔画宽度阈值：占图像短边的百分比，小于等于它判为线稿，默认 2.5 (%) */
  strokeWidthThresholdPct?: number
  /** 墨水覆盖率上限（超过视为大片实心），默认 0.5 */
  maxInkRatio?: number
}

/** 智能判定结果 */
export interface VectorDecision {
  /** 判定采用的方式 */
  mode: VectorMode
  /** 是否走了兜底规则（无墨迹 / 墨水覆盖过高 / 细化不收敛 / 骨架退化） */
  fallback: boolean
  /** 统计明细 */
  analysis: StrokeAnalysis
  /** 本次使用的笔画宽度阈值（占短边百分比） */
  thresholdPct: number
  /** 界面展示用的一行中文说明（含判定依据与关键数字） */
  summary: string
}

/** 百分比格式化（最多 2 位小数） */
function pct(v: number): string {
  const r = Math.round(v * 100) / 100
  return `${r}%`
}

/**
 * 智能判定：线稿 → 中心线描线；实心 / 粗笔画图案 → 轮廓提取。
 * 判定依据可复现，summary 里带上关键数字，便于界面上向用户解释。
 */
export function decideVectorMode(image: PotraceImage, options: VectorDecisionOptions = {}): VectorDecision {
  const thresholdPct = options.strokeWidthThresholdPct ?? 2.5
  const maxInkRatio = options.maxInkRatio ?? 0.5
  const a = analyzeStrokes(image, { ...options, strokeWidthThresholdPct: thresholdPct })
  const stats = `骨架 ${Math.round(a.skeletonLength)} px，端点 ${a.endpoints}，分支 ${a.junctions}`

  if (a.inkArea <= 0) {
    return {
      mode: 'Outline',
      fallback: true,
      analysis: a,
      thresholdPct,
      summary: '智能识别：未检测到墨迹 → 采用轮廓提取（请检查二值化阈值或反相设置）'
    }
  }

  if (a.inkRatio > maxInkRatio) {
    return {
      mode: 'Outline',
      fallback: true,
      analysis: a,
      thresholdPct,
      summary: `智能识别：墨水覆盖 ${pct(a.inkRatio * 100)} 超过上限 ${pct(
        maxInkRatio * 100
      )}（以实心块为主）→ 采用轮廓提取`
    }
  }

  if (!a.converged) {
    return {
      mode: 'Outline',
      fallback: true,
      analysis: a,
      thresholdPct,
      summary: `智能识别：笔画粗于阈值 → 采用轮廓提取（细化 ${a.iterations} 次仍未收敛，笔画宽于约 ${
        a.iterations * 2
      } px，已超过短边的 ${pct(thresholdPct)} = ${Math.round((thresholdPct / 100) * a.shortSide)} px）`
    }
  }

  if (a.degenerate) {
    return {
      mode: 'Outline',
      fallback: true,
      analysis: a,
      thresholdPct,
      summary: `智能识别：骨架为空或退化（墨水覆盖 ${pct(a.inkRatio * 100)}）→ 回退轮廓提取`
    }
  }

  const widthPct = a.strokeWidthRatio * 100
  const detail = `平均笔画宽 ≈ ${a.avgStrokeWidthPx.toFixed(1)} px，占图像短边 ${pct(widthPct)}（${stats}）`

  if (widthPct <= thresholdPct) {
    return {
      mode: 'Centerline',
      fallback: false,
      analysis: a,
      thresholdPct,
      summary: `智能识别：线稿 → 采用中心线描线（${detail}，阈值 ${pct(thresholdPct)}）`
    }
  }

  return {
    mode: 'Outline',
    fallback: false,
    analysis: a,
    thresholdPct,
    summary: `智能识别：实心 / 粗笔画图案 → 采用轮廓提取（${detail}，超过阈值 ${pct(thresholdPct)}）`
  }
}
