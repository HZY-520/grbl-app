/**
 * Potrace 位图轮廓矢量化（轮廓/描边模式）
 *
 * 忠实移植自 LaserGRBL 的 CsPotrace（Peter Selinger / Wolfgang Nagl 的 C# 版 Potrace）。
 * 完整实现了原始管线：
 *   1. 二值化（按 R+G+B 均值与阈值比较，alpha<128 视为背景）
 *   2. 位图 → 路径分解（findNext / findPath / xorPath，XOR 分解，minority 转向策略）
 *   3. calcSums（前缀和）→ calcLon（最优直线段）→ bestPolygon（最优多边形）
 *   4. adjustVertices（顶点调整 / 二次型最小化）
 *   5. smooth（平滑与拐角分析）→ optiCurve（曲线优化）
 *   6. 曲线 → 折线：贝塞尔按 flattenTolerance 自适应离散
 *
 * 与 C# 差异：无全局静态状态（每次调用独立）；不再输出 Curve 对象，直接输出折线。
 * 输出的像素坐标为「像素角坐标」，y 轴向下，与 C# 版本一致。
 */

import type { Polyline, Pt } from './Paths'

/** 输入位图（RGBA，ImageData 布局） */
export interface PotraceImage {
  data: Uint8ClampedArray
  width: number
  height: number
}

/** Potrace 选项 */
export interface PotraceOptions {
  /** 二值化阈值 0..255，默认 128（原项目 Treshold 是 0..1 的 0.45） */
  threshold?: number
  /** 去除小于该像素面积的斑点，默认 2（原 turdsize） */
  turdSize?: number
  /** 曲线圆角阈值，默认 1.0（原 alphamax） */
  alphaMax?: number
  /** 曲线优化容差，默认 0.2（原 opttolerance） */
  optTolerance?: number
  /** 是否做曲线优化，默认 true（原 curveoptimizing） */
  curveOptimizing?: boolean
  /** 反相（白底黑线时用），默认 false */
  invert?: boolean
  /** 贝塞尔离散容差（像素），默认 0.2 */
  flattenTolerance?: number
}

// ---------------------------------------------------------------------------
// 常量（与 C# 一致）
// ---------------------------------------------------------------------------
const POTRACE_CORNER = 1
const POTRACE_CURVETO = 2
const COS179 = Math.cos((179 * Math.PI) / 180) // 与原实现保持一致的常量

// ---------------------------------------------------------------------------
// 内部数据类型
// ---------------------------------------------------------------------------
interface IPt {
  x: number
  y: number
}

interface Sum {
  x: number
  y: number
  xy: number
  x2: number
  y2: number
}

interface Bm {
  w: number
  h: number
  data: Uint8Array
}

interface PrivCurve {
  n: number
  tag: Int32Array
  vertex: IPt[]
  c: IPt[]
  alpha: Float64Array
  alpha0: Float64Array
  beta: Float64Array
  alphacurve: number
}

interface PPath {
  area: number
  len: number
  sign: string
  pt: IPt[]
  minX: number
  minY: number
  maxX: number
  maxY: number
  x0: number
  y0: number
  m: number
  po: Int32Array | null
  lon: Int32Array | null
  sums: Sum[]
  curve: PrivCurve | null
}

interface Opti {
  pen: number
  c: IPt[]
  t: number
  s: number
  alpha: number
}

function newPrivCurve(count: number): PrivCurve {
  return {
    n: count,
    tag: new Int32Array(count),
    vertex: new Array<IPt>(count),
    c: new Array<IPt>(count * 3),
    alpha: new Float64Array(count),
    alpha0: new Float64Array(count),
    beta: new Float64Array(count),
    alphacurve: 0
  }
}

function newOpti(): Opti {
  return { pen: 0, c: [{ x: 0, y: 0 }, { x: 0, y: 0 }], t: 0, s: 0, alpha: 0 }
}

// ---------------------------------------------------------------------------
// 基础数学辅助（逐行移植）
// ---------------------------------------------------------------------------
function sign(i: number): number {
  return i > 0 ? 1 : i < 0 ? -1 : 0
}

function tdiv(a: number, b: number): number {
  return Math.trunc(a / b)
}

function mod(a: number, n: number): number {
  return a >= n ? a % n : a >= 0 ? a : n - 1 - ((-1 - a) % n)
}

/** range over the straight line segment [a,b] when lambda ranges over [0,1] */
function interval(lambda: number, a: IPt, b: IPt): IPt {
  return { x: a.x + lambda * (b.x - a.x), y: a.y + lambda * (b.y - a.y) }
}

/** 90 度逆时针方向，但限制到主轴方向之一 */
function dorthInfty(p0: IPt, p2: IPt): IPt {
  return { y: sign(p2.x - p0.x), x: -sign(p2.y - p0.y) }
}

function ddenom(p0: IPt, p2: IPt): number {
  const r = dorthInfty(p0, p2)
  return r.y * (p2.x - p0.x) - r.x * (p2.y - p0.y)
}

function dpara(p0: IPt, p1: IPt, p2: IPt): number {
  const x1 = p1.x - p0.x
  const y1 = p1.y - p0.y
  const x2 = p2.x - p0.x
  const y2 = p2.y - p0.y
  return x1 * y2 - x2 * y1
}

