/**
 * 前端全局状态：把 GRBL 核心（GrblCore）的事件流封装为 Vue 响应式状态，
 * 并提供界面调用的动作方法。
 */
import { reactive } from 'vue'
import { grbl } from '../core/grbl/GrblCore'
import { openBluetoothSettings as openBluetoothSettingsNative } from '../core/serial/SerialTransport'
import { Firmware, JogDirection, MacStatus, StreamingMode, ThreadingMode } from '../core/grbl/types'
import { GrblCommand, MessageType } from '../core/grbl/GrblCommand'
import { parseGcode, type GcodeFileData } from '../core/gcode/GrblFile'
import { AppSettings } from '../core/grbl/GrblConfig'
import {
  applyProfileToSettings,
  findProfileByDevice,
  isDeviceKnown
} from '../core/grbl/DeviceProfile'
import type { SerialDeviceInfo, TransportKind } from '../core/serial/types'

export interface LogItem {
  id: number
  text: string
  kind: MessageType
  time: number
}

/** 中文状态名 */
export const STATUS_LABELS: Record<string, string> = {
  [MacStatus.Disconnected]: '未连接',
  [MacStatus.Connecting]: '连接中',
  [MacStatus.Idle]: '空闲',
  [MacStatus.Run]: '运行中',
  [MacStatus.Hold]: '已暂停',
  [MacStatus.Door]: '门打开',
  [MacStatus.Home]: '回零中',
  [MacStatus.Alarm]: '报警',
  [MacStatus.Check]: '校验模式',
  [MacStatus.Jog]: '点动中',
  [MacStatus.Queue]: '排队中',
  [MacStatus.Cooling]: '冷却中',
  [MacStatus.AutoHold]: '自动暂停',
  [MacStatus.Tool]: '换刀中'
}

/** 固件类型中文名 */
export const FIRMWARE_LABELS: Record<string, string> = {
  Grbl: 'GRBL',
  Smoothie: 'Smoothieware',
  Marlin: 'Marlin',
  Vigo: 'Vigo'
}

type AnyRecord = Record<string, unknown>

export const state = reactive({
  connected: false,
  connecting: false,
  status: MacStatus.Disconnected as MacStatus,
  version: '',
  firmware: grbl.firmware as string,
  pos: { x: 0, y: 0, z: 0 },
  wpos: { x: 0, y: 0, z: 0 },
  wco: { x: 0, y: 0, z: 0 },
  feed: 0,
  spindle: 0,
  overrides: { feed: 100, rapids: 100, power: 100 },
  targetOverrides: grbl.targetOverrides,
  progress: { sent: 0, total: 0, executed: 0 },
  running: false,
  issue: grbl.lastIssue as number,
  devices: [] as SerialDeviceInfo[],
  bluetoothDevices: [] as SerialDeviceInfo[],
  scanning: false,
  /** 当前连接方式（未连接为 usb） */
  deviceKind: 'usb' as TransportKind,
  /** 当前连接的 USB 设备编号（未连接为 -1） */
  deviceId: -1,
  baud: AppSettings.get<number>('Last Baud', 115200),
  jog: { step: grbl.jogStep, speed: grbl.jogSpeed },
  log: [] as LogItem[],
  file: null as GcodeFileData | null,
  configRev: 0,
  settings: AppSettings.all() as AnyRecord,
  theme: AppSettings.theme as 'dark' | 'light',
  /** 首次连接的新设备尚未完成参数设置 */
  needsSetup: false,
  /** 触发初始化向导的设备编号 */
  setupDeviceId: -1
})

let logSeq = 0

export function pushLog(text: string, kind: MessageType = MessageType.Others) {
  state.log.push({ id: ++logSeq, text, kind, time: Date.now() })
  if (state.log.length > 600) state.log.splice(0, state.log.length - 600)
}

