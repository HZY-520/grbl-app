package com.lasergrbl.android.serial

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.lasergrbl.core.serial.PortConnection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [PortConnection] 的真机实现：把 usb-serial-for-android 的 `UsbSerialPort` 与
 * `UsbDeviceConnection` 包装成共有状态机需要的三个阻塞调用（read / write / close）。
 *
 * 为什么这一层这么薄：`UsbDeviceConnection` / `UsbSerialPort` 都是无法在 JVM 单测里构造的类型，
 * 所以凡是需要断言的状态机语义（写队列串行化、拔出只通知一次、`close()` 幂等、UTF-8 跨块拼接、
 * `isOpen()` 状态转换）全部在 `:core` 的 `SerialPortBase` 里；这里只剩「参数翻译」，逐条对齐 v2
 * `UsbSerialPlugin.java`：
 *
 * | 行为 | v2 `UsbSerialPlugin` | 本类 |
 * | --- | --- | --- |
 * | 读 | `port.read(buffer, READ_TIMEOUT_MS)`，其中 `READ_TIMEOUT_MS = 0` | [read] 固定传 [READ_TIMEOUT_FOREVER] |
 * | 写 | `port.write(bytes, WRITE_TIMEOUT_MS)`，其中 `WRITE_TIMEOUT_MS = 2000` | [write] 固定传 [WRITE_TIMEOUT_MILLIS] |
 * | 关闭 | `port.close()` 与 `connection.close()` 各自 try/catch 吞异常 | [close] 相同，且用 CAS 保证幂等 |
 * | 写入锁 | `synchronized (writeLock)` 包住 `port.write` | 由 `SerialPortBase` 的单写线程保证（见其类文档的顺序论证） |
 *
 * ### 线程契约
 * 与 [PortConnection] 的约定一致，且由 `SerialPortBase` 保证：同一时刻只有一个线程在调用
 * [write]（专用写线程）、只有一个线程在调用 [read]（专用读线程），[close] 可能来自任意线程
 * （手动关闭、拔出广播、读线程自行断开）。**因此本类不再自己加锁**，写互斥完全由共有状态机的
 * 写队列承担 —— v2 那把 `writeLock` 已经被它取代，不会出现「两把锁」的语义分叉。
 *
 * 对象只代表「**一条已经打开的**连接句柄」：设备查找、权限申请、端口初始化都在
 * [UsbSerialConnectionFactory] 里完成（对应 `:core` 的 `PortConnectionFactory` 契约），
 * 所以本类不需要（也没有）任何「打开/枚举」类的方法。
 */
class UsbSerialPortConnection(
    private val port: UsbSerialPort,
    private val connection: UsbDeviceConnection
) : PortConnection {

    /** 是否已关闭：`UsbSerialPort.close()` 第二次会抛 IOException，这里自己先挡住，保证幂等。 */
    private val closed = AtomicBoolean(false)

    /**
     * 阻塞读（真实阻塞，不是轮询）。
     *
     * 超时固定用 [READ_TIMEOUT_FOREVER]（= 0，等价 v2 的 `READ_TIMEOUT_MS`）：一直等到有数据或
     * 连接失效为止。注意 usb-serial-for-android 的源码明确警告：**非 0 超时在高波特率连续传输下
     * 会丢数据**，所以这里不允许外部改成有界超时。
     *
     * @return 读到的字节数；0 表示本次没有数据；负数表示流结束（设备断开）。
     */
    override fun read(buffer: ByteArray): Int = port.read(buffer, READ_TIMEOUT_FOREVER)

    /** 阻塞写全部字节（v2 的写超时 [WRITE_TIMEOUT_MILLIS]）。失败抛 IOException，由写线程记录。 */
    override fun write(bytes: ByteArray) {
        port.write(bytes, WRITE_TIMEOUT_MILLIS)
    }

    /**
     * 幂等关闭：`port.close()` 与 `connection.close()` **各自**吞异常（对应 v2 `closePortInternal`
     * 里两段独立的 try/catch）。第二次调用直接返回，不碰已经释放的原生句柄。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { port.close() }
        runCatching { connection.close() }
    }

    companion object {

        /**
         * v2 `READ_TIMEOUT_MS = 0`：**无限等待**（库内部走 `UsbRequest.requestWait()` 的阻塞路径）。
         *
         * `docs/UI-3.0-PLAN.md` §6.1 第 5 条把「读超时」写成了 20000 ms，那是把 v2 的
         * `PERMISSION_TIMEOUT_MS`（权限等待 20 s）误记成了读超时；v2 的读超时确实是 0，
         * 20 s 只用于 USB 权限广播等待（见 [UsbPermissionGate]）。
         */
        const val READ_TIMEOUT_FOREVER: Int = 0

        /** v2 `WRITE_TIMEOUT_MS`。 */
        const val WRITE_TIMEOUT_MILLIS: Int = 2000
    }
}