function cprod(p0: IPt, p1: IPt, p2: IPt, p3: IPt): number {
  const x1 = p1.x - p0.x
  const y1 = p1.y - p0.y
  const x2 = p3.x - p2.x
  const y2 = p3.y - p2.y
  return x1 * y2 - x2 * y1
}

function iprod(p0: IPt, p1: IPt, p2: IPt): number {
  const x1 = p1.x - p0.x
  const y1 = p1.y - p0.y
  const x2 = p2.x - p0.x
  const y2 = p2.y - p0.y
  return x1 * x2 + y1 * y2
}

function iprod1(p0: IPt, p1: IPt, p2: IPt, p3: IPt): number {
  const x1 = p1.x - p0.x
  const y1 = p1.y - p0.y
  const x2 = p3.x - p2.x
  const y2 = p3.y - p2.y
  return x1 * x2 + y1 * y2
}

function ddist(p: IPt, q: IPt): number {
  return Math.sqrt((p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y))
}

function xprodi(p1: IPt, p2: IPt): number {
  return p1.x * p2.y - p1.y * p2.x
}

/** 循环意义下 a <= b < c < a（mod n） */
function cyclic(a: number, b: number, c: number): boolean {
  if (a <= c) return a <= b && b < c
  return a <= b || b < c
}

/** 二次型 Q 作用于向量 w=（w.x,w.y） */
function quadform(Q: Float64Array, w: IPt): number {
  const v = [w.x, w.y, 1]
  let sum = 0
  for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) sum += v[i] * Q[i * 3 + j] * v[j]
  return sum
}

/** 贝塞尔曲线取点 */
function bezier(t: number, p0: IPt, p1: IPt, p2: IPt, p3: IPt): IPt {
  const s = 1 - t
  return {
    x: s * s * s * p0.x + 3 * (s * s * t) * p1.x + 3 * (t * t * s) * p2.x + t * t * t * p3.x,
    y: s * s * s * p0.y + 3 * (s * s * t) * p1.y + 3 * (t * t * s) * p2.y + t * t * t * p3.y
  }
}

/** 求贝塞尔 (p0,p1,p2,p3) 上与直线 q0-q1 相切的参数 t∈[0,1]，无解返回 -1 */
function tangent(p0: IPt, p1: IPt, p2: IPt, p3: IPt, q0: IPt, q1: IPt): number {
  const A = cprod(p0, p1, q0, q1)
  const B = cprod(p1, p2, q0, q1)
  const C = cprod(p2, p3, q0, q1)
  const a = A - 2 * B + C
  const b = -2 * A + 2 * B
  const c = A
  const d = b * b - 4 * a * c
  if (a === 0 || d < 0) return -1
  const s = Math.sqrt(d)
  const r1 = (-b + s) / (2 * a)
  const r2 = (-b - s) / (2 * a)
  if (r1 >= 0 && r1 <= 1) return r1
  if (r2 >= 0 && r2 <= 1) return r2
  return -1
}

/** 计算线段 i..j 的中心与斜率（需要 sums 已计算） */
function pointslope(path: PPath, i: number, j: number, ctr: IPt, dir: IPt): void {
  const n = path.len
  const sums = path.sums
  let r = 0
  while (j >= n) {
    j -= n
    r += 1
  }
  while (i >= n) {
    i -= n
    r -= 1
  }
  while (j < 0) {
    j += n
    r -= 1
  }
  while (i < 0) {
    i += n
    r += 1
  }

  const x = sums[j + 1].x - sums[i].x + r * sums[n].x
  const y = sums[j + 1].y - sums[i].y + r * sums[n].y
  const x2 = sums[j + 1].x2 - sums[i].x2 + r * sums[n].x2
  const xy = sums[j + 1].xy - sums[i].xy + r * sums[n].xy
  const y2 = sums[j + 1].y2 - sums[i].y2 + r * sums[n].y2
  const k = j + 1 - i + r * n

  ctr.x = x / k
  ctr.y = y / k

  let a = (x2 - (x * x) / k) / k
  let b = (xy - (x * y) / k) / k
  let c = (y2 - (y * y) / k) / k

  const lambda2 = (a + c + Math.sqrt((a - c) * (a - c) + 4 * b * b)) / 2
  a -= lambda2
  c -= lambda2

  let l: number
  if (Math.abs(a) >= Math.abs(c)) {
    l = Math.sqrt(a * a + b * b)
    if (l !== 0) {
      dir.x = -b / l
      dir.y = a / l
    }
  } else {
    l = Math.sqrt(c * c + b * b)
    if (l !== 0) {
      dir.x = -c / l
      dir.y = b / l
    }
  }
  if (l === 0) {
    dir.x = 0
    dir.y = 0
  }
}

// ---------------------------------------------------------------------------
// 位图辅助
// ---------------------------------------------------------------------------
function bmAt(bm: Bm, x: number, y: number): boolean {
  return x >= 0 && x < bm.w && y >= 0 && y < bm.h && bm.data[bm.w * y + x] === 1
}

function bmFlip(bm: Bm, x: number, y: number): void {
  if (bmAt(bm, x, y)) bm.data[bm.w * y + x] = 0
  else bm.data[bm.w * y + x] = 1
}