function syncPosition() {
  const p = grbl.position
  state.pos = { x: p.X, y: p.Y, z: p.Z }
  const w = grbl.workPosition
  state.wpos = { x: w.X, y: w.Y, z: w.Z }
  const o = grbl.wco
  state.wco = { x: o.X, y: o.Y, z: o.Z }
}

function syncOverrides() {
  state.overrides = { ...grbl.overrides }
  state.targetOverrides = { ...grbl.targetOverrides }
}

// ---- 绑定核心事件 ----
grbl.on('status', (s) => {
  state.status = s
  state.connected = grbl.isConnected
  state.connecting = s === MacStatus.Connecting
})

grbl.on('connected', () => {
  state.connected = true
  state.connecting = false
  state.version = grbl.version ? grbl.version.toString() : ''
  state.firmware = grbl.firmware
  pushLog(`已连接（${state.version || '未知版本'}）`, MessageType.Startup)
  state.configRev++
})

grbl.on('disconnected', () => {
  state.connected = false
  state.connecting = false
  state.running = false
  syncOverrides()
})

grbl.on('position', () => syncPosition())
grbl.on('fss', (v) => {
  state.feed = v.f
  state.spindle = v.s
})
grbl.on('override', () => syncOverrides())

grbl.on('progress', (p) => {
  state.progress = { ...p }
  state.running = true
})

grbl.on('programEnd', () => {
  state.running = false
  pushLog('任务执行完成', MessageType.Feedback)
})

grbl.on('message', (m) => {
  pushLog(m.message, m.type)
  if (m.type === MessageType.Config) state.configRev++
})

grbl.on('issue', (i) => {
  state.issue = i
})

grbl.on('connectTimeout', () => {
  state.connecting = false
  pushLog('连接超时，请检查设备与波特率', MessageType.Warning)
})

// ---- 动作 ----

export async function refreshDevices() {
  state.scanning = true
  try {
    state.devices = await grbl.listDevices()
  } catch (e) {
    pushLog(`枚举设备失败：${String(e)}`, MessageType.Warning)
    state.devices = []
  } finally {
    state.scanning = false
  }
}

/** 刷新蓝牙（已配对）设备列表 */
export async function refreshBluetoothDevices() {
  state.scanning = true
  try {
    state.bluetoothDevices = await grbl.listBluetoothDevices()
  } catch (e) {
    pushLog(`枚举蓝牙设备失败：${String(e)}`, MessageType.Warning)
    state.bluetoothDevices = []
  } finally {
    state.scanning = false
  }
}

export async function connect(device: SerialDeviceInfo) {
  if (state.connected || state.connecting) return
  AppSettings.set('Last Baud', state.baud)
  const label = device.product || device.name || device.address || `设备 #${device.deviceId}`
  state.connecting = true
  pushLog(`正在连接 ${label} @ ${state.baud}…`, MessageType.Command)
  try {
    await grbl.open(device, state.baud)
    state.deviceKind = device.kind
    state.deviceId = device.deviceId ?? -1
    // 只有 USB 设备有稳定的数字编号，可绑定参数档案
    if (device.deviceId !== undefined) {
      AppSettings.set('Last Port', String(device.deviceId))
      // 已绑定档案的设备：自动套用其行程 / 功率等参数
      const bound = findProfileByDevice(device.deviceId)
      if (bound) {
        applyProfileToSettings(bound)
        state.settings = AppSettings.all()
        pushLog(`已套用设备参数「${bound.name}」`, MessageType.Feedback)
      }
      // 首次连接的新设备：提示进入初始化向导设置参数
      if (!isDeviceKnown(device.deviceId)) {
        state.setupDeviceId = device.deviceId
        state.needsSetup = true
        pushLog(`首次连接设备 #${device.deviceId}，请先设置行程 / 功率等参数`, MessageType.Feedback)
      }
    }
  } catch (e) {
    state.connecting = false
    pushLog(`连接失败：${String(e)}`, MessageType.Warning)
  }
}

