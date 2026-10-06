/**
 * 矢量路径通用类型与工具
 *
 * 为 Potrace（轮廓描边）与 Centerline（中心线走线）提供共享的折线数据结构，
 * 以及排序 / 简化 / 扫描线填充 / G 代码输出等通用处理。
 *
 * 坐标系约定：像素坐标，原点在图像左上角，y 轴向下（与 ImageData 一致）。
 */

/** 二维点（像素坐标） */
export interface Pt {
  x: number
  y: number
}

/** 折线：由若干点构成；closed 为 true 表示首尾闭合 */
export interface Polyline {
  pts: Pt[]
  closed?: boolean
}

/**
 * 最近邻贪心排序，减少空移距离；start 为起点（像素坐标）。
 * 同时允许对折线做反向接入（选择更近的一端），进一步缩短空移。
 * 返回新的折线数组，不修改入参。
 */
export function optimizeOrder(paths: Polyline[], start?: Pt): Polyline[] {
  const valid: Polyline[] = []
  const empties: Polyline[] = []
  for (const p of paths) {
    const copy: Polyline = { pts: p.pts.map((q) => ({ x: q.x, y: q.y })), closed: p.closed }
    if (copy.pts.length > 0) valid.push(copy)
    else empties.push(copy)
  }

  const out: Polyline[] = []
  let cur: Pt = start ? { x: start.x, y: start.y } : valid.length ? { x: valid[0].pts[0].x, y: valid[0].pts[0].y } : { x: 0, y: 0 }

  while (valid.length > 0) {
    let bestI = -1
    let bestD = Infinity
    let bestRev = false
    for (let i = 0; i < valid.length; i++) {
      const pts = valid[i].pts
      const head = pts[0]
      const tail = pts[pts.length - 1]
      const d0 = Math.hypot(head.x - cur.x, head.y - cur.y)
      if (d0 < bestD) {
        bestD = d0
        bestI = i
        bestRev = false
      }
      const d1 = Math.hypot(tail.x - cur.x, tail.y - cur.y)
      if (d1 < bestD) {
        bestD = d1
        bestI = i
        bestRev = true
      }
    }
    if (bestI < 0) break
    const pick = valid.splice(bestI, 1)[0]
    if (bestRev && pick.pts.length > 1) pick.pts.reverse()
    out.push(pick)
    const last = pick.pts[pick.pts.length - 1]
    cur = { x: last.x, y: last.y }
  }

  for (const e of empties) out.push(e)
  return out
}

/** 点到线段的垂距（a、b 相同时退化为点距） */
function perpendicularDistance(p: Pt, a: Pt, b: Pt): number {
  const dx = b.x - a.x
  const dy = b.y - a.y
  const len = Math.hypot(dx, dy)
  if (len < 1e-12) return Math.hypot(p.x - a.x, p.y - a.y)
  return Math.abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
}

/** Douglas-Peucker 简化，tol 为像素容差（迭代实现，避免深递归） */
export function simplifyPath(pts: Pt[], tol: number): Pt[] {
  const n = pts.length
  if (n <= 2) return pts.slice()
  const keep = new Uint8Array(n)
  keep[0] = 1
  keep[n - 1] = 1
  const stack: number[] = [0, n - 1]
  while (stack.length >= 2) {
    const e = stack.pop() as number
    const s = stack.pop() as number
    if (e <= s + 1) continue
    const a = pts[s]
    const b = pts[e]
    let maxD = -1
    let idx = -1
    for (let i = s + 1; i < e; i++) {
      const d = perpendicularDistance(pts[i], a, b)
      if (d > maxD) {
        maxD = d
        idx = i
      }
    }
    if (maxD > tol && idx > 0) {
      keep[idx] = 1
      stack.push(s, idx, idx, e)
    }
  }
  const out: Pt[] = []
  for (let i = 0; i < n; i++) if (keep[i]) out.push(pts[i])
  return out
}

/** 扫描线填充产生的分段（原始坐标系 + 变换坐标系下的排序键） */
interface HatchSeg {
  row: number
  /** 变换坐标系下的两个端点（用于排序方向） */
  t0: number
  t1: number
  /** 原始坐标系下的两个端点 */
  o0: Pt
  o1: Pt
}