/** 二值化：R+G+B 均值 < 阈值 视为前景（黑）；alpha<128 记为背景 */
function binarize(image: PotraceImage, threshold: number, invert: boolean): Bm {
  const w = image.width
  const h = image.height
  const src = image.data
  const out = new Uint8Array(w * h)
  for (let i = 0, n = w * h; i < n; i++) {
    const a = src[4 * i + 3]
    if (a < 128) {
      out[i] = 0
      continue
    }
    const sum = src[4 * i] + src[4 * i + 1] + src[4 * i + 2]
    const black = sum < threshold * 3
    out[i] = black ? 1 : 0
  }
  if (invert) {
    for (let i = 0, n = w * h; i < n; i++) out[i] = out[i] ? 0 : 1
  }
  return { w, h, data: out }
}

/** 多数转向策略：以 (x,y) 为中心逐层检测 */
function majority(bm1: Bm, x: number, y: number): boolean {
  for (let i = 2; i < 5; i++) {
    let ct = 0
    for (let a = -i + 1; a <= i - 1; a++) {
      ct += bmAt(bm1, x + a, y + i - 1) ? 1 : -1
      ct += bmAt(bm1, x + i - 1, y + a - 1) ? 1 : -1
      ct += bmAt(bm1, x + a - 1, y - i) ? 1 : -1
      ct += bmAt(bm1, x - i, y + a) ? 1 : -1
    }
    if (ct > 0) return true
    if (ct < 0) return false
  }
  return false
}

/** 从 from 起向后找第一个前景像素 */
function findNext(bm1: Bm, from: IPt): IPt | null {
  let i = bm1.w * from.y + from.x
  const n = bm1.data.length
  while (i < n && bm1.data[i] !== 1) i++
  if (i >= n) return null
  const y = Math.floor(i / bm1.w)
  return { x: i - y * bm1.w, y }
}

/** 沿位图边界追踪一条路径 */
function findPath(bm0: Bm, bm1: Bm, point: IPt): PPath {
  const path: PPath = {
    area: 0,
    len: 0,
    sign: '',
    pt: [],
    minX: 100000,
    minY: 100000,
    maxX: -1,
    maxY: -1,
    x0: 0,
    y0: 0,
    m: 0,
    po: null,
    lon: null,
    sums: [],
    curve: null
  }
  let x = point.x
  let y = point.y
  let dirx = 0
  let diry = 1
  let tmp = -1

  path.sign = bmAt(bm0, point.x, point.y) ? '+' : '-'

  for (;;) {
    path.pt.push({ x, y })
    if (x > path.maxX) path.maxX = x
    if (x < path.minX) path.minX = x
    if (y > path.maxY) path.maxY = y
    if (y < path.minY) path.minY = y
    path.len++

    x += dirx
    y += diry
    path.area -= x * diry

    if (x === point.x && y === point.y) break

    const l = bmAt(bm1, x + tdiv(dirx + diry - 1, 2), y + tdiv(diry - dirx - 1, 2))
    const r = bmAt(bm1, x + tdiv(dirx - diry - 1, 2), y + tdiv(diry + dirx - 1, 2))

    if (r && !l) {
      // minority 转向策略
      if (!majority(bm1, x, y)) {
        tmp = dirx
        dirx = -diry
        diry = tmp
      } else {
        tmp = dirx
        dirx = diry
        diry = -tmp
      }
    } else if (r) {
      tmp = dirx
      dirx = -diry
      diry = tmp
    } else if (!l) {
      tmp = dirx
      dirx = diry
      diry = -tmp
    }
  }
  return path
}

/** 沿路径 XOR 翻转像素（把已追踪区域从工作位图中移除） */
function xorPath(bm1: Bm, path: PPath): void {
  let y1 = path.pt[0].y
  const len = path.len
  for (let i = 1; i < len; i++) {
    const x = path.pt[i].x
    const y = path.pt[i].y
    if (y !== y1) {
      const minY = y1 < y ? y1 : y
      const maxX = path.maxX
      for (let j = x; j < maxX; j++) bmFlip(bm1, j, minY)
      y1 = y
    }
  }
}

/** 位图 → 路径列表（XOR 分解） */
function bmToPathlist(bm: Bm, turdsize: number): PPath[] {
  const bm1: Bm = { w: bm.w, h: bm.h, data: bm.data.slice() }
  const list: PPath[] = []
  let found = findNext(bm1, { x: 0, y: 0 })
  while (found) {
    const path = findPath(bm, bm1, found)
    xorPath(bm1, path)
    if (path.area > turdsize) list.push(path)
    found = findNext(bm1, found)
  }
  return list
}

// ---------------------------------------------------------------------------
// Stage 2: 最优多边形
// ---------------------------------------------------------------------------
function calcSums(path: PPath): void {
  path.x0 = path.pt[0].x
  path.y0 = path.pt[0].y
  const s: Sum[] = path.sums
  s.push({ x: 0, y: 0, xy: 0, x2: 0, y2: 0 })
  for (let i = 0; i < path.len; i++) {
    const x = path.pt[i].x - path.x0
    const y = path.pt[i].y - path.y0
    s.push({
      x: s[i].x + x,
      y: s[i].y + y,
      xy: s[i].xy + x * y,
      x2: s[i].x2 + x * x,
      y2: s[i].y2 + y * y
    })
  }
}

