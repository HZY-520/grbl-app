/**
 * 雕刻路径自动优化
 *  1. 预处理：去重合点、Douglas–Peucker 简化、丢弃退化路径
 *  2. 断点合并：首尾相接（误差 ≤ mergeMm）的路径拼成一条连续路径，
 *     激光中途不关闭，既省时间又避免重复开关激光产生的过烧点
 *  3. 方向统一：闭合轮廓按「外轮廓逆时针 / 孔洞顺时针」重排方向
 *  4. 走线排序：贪心最近邻（空间网格加速）+ 2-opt；或扫描式排序
 *  5. 每段路径自动选择距离上一段终点更近的那个端点（允许反向走刀）
 *  6. 衔接判定：间隔小于 chainMm 的相邻路径保持激光开启直接连线
 */

import {
  dedupe, simplifyDP, polylineLength, dist, dist2,
  polygonArea, boundsOfPoints, centroid, pointInPolygon, pointInBounds
} from './geom.js';

const OPT2_LIMIT = 2200; // 超过该数量不做 2-opt，避免耗时过长

/** 键：按 tolerance 量化坐标 */
function gridKey(x, y, tol) {
  return `${Math.round(x / tol)},${Math.round(y / tol)}`;
}

/**
 * 首尾相接的路径合并
 * @param {Array} strokes
 * @param {number} tol
 */
function mergeConnected(strokes, tol) {
  if (!(tol > 0) || strokes.length < 2) return strokes;

  const startMap = new Map();
  const endMap = new Map();
  const push = (map, k, i) => {
    const arr = map.get(k);
    if (arr) arr.push(i);
    else map.set(k, [i]);
  };

  const keys = new Array(strokes.length);
  for (let i = 0; i < strokes.length; i++) {
    const pts = strokes[i].pts;
    const sk = gridKey(pts[0].x, pts[0].y, tol);
    const ek = gridKey(pts[pts.length - 1].x, pts[pts.length - 1].y, tol);
    keys[i] = { sk, ek };
    push(startMap, sk, i);
    push(endMap, ek, i);
  }

  const used = new Uint8Array(strokes.length);
  const out = [];
  const nearKeys = (x, y) => {
    const gx = Math.round(x / tol);
    const gy = Math.round(y / tol);
    const ks = [];
    for (let dx = -1; dx <= 1; dx++) {
      for (let dy = -1; dy <= 1; dy++) ks.push(`${gx + dx},${gy + dy}`);
    }
    return ks;
  };

  for (let i = 0; i < strokes.length; i++) {
    if (used[i]) continue;
    used[i] = 1;
    const base = strokes[i];
    if (base.closed) {
      out.push(base);
      continue;
    }
    let pts = base.pts.slice();
    let power = base.power;
    const group = base.group;
    let guard = 0;
    while (guard++ < strokes.length) {
      const end = pts[pts.length - 1];
      let bestJ = -1;
      let bestReverse = false;
      let bestD = tol + 1e-9;
      for (const k of nearKeys(end.x, end.y)) {
        for (const j of startMap.get(k) || []) {
          if (used[j] || strokes[j].closed) continue;
          const d = dist(end, strokes[j].pts[0]);
          if (d < bestD) { bestD = d; bestJ = j; bestReverse = false; }
        }
        for (const j of endMap.get(k) || []) {
          if (used[j] || strokes[j].closed) continue;
          const pj = strokes[j].pts;
          const d = dist(end, pj[pj.length - 1]);
          if (d < bestD) { bestD = d; bestJ = j; bestReverse = true; }
        }
      }
      if (bestJ < 0) break;
      used[bestJ] = 1;
      const next = strokes[bestJ];
      const nextPts = bestReverse ? next.pts.slice().reverse() : next.pts;
      // 连接处重合点只保留一个
      const append = dist(end, nextPts[0]) < 1e-9 ? nextPts.slice(1) : nextPts;
      pts = pts.concat(append);
      // 功率取两者较大值，避免连接段功率过低
      power = Math.max(power, next.power);
    }
    out.push({ pts, power, closed: false, group });
  }
  return out;
}

/** 闭合轮廓方向统一：外轮廓逆时针、孔洞顺时针 */
function unifyDirection(strokes) {
  const closed = strokes.filter((s) => s.closed && s.pts.length >= 3);
  if (closed.length < 2 || closed.length > 1500) return;
  const indexed = closed
    .map((s) => ({ s, area: Math.abs(polygonArea(s.pts)), bounds: boundsOfPoints(s.pts) }))
    .sort((a, b) => b.area - a.area);

  for (let i = 0; i < indexed.length; i++) {
    const item = indexed[i];
    const c = centroid(item.s.pts);
    let depth = 0;
    for (let j = 0; j < i; j++) {
      const other = indexed[j];
      if (pointInBounds(c, other.bounds) && pointInPolygon(c, other.s.pts)) depth++;
    }
    const wantCCW = depth % 2 === 0;
    const area = polygonArea(item.s.pts);
    if (area !== 0 && area > 0 !== wantCCW) item.s.pts = item.s.pts.slice().reverse();
  }
}

