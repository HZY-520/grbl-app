package com.lasergrbl.core.serial

/**
 * 已打开连接的字节通道接缝（Phase 3）—— **没有 Android 依赖**，所有平台细节
 * （`UsbManager`、`UsbDeviceConnection`、`BluetoothSocket`、权限弹窗）都不在这一层。
 *
 * 拆分的唯一目的是**可测**（见 `docs/UI-3.0-PLAN.md` §6.1 第 2 条）：Android 的 `UsbDevice`
 * 是 final 且无法在单元测试里构造/替换，所以凡是需要断言的状态机语义
 * （写顺序、拔出只通知一次、`close()` 幂等、UTF-8 跨块拼接、`isOpen()` 状态转换）
 * 全部在 [SerialPortBase] 里，用假 [PortConnection] 在 JVM 上跑；
 * Android 实现只负责把系统 API 翻译成这三个阻塞调用。
 *
 * 设备枚举 / 打开由 [PortConnectionFactory] 负责，**不放在这里** —— 句柄只代表"一条已经打开的
 * 连接"，因此不需要实现 `list()` / `open()` 这种对句柄没有意义的方法（也就不会出现
 * "为了满足接口而抛异常"的桩）。
 *
 * ### 线程契约（实现方必须遵守）
 * * [read] / [write] / [close] 是**阻塞**调用，实现方可以在内部做任何阻塞等待
 *   （例如 USB 权限弹窗的 20 s 等待）。
 * * [SerialPortBase] 会在**自己的工作线程**上调用它们：一个专用写线程、一个专用读线程，
 *   且**同一时刻只有一个线程**在调用 [write]（同理只有一个线程在调用 [read]）。
 *   [close] 可能来自任意线程（手动关闭、拔出广播、读线程自行断开），因此实现方**不需要**
 *   自己再加锁，但 [close] 必须自己保证幂等。
 * * [read] 返回 0 表示"暂无数据"，返回负数表示**流结束**（EOF / 设备断开），
 *   抛异常表示读失败 —— 三种情况 [SerialPortBase] 都会当作"连接结束"处理并只通知一次 `closed`。
 */
interface PortConnection {

    /**
     * 阻塞读一段数据。
     *
     * @return 读到的字节数（> 0 正常、0 表示暂无数据、< 0 表示 EOF/断开）
     */
    fun read(buffer: ByteArray): Int

    /** 阻塞写全部字节（失败抛异常，由写线程记录到 `lastError`）。 */
    fun write(bytes: ByteArray)

    /** 关闭并释放（**必须幂等**；未打开时调用不得抛异常）。 */
    fun close()
}

/**
 * 连接工厂 —— 每次 `SerialPortBase.open()` 调用一次 [create]，并在同一个调用里完成
 * 设备查找、权限申请与端口打开。
 *
 * 之所以把"打开"收进工厂而不是 [PortConnection] 的方法：这样"每次 open 都是一个全新句柄"
 * 成为类型层面的约束，`SerialPortBase` 也就能统一保证状态、线程与调用的生命周期。
 *
 * ⚠️ **失败必须自己清干净**（本接口最重要的契约）：[create] 抛异常时，
 * 任何已经获取的原生资源（`UsbDeviceConnection`、`BluetoothSocket`、端口句柄）都必须
 * 在抛出之前关闭。`SerialPortBase` 拿不到那个半成品句柄，因此**无法**替实现方兜底；
 * 泄漏的后果是真机上"第二次连接永远失败"，而单测里看不出来。
 */
fun interface PortConnectionFactory {

    /**
     * 打开一个新连接（**阻塞**）。失败抛异常（异常消息直接作为给用户的错误文案），
     * 且必须在抛出前释放所有已获取的资源。
     */
    fun create(device: SerialDeviceInfo, baudRate: Int): PortConnection
}
