/**
 * GRBL 核心通讯与流式发送引擎
 * 移植自 LaserGRBL Core/GrblCore.cs（仅保留激光雕刻相关功能）
 *
 * 负责：串口连接握手、命令队列、缓冲流式发送、实时状态解析、
 * 覆盖倍率、点动、配置读写、报警/错误处理。
 */
import { Emitter } from '../Emitter'
import type { SerialDeviceInfo, SerialTransport } from '../serial/types'
import { createTransport } from '../serial/SerialTransport'
import { GrblCommand, GrblMessage, MessageType, CommandStatus, type Element } from './GrblCommand'
import { AppSettings, usePWM } from './GrblConfig'
import {
  Firmware,
  GPoint,
  GrblVersionInfo,
  MacStatus,
  StreamingMode,
  ThreadingMode,
  JogDirection,
  DetectedIssue
} from './types'
import { installDecoders } from './GrblCommand'
import { SETTING_CODES, ALARM_CODES, ERROR_CODES } from './csvData'

/** GRBL 串口接收缓冲默认大小（v0.9/v1.1 为 127 可用字节） */
const DEFAULT_BUFFER_SIZE = 127

export type CoreEvents = {
  connected: void
  disconnected: void
  /** 机器状态变化 */
  status: MacStatus
  /** 位置变化 */
  position: GPoint
  /** 原始日志行 */
  message: GrblMessage
  /** 命令状态变化（流式发送用） */
  commandStatus: GrblCommand
  /** 任务进度 0..1 */
  progress: { sent: number; total: number; executed: number }
  /** 任务结束 */
  programEnd: void
  /** 覆盖倍率变化 */
  override: { feed: number; rapids: number; power: number }
  /** 进给/功率实时值 */
  fss: { f: number; s: number }
  /** 检测到的问题 */
  issue: DetectedIssue
  /** 连接超时 */
  connectTimeout: void
}

/** GRBL 设置项集合（$ 参数） */
export class GrblConfiguration {
  private map = new Map<number, number>()
  private version: GrblVersionInfo | null = null

  reset(version: GrblVersionInfo | null) {
    this.map.clear()
    this.version = version
  }

  addOrUpdate(line: string) {
    const m = /^\$(\d+)\s*=\s*(-?[\d.]+)/.exec(line.trim())
    if (!m) return
    this.map.set(parseInt(m[1], 10), parseFloat(m[2]))
  }

  get(id: number, def = 0): number {
    return this.map.has(id) ? (this.map.get(id) as number) : def
  }

  has(id: number) {
    return this.map.has(id)
  }

  entries(): [number, number][] {
    return [...this.map.entries()].sort((a, b) => a[0] - b[0])
  }

  /** 生成写回命令 */
  toCommands(): string[] {
    return this.entries().map(([k, v]) => `$${k}=${v}`)
  }

  getVersion() {
    return this.version
  }
}

export class GrblCore extends Emitter<CoreEvents> {
  // ---- 连接 ----
  private usbTransport: SerialTransport = createTransport('usb')
  private bluetoothTransport: SerialTransport = createTransport('bluetooth')
  private transport: SerialTransport = this.usbTransport
  machineStatus: MacStatus = MacStatus.Disconnected
  version: GrblVersionInfo | null = null
  readonly config = new GrblConfiguration()
  firmware: Firmware = Firmware.Grbl

  // ---- 位置 ----
  private mMPos = new GPoint(0, 0, 0)
  private mWCO = new GPoint(0, 0, 0)
  private curF = 0
  private curS = 0

  // ---- 覆盖倍率 ----
  private curOvFeed = 100
  private curOvRapids = 100
  private curOvPower = 100
  private tarOvFeed = 100
  private tarOvRapids = 100
  private tarOvPower = 100

  // ---- 队列/流式 ----
  private queue: GrblCommand[] = []
  private pending: GrblCommand[] = []
  private retryQueue: GrblCommand | null = null
  private usedBuffer = 0
  private autoBufferSize = DEFAULT_BUFFER_SIZE
  private streamingMode: StreamingMode = StreamingMode.Buffered
  inProgram = false