/** 计算 i→j 边的代价（需要 lon 与 sums） */
function penalty3(path: PPath, i: number, j: number): number {
  const n = path.len
  const pt = path.pt
  const sums = path.sums
  let r = 0
  if (j >= n) {
    j -= n
    r = 1
  }
  let x: number
  let y: number
  let x2: number
  let xy: number
  let y2: number
  let k: number
  if (r === 0) {
    x = sums[j + 1].x - sums[i].x
    y = sums[j + 1].y - sums[i].y
    x2 = sums[j + 1].x2 - sums[i].x2
    xy = sums[j + 1].xy - sums[i].xy
    y2 = sums[j + 1].y2 - sums[i].y2
    k = j + 1 - i
  } else {
    x = sums[j + 1].x - sums[i].x + sums[n].x
    y = sums[j + 1].y - sums[i].y + sums[n].y
    x2 = sums[j + 1].x2 - sums[i].x2 + sums[n].x2
    xy = sums[j + 1].xy - sums[i].xy + sums[n].xy
    y2 = sums[j + 1].y2 - sums[i].y2 + sums[n].y2
    k = j + 1 - i + n
  }

  const px = (pt[i].x + pt[j].x) / 2.0 - pt[0].x
  const py = (pt[i].y + pt[j].y) / 2.0 - pt[0].y
  const ey = pt[j].x - pt[i].x
  const ex = -(pt[j].y - pt[i].y)

  const a = (x2 - (2 * x * px) / k) / k + px * px
  const b = (xy - (x * py) / k - (y * px) / k) / k + px * py
  const c = (y2 - (2 * y * py) / k) / k + py * py

  const s = ex * ex * a + 2 * ex * ey * b + ey * ey * c
  return Math.sqrt(s)
}

/** 为每个点计算最远的、可由直线连接的枢轴点 */
function calcLon(path: PPath): void {
  const n = path.len
  const pt = path.pt
  const pivk = new Int32Array(n)
  const nc = new Int32Array(n)
  const ct = new Int32Array(4)
  path.lon = new Int32Array(n)

  const constraint: IPt[] = [{ x: 0, y: 0 }, { x: 0, y: 0 }]
  const cur: IPt = { x: 0, y: 0 }
  const off: IPt = { x: 0, y: 0 }
  const dk: IPt = { x: 0, y: 0 }
  let foundk: number
  let j: number
  let k1: number
  let a: number
  let b: number
  let c: number
  let d: number

  let k = 0
  for (let i = n - 1; i >= 0; i--) {
    if (pt[i].x !== pt[k].x && pt[i].y !== pt[k].y) k = i + 1
    nc[i] = k
  }

  for (let i = n - 1; i >= 0; i--) {
    ct[0] = ct[1] = ct[2] = ct[3] = 0
    let dir = tdiv(3 + 3 * (pt[mod(i + 1, n)].x - pt[i].x) + (pt[mod(i + 1, n)].y - pt[i].y), 2)
    ct[dir]++

    constraint[0].x = 0
    constraint[0].y = 0
    constraint[1].x = 0
    constraint[1].y = 0

    k = nc[i]
    k1 = i
    for (;;) {
      foundk = 0
      dir = tdiv(3 + 3 * sign(pt[k].x - pt[k1].x) + sign(pt[k].y - pt[k1].y), 2)
      ct[dir]++

      if (ct[0] === 1 && ct[1] === 1 && ct[2] === 1 && ct[3] === 1) {
        pivk[i] = k1
        foundk = 1
        break
      }

      cur.x = pt[k].x - pt[i].x
      cur.y = pt[k].y - pt[i].y

      if (xprodi(constraint[0], cur) < 0 || xprodi(constraint[1], cur) > 0) break

      if (!(Math.abs(cur.x) <= 1 && Math.abs(cur.y) <= 1)) {
        off.x = cur.x + (cur.y >= 0 && (cur.y > 0 || cur.x < 0) ? 1 : -1)
        off.y = cur.y + (cur.x <= 0 && (cur.x < 0 || cur.y < 0) ? 1 : -1)
        if (xprodi(constraint[0], off) >= 0) {
          constraint[0].x = off.x
          constraint[0].y = off.y
        }
        off.x = cur.x + (cur.y <= 0 && (cur.y < 0 || cur.x < 0) ? 1 : -1)
        off.y = cur.y + (cur.x >= 0 && (cur.x > 0 || cur.y < 0) ? 1 : -1)
        if (xprodi(constraint[1], off) <= 0) {
          constraint[1].x = off.x
          constraint[1].y = off.y
        }
      }
      k1 = k
      k = nc[k1]
      if (!cyclic(k, i, k1)) break
    }
    if (foundk === 0) {
      dk.x = sign(pt[k].x - pt[k1].x)
      dk.y = sign(pt[k].y - pt[k1].y)
      cur.x = pt[k1].x - pt[i].x
      cur.y = pt[k1].y - pt[i].y

      a = xprodi(constraint[0], cur)
      b = xprodi(constraint[0], dk)
      c = xprodi(constraint[1], cur)
      d = xprodi(constraint[1], dk)

      j = 10000000
      if (b < 0) j = a / -b
      if (d > 0) j = Math.min(j, -c / d)
      pivk[i] = mod(k1 + j, n)
    }
  }

  j = pivk[n - 1]
  path.lon[n - 1] = j
  for (let i = n - 2; i >= 0; i--) {
    if (cyclic(i + 1, pivk[i], j)) j = pivk[i]
    path.lon[i] = j
  }

  for (let i = n - 1; cyclic(mod(i + 1, n), j, path.lon[i]); i--) {
    path.lon[i] = j
  }
}

