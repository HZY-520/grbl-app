/**
 * SVG 矢量 → G 代码转换
 * 移植自 LaserGRBL SvgConverter/GCodeFromSVG.cs 与 SvgConverter/BezierTools.cs。
 *
 * 与 C# 版本的区别：
 * - 使用浏览器原生 DOMParser 解析 SVG，不移植 System.Drawing / SvgLibrary 渲染引擎。
 * - 仅生成轮廓描边 G 代码（不做填充、不做光栅化）。
 * - 曲线/圆弧在「毫米坐标系」内按 tolerance 自适应离散为折线。
 *
 * 输出风格（fmt、G90、F 速度、激光开/关、PWM S 值）与
 * src/core/raster/RasterConverter.ts 保持一致。
 */

/** SVG → G 代码转换选项 */
export interface SvgConvertOptions {
  /** 目标宽度（毫米），高度按比例自动计算 */
  targetWidthMm: number
  /** 目标高度（毫米），可选；若提供则以宽高拉伸 */
  targetHeightMm?: number
  /** 曲线离散精度（毫米），越小越平滑，默认 0.1 */
  tolerance?: number
  /** 雕刻进给速度 mm/min */
  markSpeed: number
  /** 空移速度 mm/min */
  travelSpeed?: number
  /** 激光开启指令，如 'M3' / 'M4' */
  laserOn: string
  /** 激光关闭指令，如 'M5' */
  laserOff: string
  /** 是否使用 S 值 PWM 控制（true 时用 S 值开激光） */
  pwm: boolean
  /** 最大功率 S 值 */
  maxPower?: number
  /** 起点偏移（毫米） */
  offsetX?: number
  offsetY?: number
  /** 自定义文件头/尾（多行字符串） */
  header?: string
  footer?: string
}

/** SVG → G 代码转换结果 */
export interface SvgConvertResult {
  /** 生成的 G 代码行（不含换行符） */
  lines: string[]
  /** 实际使用的宽度/高度（毫米） */
  widthMm: number
  heightMm: number
  /** 路径数量 */
  pathCount: number
  /** 总路径长度（毫米） */
  pathLengthMm: number
}

/** 二维点 */
interface Pt {
  x: number
  y: number
}

/** 仿射矩阵 [a b c d e f]，对应 SVG matrix(a,b,c,d,e,f)：
 *  x' = a*x + c*y + e, y' = b*x + d*y + f */
interface Matrix {
  a: number
  b: number
  c: number
  d: number
  e: number
  f: number
}

const IDENTITY: Matrix = { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 }

/** 需要整体跳过的容器元素 */
const SKIP_TAGS = new Set([
  'defs', 'clippath', 'mask', 'pattern', 'symbol', 'marker',
  'lineargradient', 'radialgradient', 'filter', 'style', 'title', 'desc', 'metadata'
])

