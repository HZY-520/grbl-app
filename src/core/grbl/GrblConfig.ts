/** 应用级设置（本地持久化） —— 对应 LaserGRBL 的 Settings.cs */
import { Firmware } from './types'

type AnyRecord = Record<string, unknown>

const STORAGE_KEY = 'lasergrbl.settings'

/** 默认设置项（对应 LaserGRBL Settings 的默认值） */
export const DEFAULT_SETTINGS: AnyRecord = {
  'Firmware Type': Firmware.Grbl,
  'Support Hardware PWM': true,
  'Reset Grbl On Connect': true,
  'Unidirectional Engraving': false,
  'Disable G0 fast skip': false,
  'Enable Continuous Jog': false,
  'Serial Monitor': true,
  'Show Program Comments': true,
  'Show Program Commands': true,
  'Threading Mode': 'Fast',
  'Mark Speed': 1000,
  'Border Speed': 1000,
  'Min Power': 0,
  'Max Power': 1000,
  'Laser On Command': 'M4',
  'Laser Off Command': 'M5',
  // 设备行程 (mm)：生成 G 代码时用于尺寸自适应
  'Travel X': 300,
  'Travel Y': 200,
  // 测试激光默认参数
  'Test Laser Power': 200,
  'Test Laser Duration': 300,
  'Jog Speed': 1000,
  'Jog Step': 1,
  'Last Port': '',
  'Last Baud': 115200,
  'Header': 'G90\nG0 X0 Y0',
  'Footer': 'M5\nG0 X0 Y0',
  'Auto Home On Connect': false,
  'Language': 'zh-CN'
}

class SettingsStore {
  private data: AnyRecord = { ...DEFAULT_SETTINGS }
  /** 主题 */
  theme: 'dark' | 'light' = 'dark'

  constructor() {
    this.load()
  }

  load() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (raw) this.data = { ...DEFAULT_SETTINGS, ...(JSON.parse(raw) as AnyRecord) }
    } catch {
      this.data = { ...DEFAULT_SETTINGS }
    }
  }

  save() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this.data))
    } catch {
      /* ignore */
    }
  }

  get<T>(key: string, def: T): T {
    const v = this.data[key]
    return (v === undefined || v === null ? def : v) as T
  }

  set(key: string, value: unknown) {
    this.data[key] = value
    this.save()
  }

  all(): AnyRecord {
    return { ...this.data }
  }
}

export const AppSettings = new SettingsStore()

/** 激光开/关指令 */
export function laserOn(): string {
  return AppSettings.get<string>('Laser On Command', 'M4')
}
export function laserOff(): string {
  return AppSettings.get<string>('Laser Off Command', 'M5')
}