/** 端点空间索引，用于快速最近邻查询 */
function buildIndex(strokes, cell) {
  const map = new Map();
  const add = (p, i) => {
    const k = `${Math.floor(p.x / cell)},${Math.floor(p.y / cell)}`;
    const arr = map.get(k);
    if (arr) arr.push(i);
    else map.set(k, [i]);
  };
  for (let i = 0; i < strokes.length; i++) {
    add(strokes[i].pts[0], i);
    if (!strokes[i].closed) add(strokes[i].pts[strokes[i].pts.length - 1], i);
  }
  return map;
}

/** 查询距 p 最近的未使用路径端点，返回 {index, reverse, distance} */
function nearestStroke(index, strokes, used, p, cell) {
  const gx = Math.floor(p.x / cell);
  const gy = Math.floor(p.y / cell);
  let best = null;
  for (let r = 0; r <= 64; r++) {
    for (let dx = -r; dx <= r; dx++) {
      for (let dy = -r; dy <= r; dy++) {
        // 只扫描当前环
        if (r > 0 && Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue;
        const arr = index.get(`${gx + dx},${gy + dy}`);
        if (!arr) continue;
        for (const i of arr) {
          if (used[i]) continue;
          const pts = strokes[i].pts;
          const d0 = dist2(p, pts[0]);
          const d1 = strokes[i].closed ? Infinity : dist2(p, pts[pts.length - 1]);
          const d = Math.min(d0, d1);
          if (!best || d < best.distance) {
            best = { index: i, reverse: d1 < d0, distance: d };
          }
        }
      }
    }
    // 已找到候选，且当前环的最小可能距离已超过最优解 → 收敛
    if (best && Math.sqrt(best.distance) <= r * cell) break;
  }
  if (!best) {
    // 稀疏场景回退为线性扫描
    for (let i = 0; i < strokes.length; i++) {
      if (used[i]) continue;
      const pts = strokes[i].pts;
      const d0 = dist2(p, pts[0]);
      const d1 = strokes[i].closed ? Infinity : dist2(p, pts[pts.length - 1]);
      const d = Math.min(d0, d1);
      if (!best || d < best.distance) best = { index: i, reverse: d1 < d0, distance: d };
    }
  }
  if (best) best.distance = Math.sqrt(best.distance);
  return best;
}

/** 贪心最近邻排序 */
function greedyOrder(strokes, start) {
  const n = strokes.length;
  let total = 0;
  for (const s of strokes) total += s.pts.length;
  const cell = Math.max(0.5, Math.sqrt((total / Math.max(1, n)) * 4));
  const index = buildIndex(strokes, cell);
  const used = new Uint8Array(n);
  const order = [];
  let cursor = { x: start.x, y: start.y };
  for (let k = 0; k < n; k++) {
    const best = nearestStroke(index, strokes, used, cursor, cell);
    if (!best) break;
    used[best.index] = 1;
    const s = strokes[best.index];
    const pts = best.reverse ? s.pts.slice().reverse() : s.pts;
    order.push({ pts, power: s.power, closed: s.closed, group: s.group });
    cursor = pts[pts.length - 1];
  }
  return order;
}

/** 2-opt 改良（开放路径 + 允许段反转） */
function twoOpt(order, start, maxPasses = 12) {
  const n = order.length;
  if (n < 4) return order;
  const items = order.slice();
  const head = (i) => (i < 0 ? start : items[i].pts[0]);
  const tail = (i) => items[i].pts[items[i].pts.length - 1];

  for (let pass = 0; pass < maxPasses; pass++) {
    let improved = false;
    for (let i = 0; i < n - 1; i++) {
      for (let j = i + 1; j < n; j++) {
        const a = i === 0 ? start : tail(i - 1);
        const b = tail(j);
        const c = head(i);
        const d = j + 1 < n ? head(j + 1) : null;
        const oldCost = dist(a, c) + (d ? dist(b, d) : 0);
        const newCost = dist(a, b) + (d ? dist(c, d) : 0);
        if (newCost < oldCost - 1e-9) {
          // 反转 i..j 区间，并翻转每段的走向
          const seg = items.slice(i, j + 1).reverse().map((it) => ({
            pts: it.pts.slice().reverse(),
            power: it.power,
            closed: it.closed,
            group: it.group
          }));
          items.splice(i, seg.length, ...seg);
          improved = true;
        }
      }
    }
    if (!improved) break;
  }
  return items;
}

/** 扫描式排序：按行分组，行内蛇形推进 */
function sweepOrder(strokes, serpentine = true) {
  const items = strokes.map((s) => {
    const b = boundsOfPoints(s.pts);
    return { s, cy: (b.minY + b.maxY) / 2, cx: (b.minX + b.maxX) / 2, h: b.height };
  });
  let band = 0;
  for (const it of items) band += it.h;
  band = band / Math.max(1, items.length);
  band = Math.max(0.3, band * 1.6);

  items.sort((a, b) => {
    const ra = Math.round(a.cy / band);
    const rb = Math.round(b.cy / band);
    if (ra !== rb) return ra - rb;
    return a.cx - b.cx;
  });

  const out = [];
  let currentRow = null;
  let rowBuf = [];
  let rowIndex = 0;
  const flush = () => {
    const seq = serpentine && rowIndex % 2 === 1 ? rowBuf.slice().reverse() : rowBuf;
    for (const it of seq) out.push(it.s);
    rowBuf = [];
    rowIndex++;
  };
  for (const it of items) {
    const r = Math.round(it.cy / band);
    if (currentRow === null) currentRow = r;
    if (r !== currentRow) {
      flush();
      currentRow = r;
    }
    rowBuf.push(it);
  }
  if (rowBuf.length) flush();
  return out;
}

/**
 * 优化雕刻路径
 * @param {Array} strokes 原始路径 [{pts, power, closed}]
 * @param {Object} [opts]
 * @param {'2opt'|'greedy'|'sweep'|'none'} [opts.order='2opt']
 * @param {boolean} [opts.allowReverse=true]
 * @param {number} [opts.chainMm=0.05] 衔接阈值：更小的间隔视为连续雕刻
 * @param {number} [opts.mergeMm=0.02] 断点合并阈值
 * @param {boolean} [opts.unify=true] 方向统一
 * @param {number} [opts.simplifyMm=0] 二次简化容差
 * @param {{x:number,y:number}} [opts.start] 起始点（通常为原点）
 * @param {number} [opts.passes=1] 走刀遍数
 * @returns {Object} plan
 */
export function optimizeStrokes(strokes, opts = {}) {
  const order = opts.order || '2opt';
  const allowReverse = opts.allowReverse !== false;
  const chainMm = Math.max(0, opts.chainMm == null ? 0.05 : opts.chainMm);
  const mergeMm = Math.max(0, opts.mergeMm == null ? 0.02 : opts.mergeMm);
  const simplifyMm = Math.max(0, opts.simplifyMm || 0);
  const passes = Math.max(1, Math.round(opts.passes || 1));
  const start = opts.start || { x: 0, y: 0 };

  // 1) 预处理
  const clean = [];
  for (const s of strokes) {
    if (!s || !s.pts || s.pts.length < 2) continue;
    let pts = dedupe(s.pts, 0.0005);
    if (simplifyMm > 0 && pts.length > 2) pts = simplifyDP(pts, simplifyMm);
    if (pts.length < 2) continue;
    if (polylineLength(pts) < 0.005) continue;
    clean.push({ pts, power: s.power == null ? 1 : s.power, closed: !!s.closed, group: s.group });
  }
  const rawCount = clean.length;

  // 2) 断点合并
  const merged = mergeConnected(clean, mergeMm);

  // 3) 方向统一
  if (opts.unify !== false) unifyDirection(merged);

  // 4) 排序
  let ordered;
  if (order === 'none') ordered = merged;
  else if (order === 'sweep') ordered = sweepOrder(merged);
  else ordered = greedyOrder(merged, start);

  if (order === '2opt' && ordered.length > 1 && ordered.length <= OPT2_LIMIT) {
    ordered = twoOpt(ordered, start);
  }

  // 5) 方向微调 + 计算空移 / 衔接
  const items = [];
  let markLength = 0;
  let travelLength = 0;
  let chainCount = 0;
  let cursor = { x: start.x, y: start.y };
  let first = true;

  for (const s of ordered) {
    let pts = s.pts;
    if (allowReverse) {
      const dStart = dist(cursor, pts[0]);
      const dEnd = s.closed ? dStart : dist(cursor, pts[pts.length - 1]);
      if (dEnd < dStart) pts = pts.slice().reverse();
    }
    const segLen = polylineLength(pts, s.closed);
    const gap = first ? 0 : dist(cursor, pts[0]);
    const chained = !first && gap <= chainMm;
    if (chained) {
      chainCount++;
    } else {
      travelLength += gap;
    }
    markLength += segLen;
    items.push({ pts, power: s.power, closed: s.closed, chained, gap, length: segLen, group: s.group });
    cursor = pts[pts.length - 1];
    first = false;
  }

  // 6) 走刀遍数：把整段路径重复 passes 次
  let sequences = [items];
  if (passes > 1) {
    sequences = [];
    for (let p = 0; p < passes; p++) sequences.push(items);
  }

  return {
    items,
    sequences,
    passes,
    count: items.length,
    rawCount,
    mergedCount: merged.length,
    markLength,
    travelLength: travelLength * passes,
    chainCount,
    totalLength: markLength * passes + travelLength * passes,
    start
  };
}