  // ---- 任务统计 ----
  private jobTotal = 0
  private jobSent = 0
  private jobExecuted = 0
  private jobStartTime = 0

  // ---- 运行控制 ----
  private txTimer: number | null = null
  private rxBuffer = ''
  private statusQueryTimer = 0
  private lastStatusAt = 0
  private connectStart = 0

  threadingMode: ThreadingMode = ThreadingMode.Fast
  lastIssue: DetectedIssue = DetectedIssue.Unknown

  // ---- 点动 ----
  jogSpeed = 1000
  jogStep = 1

  constructor() {
    super()
    const modeName = AppSettings.get<string>('Threading Mode', 'Fast')
    this.threadingMode = ThreadingMode.all().find((m) => m.name === modeName) ?? ThreadingMode.Fast
    this.firmware = AppSettings.get<Firmware>('Firmware Type', Firmware.Grbl)
    this.jogSpeed = AppSettings.get<number>('Jog Speed', 1000)
    this.jogStep = AppSettings.get<number>('Jog Step', 1)
    installDecoders(
      (key, idx) => this.lookupCode(SETTING_CODES, key, idx),
      (key, idx) => this.lookupCode(ALARM_CODES, key, idx)
    )
  }

  // ================= 连接管理 =================

  get isConnected() {
    return this.machineStatus !== MacStatus.Disconnected && this.machineStatus !== MacStatus.Connecting
  }

  get isConnecting() {
    return this.machineStatus === MacStatus.Connecting
  }

  get isOpen() {
    return this.transport.isOpen()
  }

  get position() {
    return this.mMPos
  }
  get workPosition() {
    return this.mMPos.sub(this.mWCO)
  }
  get wco() {
    return this.mWCO
  }
  get feed() {
    return this.curF
  }
  get spindle() {
    return this.curS
  }
  get overrides() {
    return { feed: this.curOvFeed, rapids: this.curOvRapids, power: this.curOvPower }
  }

  async listDevices(): Promise<SerialDeviceInfo[]> {
    return this.usbTransport.list()
  }

  async listBluetoothDevices(): Promise<SerialDeviceInfo[]> {
    return this.bluetoothTransport.list()
  }

  /** 打开串口并开始握手 */
  async open(device: SerialDeviceInfo, baudRate: number) {
    if (this.isConnected) return
    this.transport = device.kind === 'bluetooth' ? this.bluetoothTransport : this.usbTransport
    this.setStatus(MacStatus.Connecting)
    this.connectStart = Date.now()
    this.version = null
    this.config.reset(null)
    this.queue = []
    this.pending = []
    this.usedBuffer = 0
    this.autoBufferSize = DEFAULT_BUFFER_SIZE
    this.rxBuffer = ''
    this.lastIssue = DetectedIssue.Unknown

    this.transport.onData((chunk) => this.onData(chunk))
    this.transport.onClose(() => this.onTransportClosed())

    await this.transport.open(device, baudRate)

    this.statusQueryTimer = 0
    this.lastStatusAt = Date.now()
    this.startTxLoop()

    // 复位 + 握手（对应 LaserGRBL StartTX）
    if (this.firmware !== Firmware.Smoothie && AppSettings.get<boolean>('Reset Grbl On Connect', true)) {
      await this.transport.writeBytes([0x18]) // 软复位
      await new Promise((r) => setTimeout(r, 400))
    }
    await this.sendHandshake()
  }

  private async sendHandshake() {
    await this.transport.write('\r\n')
    // 请求 build info，用于确定缓冲区与版本
    this.enqueueRaw('$I', true)
    await new Promise((r) => setTimeout(r, 350))
    this.enqueueRaw('$$') // 读取设置
    await new Promise((r) => setTimeout(r, 250))
    this.enqueueRaw('$#') // 读取坐标系参数
    await new Promise((r) => setTimeout(r, 150))
    this.queryPosition()
  }

