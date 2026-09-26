/**
 * 扫描线填充 / 交叉填充
 * 输入一组闭合轮廓（可含孔洞，孔洞方向与外轮廓相反即可），
 * 输出一组水平（可按角度旋转）的填充线段 Stroke。
 * 采用「非零环绕数」判定内外，SVG/文字轮廓的孔洞能被正确掏空。
 */

import { rotatePoints, boundsOfPolylines, dist } from './geom.js';

/**
 * @typedef {Object} Stroke
 * @property {import('./geom.js').Pt[]} pts 折线点（毫米，Y 向上）
 * @property {number} power 相对功率 0..1
 * @property {boolean} [closed] 是否闭合
 * @property {string} [group] 来源标记
 */

/**
 * 计算某条水平扫描线上的内部区间。
 * @param {Array} edges [[p1,p2,dir], ...]
 * @param {number} y
 * @param {boolean} evenOdd
 * @returns {Array<[number,number]>} 区间 [x0,x1]
 */
function spansOnLine(edges, y, evenOdd) {
  const xs = [];
  for (const e of edges) {
    const [p1, p2, dir] = e;
    // 半开区间规则，避免顶点被重复计数
    if ((p1.y <= y && p2.y > y) || (p2.y <= y && p1.y > y)) {
      const t = (y - p1.y) / (p2.y - p1.y);
      xs.push([p1.x + t * (p2.x - p1.x), dir]);
    }
  }
  if (xs.length < 2) return [];
  xs.sort((a, b) => a[0] - b[0]);
  const spans = [];
  let winding = 0;
  let startX = 0;
  let inSpan = false;
  for (let i = 0; i < xs.length; i++) {
    const [x, dir] = xs[i];
    if (evenOdd) {
      winding = winding === 0 ? 1 : 0;
    } else {
      winding += dir;
    }
    const inside = winding !== 0;
    if (inside && !inSpan) {
      startX = x;
      inSpan = true;
    } else if (!inside && inSpan) {
      if (x - startX > 1e-6) spans.push([startX, x]);
      inSpan = false;
    }
  }
  return spans;
}

/**
 * 扫描线填充。
 * @param {Array<Array<{x:number,y:number}>>} polygons 闭合轮廓（Y 向上）
 * @param {Object} opts
 * @param {number} opts.spacing 行距（毫米）
 * @param {number} [opts.angleDeg=0] 扫描角度（度）
 * @param {number} [opts.power=1] 输出线段功率
 * @param {boolean} [opts.evenOdd=false] 使用奇偶规则
 * @param {boolean} [opts.serpentine=true] 双向扫描
 * @param {number} [opts.minLength=0.02] 过滤过短线段
 * @param {string} [opts.group='fill']
 * @returns {Stroke[]}
 */
export function scanlineFill(polygons, opts) {
  const spacing = Math.max(0.01, opts.spacing || 0.1);
  const angle = ((opts.angleDeg || 0) * Math.PI) / 180;
  const power = opts.power ?? 1;
  const evenOdd = !!opts.evenOdd;
  const serpentine = opts.serpentine !== false;
  const minLength = opts.minLength ?? 0.02;
  const group = opts.group || 'fill';

  const loops = polygons.filter((p) => p && p.length >= 3);
  if (!loops.length) return [];

  // 旋转到扫描坐标系：扫描线为水平线
  const rotated = loops.map((loop) => rotatePoints(loop, -angle));
  const bounds = boundsOfPolylines(rotated);
  if (!bounds) return [];

  // 预构建边表（只保留与扫描方向不平行的边）
  const edges = [];
  for (const loop of rotated) {
    for (let i = 0; i < loop.length; i++) {
      const p1 = loop[i];
      const p2 = loop[(i + 1) % loop.length];
      if (p1.y === p2.y) continue;
      edges.push([p1, p2, p2.y > p1.y ? 1 : -1]);
    }
  }
  if (!edges.length) return [];

  const strokes = [];
  let row = 0;
  const y0 = bounds.minY + spacing / 2;

  for (let y = y0; y <= bounds.maxY; y += spacing) {
    const spans = spansOnLine(edges, y, evenOdd);
    if (!spans.length) {
      row++;
      continue;
    }
    // 双向扫描：偶数行正序，奇数行倒序
    const ordered = serpentine && row % 2 === 1 ? spans.slice().reverse() : spans;
    for (const [xa, xb] of ordered) {
      if (xb - xa < minLength) continue;
      const p1 = { x: xa, y };
      const p2 = { x: xb, y };
      const pts = serpentine && row % 2 === 1 ? [p2, p1] : [p1, p2];
      strokes.push({ pts: rotatePoints(pts, angle), power, group });
    }
    row++;
  }
  return strokes;
}

/**
 * 交叉填充：按给定角度与 角度+90° 各填充一遍
 * @param {Array<Array<{x:number,y:number}>>} polygons
 * @param {Object} opts 同 scanlineFill，额外支持 crossAngle
 * @returns {Stroke[]}
 */
export function crossHatchFill(polygons, opts) {
  const a = opts.angleDeg || 0;
  const first = scanlineFill(polygons, { ...opts, angleDeg: a });
  const second = scanlineFill(polygons, { ...opts, angleDeg: a + 90 });
  return first.concat(second);
}

/**
 * 计算填充线段总长（用于统计），不生成 Stroke。
 */
export function estimateFillLength(polygons, spacing) {
  const strokes = scanlineFill(polygons, { spacing });
  let len = 0;
  for (const s of strokes) len += dist(s.pts[0], s.pts[1]);
  return len;
}