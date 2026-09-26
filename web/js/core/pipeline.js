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
import {
  normalizeDevice, workArea, usableArea, overflow, fitScale, originLabel
} from './device.js';

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
 * 自适应提取：先按当前参数生成一次测出包围盒，
 * 若超出可用安全区则按比例缩小源尺寸后重新生成，
 * 避免先放大再缩放导致的栅格密度失真（图片 / SVG 会按新宽度重新采样）。
 * @returns {{extracted:Object, scale:number}}
 */
function extractAdaptive(input, opts, area) {
  const base = extractStrokes(input, opts);
  const strokes = base.strokes;
  if (!opts.autoFit || !strokes || !strokes.length) return { extracted: base, scale: 1 };

  const b = boundsOfPolylines(strokes.map((s) => s.pts));
  const k = fitScale(b, area);
  if (!(k < 1 - 1e-4)) return { extracted: base, scale: 1 };

  const scaled = { ...opts };
  if (input.type === 'text') {
    scaled.textSize = opts.textSize * k;
  } else {
    scaled.width = opts.width * k;
    if (!opts.lockAspect) scaled.height = opts.height * k;
  }
  return { extracted: extractStrokes(input, scaled), scale: k };
}

/**
 * 完整管线
 * @param {{type:string, text?:string, imageEl?:any, svgText?:string}} input
 * @param {Object} opts 归一化后的全部选项（含设备参数）
 * @returns {{plan:Object, gcode:Object, bounds:Object, device:Object, strokeCount:number}}
 */
export function buildJob(input, opts) {
  const dev = normalizeDevice({
    travelX: opts.travelX,
    travelY: opts.travelY,
    origin: opts.origin,
    margin: opts.margin,
    maxFeed: opts.maxFeed,
    autoFit: opts.autoFit
  });
  const area = usableArea(dev);
  const wa = workArea(dev);

  const { extracted, scale } = extractAdaptive(input, opts, area);
  const raw = extracted.strokes;
  if (!raw || !raw.length) throw new Error('没有生成任何雕刻路径，请调整参数后重试');

  // 统一平移
  const rawBounds = boundsOfPolylines(raw.map((s) => s.pts));
  let dx;
  let dy;
  if (dev.autoFit) {
    // 自适应：居中放入安全区
    dx = (area.minX + area.maxX) / 2 - (rawBounds.minX + rawBounds.maxX) / 2;
    dy = (area.minY + area.maxY) / 2 - (rawBounds.minY + rawBounds.maxY) / 2;
  } else {
    ({ dx, dy } = anchorShift(rawBounds, opts.anchor, opts.offsetX, opts.offsetY));
  }
  const placed = raw.map((s) => ({ ...s, pts: translatePoints(s.pts, dx, dy) }));
  const bounds = {
    minX: rawBounds.minX + dx,
    minY: rawBounds.minY + dy,
    maxX: rawBounds.maxX + dx,
    maxY: rawBounds.maxY + dy,
    width: rawBounds.width,
    height: rawBounds.height
  };

  // 越界判定（自适应关闭或缩放后仍越界时给出提示）
  const overflowMm = overflow(bounds, area);
  const fits = overflowMm <= 1e-6;

  // 进给按机器上限限速
  const feed = Math.min(Math.max(1, opts.feed), dev.maxFeed);
  const travelFeed = Math.min(Math.max(1, opts.travelFeed), dev.maxFeed);
  const feedLimited = feed < opts.feed - 1e-6 || travelFeed < opts.travelFeed - 1e-6;

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

  const fitText = dev.autoFit
    ? `自适应居中${scale < 1 ? `，缩放至 ${(scale * 100).toFixed(0)}%` : '（原始尺寸）'}`
    : fits
      ? '按手动定位放置'
      : `⚠ 超出安全区 ${overflowMm.toFixed(1)} mm`;

  const comments = [
    `源类型：${input.type === 'text' ? '文字' : input.type === 'image' ? '图片' : 'SVG'}`,
    `尺寸：${bounds.width.toFixed(2)} × ${bounds.height.toFixed(2)} mm`,
    `设备行程：${dev.travelX} × ${dev.travelY} mm / 原点：${originLabel(dev.origin)} / 安全边距：${area.margin} mm`,
    `安全区：${area.width.toFixed(1)} × ${area.height.toFixed(1)} mm（${fitText}）`,
    `路径：${plan.count} 段（原始 ${plan.rawCount} 段，合并后 ${plan.mergedCount} 段）`,
    `雕刻长度：${plan.markLength.toFixed(1)} mm / 空移长度：${plan.travelLength.toFixed(1)} mm`,
    `连续衔接：${plan.chainCount} 处 / 走刀遍数：${plan.passes}`
  ];
  if (feedLimited) comments.push(`进给已按机器上限限制为 F${dev.maxFeed} mm/min`);

  const gcode = buildGcode(plan, {
    bounds,
    unit: opts.unit,
    laserMode: opts.laserMode,
    feed,
    travelFeed,
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
    device: {
      ...dev,
      workArea: wa,
      usable: area,
      scale,
      fits,
      overflowMm,
      feed,
      travelFeed,
      feedLimited
    },
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
      powerMinS,
      travelX: dev.travelX,
      travelY: dev.travelY,
      fitScale: scale,
      fits,
      overflowMm,
      feedLimited
    }
  };
}