package com.lasergrbl.android.serial

import android.bluetooth.BluetoothSocket
import com.lasergrbl.core.serial.PortConnection
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 经典蓝牙 SPP 的 [PortConnection] 实现：把一个**已经 `connect()` 成功**的 [BluetoothSocket]
 * 翻译成接缝里的三个阻塞调用。等价于 v2 `BluetoothSerialPlugin` 的
 * `readLoop` / `write` / `closeQuietly`（`BluetoothSerialPlugin.java:349-411`）。
 *
 * ### 谁负责建连
 * 由 [BluetoothSerialTransport] 传给 `SerialPortBase` 的 `PortConnectionFactory` 负责：它在
 * `create(device, baudRate)` 里完成「解析地址 → `cancelDiscovery()` → `createRfcommSocketToServiceRecord`
 * → `connect()`」，成功后才把 socket 交给本类；失败则**先关 socket 再抛**（接缝的硬性契约：
 * 工厂要么交出可用的句柄，要么自己清干净）。
 *
 * 因此本类只代表「一条已经打开的连接」，不需要 `list()` / `open()` 这类对句柄没有意义的方法
 * （新接缝把它们从 [PortConnection] 里去掉了）。
 *
 * ### 线程契约（follow [PortConnection] 的约定）
 * [read] / [write] / [close] 由 [com.lasergrbl.core.serial.SerialPortBase] 在各自的工作线程上
 * 调用（读一个线程、写一个线程，同一时刻每个方法只有一个调用者），所以本类内部不再加锁；
 * 只有 [close] 的幂等性用 CAS 保证 —— 它可能被手动关闭、外部断开、读线程收尾三处触发。
 *
 * ### 读语义（与 v2 一致）
 * SPP 的 `InputStream.read` 是**无限阻塞**读：有数据才返回，对端关闭/断开时返回 `-1`（EOF），
 * 连接失效时抛 `IOException`。三种情况 [com.lasergrbl.core.serial.SerialPortBase] 都会当作
 * 「连接结束」处理，并只通知一次 `closed`。
 *
 * ⚠️ 实测（本机 Android SDK 源码 android-28/30/31/35）：经典蓝牙的 RFCOMM 把负数
 * **转成 `IOException("bt socket closed, read return: -1")`**，所以真机上"断开"实际走的是
 * 异常分支，`-1` 分支在蓝牙上基本不可达（USB 侧的依据见 `UsbSerialPortConnection`）。
 * 两条分支结果一致，只是 `lastError` 文案不同 —— 这里保留 `-1` 语义是因为它是
 * [PortConnection] 的契约，而不是因为它在蓝牙上会被触发。
 */
class BluetoothSocketConnection(private val socket: BluetoothSocket) : PortConnection {

    /** [close] 的幂等闸门。 */
    private val closed = AtomicBoolean(false)

    /**
     * 输入/输出流各缓存一份：`BluetoothSocket.getInputStream()` 每次调用都会新建一个包装对象，
     * v2 也是在 `readLoop` 开头取一次（`BluetoothSerialPlugin.java:354`）。
     */
    private val input: InputStream by lazy { socket.inputStream }

    private val output: OutputStream by lazy { socket.outputStream }

    /** 阻塞读到 `buffer` 有数据为止；返回 `-1` 表示 EOF（对端关闭/断开）。 */
    override fun read(buffer: ByteArray): Int = input.read(buffer)

    /** 写全部字节并 flush（v2 的 `out.write(bytes); out.flush();`）。 */
    override fun write(bytes: ByteArray) {
        val out = output
        out.write(bytes)
        out.flush()
    }

    /**
     * 幂等关闭：只有第一次调用真正 `socket.close()`，之后的调用什么都不做。
     *
     * v2 的 `closeQuietly` 用 try/catch 吞掉重复关闭的异常（`BluetoothSerialPlugin.java:402-411`）；
     * 这里用 CAS 从源头挡住第二次调用，语义相同但不会把异常当控制流。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
    }
}