/** 求最优多边形（填充 path.m 与 path.po） */
function bestPolygon(path: PPath): void {
  let thispen: number
  let best: number
  let i: number
  let j: number
  let m: number
  const n = path.len
  let c: number
  const clip0 = new Int32Array(n)
  const pen = new Float64Array(n + 1)
  const prev = new Int32Array(n + 1)
  const clip1 = new Int32Array(n + 1)
  const seg0 = new Int32Array(n + 1)
  const seg1 = new Int32Array(n + 1)
  const lon = path.lon as Int32Array

  for (i = 0; i < n; i++) {
    c = mod(lon[mod(i - 1, n)] - 1, n)
    if (c === i) c = mod(i + 1, n)
    if (c < i) clip0[i] = n
    else clip0[i] = c
  }

  j = 1
  for (i = 0; i < n; i++) {
    while (j <= clip0[i]) {
      clip1[j] = i
      j++
    }
  }

  i = 0
  for (j = 0; i < n; j++) {
    seg0[j] = i
    i = clip0[i]
  }
  seg0[j] = n
  m = j

  i = n
  for (j = m; j > 0; j--) {
    seg1[j] = i
    i = clip1[i]
  }
  seg1[0] = 0

  pen[0] = 0
  for (j = 1; j <= m; j++) {
    for (i = seg1[j]; i <= seg0[j]; i++) {
      best = -1
      for (let k = seg0[j - 1]; k >= clip1[i]; k--) {
        thispen = penalty3(path, k, i) + pen[k]
        if (best < 0 || thispen < best) {
          prev[i] = k
          best = thispen
        }
      }
      pen[i] = best
    }
  }
  path.m = m
  path.po = new Int32Array(m)
  const po = path.po
  for (i = n, j = m - 1; i > 0; j--) {
    i = prev[i]
    po[j] = i
  }
}

// ---------------------------------------------------------------------------
// Stage 3: 顶点调整
// ---------------------------------------------------------------------------
function adjustVertices(path: PPath): void {
  const m = path.m
  const po = path.po as Int32Array
  const pt = path.pt
  const x0 = path.x0
  const y0 = path.y0
  const ctr: IPt[] = new Array(m)
  const dir: IPt[] = new Array(m)
  const q: Float64Array[] = new Array(m)
  let i: number
  let j: number
  let k: number
  let l: number
  const v = [0, 0, 0]

  const s: IPt = { x: 0, y: 0 }
  path.curve = newPrivCurve(m)

  for (i = 0; i < m; i++) {
    j = po[mod(i + 1, m)]
    j = mod(j - po[i], path.len) + po[i]
    ctr[i] = { x: 0, y: 0 }
    dir[i] = { x: 0, y: 0 }
    pointslope(path, po[i], j, ctr[i], dir[i])
  }

  for (i = 0; i < m; i++) {
    q[i] = new Float64Array(9)
    const d = dir[i].x * dir[i].x + dir[i].y * dir[i].y
    if (d === 0) {
      q[i].fill(0)
    } else {
      v[0] = dir[i].y
      v[1] = -dir[i].x
      v[2] = -v[1] * ctr[i].y - v[0] * ctr[i].x
      for (l = 0; l < 3; l++) for (k = 0; k < 3; k++) q[i][l * 3 + k] = (v[l] * v[k]) / d
    }
  }

  let dx: number
  let dy: number
  let det: number
  let z: number
  let xmin: number
  let ymin: number
  let min: number
  let cand: number

  for (i = 0; i < m; i++) {
    const Q = new Float64Array(9)
    const w: IPt = { x: 0, y: 0 }
    s.x = pt[po[i]].x - x0
    s.y = pt[po[i]].y - y0
    j = mod(i - 1, m)
    for (l = 0; l < 3; l++) for (k = 0; k < 3; k++) Q[l * 3 + k] = q[j][l * 3 + k] + q[i][l * 3 + k]

    for (;;) {
      det = Q[0] * Q[4] - Q[1] * Q[3]
      if (det !== 0) {
        w.x = (-Q[2] * Q[4] + Q[5] * Q[1]) / det
        w.y = (Q[2] * Q[3] - Q[5] * Q[0]) / det
        break
      }
      if (Q[0] > Q[4]) {
        v[0] = -Q[1]
        v[1] = Q[0]
      } else if (Q[4] !== 0) {
        v[0] = -Q[4]
        v[1] = Q[3]
      } else {
        v[0] = 1
        v[1] = 0
      }
      const d = v[0] * v[0] + v[1] * v[1]
      v[2] = -v[1] * s.y - v[0] * s.x
      for (l = 0; l < 3; l++) for (k = 0; k < 3; k++) Q[l * 3 + k] += (v[l] * v[k]) / d
    }
    dx = Math.abs(w.x - s.x)
    dy = Math.abs(w.y - s.y)
    if (dx <= 0.5 && dy <= 0.5) {
      path.curve.vertex[i] = { x: w.x + x0, y: w.y + y0 }
      continue
    }

    min = quadform(Q, s)
    xmin = s.x
    ymin = s.y

    if (Q[0] !== 0) {
      for (z = 0; z < 2; z++) {
        w.y = s.y - 0.5 + z
        w.x = -(Q[1] * w.y + Q[2]) / Q[0]
        dx = Math.abs(w.x - s.x)
        cand = quadform(Q, w)
        if (dx <= 0.5 && cand < min) {
          min = cand
          xmin = w.x
          ymin = w.y
        }
      }
    }

    if (Q[4] !== 0) {
      for (z = 0; z < 2; z++) {
        w.x = s.x - 0.5 + z
        w.y = -(Q[3] * w.x + Q[5]) / Q[4]
        dy = Math.abs(w.y - s.y)
        cand = quadform(Q, w)
        if (dy <= 0.5 && cand < min) {
          min = cand
          xmin = w.x
          ymin = w.y
        }
      }
    }

    for (l = 0; l < 2; l++) {
      for (k = 0; k < 2; k++) {
        w.x = s.x - 0.5 + l
        w.y = s.y - 0.5 + k
        cand = quadform(Q, w)
        if (cand < min) {
          min = cand
          xmin = w.x
          ymin = w.y
        }
      }
    }

    path.curve.vertex[i] = { x: xmin + x0, y: ymin + y0 }
  }
}

