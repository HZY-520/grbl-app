/**
 * 二值位图 → 矢量路径
 * 1) traceContours：沿像素边界「裂纹跟随」提取闭合轮廓（外轮廓 + 孔洞），
 *    输出为网格顶点坐标（y 向下），由调用方负责缩放到毫米并翻转 Y。
 * 2) thinBinary / traceSkeletonPaths：Zhang–Suen 细化得到单像素骨架，
 *    再沿骨架提取中心线，用于「单线字体」「细线雕刻」。
 */

/** 顶点坐标 → 线性索引（网格顶点尺寸为 (w+1) × (h+1)） */
function vertexKey(x, y, w) {
  return y * (w + 1) + x;
}

/**
 * 提取二值图的闭合轮廓
 * @param {Uint8Array} mask 1 = 前景
 * @param {number} w
 * @param {number} h
 * @param {Object} [opts]
 * @param {number} [opts.minArea=2] 最小轮廓面积（像素²），用于过滤噪点
 * @param {number} [opts.minPoints=4] 最小顶点数
 * @returns {Array<{pts:{x:number,y:number}[], area:number, closed:boolean}>}
 */
export function traceContours(mask, w, h, opts = {}) {
  const minArea = opts.minArea ?? 2;
  const minPoints = opts.minPoints ?? 4;

  const edges = [];
  const outMap = new Map();
  const addEdge = (x1, y1, x2, y2) => {
    const e = {
      from: vertexKey(x1, y1, w),
      to: vertexKey(x2, y2, w),
      fx: x1, fy: y1, tx: x2, ty: y2,
      dx: x2 - x1, dy: y2 - y1,
      used: false
    };
    edges.push(e);
    const arr = outMap.get(e.from);
    if (arr) arr.push(e);
    else outMap.set(e.from, [e]);
  };

  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * w + x;
      if (!mask[i]) continue;
      // 上边：左侧 → 右侧
      if (y === 0 || !mask[i - w]) addEdge(x, y, x + 1, y);
      // 右边：上 → 下
      if (x === w - 1 || !mask[i + 1]) addEdge(x + 1, y, x + 1, y + 1);
      // 下边：右 → 左
      if (y === h - 1 || !mask[i + w]) addEdge(x + 1, y + 1, x, y + 1);
      // 左边：下 → 上
      if (x === 0 || !mask[i - 1]) addEdge(x, y + 1, x, y);
    }
  }

  const loops = [];
  for (const start of edges) {
    if (start.used) continue;
    const pts = [{ x: start.fx, y: start.fy }];
    let cur = start;
    let guard = 0;
    while (cur && !cur.used && guard++ < edges.length + 4) {
      cur.used = true;
      if (cur.to === start.from) {
        // 回到起点，闭合
        if (pts.length && (pts[0].x !== cur.tx || pts[0].y !== cur.ty)) {
          pts.push({ x: cur.tx, y: cur.ty });
        }
        break;
      }
      pts.push({ x: cur.tx, y: cur.ty });
      const cands = (outMap.get(cur.to) || []).filter((e) => !e.used);
      if (!cands.length) break;
      // 右转优先：保证 8 连通前景得到简单不自交的环路
      let best = cands[0];
      let bestAngle = Infinity;
      for (const c of cands) {
        const cross = cur.dx * c.dy - cur.dy * c.dx;
        const dot = cur.dx * c.dx + cur.dy * c.dy;
        const ang = Math.atan2(cross, dot);
        if (ang < bestAngle) {
          bestAngle = ang;
          best = c;
        }
      }
      cur = best;
    }
    if (pts.length < minPoints) continue;
    // 去掉与首点重复的尾点
    if (pts.length > 1 && pts[0].x === pts[pts.length - 1].x && pts[0].y === pts[pts.length - 1].y) {
      pts.pop();
    }
    if (pts.length < minPoints) continue;
    let area2 = 0;
    for (let i = 0, n = pts.length; i < n; i++) {
      const p = pts[i];
      const q = pts[(i + 1) % n];
      area2 += p.x * q.y - q.x * p.y;
    }
    const area = area2 / 2;
    if (Math.abs(area) < minArea) continue;
    loops.push({ pts, area, closed: true });
  }
  return loops;
}

/**
 * Zhang–Suen 细化：二值图 → 单像素宽骨架
 * @param {Uint8Array} mask
 * @param {number} w
 * @param {number} h
 * @param {number} [maxIter=60]
 * @returns {Uint8Array}
 */
