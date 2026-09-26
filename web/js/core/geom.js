/**
 * 基础几何工具
 * 所有内部坐标统一为「毫米」，Y 轴向上（与机床坐标系一致）。
 */

/** 二维点 {x, y}。@typedef {{x:number,y:number}} Pt */

export function dist(a, b) {
  return Math.hypot(b.x - a.x, b.y - a.y);
}

export function dist2(a, b) {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  return dx * dx + dy * dy;
}

/** 折线长度；closed 为 true 时补上闭合段 */
export function polylineLength(pts, closed = false) {
  let len = 0;
  for (let i = 1; i < pts.length; i++) len += dist(pts[i - 1], pts[i]);
  if (closed && pts.length > 2) len += dist(pts[pts.length - 1], pts[0]);
  return len;
}

/** 多边形有向面积（>0 为逆时针，Y 向上时）。@returns {number} */
export function polygonArea(pts) {
  let a = 0;
  for (let i = 0, n = pts.length; i < n; i++) {
    const p = pts[i];
    const q = pts[(i + 1) % n];
    a += p.x * q.y - q.x * p.y;
  }
  return a / 2;
}

/** 判断折线是否首尾相接 */
export function isClosed(pts, tol = 0.001) {
  return pts.length > 2 && dist(pts[0], pts[pts.length - 1]) <= tol;
}

/** 合并过近的重复点（距上一个保留点小于 tol 的点被丢弃） */
export function dedupe(pts, tol = 0.001) {
  if (pts.length === 0) return [];
  const out = [pts[0]];
  for (let i = 1; i < pts.length; i++) {
    if (dist(out[out.length - 1], pts[i]) > tol) out.push(pts[i]);
  }
  return out;
}

/** 点到线段的距离 */
export function pointSegDistance(p, a, b) {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const l2 = dx * dx + dy * dy;
  if (l2 < 1e-12) return dist(p, a);
  let t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
}

/**
 * Douglas–Peucker 折线简化（迭代实现，避免深递归）
 * @param {Pt[]} pts
 * @param {number} tol 容差（毫米）
 */
export function simplifyDP(pts, tol) {
  const n = pts.length;
  if (n <= 2 || tol <= 0) return pts.slice();
  const keep = new Uint8Array(n);
  keep[0] = 1;
  keep[n - 1] = 1;
  const stack = [[0, n - 1]];
  while (stack.length) {
    const [first, last] = stack.pop();
    let maxD = -1;
    let idx = -1;
    for (let i = first + 1; i < last; i++) {
      const d = pointSegDistance(pts[i], pts[first], pts[last]);
      if (d > maxD) {
        maxD = d;
        idx = i;
      }
    }
    if (maxD > tol && idx > 0) {
      keep[idx] = 1;
      stack.push([first, idx], [idx, last]);
    }
  }
  const out = [];
  for (let i = 0; i < n; i++) if (keep[i]) out.push(pts[i]);
  return out;
}

/** 删除中间共线点（角度阈值法），比 DP 更轻量 */
export function dropCollinear(pts, angleTolDeg = 0.75) {
  if (pts.length <= 2) return pts.slice();
  const cos = Math.cos((angleTolDeg * Math.PI) / 180);
  const out = [pts[0]];
  for (let i = 1; i < pts.length - 1; i++) {
    const a = out[out.length - 1];
    const b = pts[i];
    const c = pts[i + 1];
    const v1x = b.x - a.x;
    const v1y = b.y - a.y;
    const v2x = c.x - b.x;
    const v2y = c.y - b.y;
    const l1 = Math.hypot(v1x, v1y);
    const l2 = Math.hypot(v2x, v2y);
    if (l1 < 1e-9 || l2 < 1e-9) continue;
    const dot = (v1x * v2x + v1y * v2y) / (l1 * l2);
    if (dot < cos) out.push(b);
  }
  out.push(pts[pts.length - 1]);
  return out;
}

export function reversePoints(pts) {
  return pts.slice().reverse();
}

/** 点集包围盒 */
export function boundsOfPoints(points) {
  if (!points.length) return null;
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const p of points) {
    if (p.x < minX) minX = p.x;
    if (p.y < minY) minY = p.y;
    if (p.x > maxX) maxX = p.x;
    if (p.y > maxY) maxY = p.y;
  }
  return { minX, minY, maxX, maxY, width: maxX - minX, height: maxY - minY };
}

