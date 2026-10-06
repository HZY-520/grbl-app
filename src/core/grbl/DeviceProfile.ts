/**
 * 设备参数档案（DeviceProfile）
 * 对应 LaserGRBL 中散落在 Settings 的多组机器参数：
 *  - 工作区域/行程：原版通过 GRBL 的 $130/$131（TableWidth/TableHeight，默认 300×200mm）获得
 *  - 激光功率上下限：原版通过 $30/$31（MaxPWM/MinPWM，默认 S-MAX=1000、S-MIN=0）获得
 *  - 雕刻参数：GrayScaleConversion.Gcode.LaserOptions.PowerMax/PowerMin（默认 1000/0）、
 *    Line2LineOptions.Quality（默认 3 线/mm）、Mark Speed（默认 1000）
 * 本模块把这些参数收敛为"设备档案"，并可直接写入 AppSettings，使现有转换流程立即生效。
 */

import { AppSettings } from './GrblConfig'
import { Firmware } from './types'

/** 单个设备的参数档案 */
export interface DeviceProfile {
  id: string
  /** 设备名称/型号 */
  name: string
  /** X 行程 (mm) */
  travelX: number
  /** Y 行程 (mm) */
  travelY: number
  /** 固件类型名，如 'Grbl' | 'Marlin' | 'Smoothie' */
  firmware: string
  /** 最大激光功率 S 值 (S-MAX) */
  maxPower: number
  /** 最小激光功率 S 值 */
  minPower: number
  /** 默认雕刻速度 mm/min */
  markSpeed: number
  /** 默认空移速度 mm/min */
  travelSpeed: number
  /** 默认每毫米线数 (LPC/quality) */
  quality: number
  /** 默认波特率 */
  baud: number
  /** 是否为当前选中设备 */
  active: boolean
  createdAt: number
  /** 关联的 USB 设备编号（首次连接时自动绑定） */
  deviceId?: number
}

/** 档案持久化键名（沿用 lasergrbl.* 命名风格） */
const PROFILES_KEY = 'lasergrbl.devices'
/** 首次初始化向导完成标记 */
const SETUP_KEY = 'lasergrbl.setupDone'
/** 已完成初始化向导的设备编号列表 */
const KNOWN_DEVICES_KEY = 'lasergrbl.knownDevices'

/** 行程/尺寸的最小值，避免出现 0 或负数 */
const MIN_SIZE_MM = 1

/** 尺寸计算保留的小数位（避免浮点噪声） */
function round3(v: number): number {
  return Math.round(v * 1000) / 1000
}

/** 是否可用 localStorage（模块在非浏览器环境也要能工作） */
function hasStorage(): boolean {
  return typeof localStorage !== 'undefined'
}

/** 默认值集中定义（与 AppSettings.DEFAULT_SETTINGS / LaserGRBL 保持一致） */
const DEFAULTS = {
  travelX: 300,
  travelY: 200,
  firmware: 'Grbl',
  maxPower: 1000,
  minPower: 0,
  markSpeed: 1000,
  travelSpeed: 3000,
  quality: 3,
  baud: 115200
} as const

function num(v: unknown, def: number): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : def
}

function str(v: unknown, def: string): string {
  return typeof v === 'string' && v.length > 0 ? v : def
}

/** 把任意来源的对象规整为完整、字段类型正确的档案（防御损坏数据） */
function normalize(obj: Record<string, unknown>): DeviceProfile {
  return {
    id: str(obj.id, `dev-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`),
    name: str(obj.name, '未命名设备'),
    travelX: num(obj.travelX, DEFAULTS.travelX),
    travelY: num(obj.travelY, DEFAULTS.travelY),
    firmware: str(obj.firmware, DEFAULTS.firmware),
    maxPower: num(obj.maxPower, DEFAULTS.maxPower),
    minPower: num(obj.minPower, DEFAULTS.minPower),
    markSpeed: num(obj.markSpeed, DEFAULTS.markSpeed),
    travelSpeed: num(obj.travelSpeed, DEFAULTS.travelSpeed),
    quality: num(obj.quality, DEFAULTS.quality),
    baud: num(obj.baud, DEFAULTS.baud),
    active: obj.active === true,
    createdAt: num(obj.createdAt, Date.now()),
    deviceId: typeof obj.deviceId === 'number' && Number.isFinite(obj.deviceId) ? obj.deviceId : undefined
  }
}

