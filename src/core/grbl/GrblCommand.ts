/** G 代码命令解析 —— 移植自 LaserGRBL GrblCommand.cs */
import { MacStatus } from './types'

/** 一个 G 代码字（字母 + 数值），如 G1 / X10.5 / S255 */
export class Element {
  constructor(
    public command: string,
    public number: number
  ) {}

  static parse(value: string): Element {
    return new Element(value[0], parseFloat(value.substring(1)))
  }

  equals(o: Element | null | undefined) {
    return !!o && o.command === this.command && o.number === this.number
  }

  toString() {
    return `${this.command}${formatDecimal(this.number)}`
  }
}

/** 与 C# decimal.ToString(InvariantCulture) 类似的输出 */
export function formatDecimal(v: number): string {
  if (Number.isInteger(v)) return String(v)
  return String(v)
}

export enum CommandStatus {
  Queued = 'Queued',
  WaitingResponse = 'WaitingResponse',
  ResponseGood = 'ResponseGood',
  ResponseBad = 'ResponseBad',
  InvalidResponse = 'InvalidResponse'
}

const TRIM_CHARS = /^[\r\n ]+|[\r\n ]+$/g

export class GrblCommand {
  /** 原始命令行（已 trim/大写） */
  private mLine: string
  /** 响应内容：null=排队中, ''=等待响应, 'OK'/'ERROR:xx'=已响应 */
  private mCodedResult: string | null = null
  private mTimeOffset = 0
  private mHelper: Map<string, Element> | null = null
  repeatCount: number

  constructor(line: string, repeat = 0, preservecase = false) {
    let l = line.trim()
    if (!preservecase) l = l.toUpperCase()
    this.mLine = l
    this.repeatCount = repeat
  }

  static fromElements(elements: Element[]): GrblCommand {
    let line = ''
    for (const e of elements) line = line + e.toString() + ' '
    return new GrblCommand(line.toUpperCase().trim())
  }

  static combine(first: Element, toappend: GrblCommand): GrblCommand {
    return new GrblCommand(`${first.toString()} ${toappend.command}`.toUpperCase().trim())
  }

  clone(): GrblCommand {
    const c = new GrblCommand(this.mLine, this.repeatCount, true)
    c.mCodedResult = this.mCodedResult
    c.mTimeOffset = this.mTimeOffset
    return c
  }

  get command() {
    return this.mLine
  }

  set command(v: string) {
    this.mLine = v
  }

  get justBuilt() {
    return this.mHelper !== null
  }

  /** 解析命令中的字母/数值对 */
  buildHelper(): void {
    if (this.justBuilt) return
    this.mHelper = new Map<string, Element>()
    try {
      if (!this.isGrblCommand) {
        let cmd = '\0'
        let num = ''
        let comment = false
        let oldspace = false
        let sb = ''
        for (const c of this.mLine) {
          if (c === ';') break
          if (c === '(') comment = true
          const space = c === ' '
          if (!comment) {
            if (space && !oldspace) sb += ' '
            else if (!space) sb += c
          }
          oldspace = space
          if (!comment) {
            if (/[A-Za-z]/.test(c)) {
              if (cmd !== '\0') this.add(new Element(cmd, parseFloat(num)))
              cmd = c
              num = ''
            } else if (/[0-9]/.test(c) || c === '.' || c === '-') {
              num += c
            }
          }
          if (c === ')') comment = false
        }
        this.mLine = sb
        if (cmd !== '\0') this.add(new Element(cmd, parseFloat(num)))
      }
    } catch {
      /* 与 LaserGRBL 一致：解析异常静默忽略 */
    }
  }

  deleteHelper(): void {
    this.mHelper = null
  }

  private add(e: Element) {
    this.mHelper!.set(e.command, e)
  }

  private getElement(key: string): Element | null {
    if (!this.mHelper) return null
    return this.mHelper.get(key) ?? null
  }

  setOffset(ms: number) {
    this.mTimeOffset = ms
  }
  get timeOffset() {
    return this.mTimeOffset
  }

  /** 发送到串口的数据（含换行）。GRBL 命令不压缩空格 */
  get serialData(): string {
    if (this.canCompress) return this.mLine.replace(TRIM_CHARS, '').replace(/ /g, '') + '\n'
    return this.mLine.replace(TRIM_CHARS, '') + '\n'
  }

  private get canCompress() {
    return !this.isGrblCommand
  }

  get status(): CommandStatus {
    if (this.mCodedResult === null) return CommandStatus.Queued
    if (this.mCodedResult.length === 0) return CommandStatus.WaitingResponse
    if (this.mCodedResult.startsWith('OK')) return CommandStatus.ResponseGood
    if (this.mCodedResult.startsWith('ERROR')) return CommandStatus.ResponseBad
    return CommandStatus.InvalidResponse
  }

  setResult(result: string) {
    this.mCodedResult = result.toUpperCase().trim()
  }

  setSending() {
    this.mCodedResult = ''
  }

  clearResult() {
    this.mCodedResult = null
  }

  get codedResult(): string | null {
    return this.mCodedResult
  }

  get isGrblCommand() {
    return this.mLine.startsWith('$')
  }

  get isEmpty() {
    return this.mLine.length === 0
  }

  get isWriteEEPROM() {
    return this.isGrblCommand && GrblConfSTIsSetConf(this.mLine)
  }