/** 多个折线的包围盒 */
export function boundsOfPolylines(polylines) {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  let any = false;
  for (const pl of polylines) {
    for (const p of pl) {
      any = true;
      if (p.x < minX) minX = p.x;
      if (p.y < minY) minY = p.y;
      if (p.x > maxX) maxX = p.x;
      if (p.y > maxY) maxY = p.y;
    }
  }
  if (!any) return null;
  return { minX, minY, maxX, maxY, width: maxX - minX, height: maxY - minY };
}

/** 平移折线（返回新数组） */
export function translatePoints(pts, dx, dy) {
  return pts.map((p) => ({ x: p.x + dx, y: p.y + dy }));
}

/** 缩放点集 */
export function scalePoints(pts, sx, sy = sx, origin = { x: 0, y: 0 }) {
  return pts.map((p) => ({ x: origin.x + (p.x - origin.x) * sx, y: origin.y + (p.y - origin.y) * sy }));
}

/** 以 origin 为中心旋转点集（弧度，逆时针） */
export function rotatePoints(pts, rad, origin = { x: 0, y: 0 }) {
  const c = Math.cos(rad);
  const s = Math.sin(rad);
  return pts.map((p) => {
    const dx = p.x - origin.x;
    const dy = p.y - origin.y;
    return { x: origin.x + dx * c - dy * s, y: origin.y + dx * s + dy * c };
  });
}

/** 把一条折线拆成线段数组 [[a,b], ...] */
export function toSegments(pts, closed = false) {
  const segs = [];
  for (let i = 1; i < pts.length; i++) segs.push([pts[i - 1], pts[i]]);
  if (closed && pts.length > 2) segs.push([pts[pts.length - 1], pts[0]]);
  return segs;
}

/** 数值裁剪 */
export function clamp(v, lo, hi) {
  return v < lo ? lo : v > hi ? hi : v;
}

/** 保留 n 位小数（G 代码输出用，去掉多余的 0） */
export function fmt(v, digits = 3) {
  if (!Number.isFinite(v)) return '0';
  const f = 10 ** digits;
  const r = Math.round(v * f) / f;
  if (Object.is(r, -0)) return '0';
  return String(r);
}

/** 估算加工时间（分钟）：len(mm) / feed(mm/min) */
export function timeFor(lengthMm, feed) {
  if (!(feed > 0)) return 0;
  return lengthMm / feed;
}

/** 把秒数格式化为 “1h 02m 03s” 形式 */
export function formatDuration(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return '0s';
  const s = Math.round(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m ${String(sec).padStart(2, '0')}s`;
  if (m > 0) return `${m}m ${String(sec).padStart(2, '0')}s`;
  return `${sec}s`;
}

export function formatBytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1048576).toFixed(2)} MB`;
}

/**
 * Chaikin 曲线平滑：用角点切分替代尖锐折角，可减轻位图矢量化产生的锯齿。
 * @param {Pt[]} pts
 * @param {number} iterations
 * @param {boolean} closed
 */
export function chaikin(pts, iterations = 1, closed = false) {
  let cur = pts;
  for (let k = 0; k < iterations; k++) {
    if (cur.length < 3) return cur;
    const out = [];
    const n = cur.length;
    if (!closed) out.push(cur[0]);
    const last = closed ? n : n - 1;
    for (let i = 0; i < last; i++) {
      const a = cur[i];
      const b = cur[(i + 1) % n];
      out.push({ x: a.x * 0.75 + b.x * 0.25, y: a.y * 0.75 + b.y * 0.25 });
      out.push({ x: a.x * 0.25 + b.x * 0.75, y: a.y * 0.25 + b.y * 0.75 });
    }
    if (!closed) out.push(cur[n - 1]);
    cur = out;
  }
  return cur;
}

/** 多边形质心（顶点平均值，足够用于包含性判定） */
export function centroid(pts) {
  let x = 0;
  let y = 0;
  for (const p of pts) {
    x += p.x;
    y += p.y;
  }
  return { x: x / pts.length, y: y / pts.length };
}

/** 点是否在多边形内（射线法） */
export function pointInPolygon(p, poly) {
  let inside = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const a = poly[i];
    const b = poly[j];
    if ((a.y > p.y) !== (b.y > p.y) && p.x < ((b.x - a.x) * (p.y - a.y)) / (b.y - a.y) + a.x) {
      inside = !inside;
    }
  }
  return inside;
}

/** 点是否在包围盒内 */
export function pointInBounds(p, b) {
  return p.x >= b.minX && p.x <= b.maxX && p.y >= b.minY && p.y <= b.maxY;
}