/** 读取已保存的档案；无数据或损坏时返回空数组 */
function readStored(): DeviceProfile[] {
  if (!hasStorage()) return []
  try {
    const raw = localStorage.getItem(PROFILES_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((x): x is Record<string, unknown> => typeof x === 'object' && x !== null)
      .map(normalize)
  } catch {
    return []
  }
}

function persist(profiles: DeviceProfile[]): void {
  if (!hasStorage()) return
  try {
    localStorage.setItem(PROFILES_KEY, JSON.stringify(profiles))
  } catch {
    /* 超出配额时静默忽略 */
  }
}

/** 内置的常见机型预设（稳定 id，供首次初始化向导作为模板） */
export function builtinProfiles(): DeviceProfile[] {
  return [
    {
      id: 'builtin-300x200-s1000',
      name: '300×200 桌面入门机（S1000）',
      travelX: 300,
      travelY: 200,
      firmware: 'Grbl',
      maxPower: 1000,
      minPower: 0,
      markSpeed: 1000,
      travelSpeed: 3000,
      quality: 3,
      baud: 115200,
      active: false,
      createdAt: 0
    },
    {
      id: 'builtin-400x400-s1000',
      name: '400×400 中端机（S1000）',
      travelX: 400,
      travelY: 400,
      firmware: 'Grbl',
      maxPower: 1000,
      minPower: 0,
      markSpeed: 1000,
      travelSpeed: 3000,
      quality: 3,
      baud: 115200,
      active: false,
      createdAt: 0
    },
    {
      id: 'builtin-500x500-s1000',
      name: '500×500 大幅面机（S1000）',
      travelX: 500,
      travelY: 500,
      firmware: 'Grbl',
      maxPower: 1000,
      minPower: 0,
      markSpeed: 1000,
      travelSpeed: 3000,
      quality: 3,
      baud: 115200,
      active: false,
      createdAt: 0
    },
    {
      id: 'builtin-300x180-s255',
      name: '300×180 简易机（S255）',
      travelX: 300,
      travelY: 180,
      firmware: 'Grbl',
      maxPower: 255,
      minPower: 0,
      markSpeed: 1000,
      travelSpeed: 3000,
      quality: 3,
      baud: 115200,
      active: false,
      createdAt: 0
    }
  ]
}

export function setSetupDone(done: boolean): void {
  if (!hasStorage()) return
  try {
    localStorage.setItem(SETUP_KEY, done ? '1' : '0')
  } catch {
    /* ignore */
  }
}

/** 仅返回用户真实保存过的档案（不含内置只读预设） */
export function listSavedProfiles(): DeviceProfile[] {
  return readStored()
}

/** 生成一个新的空档案（供初始化向导使用） */
export function newProfile(partial?: Partial<DeviceProfile>): DeviceProfile {
  return normalize({
    id: `dev-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    name: '我的雕刻机',
    travelX: DEFAULTS.travelX,
    travelY: DEFAULTS.travelY,
    ...partial
  } as Record<string, unknown>)
}

/** 已完成初始化向导的设备编号 */
export function listKnownDeviceIds(): number[] {
  if (!hasStorage()) return []
  try {
    const raw = localStorage.getItem(KNOWN_DEVICES_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((x): x is number => typeof x === 'number' && Number.isFinite(x))
  } catch {
    return []
  }
}

/** 该 USB 设备是否已完成过初始化向导 */
export function isDeviceKnown(deviceId: number): boolean {
  return listKnownDeviceIds().includes(deviceId)
}

/** 标记该 USB 设备已完成初始化向导 */
export function markDeviceKnown(deviceId: number): void {
  if (!hasStorage() || !Number.isFinite(deviceId)) return
  const list = listKnownDeviceIds()
  if (!list.includes(deviceId)) list.push(deviceId)
  try {
    localStorage.setItem(KNOWN_DEVICES_KEY, JSON.stringify(list))
  } catch {
    /* ignore */
  }
}

/** 按 USB 设备编号查找已绑定的档案 */
export function findProfileByDevice(deviceId: number): DeviceProfile | null {
  return readStored().find((p) => p.deviceId === deviceId) ?? null
}

/**
 * 新增或更新（按 id），并返回保存后的档案。
 * 变更只写入"已保存的档案"；内置预设仅作只读回退，不会被无意中固化到存储里。
 */
export function saveProfile(p: DeviceProfile): DeviceProfile {
  const list = readStored()
  const saved = normalize(p as unknown as Record<string, unknown>)
  const idx = list.findIndex((x) => x.id === saved.id)
  if (idx >= 0) list[idx] = saved
  else list.push(saved)
  // 同一时刻最多只有一个激活档案
  if (saved.active) {
    for (const it of list) if (it.id !== saved.id) it.active = false
  }
  persist(list)
  return saved
}

export function deleteProfile(id: string): void {
  const list = readStored().filter((p) => p.id !== id)
  // 若删除的是当前激活设备，自动激活剩余的第一个，避免出现"无激活设备"
  if (list.length > 0 && !list.some((p) => p.active)) list[0].active = true
  persist(list)
}

export function setActiveProfile(id: string): void {
  // 存储为空时，选中内置预设意味着把它固化下来（首次初始化向导场景）
  const stored = readStored()
  const list = stored.length > 0 ? stored : builtinProfiles()
  let found = false
  for (const p of list) {
    p.active = p.id === id
    if (p.active) found = true
  }
  if (found) persist(list)
}

/** 把档案名称映射到 Firmware 枚举（未知名称回退 Grbl） */
function toFirmware(name: string): Firmware {
  const all = Object.values(Firmware) as string[]
  return (all.includes(name) ? name : Firmware.Grbl) as Firmware
}

/**
 * 用档案写入/同步 AppSettings，使现有转换流程直接生效。
 * 写入的键：'Firmware Type'、'Max Power'、'Min Power'、'Mark Speed'、
 * 'Travel X'、'Travel Y'、'Last Baud'、'Jog Speed'（借用空移速度）。
 */
export function applyProfileToSettings(p: DeviceProfile): void {
  AppSettings.set('Firmware Type', toFirmware(p.firmware))
  AppSettings.set('Max Power', p.maxPower)
  AppSettings.set('Min Power', p.minPower)
  AppSettings.set('Mark Speed', p.markSpeed)
  AppSettings.set('Travel X', p.travelX)
  AppSettings.set('Travel Y', p.travelY)
  AppSettings.set('Last Baud', p.baud)
  // 空移速度没有独立设置键，借用点动速度承载（两者都是非雕刻运动速度）
  AppSettings.set('Jog Speed', p.travelSpeed)
}

/** 读取当前有效行程：显式传入优先，否则回退 AppSettings，再回退默认 300×200 */
function resolveTravel(opts?: { travelX?: number; travelY?: number }): { x: number; y: number } {
  const tx = opts?.travelX ?? AppSettings.get<number>('Travel X', DEFAULTS.travelX)
  const ty = opts?.travelY ?? AppSettings.get<number>('Travel Y', DEFAULTS.travelY)
  return {
    x: Math.max(MIN_SIZE_MM, Number.isFinite(tx) ? tx : DEFAULTS.travelX),
    y: Math.max(MIN_SIZE_MM, Number.isFinite(ty) ? ty : DEFAULTS.travelY)
  }
}

/**
 * 尺寸自适应：把期望尺寸 (mm) 限制/缩放到行程内。
 * mode:
 *  - 'Fit'：等比缩放，保证两个方向都放进行程内（以最紧的一边为准），保持宽高比
 *  - 'Clamp'：仅把各轴分别限制到行程上限，不保持宽高比
 *  - 'None'：不处理
 * 返回值：调整后的尺寸、是否被调整过，以及用于提示的中文说明。
 */
export function fitSizeToTravel(
  widthMm: number,
  heightMm: number,
  opts?: { travelX?: number; travelY?: number; mode?: 'Fit' | 'Clamp' | 'None' }
): { widthMm: number; heightMm: number; adjusted: boolean; message?: string } {
  const mode = opts?.mode ?? 'Fit'
  const rawW = Number.isFinite(widthMm) ? widthMm : MIN_SIZE_MM
  const rawH = Number.isFinite(heightMm) ? heightMm : MIN_SIZE_MM
  const w = Math.max(MIN_SIZE_MM, rawW)
  const h = Math.max(MIN_SIZE_MM, rawH)

  const travel = resolveTravel(opts)

  if (mode === 'None') {
    return { widthMm: round3(w), heightMm: round3(h), adjusted: false }
  }

  if (mode === 'Clamp') {
    const cw = Math.min(w, travel.x)
    const ch = Math.min(h, travel.y)
    const adjusted = round3(cw) !== round3(w) || round3(ch) !== round3(h)
    return {
      widthMm: round3(cw),
      heightMm: round3(ch),
      adjusted,
      message: adjusted
        ? `尺寸超出 ${travel.x}×${travel.y}mm 行程，已分别限制为 X${round3(cw)}×Y${round3(ch)}mm（未保持比例）`
        : undefined
    }
  }

  // Fit：以最紧的一边计算缩放比，只缩小不放大
  const scale = Math.min(1, travel.x / w, travel.y / h)
  const fw = Math.max(MIN_SIZE_MM, w * scale)
  const fh = Math.max(MIN_SIZE_MM, h * scale)
  const adjusted = scale < 1
  return {
    widthMm: round3(fw),
    heightMm: round3(fh),
    adjusted,
    message: adjusted
      ? `尺寸超出 ${travel.x}×${travel.y}mm 行程，已等比缩放至 X${round3(fw)}×Y${round3(fh)}mm`
      : undefined
  }
}

/** 从一行 G 代码中提取所有"字母+数值"地址字（容忍大小写、无空格、小数） */
function extractWords(line: string): string[] {
  // 去掉分号注释与圆括号注释，避免把注释里的数字当作坐标
  const noSemicolon = line.split(';')[0]
  const noParen = noSemicolon.replace(/\([^)]*\)/g, ' ')
  return noParen.toUpperCase().match(/[A-Z][-+]?(?:\d+\.?\d*|\.\d+)/g) ?? []
}

/**
 * 校验一段 G 代码是否超出行程（解析 X/Y 最大最小值）。
 *
 * 解析策略：只统计 G0/G1/G2/G3（可能写作 G00..G03）运动行；忽略无 X/Y 的行；
 * 容忍大小写、缺失空格、可选小数。
 *
 * 关于负坐标：部分机器（如原点在右上的机型）使用负的机床坐标，因此
 * "是否超出行程(ok)" 只依据非负的工作坐标来判定——即忽略那些明显是
 * 机床坐标的负值；而返回的 minX/minY/maxX/maxY 仍是包含负值在内的原始边界，
 * 以便调用方了解真实取值范围。
 */
export function checkGcodeWithinTravel(
  lines: string[],
  opts?: { travelX?: number; travelY?: number }
): { ok: boolean; maxX: number; maxY: number; minX: number; minY: number; message?: string } {
  const travel = resolveTravel(opts)

  let minX = Number.POSITIVE_INFINITY
  let maxX = Number.NEGATIVE_INFINITY
  let minY = Number.POSITIVE_INFINITY
  let maxY = Number.NEGATIVE_INFINITY
  // 仅用于 ok 判定：非负（工作坐标）点集
  let workMaxX = Number.NEGATIVE_INFINITY
  let workMaxY = Number.NEGATIVE_INFINITY
  let parsed = false
  let hasX = false
  let hasY = false

  for (const line of lines) {
    if (!line) continue
    const words = extractWords(line)
    if (words.length === 0) continue
    // 是否运动行（G0/G00/G1/G01/G2/G02/G3/G03）
    const isMove = words.some((w) => /^G0{0,2}[0-3]$/.test(w))
    if (!isMove) continue

    const mx = words.find((w) => w[0] === 'X')
    const my = words.find((w) => w[0] === 'Y')
    const x = mx ? Number.parseFloat(mx.slice(1)) : undefined
    const y = my ? Number.parseFloat(my.slice(1)) : undefined
    if (x === undefined && y === undefined) continue
    parsed = true

    if (x !== undefined && Number.isFinite(x)) {
      hasX = true
      minX = Math.min(minX, x)
      maxX = Math.max(maxX, x)
      if (x >= 0) workMaxX = Math.max(workMaxX, x)
    }
    if (y !== undefined && Number.isFinite(y)) {
      hasY = true
      minY = Math.min(minY, y)
      maxY = Math.max(maxY, y)
      if (y >= 0) workMaxY = Math.max(workMaxY, y)
    }
  }

  if (!parsed) {
    return { ok: true, maxX: 0, maxY: 0, minX: 0, minY: 0 }
  }

  const diameterX = workMaxX === Number.NEGATIVE_INFINITY ? 0 : workMaxX
  const diameterY = workMaxY === Number.NEGATIVE_INFINITY ? 0 : workMaxY
  const EPS = 0.001
  const ok = diameterX <= travel.x + EPS && diameterY <= travel.y + EPS

  return {
    ok,
    maxX: hasX ? round3(maxX) : 0,
    maxY: hasY ? round3(maxY) : 0,
    minX: hasX ? round3(minX) : 0,
    minY: hasY ? round3(minY) : 0,
    message: ok
      ? undefined
      : `G 代码超出 ${travel.x}×${travel.y}mm 行程：X 最大 ${round3(diameterX)}mm、Y 最大 ${round3(diameterY)}mm`
  }
}