/** 打开系统蓝牙设置，便于配对雕刻机蓝牙模块 */
export async function openBluetoothSettings() {
  try {
    await openBluetoothSettingsNative()
  } catch (e) {
    pushLog(`打开蓝牙设置失败：${String(e)}`, MessageType.Warning)
  }
}

/** 手动打开设备初始化向导（用于设置页入口） */
export function openSetupWizard(deviceId = -1) {
  state.setupDeviceId = deviceId
  state.needsSetup = true
}

/** 刷新设置快照（外部直接改写 AppSettings 后调用） */
export function reloadSettings() {
  state.settings = AppSettings.all()
  state.firmware = AppSettings.get<string>('Firmware Type', 'Grbl')
  grbl.firmware = AppSettings.get<Firmware>('Firmware Type', Firmware.Grbl)
}

/** 完成初始化向导：清除待设置标记，并刷新设置快照使参数立即生效 */
export function clearNeedsSetup() {
  state.needsSetup = false
  reloadSettings()
}

/**
 * 从已连接的设备读取行程与功率上下限（GRBL 的 $130/$131/$30/$31）。
 * 未连接或未读到时对应字段为 undefined，供初始化向导预填使用。
 */
export function readMachineLimits(): {
  travelX?: number
  travelY?: number
  maxPower?: number
  minPower?: number
} {
  const read = (id: number): number | undefined => (grbl.config.has(id) ? grbl.config.get(id, 0) : undefined)
  return {
    travelX: read(130),
    travelY: read(131),
    maxPower: read(30),
    minPower: read(31)
  }
}

export async function disconnect() {
  await grbl.close(true)
  state.connected = false
  state.connecting = false
  state.deviceId = -1
  pushLog('已断开连接', MessageType.Command)
}

/** 发送一条原始命令（立即进入队列） */
export function sendCommand(line: string) {
  if (!state.connected) {
    pushLog('未连接设备，无法发送', MessageType.Warning)
    return
  }
  grbl.enqueueRaw(line, true)
  pushLog(`> ${line}`, MessageType.Command)
}

/** 实时单字节命令 */
export function softReset() {
  grbl.softReset()
  state.running = false
  pushLog('软复位', MessageType.Command)
}
export function feedHold() {
  grbl.feedHold()
}
export function cycleStart() {
  grbl.cycleStart()
}
export function homing() {
  grbl.homing()
  pushLog('回原点 $H', MessageType.Command)
}
export function unlock() {
  grbl.unlock()
  pushLog('解锁 $X', MessageType.Command)
}
export function setNewZero() {
  grbl.setNewZero()
  pushLog('设置当前点为工作零点 G92', MessageType.Command)
}
export function resetWCO() {
  grbl.resetWCO()
  pushLog('清除坐标系偏移 G92.1', MessageType.Command)
}

export function jog(dir: JogDirection) {
  grbl.jog(dir)
}

/** 更新点动步长/速度并持久化 */
export function setJogParams(step?: number, speed?: number) {
  if (step !== undefined && step > 0) {
    grbl.jogStep = step
    AppSettings.set('Jog Step', step)
  }
  if (speed !== undefined && speed > 0) {
    grbl.jogSpeed = speed
    AppSettings.set('Jog Speed', speed)
  }
  state.jog = { step: grbl.jogStep, speed: grbl.jogSpeed }
}

export function moveTo(x: number, y: number) {
  grbl.moveTo(x, y)
}

/**
 * 测试激光：以恒定功率（M3）短暂出光后自动关闭，用于对焦 / 功率检查。
 * 说明：部分固件在激光模式下仅运动时出光，若无效请配合运动控制使用。
 */
export function laserTest(power: number, durationMs: number): boolean {
  if (!state.connected) {
    pushLog('未连接设备，无法测试激光', MessageType.Warning)
    return false
  }
  if (state.running) {
    pushLog('任务运行中，禁止测试激光', MessageType.Warning)
    return false
  }
  const s = Math.max(0, Math.round(power))
  const ms = Math.min(10000, Math.max(50, Math.round(durationMs)))
  grbl.enqueueRaw(`M3 S${s}`, true)
  pushLog(`> 测试激光 M3 S${s}（${ms}ms）`, MessageType.Command)
  window.setTimeout(() => {
    grbl.enqueueRaw(AppSettings.get<string>('Laser Off Command', 'M5'), true)
    pushLog('测试激光结束', MessageType.Feedback)
  }, ms)
  return true
}

