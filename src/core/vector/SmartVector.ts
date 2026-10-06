/**
 * 智能模式（Auto）—— 自动在「轮廓提取」与「中心线描线」之间选择
 *
 * 页面上的「智能 / Auto」选项调用这里的入口：
 *   - 图片 / SVG：对光栅化结果做笔画宽度启发式判定（见 StrokeAnalysis.ts）；
 *   - 文字：纯 ASCII 用 Hershey 单线字体（本就是「写字」效果，且矢量输出最干净），
 *     含中文等非 ASCII 字符时 Hershey 没有字形，改用中心线（骨架化）。
 *
 * 本文件为纯 TypeScript，无 DOM 依赖（convertImageVector 内部只用 ImageData 构造预览）。
 */

import { convertImageVector, hasNonAscii, type ImageVectorOptions, type ImageVectorResult } from './ImageVector'
import type { PotraceImage } from './Potrace'
import { decideVectorMode, type VectorDecision } from './StrokeAnalysis'

/** 图片智能转换选项：在普通选项上去掉 tool（由智能判定决定），并带上判定参数 */
export interface SmartVectorOptions extends Omit<ImageVectorOptions, 'tool'> {
  /** 笔画宽度阈值：占图像短边的百分比，默认 2.5 (%) */
  strokeWidthThresholdPct?: number
  /** 墨水覆盖率上限，默认 0.5 */
  maxInkRatio?: number
  /** 骨架化迭代上限，默认 60 */
  maxIterations?: number
}

/** 图片智能转换结果 */
export interface SmartVectorResult extends ImageVectorResult {
  /** 智能判定明细（含展示文案） */
  decision: VectorDecision
}

/**
 * 位图 → 线性 G 代码（智能选择轮廓 / 中心线）。
 * 判定用位图与出图用位图必须是同一张（同一分辨率），否则面积 / 骨架长度之比无意义。
 */
export function convertImageVectorSmart(image: PotraceImage, o: SmartVectorOptions): SmartVectorResult {
  const decision = decideVectorMode(image, {
    threshold: o.threshold,
    invert: o.invert,
    strokeWidthThresholdPct: o.strokeWidthThresholdPct,
    maxInkRatio: o.maxInkRatio,
    maxIterations: o.maxIterations
  })
  // 交给原有转换函数，两种方式的数值结果与显式选择时完全一致
  const res = convertImageVector(image, { ...o, tool: decision.mode })
  return { ...res, decision }
}

// ---------------------------------------------------------------------------
// 文字智能识别
// ---------------------------------------------------------------------------

/** 文字可用的引擎（智能模式只会选这两种） */
export type TextSmartEngine = 'Hershey' | 'Centerline'

/** 文字智能识别结果 */
export interface TextEngineDecision {
  engine: TextSmartEngine
  /** 界面展示用的一行中文说明 */
  reason: string
}

/** 是否含有 Hershey 无法渲染的字符（换行 / 制表符不算：Hershey 支持多行排版） */
export function hasNonHersheyChar(text: string): boolean {
  return hasNonAscii(text.replace(/[\r\n\t]/g, ' '))
}

/**
 * 文字智能识别：
 *   - 纯 ASCII（英文 / 数字 / 符号，可含换行）→ Hershey 单线字体；
 *   - 含中文等非 ASCII 字符 → 中心线（骨架化）。
 * 说明：换行符本身落在 hasNonAscii 的判定区间外，这里先归一化，
 * 否则多行英文会被误判成「含非 ASCII」。
 */
export function decideTextEngine(text: string): TextEngineDecision {
  if (hasNonHersheyChar(text)) {
    return {
      engine: 'Centerline',
      reason: '智能识别：含中文等非 ASCII 字符 → 采用中心线走线（骨架化），Hershey 无该字符字形'
    }
  }
  return {
    engine: 'Hershey',
    reason: '智能识别：纯 ASCII → 采用 Hershey 单线字体（单线笔画，最贴切「写字」效果）'
  }
}
