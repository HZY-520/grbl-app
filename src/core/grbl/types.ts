/** GRBL 核心类型定义 —— 移植自 LaserGRBL Core/GrblCore.cs */

/** 固件类型 */
export enum Firmware {
  Grbl = 'Grbl',
  Smoothie = 'Smoothie',
  Marlin = 'Marlin',
  Vigo = 'Vigo'
}

/** 机器状态 */
export enum MacStatus {
  Disconnected = 'Disconnected',
  Connecting = 'Connecting',
  Idle = 'Idle',
  Run = 'Run',
  Hold = 'Hold',
  Door = 'Door',
  Home = 'Home',
  Alarm = 'Alarm',
  Check = 'Check',
  Jog = 'Jog',
  Queue = 'Queue',
  Cooling = 'Cooling',
  AutoHold = 'AutoHold',
  Tool = 'Tool'
}

/** 点动方向 */
export enum JogDirection {
  Abort = 'Abort',
  Home = 'Home',
  N = 'N',
  S = 'S',
  W = 'W',
  E = 'E',
  NW = 'NW',
  NE = 'NE',
  SW = 'SW',
  SE = 'SE',
  Zup = 'Zup',
  Zdown = 'Zdown',
  Position = 'Position'
}

/** 流式发送模式 */
export enum StreamingMode {
  Buffered = 'Buffered',
  Synchronous = 'Synchronous',
  RepeatOnError = 'RepeatOnError'
}

/** 检测到的问题 */
export enum DetectedIssue {
  Unknown = 0,
  ManualReset = -1,
  ManualDisconnect = -2,
  ManualAbort = -3,
  StopResponding = 1,
  UnexpectedReset = 3,
  UnexpectedDisconnect = 4,
  MachineAlarm = 5
}

/** 状态查询/通讯速度模式 */
export class ThreadingMode {
  constructor(
    public readonly statusQuery: number,
    public readonly txLong: number,
    public readonly txShort: number,
    public readonly rxLong: number,
    public readonly rxShort: number,
    public readonly name: string
  ) {}

  static get Slow() {
    return new ThreadingMode(2000, 15, 4, 2, 1, 'Slow')
  }
  static get Quiet() {
    return new ThreadingMode(1000, 10, 2, 1, 1, 'Quiet')
  }
  static get Fast() {
    return new ThreadingMode(500, 5, 1, 1, 0, 'Fast')
  }
  static get UltraFast() {
    return new ThreadingMode(250, 1, 0, 0, 0, 'UltraFast')
  }
  static get Insane() {
    return new ThreadingMode(200, 1, 0, 0, 0, 'Insane')
  }

  static all(): ThreadingMode[] {
    return [ThreadingMode.Slow, ThreadingMode.Quiet, ThreadingMode.Fast, ThreadingMode.UltraFast, ThreadingMode.Insane]
  }

  equals(o: ThreadingMode) {
    return !!o && o.name === this.name
  }

  toString() {
    return this.name
  }
}

/** 三维坐标 (兼容 GPoint 结构) */
export class GPoint {
  constructor(
    public X = 0,
    public Y = 0,
    public Z = 0
  ) {}

  static get Zero() {
    return new GPoint(0, 0, 0)
  }

  equals(o: GPoint) {
    return this.X === o.X && this.Y === o.Y && this.Z === o.Z
  }

  sub(o: GPoint) {
    return new GPoint(this.X - o.X, this.Y - o.Y, this.Z - o.Z)
  }

  add(o: GPoint) {
    return new GPoint(this.X + o.X, this.Y + o.Y, this.Z + o.Z)
  }

  toString() {
    return `X${this.X} Y${this.Y} Z${this.Z}`
  }
}

/** GRBL 版本信息 */
export class GrblVersionInfo {
  readonly major: number
  readonly minor: number
  readonly build: string
  readonly vendorInfo: string | null
  readonly vendorVersion: string | null
  readonly isHAL: boolean
  readonly isOrtur: boolean
  readonly isLonger: boolean

  constructor(
    major: number,
    minor: number,
    build = '',
    vendorInfo: string | null = null,
    vendorVersion: string | null = null,
    isHAL = false
  ) {
    this.major = major
    this.minor = minor
    this.build = build
    this.vendorInfo = vendorInfo
    this.vendorVersion = vendorVersion
    this.isHAL = isHAL
    this.isOrtur = !!vendorInfo && (vendorInfo.includes('Ortur') || vendorInfo.includes('Aufero'))
    this.isLonger = !!vendorInfo && (vendorInfo.includes('Longer') || vendorInfo.includes('NanoDuo'))
  }

  get machineName() {
    return this.vendorInfo
  }

  get isLuckyWiFi() {
    return (
      (this.isOrtur && this.vendorInfo === 'Ortur Laser Master 3') ||
      (this.isLonger && (this.vendorInfo === 'Longer Nano' || this.vendorInfo === 'NanoDuo'))
    )
  }

  /** Ortur 固件版本号，例如 1.7 => 170 */
  get orturFWVersionNumber(): number {
    if (!this.vendorVersion) return 0
    const m = /(\d+)\.(\d+)/.exec(this.vendorVersion)
    if (!m) return 0
    return parseInt(m[1], 10) * 100 + parseInt(m[2], 10) * 10
  }

  private key() {
    return `${this.major}.${this.minor}${this.build ? this.build : ''}`
  }

  compareTo(o: GrblVersionInfo | null): number {
    if (o === null || o === undefined) return 1
    if (this.major !== o.major) return this.major > o.major ? 1 : -1
    if (this.minor !== o.minor) return this.minor > o.minor ? 1 : -1
    const a = this.build || ''
    const b = o.build || ''
    if (a === b) return 0
    return a > b ? 1 : -1
  }

  gte(o: GrblVersionInfo) {
    return this.compareTo(o) >= 0
  }
  lt(o: GrblVersionInfo) {
    return this.compareTo(o) < 0
  }

  equals(o: GrblVersionInfo | null) {
    return !!o && this.compareTo(o) === 0
  }

  toString() {
    return this.key()
  }
}

export const GCODE_EXTENSIONS = ['.nc', '.cnc', '.tap', '.gcode', '.ngc', '.txt']

