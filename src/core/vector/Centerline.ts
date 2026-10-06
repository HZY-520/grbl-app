/**
 * 中心线走线（骨骼 / 中轴）矢量化
 *
 * 原 LaserGRBL 的 Centerline 模式依赖外部 autotrace.exe，无法直接移植。
 * 这里改为纯 TypeScript 实现：
 *   1. 二值化（R+G+B 均值阈值；alpha<128 视为背景）
 *   2. Zhang-Suen 细化算法得到 1 像素宽的骨架
 *   3. 建立 8 邻域图，从端点/分支点出发walk出折线分支（纯环单独处理）
 *   4. 剪除短于 minBranchPx 的毛刺分支（迭代，支持级联）
 *   5. Douglas-Peucker 简化（同时合并共线点）
 *
 * 输出为像素坐标系（y 向下）中的单线折线，适合线稿 / 汉字笔画的“走线”雕刻。
 */

import type { Polyline, Pt } from './Paths'
import { simplifyPath } from './Paths'
import type { PotraceImage } from './Potrace'

/** 中心线走线选项 */
export interface CenterlineOptions {
  /** 二值化阈值 0..255，默认 128 */
  threshold?: number
  invert?: boolean
  /** 是否输出闭合折线（默认 false） */
  closed?: boolean
  /** 丢弃短于该像素长度的分支（去毛刺），默认 6 */
  minBranchPx?: number
  /** Douglas-Peucker 简化容差（像素），默认 1.2 */
  simplifyTolerance?: number
  /** 骨架化迭代上限，默认 60 */
  maxIterations?: number
}

/** 8 邻域偏移（顺时针：左上、上、右上、右、右下、下、左下、左） */
const OFFS: ReadonlyArray<readonly [number, number]> = [
  [-1, -1],
  [0, -1],
  [1, -1],
  [1, 0],
  [1, 1],
  [0, 1],
  [-1, 1],
  [-1, 0]
]

/** 二值化：返回 1=前景（黑）的位图 */
function binarize(image: PotraceImage, threshold: number, invert: boolean): Uint8Array {
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
  return out
}

/** 四周补一圈 0，避免贴边图形被细化算法误删 */
function pad(src: Uint8Array, w: number, h: number): { data: Uint8Array; w: number; h: number } {
  const pw = w + 2
  const ph = h + 2
  const out = new Uint8Array(pw * ph)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) out[(y + 1) * pw + (x + 1)] = src[y * w + x]
  }
  return { data: out, w: pw, h: ph }
}

/**
 * Zhang-Suen 细化，原地修改，得到 1 像素宽骨架。
 * 返回是否「收敛」（最后一轮迭代没有再删除任何像素）。
 * 注意：粗图形（笔画宽 w）需要约 w/2 次迭代才能收敛，迭代上限因此会直接影响
 * 骨架是否真的只有 1 像素宽——centerlineTrace 保持原有的 60 次上限不变。
 */
function thin(skel: Uint8Array, w: number, h: number, maxIter: number): boolean {
  let changed = true
  let iter = 0
  const del: number[] = []
  while (changed && iter < maxIter) {
    changed = false
    iter++
    for (let pass = 0; pass < 2; pass++) {
      del.length = 0
      for (let y = 1; y < h - 1; y++) {
        const row = y * w
        for (let x = 1; x < w - 1; x++) {
          const i = row + x
          if (!skel[i]) continue
          const p2 = skel[i - w]
          const p3 = skel[i - w + 1]
          const p4 = skel[i + 1]
          const p5 = skel[i + w + 1]
          const p6 = skel[i + w]
          const p7 = skel[i + w - 1]
          const p8 = skel[i - 1]
          const p9 = skel[i - w - 1]
          const b = p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9
          if (b < 2 || b > 6) continue
          // 0→1 跳变次数
          let a = 0
          if (!p2 && p3) a++
          if (!p3 && p4) a++
          if (!p4 && p5) a++
          if (!p5 && p6) a++
          if (!p6 && p7) a++
          if (!p7 && p8) a++
          if (!p8 && p9) a++
          if (!p9 && p2) a++
          if (a !== 1) continue
          if (pass === 0) {
            if (p2 * p4 * p6 !== 0) continue
            if (p4 * p6 * p8 !== 0) continue
          } else {
            if (p2 * p4 * p8 !== 0) continue
            if (p2 * p6 * p8 !== 0) continue
          }
          del.push(i)
        }
      }
      if (del.length > 0) {
        changed = true
        for (const i of del) skel[i] = 0
      }
    }
  }
  return !changed
}

