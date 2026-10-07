package com.lasergrbl.core.serial

/**
 * 串口传输层抽象 —— 移植自 v2 `src/core/serial/types.ts`。
 *
 * v2 用 Promise；Kotlin 侧对应 `suspend`。只负责字节收发，不涉及 GRBL 协议。
 * Android 侧实现（USB / 蓝牙 SPP）在 `:app` 里用 Kotlin 重写（Phase 3）。
 */

/** 传输类型：USB 串口 / 蓝牙串口（SPP）。 */
enum class TransportKind(val value: String) {
    Usb("usb"),
    Bluetooth("bluetooth");

    companion object {
        fun fromValue(value: String): TransportKind? = entries.firstOrNull { it.value == value }
    }
}

/** 串口设备信息（USB 与蓝牙统一表示）。 */
data class SerialDeviceInfo(
    /** 稳定唯一标识：USB 为数字 deviceId 的字符串，蓝牙为 MAC 地址。 */
    val id: String,
    val kind: TransportKind,
    /** 设备名。 */
    val name: String,
    /** USB 数字设备编号（仅 USB）。 */
    val deviceId: Double? = null,
    /** 蓝牙 MAC 地址（仅蓝牙）。 */
    val address: String? = null,
    /** 厂商名。 */
    val vendor: String? = null,
    /** 产品名。 */
    val product: String? = null,
    /** VID（仅 USB）。 */
    val vendorId: Double? = null,
    /** PID（仅 USB）。 */
    val productId: Double? = null
)

/**
 * 串口传输层抽象：屏蔽 Android 原生 USB / 蓝牙串口与浏览器 Web Serial 的差异。
 *
 * ⚠️ 与 v2 的一处必要差异：`writeBytes` 接收 `ByteArray`（v2 是 `number[]`），
 * GRBL 的实时命令都是单字节（`?` / 0x18 / 0x21 / 0x7E / 覆盖倍率 0x90..0x9D），
 * 调用方写 `byteArrayOf(0x3F)` 即可，语义不变。
 */
interface SerialTransport {

    /** 传输类型。 */
    val kind: TransportKind

    /** 列出可用设备。 */
    suspend fun list(): List<SerialDeviceInfo>

    /** 打开设备。 */
    suspend fun open(device: SerialDeviceInfo, baudRate: Int)

    /** 关闭设备。 */
    suspend fun close()

    /** 发送文本。 */
    suspend fun write(text: String)

    /** 发送原始字节（实时命令用）。 */
    suspend fun writeBytes(bytes: ByteArray)

    /** 数据回调（每次收到一段 UTF-8 文本就回调一次，分块边界由传输层决定）。 */
    fun onData(cb: (chunk: String) -> Unit)

    /** 断开回调。 */
    fun onClose(cb: () -> Unit)

    /** 是否已打开。 */
    fun isOpen(): Boolean
}