  async close(manual = true) {
    this.stopTxLoop()
    try {
      await this.transport.close()
    } catch {
      /* ignore */
    }
    this.queue = []
    this.pending = []
    this.usedBuffer = 0
    this.inProgram = false
    if (manual) this.setIssue(DetectedIssue.ManualDisconnect)
    this.setStatus(MacStatus.Disconnected)
    this.emit('disconnected', undefined)
  }

  private onTransportClosed() {
    if (this.machineStatus !== MacStatus.Disconnected) {
      this.stopTxLoop()
      if (this.lastIssue === DetectedIssue.Unknown) this.setIssue(DetectedIssue.UnexpectedDisconnect)
      this.setStatus(MacStatus.Disconnected)
      this.emit('disconnected', undefined)
    }
  }

  private startTxLoop() {
    if (this.txTimer !== null) return
    this.txTimer = window.setInterval(() => this.txTick(), 4)
  }

  private stopTxLoop() {
    if (this.txTimer !== null) {
      clearInterval(this.txTimer)
      this.txTimer = null
    }
  }

  private txTick() {
    if (!this.transport.isOpen()) return
    try {
      // 连接超时检测
      if (this.machineStatus === MacStatus.Connecting && Date.now() - this.connectStart > 10000) {
        this.emit('connectTimeout', undefined)
        this.close(false)
        return
      }

      // 状态查询
      if (Date.now() - this.lastStatusAt >= this.threadingMode.statusQuery) {
        this.queryPosition()
      }

      // 发送队列
      if (this.canSend()) this.sendLine()

      // 覆盖倍率同步
      this.manageOverrides()

      // 运行中卡死检测
      this.detectHang()
    } catch (e) {
      console.error('[GrblCore.tx]', e)
    }
  }

  private detectHang() {
    if (this.lastIssue === DetectedIssue.Unknown && this.machineStatus === MacStatus.Run && this.inProgram) {
      const since = Date.now() - this.lastStatusAt
      if (since > Math.max(this.threadingMode.statusQuery * 10, 5000)) {
        this.setIssue(DetectedIssue.StopResponding)
      }
    }
  }

  // ================= 流式发送 =================

  setStreamingMode(mode: StreamingMode) {
    if (this.streamingMode === mode) return
    this.streamingMode = mode
  }

  private peekNext(): GrblCommand | null {
    if (this.pending.length > 0 && this.pending[0].isWriteEEPROM) return null
    if (this.streamingMode === StreamingMode.Buffered && this.queue.length > 0) return this.queue[0]
    if (this.streamingMode !== StreamingMode.Buffered && this.pending.length === 0) {
      if (this.retryQueue) return this.retryQueue
      return this.queue.length > 0 ? this.queue[0] : null
    }
    return null
  }

  private hasSpaceInBuffer(cmd: GrblCommand) {
    return this.usedBuffer + cmd.serialData.length <= this.autoBufferSize
  }

  private canSend() {
    const next = this.peekNext()
    return next !== null && this.hasSpaceInBuffer(next)
  }

  private removeManagedCommand() {
    if (this.retryQueue) this.retryQueue = null
    else this.queue.shift()
  }

  private sendLine() {
    const tosend = this.peekNext()
    if (!tosend) return
    try {
      tosend.buildHelper()
      tosend.setSending()
      this.pending.push(tosend)
      this.removeManagedCommand()
      this.usedBuffer += tosend.serialData.length
      void this.transport.write(tosend.serialData)
      this.jobSent++
      if (this.inProgram) {
        this.emit('progress', { sent: this.jobSent, total: this.jobTotal, executed: this.jobExecuted })
      }
    } catch (e) {
      console.error('[sendLine]', e)
    } finally {
      tosend.deleteHelper()
    }
  }