/** 计算每个骨架像素的 8 邻域度数 */
function computeDegree(skel: Uint8Array, w: number, h: number): Uint8Array {
  const deg = new Uint8Array(w * h)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * w + x
      if (!skel[i]) continue
      let c = 0
      for (const o of OFFS) {
        const nx = x + o[0]
        const ny = y + o[1]
        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
        if (skel[ny * w + nx]) c++
      }
      deg[i] = c
    }
  }
  return deg
}

interface Branch {
  pix: number[]
  loop: boolean
}

/** 从骨架图中提取折线分支（端点/分支点之间的路径，以及孤立环） */
function extractBranches(skel: Uint8Array, w: number, h: number, deg: Uint8Array): Branch[] {
  const size = w * h
  const visited = new Set<number>()
  const covered = new Uint8Array(size)
  const enc = (a: number, b: number): number => (a < b ? a * size + b : b * size + a)
  const isNode = (i: number): boolean => deg[i] === 1 || deg[i] >= 3
  const branches: Branch[] = []

  // 1) 从端点/分支点出发
  for (let i = 0; i < size; i++) {
    if (!skel[i] || !isNode(i)) continue
    const ix = i % w
    const iy = (i - ix) / w
    for (const o of OFFS) {
      const nx = ix + o[0]
      const ny = iy + o[1]
      if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
      const j = ny * w + nx
      if (!skel[j]) continue
      if (visited.has(enc(i, j))) continue

      const pix: number[] = [i]
      covered[i] = 1
      visited.add(enc(i, j))
      let prev = i
      let cur = j
      pix.push(cur)
      covered[cur] = 1
      while (!isNode(cur)) {
        const cx = cur % w
        const cy = (cur - cx) / w
        let next = -1
        for (const oo of OFFS) {
          const mx = cx + oo[0]
          const my = cy + oo[1]
          if (mx < 0 || mx >= w || my < 0 || my >= h) continue
          const k = my * w + mx
          if (skel[k] && k !== prev) {
            next = k
            break
          }
        }
        if (next < 0) break
        visited.add(enc(cur, next))
        pix.push(next)
        covered[next] = 1
        prev = cur
        cur = next
      }
      branches.push({ pix, loop: cur === i })
    }
  }

  // 2) 完全由 deg==2 像素构成的孤立环
  for (let i = 0; i < size; i++) {
    if (!skel[i] || covered[i]) continue
    const ix = i % w
    const iy = (i - ix) / w
    let startEdge = -1
    for (const o of OFFS) {
      const nx = ix + o[0]
      const ny = iy + o[1]
      if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
      const j = ny * w + nx
      if (skel[j] && !visited.has(enc(i, j))) {
        startEdge = j
        break
      }
    }
    if (startEdge < 0) continue

    const pix: number[] = [i]
    covered[i] = 1
    visited.add(enc(i, startEdge))
    let prev = i
    let cur = startEdge
    while (cur !== i) {
      pix.push(cur)
      covered[cur] = 1
      const cx = cur % w
      const cy = (cur - cx) / w
      let next = -1
      for (const oo of OFFS) {
        const mx = cx + oo[0]
        const my = cy + oo[1]
        if (mx < 0 || mx >= w || my < 0 || my >= h) continue
        const k = my * w + mx
        if (skel[k] && k !== prev && !visited.has(enc(cur, k))) {
          next = k
          break
        }
      }
      if (next < 0) break
      visited.add(enc(cur, next))
      prev = cur
      cur = next
    }
    if (cur === i && pix.length >= 3) branches.push({ pix, loop: true })
  }

  return branches
}