/** 支持解析的图形元素 */
const SHAPE_TAGS = new Set(['path', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon'])

/** 贝塞尔递归最大细分次数（与 BezierTools.FlattenTo 一致） */
const MAX_SUBDIV = 20

/** C# "0.###" 格式化（与 RasterConverter.fmt 一致） */
function fmt(v: number): string {
  const r = Math.round(v * 1000) / 1000
  if (Number.isInteger(r)) return String(r)
  return String(r)
}

/** 矩阵乘法：结果表示「先应用 m2，再应用 m1」 */
function mul(m1: Matrix, m2: Matrix): Matrix {
  return {
    a: m1.a * m2.a + m1.c * m2.b,
    b: m1.b * m2.a + m1.d * m2.b,
    c: m1.a * m2.c + m1.c * m2.d,
    d: m1.b * m2.c + m1.d * m2.d,
    e: m1.a * m2.e + m1.c * m2.f + m1.e,
    f: m1.b * m2.e + m1.d * m2.f + m1.f
  }
}

/** 用矩阵变换点 */
function apply(m: Matrix, x: number, y: number): Pt {
  return { x: m.a * x + m.c * y + m.e, y: m.b * x + m.d * y + m.f }
}

/** 从字符串中提取所有数字（支持逗号/空格分隔、科学计数法） */
function parseNumbers(str: string | null): number[] {
  if (!str) return []
  const re = /[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?/g
  const out: number[] = []
  let m: RegExpExecArray | null
  while ((m = re.exec(str)) !== null) out.push(parseFloat(m[0]))
  return out
}

/**
 * 解析 SVG 长度（px/mm/cm/in/pt/pc/em），返回像素值。
 * （新增导出：供 SVG 光栅化走线复用，行为不变）
 */
export function parseLength(str: string | null): number {
  if (!str) return 0
  const m = /^\s*([-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?)\s*([a-zA-Z%]*)\s*$/.exec(str)
  if (!m) return 0
  const v = parseFloat(m[1])
  const unit = m[2].toLowerCase()
  switch (unit) {
    case '':
    case 'px':
      return v
    case 'mm':
      return (v * 96) / 25.4
    case 'cm':
      return (v * 96) / 2.54
    case 'in':
      return v * 96
    case 'pt':
      return (v * 96) / 72
    case 'pc':
      return (v * 12 * 96) / 72
    case 'em':
      return v * 16
    default:
      return v
  }
}

/**
 * 解析 viewBox，返回 [minX, minY, width, height]；无效时返回 null。
 * （新增导出：供 SVG 光栅化走线复用，行为不变）
 */
export function parseViewBox(str: string | null): [number, number, number, number] | null {
  const n = parseNumbers(str)
  if (n.length < 4) return null
  if (!(n[2] > 0) || !(n[3] > 0)) return null
  return [n[0], n[1], n[2], n[3]]
}

/** 由单个变换函数名与参数构造矩阵 */
function transformFromArgs(name: string, a: number[]): Matrix {
  switch (name) {
    case 'translate':
      return { a: 1, b: 0, c: 0, d: 1, e: a[0] || 0, f: a[1] || 0 }
    case 'scale': {
      const sx = a.length > 0 ? a[0] : 1
      const sy = a.length > 1 ? a[1] : sx
      return { a: sx, b: 0, c: 0, d: sy, e: 0, f: 0 }
    }
    case 'rotate': {
      const ang = ((a[0] || 0) * Math.PI) / 180
      const cos = Math.cos(ang)
      const sin = Math.sin(ang)
      const rot: Matrix = { a: cos, b: sin, c: -sin, d: cos, e: 0, f: 0 }
      if (a.length >= 3) {
        const cx = a[1]
        const cy = a[2]
        return mul(
          mul({ a: 1, b: 0, c: 0, d: 1, e: cx, f: cy }, rot),
          { a: 1, b: 0, c: 0, d: 1, e: -cx, f: -cy }
        )
      }
      return rot
    }
    case 'matrix':
      return { a: a[0], b: a[1], c: a[2], d: a[3], e: a[4], f: a[5] }
    case 'skewX': {
      const t = Math.tan(((a[0] || 0) * Math.PI) / 180)
      return { a: 1, b: 0, c: t, d: 1, e: 0, f: 0 }
    }
    case 'skewY': {
      const t = Math.tan(((a[0] || 0) * Math.PI) / 180)
      return { a: 1, b: t, c: 0, d: 1, e: 0, f: 0 }
    }
    default:
      return IDENTITY
  }
}

/**
 * 解析元素的 transform 属性（translate/scale/rotate/matrix/skewX/skewY）。
 * 按 SVG 规范：变换列表从左到右构造矩阵，作用于点时靠后的变换先生效。
 * 这里手动解析，避免 Android WebView 中未挂载元素取不到 getCTM() 的问题。
 */
function parseTransform(str: string | null): Matrix {
  if (!str) return IDENTITY
  const re = /([a-zA-Z]+)\s*\(([^)]*)\)/g
  let result = IDENTITY
  let m: RegExpExecArray | null
  while ((m = re.exec(str)) !== null) {
    const name = m[1]
    const args = parseNumbers(m[2])
    const t = transformFromArgs(name, args)
    result = mul(result, t)
  }
  return result
}

/** 三角形面积（用于判断贝塞尔是否足够平坦） */
function triArea(a: Pt, b: Pt, c: Pt): number {
  return Math.abs(a.x * b.y + b.x * c.y + c.x * a.y - a.y * b.x - b.y * c.x - c.y * a.x) / 2
}

function isFlat(p0: Pt, p1: Pt, p2: Pt, p3: Pt, tol: number): boolean {
  return Math.sqrt(triArea(p0, p1, p2)) < tol && Math.sqrt(triArea(p1, p2, p3)) < tol
}

/** 递归自适应离散三次贝塞尔，追加除起点外的所有点 */
function flattenCubicRec(
  p0: Pt,
  p1: Pt,
  p2: Pt,
  p3: Pt,
  tol: number,
  depth: number,
  out: Pt[]
): void {
  if (depth >= MAX_SUBDIV || isFlat(p0, p1, p2, p3, tol)) {
    out.push(p3)
    return
  }
  // De Casteljau 在 t=0.5 处切分
  const p01: Pt = { x: (p0.x + p1.x) / 2, y: (p0.y + p1.y) / 2 }
  const p12: Pt = { x: (p1.x + p2.x) / 2, y: (p1.y + p2.y) / 2 }
  const p23: Pt = { x: (p2.x + p3.x) / 2, y: (p2.y + p3.y) / 2 }
  const p012: Pt = { x: (p01.x + p12.x) / 2, y: (p01.y + p12.y) / 2 }
  const p123: Pt = { x: (p12.x + p23.x) / 2, y: (p12.y + p23.y) / 2 }
  const mid: Pt = { x: (p012.x + p123.x) / 2, y: (p012.y + p123.y) / 2 }
  flattenCubicRec(p0, p01, p012, mid, tol, depth + 1, out)
  flattenCubicRec(mid, p123, p23, p3, tol, depth + 1, out)
}

/** 有向夹角（与 C# CalculateVectorAngle 一致） */
function vectorAngle(ux: number, uy: number, vx: number, vy: number): number {
  const ta = Math.atan2(uy, ux)
  const tb = Math.atan2(vy, vx)
  if (tb >= ta) return tb - ta
  return Math.PI * 2 - (ta - tb)
}

/**
 * 椭圆弧端点参数化 → 拆成若干 ≤90° 的三次贝塞尔段，逐段回调。
 * 移植自 GCodeFromSVG.calcArc（源于 vvvv/SVG SvgArcSegment.cs）。
 */
function arcToCubics(
  sx: number,
  sy: number,
  rx0: number,
  ry0: number,
  angleDeg: number,
  large: number,
  sweep: number,
  ex: number,
  ey: number,
  emit: (p0: Pt, p1: Pt, p2: Pt, p3: Pt) => void
): void {
  const phi = (angleDeg * Math.PI) / 180
  const sinPhi = Math.sin(phi)
  const cosPhi = Math.cos(phi)
  const x1dash = (cosPhi * (sx - ex)) / 2 + (sinPhi * (sy - ey)) / 2
  const y1dash = (-sinPhi * (sx - ex)) / 2 + (cosPhi * (sy - ey)) / 2

  const numerator =
    rx0 * rx0 * ry0 * ry0 - rx0 * rx0 * y1dash * y1dash - ry0 * ry0 * x1dash * x1dash

  let rx = rx0
  let ry = ry0
  let root: number
  if (numerator < 0) {
    const s = Math.sqrt(1 - numerator / (rx0 * rx0 * ry0 * ry0))
    rx *= s
    ry *= s
    root = 0
  } else {
    const sign = (large === 1 && sweep === 1) || (large === 0 && sweep === 0) ? -1 : 1
    root = sign * Math.sqrt(numerator / (rx0 * rx0 * y1dash * y1dash + ry0 * ry0 * x1dash * x1dash))
  }

  const cxdash = (root * rx * y1dash) / ry
  const cydash = (-root * ry * x1dash) / rx
  const cx = cosPhi * cxdash - sinPhi * cydash + (sx + ex) / 2
  const cy = sinPhi * cxdash + cosPhi * cydash + (sy + ey) / 2

  let theta1 = vectorAngle(1, 0, (x1dash - cxdash) / rx, (y1dash - cydash) / ry)
  let dtheta = vectorAngle(
    (x1dash - cxdash) / rx,
    (y1dash - cydash) / ry,
    (-x1dash - cxdash) / rx,
    (-y1dash - cydash) / ry
  )
  if (sweep === 0 && dtheta > 0) dtheta -= 2 * Math.PI
  else if (sweep === 1 && dtheta < 0) dtheta += 2 * Math.PI

  const segments = Math.max(1, Math.ceil(Math.abs(dtheta / (Math.PI / 2))))
  const delta = dtheta / segments
  const t = ((8 / 3) * Math.sin(delta / 4) * Math.sin(delta / 4)) / Math.sin(delta / 2)

  let startX = sx
  let startY = sy
  for (let i = 0; i < segments; i++) {
    const cosT1 = Math.cos(theta1)
    const sinT1 = Math.sin(theta1)
    const theta2 = theta1 + delta
    const cosT2 = Math.cos(theta2)
    const sinT2 = Math.sin(theta2)

    const endpointX = cosPhi * rx * cosT2 - sinPhi * ry * sinT2 + cx
    const endpointY = sinPhi * rx * cosT2 + cosPhi * ry * sinT2 + cy

    const dx1 = t * (-cosPhi * rx * sinT1 - sinPhi * ry * cosT1)
    const dy1 = t * (-sinPhi * rx * sinT1 + cosPhi * ry * cosT1)
    const dxe = t * (cosPhi * rx * sinT2 + sinPhi * ry * cosT2)
    const dye = t * (sinPhi * rx * sinT2 - cosPhi * ry * cosT2)

    emit(
      { x: startX, y: startY },
      { x: startX + dx1, y: startY + dy1 },
      { x: endpointX + dxe, y: endpointY + dye },
      { x: endpointX, y: endpointY }
    )

    theta1 = theta2
    startX = endpointX
    startY = endpointY
  }
}

/** 路径命令分组 */
interface CmdGroup {
  cmd: string
  nums: number[]
}

/** 把 path 的 d 拆成「命令 + 数字列表」 */
function tokenizePath(d: string): CmdGroup[] {
  const re = /([MmLlHhVvCcSsQqTtAaZz])|([-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?)/g
  const groups: CmdGroup[] = []
  let cur: CmdGroup | null = null
  let m: RegExpExecArray | null
  while ((m = re.exec(d)) !== null) {
    if (m[1]) {
      cur = { cmd: m[1], nums: [] }
      groups.push(cur)
    } else if (m[2] !== undefined && cur) {
      cur.nums.push(parseFloat(m[2]))
    }
  }
  return groups
}

/**
 * 解析 path 的 d 属性，输出毫米坐标系下的折线子路径。
 * 支持 M/m L/l H/h V/v C/c S/s Q/q T/t A/a Z/z。
 */
function processPath(d: string, m: Matrix, tol: number, out: Pt[][]): void {
  const groups = tokenizePath(d)
  if (groups.length === 0) return

  let cur: Pt = { x: 0, y: 0 }
  let subStart: Pt = { x: 0, y: 0 }
  let currentPts: Pt[] = []
  let prevCubicCtrl: Pt | null = null
  let prevQuadCtrl: Pt | null = null
  let lastCmd = ''
  let firstCmd = true

  const pushPt = (p: Pt) => currentPts.push(p)

  // 若当前没有活动子路径，则以当前点作为子路径起点
  const ensureSub = () => {
    if (currentPts.length === 0) {
      currentPts.push(apply(m, cur.x, cur.y))
      subStart = cur
    }
  }
  const finish = () => {
    if (currentPts.length >= 2) out.push(currentPts)
    currentPts = []
  }
  // 用户坐标 → 毫米后离散三次贝塞尔
  const emitCubic = (p0: Pt, p1: Pt, p2: Pt, p3: Pt) => {
    flattenCubicRec(
      apply(m, p0.x, p0.y),
      apply(m, p1.x, p1.y),
      apply(m, p2.x, p2.y),
      apply(m, p3.x, p3.y),
      tol,
      0,
      currentPts
    )
  }
  // 二次贝塞尔升阶为三次
  const emitQuad = (p0: Pt, c: Pt, p1: Pt) => {
    const c1: Pt = { x: p0.x + (2 / 3) * (c.x - p0.x), y: p0.y + (2 / 3) * (c.y - p0.y) }
    const c2: Pt = { x: p1.x + (2 / 3) * (c.x - p1.x), y: p1.y + (2 / 3) * (c.y - p1.y) }
    emitCubic(p0, c1, c2, p1)
  }
  const emitArc = (sx: number, sy: number, rx: number, ry: number, rot: number, large: number, sweep: number, ex: number, ey: number) => {
    arcToCubics(sx, sy, rx, ry, rot, large, sweep, ex, ey, emitCubic)
  }

  for (const g of groups) {
    const cmd = g.cmd
    const upper = cmd.toUpperCase()
    const rel = cmd !== upper
    const nums = g.nums
    lastCmd = upper

    switch (upper) {
      case 'M': {
        for (let i = 0; i + 1 < nums.length; i += 2) {
          let px = nums[i]
          let py = nums[i + 1]
          // 路径首个相对 moveto 按绝对坐标处理（SVG 规范）
          if (rel && !(firstCmd && i === 0)) {
            px += cur.x
            py += cur.y
          }
          if (i === 0) {
            finish()
            cur = { x: px, y: py }
            subStart = cur
            currentPts = [apply(m, px, py)]
          } else {
            cur = { x: px, y: py }
            pushPt(apply(m, px, py))
          }
        }
        prevCubicCtrl = null
        prevQuadCtrl = null
        break
      }
      case 'L': {
        for (let i = 0; i + 1 < nums.length; i += 2) {
          const px = rel ? cur.x + nums[i] : nums[i]
          const py = rel ? cur.y + nums[i + 1] : nums[i + 1]
          ensureSub()
          cur = { x: px, y: py }
          pushPt(apply(m, px, py))
        }
        prevCubicCtrl = null
        prevQuadCtrl = null
        break
      }
      case 'H': {
        for (let i = 0; i < nums.length; i++) {
          const px = rel ? cur.x + nums[i] : nums[i]
          ensureSub()
          cur = { x: px, y: cur.y }
          pushPt(apply(m, cur.x, cur.y))
        }
        prevCubicCtrl = null
        prevQuadCtrl = null
        break
      }
      case 'V': {
        for (let i = 0; i < nums.length; i++) {
          const py = rel ? cur.y + nums[i] : nums[i]
          ensureSub()
          cur = { x: cur.x, y: py }
          pushPt(apply(m, cur.x, cur.y))
        }
        prevCubicCtrl = null
        prevQuadCtrl = null
        break
      }
      case 'C': {
        for (let i = 0; i + 5 < nums.length; i += 6) {
          const c1: Pt = { x: rel ? cur.x + nums[i] : nums[i], y: rel ? cur.y + nums[i + 1] : nums[i + 1] }
          const c2: Pt = { x: rel ? cur.x + nums[i + 2] : nums[i + 2], y: rel ? cur.y + nums[i + 3] : nums[i + 3] }
          const p: Pt = { x: rel ? cur.x + nums[i + 4] : nums[i + 4], y: rel ? cur.y + nums[i + 5] : nums[i + 5] }
          ensureSub()
          emitCubic(cur, c1, c2, p)
          prevCubicCtrl = c2
          prevQuadCtrl = null
          cur = p
        }
        break
      }
      case 'S': {
        for (let i = 0; i + 3 < nums.length; i += 4) {
          const c2: Pt = { x: rel ? cur.x + nums[i] : nums[i], y: rel ? cur.y + nums[i + 1] : nums[i + 1] }
          const p: Pt = { x: rel ? cur.x + nums[i + 2] : nums[i + 2], y: rel ? cur.y + nums[i + 3] : nums[i + 3] }
          const useMirror = (lastCmd === 'C' || lastCmd === 'S') && prevCubicCtrl !== null
          const c1: Pt = useMirror
            ? { x: 2 * cur.x - (prevCubicCtrl as Pt).x, y: 2 * cur.y - (prevCubicCtrl as Pt).y }
            : { x: cur.x, y: cur.y }
          ensureSub()
          emitCubic(cur, c1, c2, p)
          prevCubicCtrl = c2
          prevQuadCtrl = null
          cur = p
        }
        break
      }
      case 'Q': {
        for (let i = 0; i + 3 < nums.length; i += 4) {
          const c: Pt = { x: rel ? cur.x + nums[i] : nums[i], y: rel ? cur.y + nums[i + 1] : nums[i + 1] }
          const p: Pt = { x: rel ? cur.x + nums[i + 2] : nums[i + 2], y: rel ? cur.y + nums[i + 3] : nums[i + 3] }
          ensureSub()
          emitQuad(cur, c, p)
          prevQuadCtrl = c
          prevCubicCtrl = null
          cur = p
        }
        break
      }
      case 'T': {
        for (let i = 0; i + 1 < nums.length; i += 2) {
          const p: Pt = { x: rel ? cur.x + nums[i] : nums[i], y: rel ? cur.y + nums[i + 1] : nums[i + 1] }
          const useMirror = (lastCmd === 'Q' || lastCmd === 'T') && prevQuadCtrl !== null
          const c: Pt = useMirror
            ? { x: 2 * cur.x - (prevQuadCtrl as Pt).x, y: 2 * cur.y - (prevQuadCtrl as Pt).y }
            : { x: cur.x, y: cur.y }
          ensureSub()
          emitQuad(cur, c, p)
          prevQuadCtrl = c
          prevCubicCtrl = null
          cur = p
        }
        break
      }
      case 'A': {
        for (let i = 0; i + 6 < nums.length; i += 7) {
          const rx = nums[i]
          const ry = nums[i + 1]
          const rot = nums[i + 2]
          const large = nums[i + 3]
          const sweep = nums[i + 4]
          const p: Pt = { x: rel ? cur.x + nums[i + 5] : nums[i + 5], y: rel ? cur.y + nums[i + 6] : nums[i + 6] }
          ensureSub()
          if (rx > 0 && ry > 0) {
            emitArc(cur.x, cur.y, rx, ry, rot, large, sweep, p.x, p.y)
          } else {
            // 半径为 0：退化为直线
            pushPt(apply(m, p.x, p.y))
          }
          prevCubicCtrl = null
          prevQuadCtrl = null
          cur = p
        }
        break
      }
      case 'Z': {
        if (currentPts.length > 0) {
          const startMm = apply(m, subStart.x, subStart.y)
          const last = currentPts[currentPts.length - 1]
          if (Math.hypot(startMm.x - last.x, startMm.y - last.y) > 1e-6) pushPt(startMm)
        }
        finish()
        cur = { ...subStart }
        prevCubicCtrl = null
        prevQuadCtrl = null
        break
      }
      default:
        break
    }
    firstCmd = false
  }
  finish()
}

/** 点到线段的垂直距离（用于共线点合并） */
function perpendicularDistance(a: Pt, b: Pt, c: Pt): number {
  const dx = c.x - a.x
  const dy = c.y - a.y
  const len = Math.hypot(dx, dy)
  if (len < 1e-9) return Math.hypot(b.x - a.x, b.y - a.y)
  return Math.abs((b.x - a.x) * dy - (b.y - a.y) * dx) / len
}

/** 合并共线中间点，压缩 G 代码量 */
function simplify(points: Pt[]): Pt[] {
  if (points.length <= 2) return points.slice()
  const out: Pt[] = [points[0]]
  for (let i = 1; i < points.length - 1; i++) {
    const a = out[out.length - 1]
    const b = points[i]
    const c = points[i + 1]
    if (perpendicularDistance(a, b, c) > 0.001) out.push(b)
  }
  out.push(points[points.length - 1])
  return out
}

/** 读取元素数值属性（支持单位） */
function numAttr(el: Element, name: string): number {
  return parseLength(el.getAttribute(name))
}

/** 解析 points 属性为坐标点 */
function parsePointsAttr(str: string | null): Pt[] {
  const n = parseNumbers(str)
  const pts: Pt[] = []
  for (let i = 0; i + 1 < n.length; i += 2) pts.push({ x: n[i], y: n[i + 1] })
  return pts
}

/** 把基础图形元素转成等价的 path d 字符串 */
function shapeToPathData(el: Element, tag: string): string | null {
  switch (tag) {
    case 'path':
      return el.getAttribute('d')
    case 'rect': {
      const x = numAttr(el, 'x')
      const y = numAttr(el, 'y')
      const w = numAttr(el, 'width')
      const h = numAttr(el, 'height')
      if (!(w > 0) || !(h > 0)) return null
      let rx = numAttr(el, 'rx')
      let ry = numAttr(el, 'ry')
      if (rx <= 0 && ry > 0) rx = ry
      if (ry <= 0 && rx > 0) ry = rx
      rx = Math.min(rx, w / 2)
      ry = Math.min(ry, h / 2)
      if (rx > 0 && ry > 0) {
        return (
          `M ${x + rx} ${y} H ${x + w - rx} ` +
          `A ${rx} ${ry} 0 0 1 ${x + w} ${y + ry} ` +
          `V ${y + h - ry} A ${rx} ${ry} 0 0 1 ${x + w - rx} ${y + h} ` +
          `H ${x + rx} A ${rx} ${ry} 0 0 1 ${x} ${y + h - ry} ` +
          `V ${y + ry} A ${rx} ${ry} 0 0 1 ${x + rx} ${y} Z`
        )
      }
      return `M ${x} ${y} H ${x + w} V ${y + h} H ${x} Z`
    }
    case 'circle': {
      const cx = numAttr(el, 'cx')
      const cy = numAttr(el, 'cy')
      const r = numAttr(el, 'r')
      if (!(r > 0)) return null
      return `M ${cx - r} ${cy} A ${r} ${r} 0 1 1 ${cx + r} ${cy} A ${r} ${r} 0 1 1 ${cx - r} ${cy} Z`
    }
    case 'ellipse': {
      const cx = numAttr(el, 'cx')
      const cy = numAttr(el, 'cy')
      const rx = numAttr(el, 'rx')
      const ry = numAttr(el, 'ry')
      if (!(rx > 0) || !(ry > 0)) return null
      return `M ${cx - rx} ${cy} A ${rx} ${ry} 0 1 1 ${cx + rx} ${cy} A ${rx} ${ry} 0 1 1 ${cx - rx} ${cy} Z`
    }
    case 'line':
      return `M ${numAttr(el, 'x1')} ${numAttr(el, 'y1')} L ${numAttr(el, 'x2')} ${numAttr(el, 'y2')}`
    case 'polyline':
    case 'polygon': {
      const pts = parsePointsAttr(el.getAttribute('points'))
      if (pts.length < 2) return null
      let d = `M ${pts[0].x} ${pts[0].y}`
      for (let i = 1; i < pts.length; i++) d += ` L ${pts[i].x} ${pts[i].y}`
      if (tag === 'polygon') d += ' Z'
      return d
    }
    default:
      return null
  }
}

/** 递归遍历 DOM，累积父级变换并处理图形元素 */
function walk(el: Element, parentMatrix: Matrix, out: Pt[][], tol: number): void {
  const children = el.children
  for (let i = 0; i < children.length; i++) {
    const child = children[i]
    const tag = child.tagName.toLowerCase()
    if (SKIP_TAGS.has(tag)) continue

    // 元素自身变换叠加在父级之上
    const m = mul(parentMatrix, parseTransform(child.getAttribute('transform')))

    if (SHAPE_TAGS.has(tag)) {
      const d = shapeToPathData(child, tag)
      if (d && d.length > 0) processPath(d, m, tol, out)
    } else {
      // 容器（g / a / 嵌套 svg 等）：继续遍历
      walk(child, m, out, tol)
    }
  }
}

/** 把 SVG 文本转成 G 代码 */
export function convertSvgToGcode(svgText: string, options: SvgConvertOptions): SvgConvertResult {
  const parser = new DOMParser()
  const doc = parser.parseFromString(svgText, 'image/svg+xml')
  if (doc.querySelector('parsererror')) throw new Error('SVG 解析失败：文件格式无效')

  const root = doc.documentElement
  if (!root || root.tagName.toLowerCase() !== 'svg') throw new Error('SVG 解析失败：缺少 <svg> 根元素')

  const tolerance = options.tolerance ?? 0.1
  const maxPower = options.maxPower ?? 1000
  const offsetX = options.offsetX ?? 0
  const offsetY = options.offsetY ?? 0
  const travelSpeed = options.travelSpeed ?? 0
  const markSpeed = options.markSpeed

  // ---- 内容尺寸：优先 viewBox，其次 width/height ----
  const vb = parseViewBox(root.getAttribute('viewBox'))
  let minX = 0
  let minY = 0
  let contentW = 0
  let contentH = 0
  if (vb) {
    minX = vb[0]
    minY = vb[1]
    contentW = vb[2]
    contentH = vb[3]
  } else {
    contentW = parseLength(root.getAttribute('width'))
    contentH = parseLength(root.getAttribute('height'))
    if (contentW <= 0 && contentH > 0) contentW = contentH
    if (contentH <= 0 && contentW > 0) contentH = contentW
  }
  if (!(contentW > 0)) contentW = 1
  if (!(contentH > 0)) contentH = 1

  // ---- 缩放 + Y 轴翻转（SVG Y 向下，机床 Y 向上）----
  const scaleX = options.targetWidthMm / contentW
  const heightMm = options.targetHeightMm ?? (options.targetWidthMm * contentH) / contentW
  const scaleY = heightMm / contentH

  // 用户坐标 → 毫米：全局矩阵，含 viewBox 偏移、缩放、Y 翻转与用户偏移
  const globalMatrix: Matrix = {
    a: scaleX,
    b: 0,
    c: 0,
    d: -scaleY,
    e: offsetX - minX * scaleX,
    f: offsetY + (contentH + minY) * scaleY
  }

  const subpaths: Pt[][] = []
  walk(root, globalMatrix, subpaths, tolerance)
  const realSubpaths = subpaths.filter((p) => p.length >= 2)

  // ---- 统计总路径长度 ----
  let pathLengthMm = 0
  for (const sp of realSubpaths) {
    for (let i = 1; i < sp.length; i++) {
      pathLengthMm += Math.hypot(sp[i].x - sp[i - 1].x, sp[i].y - sp[i - 1].y)
    }
  }

  // ---- 生成 G 代码 ----
  const lines: string[] = []

  // 文件头（默认 G90，与 RasterConverter 默认 header 一致）
  const header = options.header && options.header.trim() ? options.header : 'G90'
  for (const l of header.split('\n')) if (l.trim()) lines.push(l.trim())

  const feed = { v: null as number | null }
  const travelCmd = travelSpeed > 0 ? 'G1' : 'G0'
  const travelTo = (p: Pt): string => {
    let s = `${travelCmd} X${fmt(p.x)} Y${fmt(p.y)}`
    if (travelSpeed > 0 && feed.v !== travelSpeed) {
      s += ` F${fmt(travelSpeed)}`
      feed.v = travelSpeed
    }
    return s
  }
  const cutTo = (p: Pt): string => {
    let s = `G1 X${fmt(p.x)} Y${fmt(p.y)}`
    if (feed.v !== markSpeed) {
      s += ` F${fmt(markSpeed)}`
      feed.v = markSpeed
    }
    return s
  }

  // 初始定位到起点并设置速度，随后确保激光关闭
  lines.push(`${travelCmd} X${fmt(offsetX)} Y${fmt(offsetY)} F${fmt(markSpeed)}`)
  feed.v = markSpeed
  lines.push(options.pwm ? `${options.laserOn} S0` : options.laserOff)

  for (const raw of realSubpaths) {
    const sp = simplify(raw)
    if (sp.length < 2) continue
    lines.push(travelTo(sp[0]))
    lines.push(options.pwm ? `${options.laserOn} S${maxPower}` : options.laserOn)
    for (let i = 1; i < sp.length; i++) lines.push(cutTo(sp[i]))
    lines.push(options.laserOff)
  }

  // 文件尾
  if (options.footer && options.footer.trim()) {
    for (const l of options.footer.split('\n')) if (l.trim()) lines.push(l.trim())
  }

  return {
    lines,
    widthMm: options.targetWidthMm,
    heightMm,
    pathCount: realSubpaths.length,
    pathLengthMm
  }
}