  /** 处理命令响应（ok / error / ALARM 等） */
  private manageCommandResponse(rline: string) {
    this.lastStatusAt = Date.now()
    if (this.pending.length > 0) {
      const p = this.pending.shift() as GrblCommand
      p.setResult(rline)
      this.usedBuffer = Math.max(0, this.usedBuffer - p.serialData.length)
      this.emit('commandStatus', p)

      if (this.inProgram) {
        if (p.repeatCount === 0) {
          this.jobExecuted++
          this.emit('progress', { sent: this.jobSent, total: this.jobTotal, executed: this.jobExecuted })
        }
        if (p.status === CommandStatus.ResponseBad) this.setIssue(DetectedIssue.MachineAlarm)
      }

      if (p.isWriteEEPROM && p.status === CommandStatus.ResponseGood) {
        this.config.addOrUpdate(p.getDecodedMessage())
      }

      if (
        this.inProgram &&
        this.streamingMode === StreamingMode.RepeatOnError &&
        this.pending.length === 0 &&
        p.status === CommandStatus.ResponseBad &&
        p.repeatCount < 3
      ) {
        const retry = new GrblCommand(p.command, p.repeatCount + 1)
        retry.buildHelper()
        this.retryQueue = retry
      }
    }

    if (this.inProgram && this.queue.length === 0 && this.pending.length === 0) {
      this.onProgramEnd()
    }
  }

  private onProgramEnd() {
    this.inProgram = false
    this.emit('programEnd', undefined)
  }

  /** 入队一条原始命令 */
  enqueueRaw(line: string, preserveCase = false) {
    if (!line.trim()) return
    const cmd = new GrblCommand(line, 0, preserveCase)
    this.queue.push(cmd)
  }

  enqueue(cmd: GrblCommand) {
    this.queue.push(cmd)
  }

  /** 开始执行一段程序（命令列表） */
  runProgram(commands: GrblCommand[], resetBufferAccounting = true) {
    if (resetBufferAccounting) {
      this.usedBuffer = 0
      this.pending = []
      this.queue = []
    }
    this.queue.push(...commands)
    this.jobTotal = commands.length
    this.jobSent = 0
    this.jobExecuted = 0
    this.jobStartTime = Date.now()
    this.inProgram = true
    this.emit('progress', { sent: 0, total: this.jobTotal, executed: 0 })
  }

  /** 中止当前任务 */
  abortProgram() {
    this.queue = []
    this.retryQueue = null
    this.inProgram = false
    this.setIssue(DetectedIssue.ManualAbort)
  }

  // ================= 接收处理 =================

  private onData(chunk: string) {
    this.rxBuffer += chunk
    let idx: number
    while ((idx = this.rxBuffer.indexOf('\n')) >= 0) {
      let line = this.rxBuffer.slice(0, idx)
      this.rxBuffer = this.rxBuffer.slice(idx + 1)
      line = line.replace(/[\r\n]+$/g, '').trim()
      if (line.length === 0) continue
      this.manageReceivedLine(line)
    }
    // 防止无换行的脏数据无限增长
    if (this.rxBuffer.length > 4096) this.rxBuffer = this.rxBuffer.slice(-1024)
  }

  private manageReceivedLine(line: string) {
    try {
      // 实时状态
      if (line.startsWith('<') && line.endsWith('>')) {
        this.manageRealtimeStatus(line)
        return
      }
      // 命令响应
      const upper = line.toUpperCase()
      if (upper === 'OK' || upper.startsWith('OK')) {
        this.manageCommandResponse('ok')
        return
      }
      if (upper.startsWith('ERROR')) {
        this.manageCommandResponse(line)
        this.pushMessage(line, MessageType.Others)
        return
      }
      if (upper.startsWith('ALARM')) {
        // ALARM 也可能代表状态
        this.manageCommandResponse(line)
        this.pushMessage(line, MessageType.Alarm)
        return
      }
      // 欢迎/版本信息
      if (upper.startsWith('GRBL')) {
        this.parseVersionBanner(line)
        this.pushMessage(line, MessageType.Startup)
        return
      }
      if (upper.startsWith('[VER:')) {
        this.parseVerMessage(line)
        this.pushMessage(line, MessageType.Startup)
        return
      }
      if (upper.startsWith('[OPT:')) {
        this.parseOptMessage(line)
        this.pushMessage(line, MessageType.Feedback)
        return
      }
      if (line.startsWith('$') && line.includes('=')) {
        this.config.addOrUpdate(line)
        this.pushMessage(line, MessageType.Config)
        return
      }
      if (line.startsWith('[') && line.endsWith(']')) {
        this.pushMessage(line, MessageType.Feedback)
        return
      }
      this.pushMessage(line, MessageType.Others)
    } catch (e) {
      console.error('[manageReceivedLine]', line, e)
    }
  }

