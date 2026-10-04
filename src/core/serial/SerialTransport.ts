import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'
import type { SerialDeviceInfo, SerialTransport, TransportKind } from './types'

/** 原生 USB 串口插件（Android） */
interface UsbSerialPlugin {
  list(): Promise<{ devices: RawDevice[] }>
  open(options: { deviceId: number; baudRate: number }): Promise<void>
  close(): Promise<void>
  write(options: { data: string; encoding?: 'utf8' | 'base64' }): Promise<void>
  addListener(eventName: 'data', cb: (data: { data: string }) => void): Promise<PluginListenerHandle>
  addListener(eventName: 'closed', cb: () => void): Promise<PluginListenerHandle>
  removeAllListeners(): Promise<void>
}

/** 原生蓝牙串口插件（Android，经典蓝牙 SPP） */
interface BluetoothSerialPlugin {
  list(): Promise<{ devices: RawBluetoothDevice[] }>
  open(options: { address: string; baudRate: number }): Promise<void>
  close(): Promise<void>
  write(options: { data: string; encoding?: 'utf8' | 'base64' }): Promise<void>
  openSettings(): Promise<void>
  addListener(eventName: 'data', cb: (data: { data: string }) => void): Promise<PluginListenerHandle>
  addListener(eventName: 'closed', cb: () => void): Promise<PluginListenerHandle>
  removeAllListeners(): Promise<void>
}

interface RawDevice {
  deviceId: number
  name: string
  vendor?: string
  product?: string
  vendorId?: number
  productId?: number
}

interface RawBluetoothDevice {
  address: string
  name: string
}

export const UsbSerial = registerPlugin<UsbSerialPlugin>('UsbSerial')
export const BluetoothSerial = registerPlugin<BluetoothSerialPlugin>('BluetoothSerial')