// ---------------------------------------------------------------------------
// Stage 4: 平滑与拐角分析
// ---------------------------------------------------------------------------
function reverseCurve(path: PPath): void {
  const curve = path.curve as PrivCurve
  const v = curve.vertex
  for (let i = 0, j = curve.n - 1; i < j; i++, j--) {
    const tmp = v[i]
    v[i] = v[j]
    v[j] = tmp
  }
}

function smooth(path: PPath, alphaMax: number): void {
  const curve = path.curve as PrivCurve
  const m = curve.n
  if (path.sign === '-') reverseCurve(path)
  for (let i = 0; i < m; i++) {
    const j = mod(i + 1, m)
    const k = mod(i + 2, m)
    const p4 = interval(1 / 2.0, curve.vertex[k], curve.vertex[j])

    const denom = ddenom(curve.vertex[i], curve.vertex[k])
    let alpha: number
    if (denom !== 0) {
      let dd = dpara(curve.vertex[i], curve.vertex[j], curve.vertex[k]) / denom
      dd = Math.abs(dd)
      alpha = dd > 1 ? 1 - 1.0 / dd : 0
      alpha = alpha / 0.75
    } else {
      alpha = 4 / 3.0
    }
    curve.alpha0[j] = alpha

    if (alpha >= alphaMax) {
      curve.tag[j] = POTRACE_CORNER
      curve.c[3 * j + 1] = curve.vertex[j]
      curve.c[3 * j + 2] = p4
    } else {
      if (alpha < 0.55) alpha = 0.55
      else if (alpha > 1) alpha = 1
      const p2 = interval(0.5 + 0.5 * alpha, curve.vertex[i], curve.vertex[j])
      const p3 = interval(0.5 + 0.5 * alpha, curve.vertex[k], curve.vertex[j])
      curve.tag[j] = POTRACE_CURVETO
      curve.c[3 * j + 0] = p2
      curve.c[3 * j + 1] = p3
      curve.c[3 * j + 2] = p4
    }
    curve.alpha[j] = alpha
    curve.beta[j] = 0.5
  }
  curve.alphacurve = 1
}

// ---------------------------------------------------------------------------
// Stage 5: 曲线优化
// ---------------------------------------------------------------------------
/** 计算 i+.5 → j+.5 的最优拟合；成功返回 0，否则返回 1 */
function optiPenalty(
  path: PPath,
  i: number,
  j: number,
  res: Opti,
  opttolerance: number,
  convc: Int8Array,
  areac: Float64Array
): number {
  const curve = path.curve as PrivCurve
  const m = curve.n
  const vertex = curve.vertex
  let k: number
  let k1: number
  let k2: number
  let conv: number
  let i1: number
  let area: number
  let alpha: number
  let d: number
  let d1: number
  let d2: number
  let p0: IPt
  let p1: IPt
  let p2: IPt
  let p3: IPt
  let pt: IPt
  let A: number
  let R: number
  let s: number
  let t: number

  if (i === j) return 1

  k = i
  i1 = mod(i + 1, m)
  k1 = mod(k + 1, m)
  conv = convc[k1]
  if (conv === 0) return 1
  d = ddist(vertex[i], vertex[i1])
  for (k = k1; k !== j; k = k1) {
    k1 = mod(k + 1, m)
    k2 = mod(k + 2, m)
    if (convc[k1] !== conv) return 1
    if (sign(cprod(vertex[i], vertex[i1], vertex[k1], vertex[k2])) !== conv) return 1
    if (iprod1(vertex[i], vertex[i1], vertex[k1], vertex[k2]) < d * ddist(vertex[k1], vertex[k2]) * COS179) return 1
  }

  p0 = { ...curve.c[mod(i, m) * 3 + 2] }
  p1 = { ...vertex[mod(i + 1, m)] }
  p2 = { ...vertex[mod(j, m)] }
  p3 = { ...curve.c[mod(j, m) * 3 + 2] }

  area = areac[j] - areac[i]
  area -= dpara(vertex[0], curve.c[i * 3 + 2], curve.c[j * 3 + 2]) / 2
  if (i >= j) area += areac[m]

  const A1 = dpara(p0, p1, p2)
  const A2 = dpara(p0, p1, p3)
  const A3 = dpara(p0, p2, p3)
  const A4 = A1 + A3 - A2

  if (A2 === A1) return 1

  t = A3 / (A3 - A4)
  s = A2 / (A2 - A1)
  A = (A2 * t) / 2.0

  if (A === 0.0) return 1

  R = area / A
  alpha = 2 - Math.sqrt(4 - R / 0.3)

  res.c[0] = interval(t * alpha, p0, p1)
  res.c[1] = interval(s * alpha, p3, p2)
  res.alpha = alpha
  res.t = t
  res.s = s

  p1 = { ...res.c[0] }
  p2 = { ...res.c[1] }

  res.pen = 0
  for (k = mod(i + 1, m); k !== j; k = k1) {
    k1 = mod(k + 1, m)
    t = tangent(p0, p1, p2, p3, vertex[k], vertex[k1])
    if (t < -0.5) return 1
    pt = bezier(t, p0, p1, p2, p3)
    d = ddist(vertex[k], vertex[k1])
    if (d === 0.0) return 1
    d1 = dpara(vertex[k], vertex[k1], pt) / d
    if (Math.abs(d1) > opttolerance) return 1
    if (iprod(vertex[k], vertex[k1], pt) < 0 || iprod(vertex[k1], vertex[k], pt) < 0) return 1
    res.pen += d1 * d1
  }
  for (k = i; k !== j; k = k1) {
    k1 = mod(k + 1, m)
    t = tangent(p0, p1, p2, p3, curve.c[k * 3 + 2], curve.c[k1 * 3 + 2])
    if (t < -0.5) return 1
    pt = bezier(t, p0, p1, p2, p3)
    d = ddist(curve.c[k * 3 + 2], curve.c[k1 * 3 + 2])
    if (d === 0.0) return 1
    d1 = dpara(curve.c[k * 3 + 2], curve.c[k1 * 3 + 2], pt) / d
    d2 = dpara(curve.c[k * 3 + 2], curve.c[k1 * 3 + 2], vertex[k1]) / d
    d2 *= 0.75 * curve.alpha[k1]
    if (d2 < 0) {
      d1 = -d1
      d2 = -d2
    }
    if (d1 < d2 - opttolerance) return 1
    if (d1 < d2) res.pen += (d1 - d2) * (d1 - d2)
  }

  return 0
}

