/** USB 串口设备信息 */
export interface UsbDeviceInfo {
  /** 平台内部设备 id（Android 为 deviceId，Web 为序号） */
  deviceId: number
  /** 设备名，如 /dev/bus/usb/001/002 */
  name: string
  /** 厂商名 */
  vendor?: string
  /** 产品名 */
  product?: string
  /** VID */
  vendorId?: number
  /** PID */
  productId?: number
}

/**
 * 串口传输层抽象：屏蔽 Android 原生 USB 与浏览器 Web Serial 的差异。
 * 只负责字节收发，不涉及 GRBL 协议。
 */
export interface SerialTransport {
  /** 列出可用串口设备 */
  list(): Promise<UsbDeviceInfo[]>
  /** 打开串口 */
  open(deviceId: number, baudRate: number): Promise<void>
  /** 关闭串口 */
  close(): Promise<void>
  /** 发送文本 */
  write(text: string): Promise<void>
  /** 发送原始字节（实时命令用） */
  writeBytes(bytes: number[]): Promise<void>
  /** 换行结束符：GRBL 使用 \n */
  /** 数据回调 */
  onData(cb: (chunk: string) => void): void
  onClose(cb: () => void): void
  /** 是否已打开 */
  isOpen(): boolean
}