  private pushMessage(line: string, type: MessageType) {
    const msg = new GrblMessage(line, type)
    if (type === MessageType.Config || type === MessageType.Alarm) {
      // 触发解码
      const decoded = GrblMessage.fromLine(line, true)
      msg.message = decoded.message
      msg.tooltip = decoded.tooltip
    }
    this.emit('message', msg)
  }

  private parseVersionBanner(line: string) {
    // 形如 "Grbl 1.1f ['$' for help]"
    const m = /Grbl\s+(\d+)\.(\d+)([a-zA-Z])?/i.exec(line)
    if (m) {
      const v = new GrblVersionInfo(parseInt(m[1], 10), parseInt(m[2], 10), m[3] ?? '')
      if (!this.version || this.version.lt(v)) {
        this.version = v
        this.config.reset(v)
      }
    }
  }

  private parseVerMessage(line: string) {
    // [VER:1.1f.20170801:]
    const body = line.slice(1, -1)
    const parts = body.split(':')
    if (parts.length < 2) return
    const ver = parts[1]
    const m = /(\d+)\.(\d+)([a-zA-Z])?/.exec(ver)
    if (!m) return
    const build = ver.substring(m[0].length).replace(/^[.]/, '')
    this.version = new GrblVersionInfo(
      parseInt(m[1], 10),
      parseInt(m[2], 10),
      build || (m[3] ?? ''),
      parts[2] || null,
      parts[3] || null
    )
    this.config.reset(this.version)
  }

  private parseOptMessage(line: string) {
    // [OPT:VZ,15,128]
    const body = line.slice(1, -1)
    const parts = body.split(':')
    if (parts.length < 2) return
    const opts = parts[1].split(',')
    if (opts.length >= 3) {
      const buf = parseInt(opts[2], 10)
      if (!Number.isNaN(buf) && buf > 0) this.setAutoBufferSize(buf, true)
    }
  }

  private setAutoBufferSize(v: number, force: boolean) {
    if (force) {
      this.autoBufferSize = v
      return
    }
    if (this.autoBufferSize === DEFAULT_BUFFER_SIZE) {
      if ([128, 255, 256, 10240, 254].includes(v)) this.autoBufferSize = v
    }
  }

  private statusReportVersion(line: string): GrblVersionInfo {
    if (this.version) return this.version
    if (line.includes('|') && !line.includes('Pin:')) return new GrblVersionInfo(1, 1)
    if (line.includes('|') && line.includes('Pin:')) return new GrblVersionInfo(1, 0, 'c')
    return new GrblVersionInfo(0, 9)
  }

