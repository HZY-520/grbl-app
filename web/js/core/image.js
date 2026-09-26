/**
 * 位图 → 雕刻路径
 * 支持四种模式：
 *  - dither   光栅抖动（误差扩散 / 有序抖动）→ 扫描行上的开关线段
 *  - gray     光栅灰度（多级功率）→ 扫描行上的分段功率
 *  - trace    轮廓描边（矢量化）→ 闭合轮廓折线
 *  - halftone 半色调点阵 → 按网格排布的短线段（点越大越黑）
 *
 * 采样思路：先在「输出空间」建立方格网，再把每个格点反向映射回原图取灰度，
 * 这样任意扫描角度都无需旋转图片，也不会产生黑边。
 */

import { ditherGray, quantizeGray } from './dither.js';
import { traceContours } from './trace.js';
import { chaikin, simplifyDP, clamp } from './geom.js';

const MAX_CELLS = 2_600_000; // 采样格点上限，避免超大图卡死
const MAX_SRC = 1500; // 原图长边最大像素

/** 把图片绘制到离屏 canvas 并取回像素 */
function readPixels(img) {
  const sw0 = img.naturalWidth || img.width;
  const sh0 = img.naturalHeight || img.height;
  const k = Math.min(1, MAX_SRC / Math.max(sw0, sh0));
  const sw = Math.max(1, Math.round(sw0 * k));
  const sh = Math.max(1, Math.round(sh0 * k));
  const canvas = document.createElement('canvas');
  canvas.width = sw;
  canvas.height = sh;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  ctx.drawImage(img, 0, 0, sw, sh);
  return { data: ctx.getImageData(0, 0, sw, sh).data, sw, sh };
}

/** 双线性采样亮度（0=黑 … 1=白），越界返回 1（白，不雕刻） */
function sampleLuma(data, sw, sh, px, py) {
  if (px < 0 || py < 0 || px > sw - 1 || py > sh - 1) return 1;
  const x0 = Math.floor(px);
  const y0 = Math.floor(py);
  const x1 = Math.min(sw - 1, x0 + 1);
  const y1 = Math.min(sh - 1, y0 + 1);
  const fx = px - x0;
  const fy = py - y0;
  const at = (x, y) => {
    const i = (y * sw + x) * 4;
    const a = data[i + 3] / 255;
    // 透明像素按白色处理（PNG 背景）
    const l = 0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2];
    return (l / 255) * a + (1 - a);
  };
  const top = at(x0, y0) * (1 - fx) + at(x1, y0) * fx;
  const bottom = at(x0, y1) * (1 - fx) + at(x1, y1) * fx;
  return top * (1 - fy) + bottom * fy;
}

/**
 * 位图转雕刻路径
 * @param {HTMLImageElement|ImageBitmap} img
 * @param {Object} opts
 * @returns {{strokes:Array, bounds:{minX,minY,maxX,maxY}, info:Object}}
 */