/** 用单段贝塞尔替换可合并的贝塞尔序列 */
function optiCurve(path: PPath, opttolerance: number): void {
  const curve = path.curve as PrivCurve
  const m = curve.n
  const vert = curve.vertex
  const pt = new Int32Array(m + 1)
  const pen = new Float64Array(m + 1)
  const len = new Int32Array(m + 1)
  const opt: Opti[] = new Array<Opti>(m + 1)
  let o = newOpti()
  let om: number
  let i: number
  let j: number
  let r: number
  const p0 = curve.vertex[0]
  let i1: number
  let area: number
  let alpha: number
  const convc = new Int8Array(m)
  const areac = new Float64Array(m + 1)

  for (i = 0; i < m; i++) {
    if (curve.tag[i] === POTRACE_CURVETO) {
      convc[i] = sign(dpara(vert[mod(i - 1, m)], vert[i], vert[mod(i + 1, m)]))
    } else {
      convc[i] = 0
    }
  }

  area = 0.0
  areac[0] = 0.0
  for (i = 0; i < m; i++) {
    i1 = mod(i + 1, m)
    if (curve.tag[i1] === POTRACE_CURVETO) {
      alpha = curve.alpha[i1]
      area += (0.3 * alpha * (4 - alpha) * dpara(curve.c[i * 3 + 2], vert[i1], curve.c[i1 * 3 + 2])) / 2
      area += dpara(p0, curve.c[i * 3 + 2], curve.c[i1 * 3 + 2]) / 2
    }
    areac[i + 1] = area
  }

  pt[0] = -1
  pen[0] = 0
  len[0] = 0

  for (j = 1; j <= m; j++) {
    pt[j] = j - 1
    pen[j] = pen[j - 1]
    len[j] = len[j - 1] + 1

    for (i = j - 2; i >= 0; i--) {
      r = optiPenalty(path, i, mod(j, m), o, opttolerance, convc, areac)
      if (r === 1) break
      if (len[j] > len[i] + 1 || (len[j] === len[i] + 1 && pen[j] > pen[i] + o.pen)) {
        pt[j] = i
        pen[j] = pen[i] + o.pen
        len[j] = len[i] + 1
        opt[j] = o
        o = newOpti()
      }
    }
  }

  om = len[m]
  if (om < 1) return
  const ocurve = newPrivCurve(om)
  const s = new Float64Array(om)
  const t = new Float64Array(om)

  j = m
  for (i = om - 1; i >= 0; i--) {
    if (pt[j] === j - 1) {
      ocurve.tag[i] = curve.tag[mod(j, m)]
      ocurve.c[i * 3 + 0] = curve.c[mod(j, m) * 3 + 0]
      ocurve.c[i * 3 + 1] = curve.c[mod(j, m) * 3 + 1]
      ocurve.c[i * 3 + 2] = curve.c[mod(j, m) * 3 + 2]
      ocurve.vertex[i] = curve.vertex[mod(j, m)]
      ocurve.alpha[i] = curve.alpha[mod(j, m)]
      ocurve.alpha0[i] = curve.alpha0[mod(j, m)]
      ocurve.beta[i] = curve.beta[mod(j, m)]
      s[i] = 1.0
      t[i] = 1.0
    } else {
      const oj = opt[j]
      ocurve.tag[i] = POTRACE_CURVETO
      ocurve.c[i * 3 + 0] = oj.c[0]
      ocurve.c[i * 3 + 1] = oj.c[1]
      ocurve.c[i * 3 + 2] = curve.c[mod(j, m) * 3 + 2]
      ocurve.vertex[i] = interval(oj.s, curve.c[mod(j, m) * 3 + 2], vert[mod(j, m)])
      ocurve.alpha[i] = oj.alpha
      ocurve.alpha0[i] = oj.alpha
      s[i] = oj.s
      t[i] = oj.t
    }
    j = pt[j]
  }

  for (i = 0; i < om; i++) {
    i1 = mod(i + 1, om)
    ocurve.beta[i] = s[i] / (s[i] + t[i1])
  }
  ocurve.alphacurve = 1
  path.curve = ocurve
}

