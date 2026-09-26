/**
 * 处理管线：输入资源 → 雕刻路径 → 路径优化 → G 代码
 * 所有来源（文字 / 图片 / SVG）最终都会归一为统一的 Stroke 列表。
 */

import { textToStrokes } from './text.js';
import { imageToStrokes } from './image.js';
import { svgToStrokes } from './svg.js';
import { optimizeStrokes } from './optimize.js';
import { buildGcode } from './gcode.js';
import { boundsOfPolylines, translatePoints, formatDuration } from './geom.js';

/**
 * 根据「放置位置」计算平移量
 * @returns {{dx:number, dy:number}}
 */
function anchorShift(bounds, anchor, offsetX, offsetY) {
  switch (anchor) {
    case 'top-left':
      return { dx: -bounds.minX + offsetX, dy: -bounds.maxY + offsetY };
    case 'center':
      return {
        dx: -(bounds.minX + bounds.maxX) / 2 + offsetX,
        dy: -(bounds.minY + bounds.maxY) / 2 + offsetY
      };
    default: // bottom-left
      return { dx: -bounds.minX + offsetX, dy: -bounds.minY + offsetY };
  }
}

/** 生成输入资源的路径（不含优化） */
export function extractStrokes(input, opts) {
  if (input.type === 'text') {
    return textToStrokes({
      text: input.text,
      fontFamily: opts.textFont,
      fontSizeMm: opts.textSize,
      weight: opts.textWeight,
      italic: opts.textItalic,
      align: opts.textAlign,
      maxWidthMm: opts.textWrap,
      spacingPct: opts.textSpacing,
      lineHeightPct: opts.textLineHeight,
      mode: opts.textMode,
      fillSpacing: opts.fillSpacing,
      fillAngle: opts.fillAngle,
      fillCross: opts.fillCross,
      simplifyTolerance: opts.simplify,
      power: 1
    });
  }
  if (input.type === 'image') {
    if (!input.imageEl) throw new Error('请先选择一张图片');
    return imageToStrokes(input.imageEl, {
      mode: opts.imgMode,
      ditherAlgo: opts.imgDither,
      ditherStrength: opts.imgDitherStrength,
      spacingMm: opts.imgSpacing,
      angleDeg: opts.imgAngle,
      serpentine: opts.imgSerpentine,
      brightness: opts.imgBright,
      contrast: opts.imgContrast,
      gamma: opts.imgGamma,
      threshold: opts.imgThreshold / 255,
      invert: opts.imgInvert,
      levels: opts.levels,
      targetWidthMm: opts.width,
      power: 1,
      minPower: 0,
      simplifyTolerance: opts.simplify
    });
  }
  if (input.type === 'svg') {
    return svgToStrokes(input.svgText, {
      targetWidthMm: opts.width,
      targetHeightMm: opts.lockAspect ? undefined : opts.height,
      tolerance: opts.svgTolerance,
      extract: opts.svgExtract,
      fillSpacing: opts.fillSpacing,
      fillAngle: opts.fillAngle,
      crossHatch: opts.fillCross,
      simplifyTolerance: opts.simplify,
      power: 1
    });
  }
  throw new Error('未知的输入类型');
}

/**
 * 完整管线
 * @param {{type:string, text?:string, imageEl?:any, svgText?:string}} input
 * @param {Object} opts 归一化后的全部选项
 * @returns {{plan:Object, gcode:Object, bounds:Object, strokeCount:number}}
 */
export function buildJob(input, opts) {
  const extracted = extractStrokes(input, opts);
  const raw = extracted.strokes;
  if (!raw || !raw.length) throw new Error('没有生成任何雕刻路径，请调整参数后重试');

  // 统一平移：先算原始包围盒，再按放置位置整体偏移
  const rawBounds = boundsOfPolylines(raw.map((s) => s.pts));
  const { dx, dy } = anchorShift(rawBounds, opts.anchor, opts.offsetX, opts.offsetY);
  const placed = raw.map((s) => ({ ...s, pts: translatePoints(s.pts, dx, dy) }));
  const bounds = {
    minX: rawBounds.minX + dx,
    minY: rawBounds.minY + dy,
    maxX: rawBounds.maxX + dx,
    maxY: rawBounds.maxY + dy,
    width: rawBounds.width,
    height: rawBounds.height
  };

  const plan = optimizeStrokes(placed, {
    order: opts.order,
    allowReverse: opts.allowReverse,
    chainMm: opts.chain,
    mergeMm: opts.merge,
    unify: opts.unify,
    simplifyMm: 0,
    start: { x: 0, y: 0 },
    passes: opts.passes
  });

  const powerMaxS = Math.round((opts.maxS * opts.power) / 100);
  const powerMinS = Math.round((powerMaxS * opts.minPower) / 100);

  const comments = [
    `源类型：${input.type === 'text' ? '文字' : input.type === 'image' ? '图片' : 'SVG'}`,
    `尺寸：${bounds.width.toFixed(2)} × ${bounds.height.toFixed(2)} mm`,
    `路径：${plan.count} 段（原始 ${plan.rawCount} 段，合并后 ${plan.mergedCount} 段）`,
    `雕刻长度：${plan.markLength.toFixed(1)} mm / 空移长度：${plan.travelLength.toFixed(1)} mm`,
    `连续衔接：${plan.chainCount} 处 / 走刀遍数：${plan.passes}`
  ];

  const gcode = buildGcode(plan, {
    bounds,
    unit: opts.unit,
    laserMode: opts.laserMode,
    feed: opts.feed,
    travelFeed: opts.travelFeed,
    powerMinS,
    powerMaxS,
    compact: opts.compact,
    useS0: opts.s0,
    frame: opts.frame,
    home: opts.home,
    header: opts.header,
    footer: opts.footer,
    comments
  });

  const estSeconds = gcode.stats.estSeconds;
  return {
    plan,
    gcode,
    bounds,
    rawBounds,
    strokeCount: plan.count,
    stats: {
      strokes: plan.count,
      rawStrokes: plan.rawCount,
      mergedStrokes: plan.mergedCount,
      chainCount: plan.chainCount,
      markLength: plan.markLength,
      travelLength: plan.travelLength,
      estSeconds,
      estText: formatDuration(estSeconds),
      lines: gcode.stats.lines,
      sizeText: gcode.stats.sizeText,
      width: bounds.width,
      height: bounds.height,
      powerMaxS,
      powerMinS
    }
  };
}