/**
 * 前端全局状态：把 GRBL 核心（GrblCore）的事件流封装为 Vue 响应式状态，
 * 并提供界面调用的动作方法。
 */
import { reactive } from 'vue'
import { grbl } from '../core/grbl/GrblCore'
import { Firmware, JogDirection, MacStatus, StreamingMode, ThreadingMode } from '../core/grbl/types'
import { GrblCommand, MessageType } from '../core/grbl/GrblCommand'
import { parseGcode, type GcodeFileData } from '../core/gcode/GrblFile'
import { AppSettings } from '../core/grbl/GrblConfig'
import type { UsbDeviceInfo } from '../core/serial/types'

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
  devices: [] as UsbDeviceInfo[],
  scanning: false,
  baud: AppSettings.get<number>('Last Baud', 115200),
  jog: { step: grbl.jogStep, speed: grbl.jogSpeed },
  log: [] as LogItem[],
  file: null as GcodeFileData | null,
  configRev: 0,
  settings: AppSettings.all() as AnyRecord,
  theme: AppSettings.theme as 'dark' | 'light'
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

export async function connect(deviceId: number) {
  if (state.connected || state.connecting) return
  AppSettings.set('Last Baud', state.baud)
  AppSettings.set('Last Port', String(deviceId))
  state.connecting = true
  pushLog(`正在连接设备 #${deviceId} @ ${state.baud}…`, MessageType.Command)
  try {
    await grbl.open(deviceId, state.baud)
  } catch (e) {
    state.connecting = false
    pushLog(`连接失败：${String(e)}`, MessageType.Warning)
  }
}

export async function disconnect() {
  await grbl.close(true)
  state.connected = false
  state.connecting = false
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