interface Clusters {
  /** 每个像素所属的分支点簇编号，非分支点为 -1 */
  id: Int32Array
  /** 各簇的质心 x */
  cx: number[]
  /** 各簇的质心 y */
  cy: number[]
}

/**
 * 细化算法在粗交叉处常留下 2x2 左右的“分支点簇”。
 * 这里把 8 连通的分支点（deg>=3）聚为一簇，用质心代表，
 * 从而把簇内的微小碎分支合并掉，得到干净的单线交叉。
 */
function junctionClusters(skel: Uint8Array, w: number, h: number, deg: Uint8Array): Clusters {
  const size = w * h
  const id = new Int32Array(size).fill(-1)
  const cx: number[] = []
  const cy: number[] = []
  const stack: number[] = []
  for (let i = 0; i < size; i++) {
    if (!skel[i] || deg[i] < 3 || id[i] !== -1) continue
    const cid = cx.length
    let sx = 0
    let sy = 0
    let cnt = 0
    stack.length = 0
    stack.push(i)
    id[i] = cid
    while (stack.length > 0) {
      const p = stack.pop() as number
      const px = p % w
      const py = (p - px) / w
      sx += px
      sy += py
      cnt++
      for (const o of OFFS) {
        const nx = px + o[0]
        const ny = py + o[1]
        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
        const q = ny * w + nx
        if (skel[q] && deg[q] >= 3 && id[q] === -1) {
          id[q] = cid
          stack.push(q)
        }
      }
    }
    cx.push(sx / cnt)
    cy.push(sy / cnt)
  }
  return { id, cx, cy }
}

/** 迭代剪除短毛刺（返回是否发生修改） */
function pruneSpurs(skel: Uint8Array, w: number, h: number, minBranchPx: number): boolean {
  let anyChange = false
  for (let pass = 0; pass < 10; pass++) {
    const deg = computeDegree(skel, w, h)
    const branches = extractBranches(skel, w, h, deg)
    const remove: number[] = []
    let changed = false
    for (const br of branches) {
      if (br.loop) continue
      const pix = br.pix
      const n = pix.length
      if (n >= minBranchPx) continue
      const aTip = deg[pix[0]] === 1
      const bTip = deg[pix[n - 1]] === 1
      const aJun = deg[pix[0]] >= 3
      const bJun = deg[pix[n - 1]] >= 3
      if ((aTip && bJun) || (bTip && aJun) || (aTip && bTip)) {
        changed = true
        if (aTip && bJun) {
          for (let i = 0; i < n - 1; i++) remove.push(pix[i]) // 保留连接点
        } else if (bTip && aJun) {
          for (let i = 1; i < n; i++) remove.push(pix[i]) // 保留连接点
        } else {
          for (let i = 0; i < n; i++) remove.push(pix[i])
        }
      }
    }
    if (!changed) break
    anyChange = true
    for (const i of remove) skel[i] = 0
  }
  return anyChange
}

// ---------------------------------------------------------------------------
// 骨架化中间结果导出（新增，供「智能识别」统计笔画宽度使用）
//
// 说明：以下两个导出只是把原有内部实现暴露出来，centerlineTrace 的行为、
// 数值与调用方式完全不变；智能识别必须与真正走线时使用同一份二值化 / 细化实现，
// 否则「判定用的骨架」与「实际输出的骨架」会不一致。
// ---------------------------------------------------------------------------

/** 骨架化结果 */
export interface SkeletonResult {
  /** 骨架位图（1=骨架），尺寸为原图四周各补 1 像素（补边避免贴边图形被误删） */
  data: Uint8Array
  /** 补边后的宽度（= 原图宽度 + 2） */
  width: number
  /** 补边后的高度（= 原图高度 + 2） */
  height: number
  /** 前景（墨水）像素数，按原图尺寸统计 */
  inkArea: number
  /**
   * 细化是否在给定迭代上限内收敛。
   * false 表示笔画比「上限 × 2」还粗（骨架仍不是 1 像素宽），
   * 此时面积 / 骨架长度不能当作笔画宽度使用。
   */
  converged: boolean
}