  private manageRealtimeStatus(line: string) {
    this.lastStatusAt = Date.now()
    const rline = line.slice(1, -1)
    const rv = this.statusReportVersion(rline)
    if (rv.gte(new GrblVersionInfo(1, 1))) {
      const arr = rline.split('|')
      this.parseMachineStatus(arr[0])
      for (let i = 1; i < arr.length; i++) {
        const a = arr[i]
        if (a.startsWith('Ov:')) this.parseOverrides(a)
        else if (a.startsWith('Bf:')) this.parseBf(a)
        else if (a.startsWith('WPos:')) this.parseWPos(a)
        else if (a.startsWith('MPos:')) this.parseMPos(a)
        else if (a.startsWith('WCO:')) this.parseWCO(a)
        else if (a.startsWith('FS:')) this.parseFS(a)
        else if (a.startsWith('F:')) this.setFS(parseFloat(a.substring(2)), 0)
      }
    } else {
      const arr = rline.split(',')
      if (arr.length > 0) this.parseMachineStatus(arr[0])
      if (arr.length > 3) {
        this.setMPosition(
          new GPoint(parseFloat(arr[1].substring(5)), parseFloat(arr[2]), parseFloat(arr[3]))
        )
      }
      if (arr.length > 6) {
        this.setWCO(this.mMPos.sub(new GPoint(parseFloat(arr[4].substring(5)), parseFloat(arr[5]), parseFloat(arr[6]))))
      }
    }
  }

  private parseMachineStatus(data: string) {
    const name = data.includes(':') ? data.substring(0, data.indexOf(':')) : data
    const found = Object.values(MacStatus).find((s) => s === name)
    if (found) this.setStatus(found)
  }

  private parseOverrides(p: string) {
    const arr = p.substring(3).split(',')
    const feed = parseInt(arr[0], 10)
    const rapids = parseInt(arr[1], 10)
    const power = parseInt(arr[2], 10)
    const changed = feed !== this.curOvFeed || rapids !== this.curOvRapids || power !== this.curOvPower
    this.curOvFeed = feed
    this.curOvRapids = rapids
    this.curOvPower = power
    if (changed) this.emit('override', this.overrides)
    this.manageOverrides()
  }

  private parseBf(p: string) {
    const ab = p.substring(3).split(',')
    const blocks = parseInt(ab[0], 10)
    const buffer = parseInt(ab[1], 10)
    if (!Number.isNaN(buffer)) this.setAutoBufferSize(buffer, false)
    this.grblBlocks = blocks
  }

  private parseWCO(p: string) {
    const xyz = p.substring(4).split(',')
    this.setWCO(new GPoint(parseFloat(xyz[0]), parseFloat(xyz[1]), parseFloat(xyz[2])))
  }

  private parseWPos(p: string) {
    const xyz = p.substring(5).split(',')
    this.setMPosition(this.mWCO.add(new GPoint(parseFloat(xyz[0]), parseFloat(xyz[1]), parseFloat(xyz[2]))))
  }

  private parseMPos(p: string) {
    const xyz = p.substring(5).split(',')
    this.setMPosition(new GPoint(parseFloat(xyz[0]), parseFloat(xyz[1]), parseFloat(xyz[2])))
  }

  private parseFS(p: string) {
    const fs = p.substring(3).split(',')
    this.setFS(parseFloat(fs[0]) || 0, parseFloat(fs[1]) || 0)
  }

  private setFS(f: number, s: number) {
    const changed = f !== this.curF || s !== this.curS
    this.curF = f
    this.curS = s
    if (changed) this.emit('fss', { f, s })
  }

  grblBlocks = 0
  grblBuffer = 0

  private setMPosition(pos: GPoint) {
    this.mMPos = pos
    this.emit('position', pos)
  }

  private setWCO(wco: GPoint) {
    this.mWCO = wco
  }

  private setStatus(s: MacStatus) {
    if (this.machineStatus === s) return
    const wasConnecting = this.machineStatus === MacStatus.Connecting
    this.machineStatus = s
    this.emit('status', s)
    if (wasConnecting && s !== MacStatus.Disconnected && s !== MacStatus.Connecting) {
      this.lastIssue = DetectedIssue.Unknown
      this.emit('connected', undefined)
    }
  }

  private setIssue(issue: DetectedIssue) {
    this.lastIssue = issue
    this.emit('issue', issue)
  }

  // ================= 实时命令 =================

  /** 发送单字节实时命令 */
  sendImmediate(byte: number) {
    if (!this.transport.isOpen()) return
    void this.transport.writeBytes([byte & 0xff])
  }