function base64ToBytes(b64: string): number[] {
  const bin = atob(b64)
  const out = new Array<number>(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

function bytesToBase64(bytes: number[]): string {
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b & 0xff)
  return btoa(bin)
}

/** Android 原生 USB 串口实现 */
export class NativeUsbSerial implements SerialTransport {
  readonly kind: TransportKind = 'usb'
  private opened = false
  private dataHandles: PluginListenerHandle[] = []
  private closeHandles: PluginListenerHandle[] = []

  async list(): Promise<SerialDeviceInfo[]> {
    const r = await UsbSerial.list()
    return (r.devices ?? []).map((d) => ({
      id: String(d.deviceId),
      kind: 'usb' as const,
      name: d.name,
      deviceId: d.deviceId,
      vendor: d.vendor,
      product: d.product,
      vendorId: d.vendorId,
      productId: d.productId
    }))
  }

  async open(device: SerialDeviceInfo, baudRate: number): Promise<void> {
    if (device.deviceId === undefined) throw new Error('缺少 USB 设备编号')
    await UsbSerial.open({ deviceId: device.deviceId, baudRate })
    this.opened = true
  }

  async close(): Promise<void> {
    for (const h of this.dataHandles) await h.remove()
    for (const h of this.closeHandles) await h.remove()
    this.dataHandles = []
    this.closeHandles = []
    if (this.opened) await UsbSerial.close()
    this.opened = false
  }

  async write(text: string): Promise<void> {
    await UsbSerial.write({ data: text, encoding: 'utf8' })
  }

  async writeBytes(bytes: number[]): Promise<void> {
    await UsbSerial.write({ data: bytesToBase64(bytes), encoding: 'base64' })
  }

  onData(cb: (chunk: string) => void): void {
    UsbSerial.addListener('data', (d) => cb(d.data)).then((h) => this.dataHandles.push(h))
  }

  onClose(cb: () => void): void {
    UsbSerial.addListener('closed', () => cb()).then((h) => this.closeHandles.push(h))
  }

  isOpen(): boolean {
    return this.opened
  }
}

/** Android 原生蓝牙串口实现（经典蓝牙 SPP） */
export class NativeBluetoothSerial implements SerialTransport {
  readonly kind: TransportKind = 'bluetooth'
  private opened = false
  private dataHandles: PluginListenerHandle[] = []
  private closeHandles: PluginListenerHandle[] = []

  async list(): Promise<SerialDeviceInfo[]> {
    const r = await BluetoothSerial.list()
    return (r.devices ?? []).map((d) => ({
      id: d.address,
      kind: 'bluetooth' as const,
      name: d.name || d.address,
      address: d.address
    }))
  }

  async open(device: SerialDeviceInfo, baudRate: number): Promise<void> {
    if (!device.address) throw new Error('缺少蓝牙 MAC 地址')
    await BluetoothSerial.open({ address: device.address, baudRate })
    this.opened = true
  }

  async close(): Promise<void> {
    for (const h of this.dataHandles) await h.remove()
    for (const h of this.closeHandles) await h.remove()
    this.dataHandles = []
    this.closeHandles = []
    if (this.opened) await BluetoothSerial.close()
    this.opened = false
  }

  async write(text: string): Promise<void> {
    await BluetoothSerial.write({ data: text, encoding: 'utf8' })
  }

  async writeBytes(bytes: number[]): Promise<void> {
    await BluetoothSerial.write({ data: bytesToBase64(bytes), encoding: 'base64' })
  }

  onData(cb: (chunk: string) => void): void {
    BluetoothSerial.addListener('data', (d) => cb(d.data)).then((h) => this.dataHandles.push(h))
  }

  onClose(cb: () => void): void {
    BluetoothSerial.addListener('closed', () => cb()).then((h) => this.closeHandles.push(h))
  }

  isOpen(): boolean {
    return this.opened
  }
}

/** 浏览器 Web Serial 实现（便于桌面调试） */
export class WebSerialTransport implements SerialTransport {
  readonly kind: TransportKind = 'usb'
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private port: any = null
  private reader: ReadableStreamDefaultReader<Uint8Array> | null = null
  private dataCb: ((c: string) => void) | null = null
  private closeCb: (() => void) | null = null
  private decoder = new TextDecoder()

  static supported(): boolean {
    return typeof navigator !== 'undefined' && 'serial' in navigator
  }

  async list(): Promise<SerialDeviceInfo[]> {
    // Web Serial 不支持枚举，返回占位；用户点击后弹出授权框
    return [{ id: '0', kind: 'usb', name: '选择串口设备…', deviceId: 0 }]
  }

  async open(_device: SerialDeviceInfo, baudRate: number): Promise<void> {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const serial = (navigator as any).serial
    this.port = await serial.requestPort()
    await this.port.open({ baudRate })
    this.readLoop()
  }

  private async readLoop() {
    while (this.port && this.port.readable) {
      try {
        const reader = this.port.readable.getReader()
        this.reader = reader
        for (;;) {
          const { value, done } = await reader.read()
          if (done) break
          if (value) this.dataCb?.(this.decoder.decode(value, { stream: true }))
        }
      } catch {
        break
      } finally {
        try {
          this.reader?.releaseLock()
        } catch {
          /* ignore */
        }
        this.reader = null
      }
    }
    this.closeCb?.()
  }

  async close(): Promise<void> {
    try {
      await this.reader?.cancel()
    } catch {
      /* ignore */
    }
    try {
      await this.port?.close()
    } catch {
      /* ignore */
    }
    this.port = null
  }

  async write(text: string): Promise<void> {
    const w = this.port.writable.getWriter()
    await w.write(new TextEncoder().encode(text))
    w.releaseLock()
  }

  async writeBytes(bytes: number[]): Promise<void> {
    const w = this.port.writable.getWriter()
    await w.write(new Uint8Array(bytes))
    w.releaseLock()
  }

  onData(cb: (chunk: string) => void): void {
    this.dataCb = cb
  }

  onClose(cb: () => void): void {
    this.closeCb = cb
  }

  isOpen(): boolean {
    return !!this.port
  }
}

/** 打开系统蓝牙设置，便于用户配对雕刻机蓝牙模块 */
export async function openBluetoothSettings(): Promise<void> {
  await BluetoothSerial.openSettings()
}

/** 根据运行环境与传输类型选择实现 */
export function createTransport(kind: TransportKind = 'usb'): SerialTransport {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const cap = (globalThis as any).Capacitor
  const native = !!cap?.isNativePlatform?.()
  if (kind === 'bluetooth') return new NativeBluetoothSerial()
  if (native) return new NativeUsbSerial()
  if (WebSerialTransport.supported()) return new WebSerialTransport()
  return new NativeUsbSerial()
}

export { base64ToBytes, bytesToBase64 }