/** 折线 → G 代码选项 */
export interface PolylineGcodeOptions {
  /** 每个像素对应的毫米数（pixelSizeMm） */
  pixelSizeMm: number
  offsetX: number
  offsetY: number
  markSpeed: number
  travelSpeed?: number
  minPower: number
  maxPower: number
  /** 绘制线条时的 S 值（0..maxPower），默认 maxPower */
  laserPower?: number
  /** 激光开启/关闭指令，如 'M3'/'M4' 与 'M5' */
  laserOn: string
  laserOff: string
  /** 是否用 S 值调制功率；false 时只用开/关指令 */
  pwm: boolean
  /** 图像 y 轴向下，G 代码 y 轴向上；true 时翻转（y → -y，调用方需用 offsetY 补偿到正区间） */
  flipY?: boolean
  header?: string
  footer?: string
  /** 小数位数，默认 3 */
  decimals?: number
  /** 是否对路径做最近邻排序，默认 true */
  optimize?: boolean
  /** 空移指令，默认 'G0' */
  travelCommand?: string
}

/** 折线 → G 代码结果 */
export interface PolylineGcodeResult {
  lines: string[]
  pathCount: number
  lengthMm: number
}

/** 数字格式化：最多 decimals 位小数，去掉多余的尾零（对齐 C# "0.###"） */
function fmt(v: number, decimals: number): string {
  if (!Number.isFinite(v)) v = 0
  let s = v.toFixed(decimals)
  if (s.indexOf('.') >= 0) {
    s = s.replace(/0+$/, '')
    s = s.replace(/\.$/, '')
  }
  if (s === '-0') s = '0'
  return s
}

/** 把折线集合输出为 G 代码（像素坐标 → 毫米），包含 header/footer、G0 空移、G1 雕刻、激光开/关 */
export function polylinesToGcode(paths: Polyline[], o: PolylineGcodeOptions): PolylineGcodeResult {
  const decimals = o.decimals ?? 3
  const optimize = o.optimize ?? true
  const travelCommand = o.travelCommand ?? 'G0'
  const flipY = o.flipY ?? false
  const laserPower = o.laserPower ?? o.maxPower
  const sc = o.pixelSizeMm

  const lines: string[] = []
  if (o.header) {
    for (const l of o.header.split('\n')) if (l.trim()) lines.push(l.trim())
  }

  const ordered = optimize ? optimizeOrder(paths) : paths

  const toMm = (p: Pt): Pt => ({
    x: p.x * sc + o.offsetX,
    y: (flipY ? -p.y : p.y) * sc + o.offsetY
  })

  let lengthMm = 0
  let pathCount = 0
  let curFeed: number | null = null

  for (const poly of ordered) {
    if (poly.pts.length < 2) continue
    // 毫米坐标 + 去除连续重复点，避免输出零长度 G1 指令
    const mm: Pt[] = []
    for (const p of poly.pts) {
      const q = toMm(p)
      const last = mm[mm.length - 1]
      if (last && Math.abs(last.x - q.x) < 1e-6 && Math.abs(last.y - q.y) < 1e-6) continue
      mm.push(q)
    }
    if (mm.length < 2) continue
    pathCount++

    for (let i = 1; i < mm.length; i++) {
      lengthMm += Math.hypot(mm[i].x - mm[i - 1].x, mm[i].y - mm[i - 1].y)
    }

    // 空移到起点
    let travel = `${travelCommand} X${fmt(mm[0].x, decimals)} Y${fmt(mm[0].y, decimals)}`
    if (o.travelSpeed && curFeed !== o.travelSpeed) {
      travel += ` F${fmt(o.travelSpeed, decimals)}`
      curFeed = o.travelSpeed
    }
    lines.push(travel)

    // 开激光
    lines.push(o.pwm ? `${o.laserOn} S${Math.round(laserPower)}` : o.laserOn)

    // 雕刻移动
    for (let i = 1; i < mm.length; i++) {
      let move = `G1 X${fmt(mm[i].x, decimals)} Y${fmt(mm[i].y, decimals)}`
      if (curFeed !== o.markSpeed) {
        move += ` F${fmt(o.markSpeed, decimals)}`
        curFeed = o.markSpeed
      }
      lines.push(move)
    }

    // 关激光
    lines.push(o.laserOff)
  }

  if (o.footer) {
    for (const l of o.footer.split('\n')) if (l.trim()) lines.push(l.trim())
  }

  return { lines, pathCount, lengthMm }
}