  /** 查询状态 '?' */
  queryPosition() {
    this.sendImmediate(0x3f)
  }

  /** 软复位 0x18 */
  softReset() {
    if (this.inProgram) this.setIssue(DetectedIssue.ManualReset)
    this.sendImmediate(0x18)
    this.queue = []
    this.pending = []
    this.retryQueue = null
    this.usedBuffer = 0
    this.inProgram = false
  }

  /** 进给保持 '!' */
  feedHold() {
    this.sendImmediate(0x21)
  }

  /** 循环启动（继续）'~' */
  cycleStart() {
    this.sendImmediate(0x7e)
  }

  // ---- 覆盖倍率 ----
  setFeedOverride(target: number) {
    this.tarOvFeed = Math.max(10, Math.min(200, Math.round(target)))
  }
  setRapidOverride(target: number) {
    this.tarOvRapids = target
  }
  setPowerOverride(target: number) {
    this.tarOvPower = Math.max(10, Math.min(200, Math.round(target)))
  }
  get targetOverrides() {
    return { feed: this.tarOvFeed, rapids: this.tarOvRapids, power: this.tarOvPower }
  }

  private manageOverrides() {
    if (!this.transport.isOpen()) return
    if (this.tarOvFeed === 100 && this.curOvFeed !== 100) this.sendImmediate(0x90)
    else if (this.tarOvFeed - this.curOvFeed >= 10) this.sendImmediate(0x91)
    else if (this.curOvFeed - this.tarOvFeed >= 10) this.sendImmediate(0x92)
    else if (this.tarOvFeed - this.curOvFeed >= 1) this.sendImmediate(0x93)
    else if (this.curOvFeed - this.tarOvFeed >= 1) this.sendImmediate(0x94)

    if (this.tarOvPower === 100 && this.curOvPower !== 100) this.sendImmediate(0x99)
    else if (this.tarOvPower - this.curOvPower >= 10) this.sendImmediate(0x9a)
    else if (this.curOvPower - this.tarOvPower >= 10) this.sendImmediate(0x9b)
    else if (this.tarOvPower - this.curOvPower >= 1) this.sendImmediate(0x9c)
    else if (this.curOvPower - this.tarOvPower >= 1) this.sendImmediate(0x9d)

    if (this.tarOvRapids === 100 && this.curOvRapids !== 100) this.sendImmediate(0x95)
    else if (this.tarOvRapids === 50 && this.curOvRapids !== 50) this.sendImmediate(0x96)
    else if (this.tarOvRapids === 25 && this.curOvRapids !== 25) this.sendImmediate(0x97)
  }

  // ================= 常用操作 =================

  /** 回原点 $H */
  homing() {
    if (this.canDoHoming) this.enqueueRaw('$H')
  }
  /** 解锁 $X */
  unlock() {
    this.enqueueRaw('$X')
  }
  /** 设置新零点 G92 */
  setNewZero() {
    this.enqueueRaw('G92 X0 Y0 Z0')
  }
  /** 清除坐标系偏移 */
  resetWCO() {
    this.enqueueRaw('G92.1')
  }
  /** 写入一条 GRBL 设置 $num=val */
  writeSetting(num: number, value: number) {
    this.enqueueRaw(`$${num}=${value}`)
  }
  /** 清空报警/恢复 */
  killAlarm() {
    this.enqueueRaw('$X')
  }

  get canDoHoming() {
    return this.isConnected && this.machineStatus !== MacStatus.Alarm && this.machineStatus !== MacStatus.Run
  }
  get canReset() {
    return this.isConnected
  }
  get canFeedHold() {
    return this.isConnected && (this.machineStatus === MacStatus.Run || this.machineStatus === MacStatus.Jog)
  }