export function imageToStrokes(img, opts) {
  const mode = opts.mode || 'dither';
  const spacing = clamp(opts.spacingMm || 0.15, 0.02, 5);
  const angleDeg = opts.angleDeg || 0;
  const targetW = Math.max(1, opts.targetWidthMm || 100);
  const brightness = (opts.brightness || 0) / 100;
  const contrast = (opts.contrast || 0) / 100;
  const gamma = opts.gamma == null ? 1 : opts.gamma;
  const threshold = clamp(opts.threshold == null ? 0.5 : opts.threshold, 0.01, 0.99);
  const invert = !!opts.invert;
  const serpentine = opts.serpentine !== false;
  const power = opts.power == null ? 1 : opts.power;
  const minPower = opts.minPower == null ? 0 : opts.minPower;

  const { data, sw, sh } = readPixels(img);

  // ---- 输出空间尺寸 ----
  const W = targetW;
  const H = (targetW * sh) / sw;

  // ---- 采样方格 ----
  let cell = spacing;
  let cols = Math.max(1, Math.round(W / cell));
  let rows = Math.max(1, Math.round(H / cell));
  if (cols * rows > MAX_CELLS) {
    const s = Math.sqrt((cols * rows) / MAX_CELLS);
    cell *= s;
    cols = Math.max(1, Math.round(W / cell));
    rows = Math.max(1, Math.round(H / cell));
  }
  const cw = W / cols;
  const ch = H / rows;

  // ---- 旋转后的输出包围盒 ----
  const A = (angleDeg * Math.PI) / 180;
  const cx = W / 2;
  const cy = H / 2;
  let minX = 0;
  let minY = 0;
  let maxX = W;
  let maxY = H;
  if (angleDeg !== 0) {
    const cos = Math.cos(A);
    const sin = Math.sin(A);
    let bx0 = Infinity, by0 = Infinity, bx1 = -Infinity, by1 = -Infinity;
    for (const [px, py] of [[0, 0], [W, 0], [W, H], [0, H]]) {
      const dx = px - cx;
      const dy = py - cy;
      const rx = cx + dx * cos - dy * sin;
      const ry = cy + dx * sin + dy * cos;
      bx0 = Math.min(bx0, rx);
      bx1 = Math.max(bx1, rx);
      by0 = Math.min(by0, ry);
      by1 = Math.max(by1, ry);
    }
    minX = bx0; maxX = bx1; minY = by0; maxY = by1;
  }
  const outW = maxX - minX;
  const outH = maxY - minY;

  // ---- 采样灰度 ----
  const gray = new Float32Array(cols * rows);
  const cosA = Math.cos(-A);
  const sinA = Math.sin(-A);
  for (let r = 0; r < rows; r++) {
    const qy = maxY - (r + 0.5) * ch;
    for (let c = 0; c < cols; c++) {
      const qx = minX + (c + 0.5) * cw;
      // 反向旋转回原图坐标系
      const dx = qx - cx;
      const dy = qy - cy;
      const sxmm = cx + dx * cosA - dy * sinA;
      const symm = cy + dx * sinA + dy * cosA;
      let l = sxmm < 0 || sxmm > W || symm < 0 || symm > H
        ? 1
        : sampleLuma(data, sw, sh, (sxmm / W) * sw, ((H - symm) / H) * sh);
      // 图像调整
      if (brightness) l += brightness;
      if (contrast) l = (l - 0.5) * (1 + contrast) + 0.5;
      if (gamma !== 1) l = Math.pow(clamp(l, 0, 1), 1 / gamma);
      if (invert) l = 1 - l;
      gray[r * cols + c] = clamp(l, 0, 1);
    }
  }

  const mmX = (c) => minX + c * cw;
  const mmY = (r) => maxY - r * ch;

  const strokes = [];

  if (mode === 'dither') {
    const bin = ditherGray(gray, cols, rows, {
      algo: opts.ditherAlgo || 'floyd',
      strength: (opts.ditherStrength == null ? 100 : opts.ditherStrength) / 100,
      threshold: clamp(threshold, 0.01, 0.99)
    });
    for (let r = 0; r < rows; r++) {
      const y = maxY - (r + 0.5) * ch;
      let c = 0;
      while (c < cols) {
        if (!bin[r * cols + c]) { c++; continue; }
        let end = c;
        while (end + 1 < cols && bin[r * cols + end + 1]) end++;
        const x1 = mmX(c);
        const x2 = mmX(end + 1);
        const pts = serpentine && r % 2 === 1 ? [{ x: x2, y }, { x: x1, y }] : [{ x: x1, y }, { x: x2, y }];
        strokes.push({ pts, power, group: 'raster' });
        c = end + 1;
      }
    }
  } else if (mode === 'gray') {
    const q = quantizeGray(gray, opts.levels || 32);
    const span = Math.max(1e-6, power - minPower);
    for (let r = 0; r < rows; r++) {
      const y = maxY - (r + 0.5) * ch;
      let c = 0;
      while (c < cols) {
        const v = q[r * cols + c];
        if (v <= 0) { c++; continue; }
        let end = c;
        while (end + 1 < cols && Math.abs(q[r * cols + end + 1] - v) < 1 / 255) end++;
        const x1 = mmX(c);
        const x2 = mmX(end + 1);
        const p = minPower + span * v;
        const pts = serpentine && r % 2 === 1 ? [{ x: x2, y }, { x: x1, y }] : [{ x: x1, y }, { x: x2, y }];
        strokes.push({ pts, power: p, group: 'raster' });
        c = end + 1;
      }
    }
  } else if (mode === 'trace') {
    const mask = new Uint8Array(cols * rows);
    for (let i = 0; i < gray.length; i++) mask[i] = gray[i] < threshold ? 1 : 0;
    const loops = traceContours(mask, cols, rows, { minArea: 2 });
    const tol = opts.simplifyTolerance == null ? 0.05 : opts.simplifyTolerance;
    for (const loop of loops) {
      let pts = loop.pts.map((p) => ({ x: mmX(p.x), y: mmY(p.y) }));
      if (opts.smooth !== false && pts.length > 6) pts = chaikin(pts, 1, true);
      if (tol > 0) pts = simplifyDP(pts.concat([pts[0]]), tol);
      if (pts.length > 1 && (pts[0].x === pts[pts.length - 1].x && pts[0].y === pts[pts.length - 1].y)) pts.pop();
      if (pts.length < 3) continue;
      strokes.push({ pts, power, closed: true, group: 'trace' });
    }
  } else if (mode === 'halftone') {
    const pitchCols = Math.max(1, Math.round(spacing / cw));
    const pitchRows = Math.max(1, Math.round(spacing / ch));
    const dotMax = spacing * 0.75;
    for (let r = 0; r < rows; r += pitchRows) {
      for (let c = 0; c < cols; c += pitchCols) {
        let sum = 0;
        let n = 0;
        for (let rr = r; rr < Math.min(rows, r + pitchRows); rr++) {
          for (let cc = c; cc < Math.min(cols, c + pitchCols); cc++) {
            sum += 1 - gray[rr * cols + cc];
            n++;
          }
        }
        const darkness = n ? sum / n : 0;
        const len = dotMax * darkness;
        if (len < 0.03) continue;
        const y = maxY - (r + pitchRows / 2) * ch;
        const x = minX + (c + pitchCols / 2) * cw;
        strokes.push({
          pts: [{ x: x - len / 2, y }, { x: x + len / 2, y }],
          power,
          group: 'halftone'
        });
      }
    }
  }

  return {
    strokes,
    bounds: { minX, minY, maxX, maxY, width: outW, height: outH },
    info: { mode, cols, rows, cell: cw }
  };
}