/**
 * 只做「二值化 + Zhang-Suen 细化」，返回骨架（不做剪毛刺与折线提取）。
 * 与 centerlineTrace 的前两步是同一份实现，迭代上限可由调用方指定。
 */
export function skeletonize(image: PotraceImage, options: CenterlineOptions = {}): SkeletonResult {
  if (!image || image.width <= 0 || image.height <= 0) {
    return { data: new Uint8Array(0), width: 0, height: 0, inkArea: 0, converged: true }
  }
  const threshold = options.threshold ?? 128
  const invert = options.invert ?? false
  const maxIterations = Math.max(1, Math.round(options.maxIterations ?? 60))

  const bin = binarize(image, threshold, invert)
  let inkArea = 0
  for (let i = 0, n = bin.length; i < n; i++) if (bin[i]) inkArea++

  const p = pad(bin, image.width, image.height)
  const converged = thin(p.data, p.w, p.h, maxIterations)
  return { data: p.data, width: p.w, height: p.h, inkArea, converged }
}

/** 8 邻域度数（供智能识别统计端点 / 分支点） */
export function skeletonDegree(skel: Uint8Array, w: number, h: number): Uint8Array {
  return computeDegree(skel, w, h)
}

/**
 * 单线“走线”矢量化。
 * 返回像素坐标系（y 向下）中的折线；默认输出开放折线。
 */
export function centerlineTrace(image: PotraceImage, options: CenterlineOptions = {}): Polyline[] {
  if (!image || image.width <= 0 || image.height <= 0) return []
  const threshold = options.threshold ?? 128
  const invert = options.invert ?? false
  const closed = options.closed ?? false
  const minBranchPx = options.minBranchPx ?? 6
  const simplifyTolerance = options.simplifyTolerance ?? 1.2
  const maxIterations = options.maxIterations ?? 60

  const bin = binarize(image, threshold, invert)

  let black = 0
  const total = image.width * image.height
  for (let i = 0; i < total; i++) if (bin[i]) black++
  if (black === 0 || black === total) return [] // 空 / 全白 / 全黑

  const p = pad(bin, image.width, image.height)
  thin(p.data, p.w, p.h, maxIterations)
  pruneSpurs(p.data, p.w, p.h, minBranchPx)

  const deg = computeDegree(p.data, p.w, p.h)
  const branches = extractBranches(p.data, p.w, p.h, deg)
  const clusters = junctionClusters(p.data, p.w, p.h, deg)

  const out: Polyline[] = []
  for (const br of branches) {
    // 去掉补边偏移
    let pts: Pt[] = br.pix.map((i) => {
      const x = i % p.w
      const y = (i - x) / p.w
      return { x: x - 1, y: y - 1 }
    })
    const aId = clusters.id[br.pix[0]]
    const bId = clusters.id[br.pix[br.pix.length - 1]]
    if (!br.loop && aId >= 0 && aId === bId) continue // 分支点簇内的碎分支：合并丢弃
    // 端点落在分支点簇上时，吸附到簇质心，使交叉处干净相接
    if (aId >= 0) pts[0] = { x: clusters.cx[aId] - 1, y: clusters.cy[aId] - 1 }
    if (bId >= 0) pts[pts.length - 1] = { x: clusters.cx[bId] - 1, y: clusters.cy[bId] - 1 }

    pts = simplifyPath(pts, simplifyTolerance)
    if (pts.length < 2) continue
    if (Math.hypot(pts[0].x - pts[pts.length - 1].x, pts[0].y - pts[pts.length - 1].y) < 1e-6) continue
    out.push({ pts, closed: br.loop && closed })
  }
  return out
}