  /** 点动 */
  jog(dir: JogDirection, step = this.jogStep, speed = this.jogSpeed) {
    if (!this.isConnected) return
    if (this.version && this.version.gte(new GrblVersionInfo(1, 1))) {
      if (dir === JogDirection.Home) {
        this.enqueueRaw(`$J=G90X0Y0F${speed}`)
        return
      }
      let cmd = '$J=G91'
      const s = step.toFixed(1)
      if (dir === JogDirection.NE || dir === JogDirection.E || dir === JogDirection.SE) cmd += `X${s}`
      if (dir === JogDirection.NW || dir === JogDirection.W || dir === JogDirection.SW) cmd += `X-${s}`
      if (dir === JogDirection.NW || dir === JogDirection.N || dir === JogDirection.NE) cmd += `Y${s}`
      if (dir === JogDirection.SW || dir === JogDirection.S || dir === JogDirection.SE) cmd += `Y-${s}`
      if (dir === JogDirection.Zdown) cmd += `Z-${s}`
      if (dir === JogDirection.Zup) cmd += `Z${s}`
      cmd += `F${speed}`
      this.enqueueRaw(cmd)
    } else {
      // v0.9 用相对移动模拟
      const s = step.toFixed(1)
      let cmd = 'G91 G1'
      if (dir === JogDirection.NE || dir === JogDirection.E || dir === JogDirection.SE) cmd += `X${s}`
      if (dir === JogDirection.NW || dir === JogDirection.W || dir === JogDirection.SW) cmd += `X-${s}`
      if (dir === JogDirection.NW || dir === JogDirection.N || dir === JogDirection.NE) cmd += `Y${s}`
      if (dir === JogDirection.SW || dir === JogDirection.S || dir === JogDirection.SE) cmd += `Y-${s}`
      cmd += `F${speed}`
      this.enqueueRaw(cmd)
      this.enqueueRaw('G90')
    }
  }

  /** 移动到一个工作坐标点（G90 绝对坐标） */
  moveTo(x: number, y: number, speed = this.jogSpeed) {
    this.enqueueRaw(`G90 G1 X${x.toFixed(3)} Y${y.toFixed(3)} F${speed}`)
  }

  /** 设置当前位置为工作原点 */
  setZeroHere(x = 0, y = 0) {
    this.enqueueRaw(`G92 X${x} Y${y}`)
  }

  private lookupCode(group: Record<string, Record<string, string[]>>, key: string, idx: number): string | null {
    const version = this.config.getVersion()
    let groupName = 'v1.1'
    if (version) {
      if (version.isOrtur && version.isHAL) groupName = 'ortur.GrblHal'
      else if (version.isOrtur && version.orturFWVersionNumber >= 170) groupName = 'ortur.v1.7.x'
      else if (version.isOrtur && version.orturFWVersionNumber >= 150) groupName = 'ortur.v1.5.x'
      else if (version.isOrtur) groupName = 'ortur.v1.4.x'
      else if (version.isLonger && version.vendorInfo === 'NanoDuo') groupName = 'longer.nanoduo'
      else groupName = `v${version.major}.${version.minor}`
    }
    const g = group[groupName] ?? group['v1.1'] ?? group['standard']
    if (!g) return null
    const entry = g[key]
    return entry && entry[idx] !== undefined ? entry[idx] : null
  }

  /** 查找错误码描述 */
  errorDescription(code: string): string | null {
    return this.lookupCode(ERROR_CODES, code, 1)
  }

  /** 查找设置项说明：[名称, 单位, 说明] */
  settingInfo(id: number): string[] {
    const key = String(id)
    return [
      this.lookupCode(SETTING_CODES, key, 0) ?? '',
      this.lookupCode(SETTING_CODES, key, 1) ?? '',
      this.lookupCode(SETTING_CODES, key, 2) ?? ''
    ]
  }

  /** 查找报警码描述 */
  alarmInfo(code: string): string[] {
    return [
      this.lookupCode(ALARM_CODES, code, 0) ?? '',
      this.lookupCode(ALARM_CODES, code, 1) ?? ''
    ]
  }
}

/** 全局唯一核心实例 */
export const grbl = new GrblCore()

export { MacStatus }
export type { Element }