export function thinBinary(mask, w, h, maxIter = 60) {
  const img = Uint8Array.from(mask);
  const marks = [];
  const at = (x, y) => (x < 0 || y < 0 || x >= w || y >= h ? 0 : img[y * w + x]);

  for (let iter = 0; iter < maxIter; iter++) {
    let changed = false;
    for (let step = 0; step < 2; step++) {
      marks.length = 0;
      for (let y = 0; y < h; y++) {
        for (let x = 0; x < w; x++) {
          if (!img[y * w + x]) continue;
          const p2 = at(x, y - 1);
          const p3 = at(x + 1, y - 1);
          const p4 = at(x + 1, y);
          const p5 = at(x + 1, y + 1);
          const p6 = at(x, y + 1);
          const p7 = at(x - 1, y + 1);
          const p8 = at(x - 1, y);
          const p9 = at(x - 1, y - 1);
          const B = p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9;
          if (B < 2 || B > 6) continue;
          const seq = [p2, p3, p4, p5, p6, p7, p8, p9, p2];
          let A = 0;
          for (let i = 0; i < 8; i++) if (seq[i] === 0 && seq[i + 1] === 1) A++;
          if (A !== 1) continue;
          if (step === 0) {
            if (p2 * p4 * p6 !== 0) continue;
            if (p4 * p6 * p8 !== 0) continue;
          } else {
            if (p2 * p4 * p8 !== 0) continue;
            if (p2 * p6 * p8 !== 0) continue;
          }
          marks.push(y * w + x);
        }
      }
      if (marks.length) {
        changed = true;
        for (const i of marks) img[i] = 0;
      }
    }
    if (!changed) break;
  }
  return img;
}

const NB8 = [
  [-1, 0], [1, 0], [0, -1], [0, 1],
  [-1, -1], [1, -1], [-1, 1], [1, 1]
];

/**
 * 沿骨架提取中心线折线
 * @param {Uint8Array} skel
 * @param {number} w
 * @param {number} h
 * @param {Object} [opts]
 * @param {number} [opts.minPoints=4] 过滤过短的毛刺
 * @returns {Array<Array<{x:number,y:number}>>} 折线点集（网格坐标）
 */
export function traceSkeletonPaths(skel, w, h, opts = {}) {
  const minPoints = opts.minPoints ?? 4;
  const degree = new Uint8Array(w * h);
  const neighbors = (x, y) => {
    const list = [];
    for (const [dx, dy] of NB8) {
      const nx = x + dx;
      const ny = y + dy;
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
      if (skel[ny * w + nx]) list.push({ x: nx, y: ny });
    }
    return list;
  };

  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (skel[y * w + x]) degree[y * w + x] = neighbors(x, y).length;
    }
  }

  const usedEdge = new Set();
  const edgeKey = (a, b) => {
    const k1 = a.y * w + a.x;
    const k2 = b.y * w + b.x;
    return k1 < k2 ? `${k1}_${k2}` : `${k2}_${k1}`;
  };
  const visitedPixel = new Uint8Array(w * h);
  const paths = [];

  const walk = (start, firstNext) => {
    const pts = [{ x: start.x, y: start.y }];
    visitedPixel[start.y * w + start.x] = 1;
    let prev = null;
    let cur = start;
    let next = firstNext;
    let guard = 0;
    while (next && guard++ < w * h + 8) {
      usedEdge.add(edgeKey(cur, next));
      visitedPixel[next.y * w + next.x] = 1;
      if (next.x === start.x && next.y === start.y) break; // 闭合
      pts.push({ x: next.x, y: next.y });
      prev = cur;
      cur = next;
      if (degree[cur.y * w + cur.x] !== 2) break; // 到达端点或交叉点
      const ns = neighbors(cur.x, cur.y);
      next = null;
      for (const n of ns) {
        if (prev && n.x === prev.x && n.y === prev.y) continue;
        if (!usedEdge.has(edgeKey(cur, n))) {
          next = n;
          break;
        }
      }
    }
    return pts;
  };

  const starts = [];
  // 端点优先，其次交叉点
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const d = degree[y * w + x];
      if (d === 1) starts.push({ x, y, off: 0 });
    }
  }
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (degree[y * w + x] >= 3) starts.push({ x, y, off: 1 });
    }
  }

  for (const s of starts) {
    for (const n of neighbors(s.x, s.y)) {
      if (usedEdge.has(edgeKey(s, n))) continue;
      const pts = walk(s, n);
      if (pts.length >= minPoints) paths.push(pts);
    }
  }

  // 剩余的纯环路（所有点度数为 2）
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (!skel[y * w + x] || visitedPixel[y * w + x]) continue;
      const ns = neighbors(x, y);
      if (!ns.length) continue;
      const pts = walk({ x, y }, ns[0]);
      if (pts.length >= minPoints) paths.push(pts);
    }
  }

  return paths;
}