// ---------------------------------------------------------------------------
// 曲线 → 折线（贝塞尔自适应离散）
// ---------------------------------------------------------------------------
function pushPoint(out: Pt[], p: IPt): void {
  const last = out[out.length - 1]
  if (last && Math.abs(last.x - p.x) < 1e-9 && Math.abs(last.y - p.y) < 1e-9) return
  out.push({ x: p.x, y: p.y })
}

function flattenCubicRec(p0: IPt, p1: IPt, p2: IPt, p3: IPt, tol: number, depth: number, out: Pt[]): void {
  if (depth >= 20 || isFlat(p0, p1, p2, p3, tol)) {
    pushPoint(out, p3)
    return
  }
  const p01 = { x: (p0.x + p1.x) / 2, y: (p0.y + p1.y) / 2 }
  const p12 = { x: (p1.x + p2.x) / 2, y: (p1.y + p2.y) / 2 }
  const p23 = { x: (p2.x + p3.x) / 2, y: (p2.y + p3.y) / 2 }
  const p012 = { x: (p01.x + p12.x) / 2, y: (p01.y + p12.y) / 2 }
  const p123 = { x: (p12.x + p23.x) / 2, y: (p12.y + p23.y) / 2 }
  const mid = { x: (p012.x + p123.x) / 2, y: (p012.y + p123.y) / 2 }
  flattenCubicRec(p0, p01, p012, mid, tol, depth + 1, out)
  flattenCubicRec(mid, p123, p23, p3, tol, depth + 1, out)
}

function triArea(a: IPt, b: IPt, c: IPt): number {
  return Math.abs(a.x * b.y + b.x * c.y + c.x * a.y - a.y * b.x - b.y * c.x - c.y * a.x) / 2
}

function isFlat(p0: IPt, p1: IPt, p2: IPt, p3: IPt, tol: number): boolean {
  return Math.sqrt(triArea(p0, p1, p2)) < tol && Math.sqrt(triArea(p1, p2, p3)) < tol
}

/** 把单条路径的曲线集合转成闭合折线 */
function pathToPolyline(path: PPath, tol: number): Polyline | null {
  const curve = path.curve
  if (!curve || curve.n < 1) return null
  const n = curve.n
  const start = curve.c[(n - 1) * 3 + 2]
  if (!start) return null
  const out: Pt[] = [{ x: start.x, y: start.y }]
  let L: IPt = start
  for (let j = 0; j < n; j++) {
    if (curve.tag[j] === POTRACE_CORNER) {
      const A = curve.c[j * 3 + 1]
      const B = curve.c[j * 3 + 2]
      if (!A || !B) return null
      pushPoint(out, A)
      pushPoint(out, B)
      L = B
    } else {
      const CP = curve.c[j * 3 + 0]
      const A = curve.c[j * 3 + 1]
      const B = curve.c[j * 3 + 2]
      if (!CP || !A || !B) return null
      flattenCubicRec(L, CP, A, B, tol, 0, out)
      L = B
    }
  }
  if (out.length < 3) return null
  return { pts: out, closed: true }
}

// ---------------------------------------------------------------------------
// 对外接口
// ---------------------------------------------------------------------------
/**
 * 对位图执行 Potrace 轮廓矢量化。
 * 返回像素坐标系（y 向下）中的折线；孔洞与外轮廓都返回，均为闭合折线。
 */
export function potraceTrace(image: PotraceImage, options: PotraceOptions = {}): Polyline[] {
  if (!image || image.width <= 0 || image.height <= 0) return []
  const threshold = options.threshold ?? 128
  const turdSize = options.turdSize ?? 2
  const alphaMax = options.alphaMax ?? 1.0
  const optTolerance = options.optTolerance ?? 0.2
  const curveOptimizing = options.curveOptimizing ?? true
  const invert = options.invert ?? false
  const flattenTolerance = options.flattenTolerance ?? 0.2

  const bm = binarize(image, threshold, invert)

  // 全白 / 全黑保护：全黑时整幅图像为单一区域，无有效轮廓
  const total = bm.w * bm.h
  let black = 0
  for (let i = 0, n = bm.data.length; i < n; i++) if (bm.data[i]) black++
  if (black === 0 || black === total) return []

  const list = bmToPathlist(bm, turdSize)
  if (list.length === 0) return []

  const out: Polyline[] = []
  for (const path of list) {
    if (path.len < 3) continue
    calcSums(path)
    calcLon(path)
    bestPolygon(path)
    if (path.m < 1) continue
    adjustVertices(path)
    smooth(path, alphaMax)
    if (curveOptimizing) optiCurve(path, optTolerance)
    const poly = pathToPolyline(path, flattenTolerance)
    if (poly) out.push(poly)
  }
  return out
}