export function setTargetOverride(kind: 'feed' | 'rapids' | 'power', value: number) {
  if (kind === 'feed') grbl.setFeedOverride(value)
  else if (kind === 'rapids') grbl.setRapidOverride(value)
  else grbl.setPowerOverride(value)
  syncOverrides()
}

export function setStreamingMode(mode: StreamingMode) {
  grbl.setStreamingMode(mode)
}

/** 载入 G 代码文本 */
export function loadGcodeText(name: string, text: string) {
  state.file = parseGcode(name, text)
  pushLog(`已载入 ${name}：${state.file.stats.totalLines} 行`, MessageType.Feedback)
  return state.file
}

/** 载入转换生成的 G 代码行 */
export function loadGcodeLines(name: string, lines: string[]) {
  return loadGcodeText(name, lines.join('\n'))
}

export function clearFile() {
  state.file = null
}

/** 开始执行当前文件 */
export function runFile(resetBuffer = true) {
  if (!state.file) {
    pushLog('请先载入或生成 G 代码', MessageType.Warning)
    return false
  }
  if (!state.connected) {
    pushLog('未连接设备，无法开始雕刻', MessageType.Warning)
    return false
  }
  grbl.runProgram(state.file.commands as GrblCommand[], resetBuffer)
  state.running = true
  pushLog(`开始执行：${state.file.name}`, MessageType.Command)
  return true
}

/** 从指定行开始执行 */
export function runFileFromLine(index: number) {
  if (!state.file || !state.connected) return false
  const cmds = state.file.commands.slice(Math.max(0, index)) as GrblCommand[]
  grbl.runProgram(cmds, true)
  state.running = true
  pushLog(`从第 ${index + 1} 行开始执行`, MessageType.Command)
  return true
}

export function abortFile() {
  grbl.abortProgram()
  grbl.softReset()
  state.running = false
  pushLog('已中止任务', MessageType.Warning)
}

/** 读取机器设置（$$） */
export function readMachineConfig() {
  grbl.enqueueRaw('$$')
  state.configRev++
}
export function writeMachineSetting(id: number, value: number) {
  grbl.writeSetting(id, value)
}
export function refreshConfigEntries(): [number, number][] {
  return grbl.config.entries()
}

/** 应用级设置读写 */
export function updateSetting(key: string, value: unknown) {
  AppSettings.set(key, value)
  state.settings = AppSettings.all()
  state.firmware = AppSettings.get<string>('Firmware Type', 'Grbl')
  grbl.firmware = AppSettings.get<Firmware>('Firmware Type', Firmware.Grbl)
}

/** 设置通讯模式（状态查询频率/时序） */
export function setThreadingMode(name: string) {
  const m = ThreadingMode.all().find((x) => x.name === name)
  if (m) grbl.threadingMode = m
  AppSettings.set('Threading Mode', name)
  state.settings = AppSettings.all()
}

/** 机器设置项说明：[名称, 单位, 说明] */
export function settingInfo(id: number): string[] {
  return grbl.settingInfo(id)
}

/** 当前连接机器的设置项列表（$ 参数） */
export function configEntries(): [number, number][] {
  return grbl.config.entries()
}

export function setTheme(theme: 'dark' | 'light') {
  AppSettings.theme = theme
  state.theme = theme
}

export function clearLog() {
  state.log.splice(0, state.log.length)
}

/** 供预览使用：让命令列表可复用 */
export function commandsOf(lines: string[]): GrblCommand[] {
  return lines.map((l) => new GrblCommand(l))
}

export { grbl, MacStatus, JogDirection, StreamingMode, MessageType }