  // ---- G 代码 ----
  get G(): Element | null {
    return this.getElement('G')
  }
  get M(): Element | null {
    return this.getElement('M')
  }
  get T(): Element | null {
    return this.getElement('T')
  }
  get S(): Element | null {
    return this.getElement('S')
  }
  get P(): Element | null {
    return this.getElement('P')
  }
  get X(): Element | null {
    return this.getElement('X')
  }
  get Y(): Element | null {
    return this.getElement('Y')
  }
  get Z(): Element | null {
    return this.getElement('Z')
  }
  get I(): Element | null {
    return this.getElement('I')
  }
  get J(): Element | null {
    return this.getElement('J')
  }
  get F(): Element | null {
    return this.getElement('F')
  }
  get R(): Element | null {
    return this.getElement('R')
  }

  get isSetWCO() {
    return this.G !== null && this.G.number === 92
  }

  get isMovement() {
    return this.isLinearMovement || this.isArcMovement
  }

  get isLinearMovement() {
    return !this.isSetWCO && (this.X !== null || this.Y !== null || this.Z !== null) && this.I === null && this.J === null && this.R === null
  }

  get isArcMovement() {
    return !this.isSetWCO && (this.I !== null || this.J !== null || this.R !== null)
  }

  isCW(prev: boolean) {
    if (this.G !== null && this.G.number === 2) return true
    if (this.G !== null && this.G.number === 3) return false
    return prev
  }

  get isPause() {
    return this.G !== null && this.G.number === 4
  }

  get isAbsoluteCoord() {
    return this.G !== null && this.G.number === 90
  }
  get isRelativeCoord() {
    return this.G !== null && this.G.number === 91
  }

  get isLaserON() {
    return this.isM3 || this.isM4
  }
  get isM3() {
    return this.M !== null && this.M.number === 3
  }
  get isM4() {
    return this.M !== null && this.M.number === 4
  }
  get isLaserOFF() {
    return this.isM5
  }
  get isM5() {
    return this.M !== null && this.M.number === 5
  }

  getDecodedMessage(): string {
    return this.repeatCount === 0 ? this.command : `${this.command} (重试 ${this.repeatCount})`
  }

  toString() {
    return this.mLine
  }
}

/** 设置项行匹配: $NUM=VAL */
const CONF_REGEX = /^\$(\d+)\s*=(.*)/

export function GrblConfSTIsSetConf(p: string) {
  return CONF_REGEX.test(p)
}

/** 日志行类型 */
export enum MessageType {
  Startup = 'Startup',
  Config = 'Config',
  Alarm = 'Alarm',
  Feedback = 'Feedback',
  Position = 'Position',
  Others = 'Others',
  Warning = 'Warning',
  Diagnostic = 'Diagnostic',
  Command = 'Command'
}

/** 日志中的一条消息（来自机器或应用） */
export class GrblMessage {
  readonly nativeMessage: string
  message: string
  tooltip = ''
  type: MessageType
  leftColor = ''
  rightColor = ''
  imageIndex = 3
  /** 对应的响应信息（用于命令日志） */
  codedResult: string | null = null

  constructor(message: string, type: MessageType) {
    this.message = message.trim()
    this.nativeMessage = this.message
    this.type = type
  }

  static fromLine(message: string, decode: boolean): GrblMessage {
    const trimmed = message.trim()
    let type: MessageType
    const lower = trimmed.toLowerCase()
    if (lower.startsWith('$') && trimmed.includes('=')) type = MessageType.Config
    else if (lower.startsWith('grbl')) type = MessageType.Startup
    else if (lower.startsWith('alarm')) type = MessageType.Alarm
    else if (trimmed.startsWith('<') && trimmed.endsWith('>')) type = MessageType.Position
    else if (trimmed.startsWith('[') && trimmed.endsWith(']')) type = MessageType.Feedback
    else type = MessageType.Others

    const m = new GrblMessage(trimmed, type)
    if (decode) {
      m.decode()
    }
    return m
  }

  private decode() {
    const { settingLookup, alarmLookup } = requireDecoders()
    try {
      if (this.type === MessageType.Config) {
        const key = this.message.substring(1, this.message.indexOf('='))
        const brief = settingLookup(key, 0)
        const unit = settingLookup(key, 1)
        const desc = settingLookup(key, 2)
        if (brief) this.message = `${this.message} (${brief})`
        if (desc) this.tooltip = `${desc} [${unit}]`
      } else if (this.type === MessageType.Alarm) {
        const key = this.message.substring(this.message.indexOf(':') + 1)
        const brief = alarmLookup(key, 0)
        const desc = alarmLookup(key, 1)
        if (brief) this.message = brief
        if (desc) this.tooltip = desc
      }
    } catch {
      /* ignore */
    }
  }

  getDecodedMessage() {
    return this.message
  }
  getNativeMessage() {
    return this.nativeMessage
  }
}

// 延迟注入的解码表，避免循环依赖
type LookupFn = (key: string, index: number) => string | null
let _settingLookup: LookupFn = () => null
let _alarmLookup: LookupFn = () => null

export function installDecoders(settings: LookupFn, alarms: LookupFn) {
  _settingLookup = settings
  _alarmLookup = alarms
}

function requireDecoders() {
  return { settingLookup: _settingLookup, alarmLookup: _alarmLookup }
}

export type { MacStatus }