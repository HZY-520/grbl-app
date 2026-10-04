/** 传输类型：USB 串口 / 蓝牙串口（SPP） */
export type TransportKind = 'usb' | 'bluetooth'

/** 串口设备信息（USB 与蓝牙统一表示） */
export interface SerialDeviceInfo {
  /** 稳定唯一标识：USB 为数字 deviceId 的字符串，蓝牙为 MAC 地址 */
  id: string
  /** 传输类型 */
  kind: TransportKind
  /** 设备名 */
  name: string
  /** USB 数字设备编号（仅 USB） */
  deviceId?: number
  /** 蓝牙 MAC 地址（仅蓝牙） */
  address?: string
  /** 厂商名 */
  vendor?: string
  /** 产品名 */
  product?: string
  /** VID（仅 USB） */
  vendorId?: number
  /** PID（仅 USB） */
  productId?: number
}

/**
 * 串口传输层抽象：屏蔽 Android 原生 USB / 蓝牙串口与浏览器 Web Serial 的差异。
 * 只负责字节收发，不涉及 GRBL 协议。
 */
export interface SerialTransport {
  /** 传输类型 */
  readonly kind: TransportKind
  /** 列出可用设备 */
  list(): Promise<SerialDeviceInfo[]>
  /** 打开设备 */
  open(device: SerialDeviceInfo, baudRate: number): Promise<void>
  /** 关闭设备 */
  close(): Promise<void>
  /** 发送文本 */
  write(text: string): Promise<void>
  /** 发送原始字节（实时命令用） */
  writeBytes(bytes: number[]): Promise<void>
  /** 数据回调 */
  onData(cb: (chunk: string) => void): void
  onClose(cb: () => void): void
  /** 是否已打开 */
  isOpen(): boolean
}
