package com.lasergrbl.android.serial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.IntentCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.lasergrbl.core.serial.PortConnection
import com.lasergrbl.core.serial.PortConnectionFactory
import com.lasergrbl.core.serial.SerialDeviceInfo
import com.lasergrbl.core.serial.SerialPortBase
import com.lasergrbl.core.serial.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * USB 串口传输层（Android 真机实现）—— `:core` 的 [SerialDeviceInfo] / `SerialTransport` 契约
 * 在 Android 上的落地，取代 v2 的 Capacitor 插件
 * `android/app/src/main/java/com/lasergrbl/android/UsbSerialPlugin.java`。
 *
 * 分工：**状态机不在本类**。写队列串行化、读线程 + 增量 UTF-8、`close()` 幂等、`closed` 只通知一次
 * 全部在 [SerialPortBase]（`:core`，可在 JVM 上单测）。USB 侧只做三件事：
 *  1. [list]：用 `UsbSerialProber` 枚举设备并按 v2 的规则拼装字段（[usbSerialDeviceInfo]）；
 *  2. [UsbSerialConnectionFactory]：实现 `PortConnectionFactory`，在 `create()` 里完成
 *     「找驱动 → 权限门禁 → `openDevice` → `port.open` → 8N1 → DTR/RTS」；
 *  3. [onOpened] / [onClosed]：注册与注销 `ACTION_USB_DEVICE_DETACHED` 拔出广播。
 *
 * ### 与 v2 `UsbSerialPlugin` 的逐条对齐
 * | 行为 | v2 | 本类 |
 * | --- | --- | --- |
 * | 设备枚举 | `UsbSerialProber.getDefaultProber().findAllDrivers(manager)` | [list] 相同 |
 * | 字段拼装 | `name` = 厂商 + 空格 +（产品名或设备名）；`vendor` = 厂商或 `0x%04X`；`product` 缺省空串 | [usbSerialDeviceInfo]（同一规则，抽成纯函数） |
 * | 打开顺序 | `findDriver` → 权限 → `ports.get(0)` → `openDevice` → `port.open` → `setParameters(baud,8,1,NONE)` → `setDTR(true)`/`setRTS(true)` | [UsbSerialConnectionFactory.create] 相同 |
 * | 权限 | `requestPermission` + 广播 + 20 s `CountDownLatch` | [UsbPermissionGate]（同一 action / PendingIntent / 判据，改挂起等待） |
 * | 失败清理 | `port.close()` + `connection.close()` 后 reject | 相同，且覆盖**所有**异常路径（不只是 IOException）；这是 `PortConnectionFactory` 的硬性契约 |
 * | 拔出 | `ACTION_USB_DEVICE_DETACHED`，只通知一次 | [onOpened] 注册、[onClosed] 注销，通知幂等由 [SerialPortBase] 的 teardown 保证 |
 * | 错误文案 | `"无法获取 UsbManager"` / `"未找到可用的串口驱动 (deviceId=…)"` / `"该 USB 设备没有可用串口"` / `"打开 USB 设备连接失败"` / `"初始化串口失败: …"` / `"未获得 USB 设备权限"` | 逐字相同 |
 *
 * ### 线程模型（Phase 4 接线必须遵守）
 * `SerialPortBase.open()` 是 `suspend`，但它调用的 `PortConnectionFactory.create()` 是**阻塞**
 * 函数，于是「枚举 → 权限 → 打开端口」整段同步跑在 `create()` 里：
 *
 *  1. 整段包在 `runBlocking(Dispatchers.IO)` 内，落在 IO 线程池上执行；
 *  2. 权限广播**不依赖主线程 Looper**：接收器注册时带了 [UsbBroadcastLooper]（后台
 *     `HandlerThread`），所以即使 `open()` 是在主线程被调用，广播也照样能送达，**不会死锁**；
 *  3. 但 `runBlocking` 会**阻塞调用线程**直到整段结束（首次连接要等用户点权限弹窗，最长
 *     `permissionTimeoutMillis`）。因此 Phase 4 接线**必须**把 `GrblCore.open()` 放在
 *     `withContext(Dispatchers.IO)` 上，不要在 `Dispatchers.Main` 上直接调，否则 UI 会卡住；
 *  4. [list] 自己是 `suspend` + `withContext(Dispatchers.IO)`，可以在任意线程调用。
 *
 * 为什么接收器要自带 Looper：[Context.registerReceiver] 不带 Handler 时 `onReceive` 在**进程主
 * 线程**执行；而本类可能在主线程被 `runBlocking` 阻塞着等权限结果 —— 主线程等结果、结果等主线程，
 * 这就是 v2 那套同步等待在 Kotlin 里最容易踩的死锁。把投递放到专属 `HandlerThread` 后，
 * 投递线程与阻塞线程互不相干，该死锁在结构上不可能发生。
 *
 * ### 无法离线验证（构建通过 ≠ 真机可用）
 * USB 设备的**枚举结果、权限弹窗、实际波特率、DTR/RTS 生效、拔出广播时机**都必须在真机上验；
 * `UsbDevice` 是 final 且没有公开构造函数，JVM 单测既不能构造也不能可靠替换。本类只保证源码级
 * 对齐 v2 与编译通过。
 *
 * @param context 任意 Context（内部只保留 `applicationContext`，不会泄漏 Activity）。
 * @param permissionTimeoutMillis USB 权限等待上限，默认 20 s（= v2 `PERMISSION_TIMEOUT_MS`）。
 */
class UsbSerialTransport(
    context: Context,
    permissionTimeoutMillis: Long = UsbPermissionGate.DEFAULT_TIMEOUT_MILLIS
) : SerialPortBase(
    TransportKind.Usb,
    UsbSerialConnectionFactory(context.applicationContext, permissionTimeoutMillis)
) {

    private val appContext: Context = context.applicationContext

    /** 当前监听的设备编号（[NO_DEVICE] 表示未打开）；拔出广播按它过滤。 */
    @Volatile
    private var watchedDeviceId: Int = NO_DEVICE

    /** 拔出广播接收器；null 表示未注册（注册/注销都在 [detachLock] 内，保证幂等）。 */
    private var detachReceiver: BroadcastReceiver? = null

    private val detachLock = Any()

    // ==================== 设备枚举 ====================

    /**
     * 枚举当前可识别的 USB 串口设备（v2 插件的 `list`）。
     *
     * `:core` 的契约要求返回 [SerialDeviceInfo]（`GrblCore.listDevices()` 直接给 UI 用），
     * 所以字段拼装规则与 v2 的 `list` 完全一致，只是落到 [SerialDeviceInfo] 上 ——
     * 见 [usbSerialDeviceInfo]（纯函数，可离线单测）。
     *
     * 跑在 [Dispatchers.IO] 上：枚举要读 USB 描述符，属于阻塞调用。
     *
     * @throws IOException 取不到 `UsbManager` 时（v2 的 `"无法获取 UsbManager"`）。
     */
    override suspend fun list(): List<SerialDeviceInfo> = withContext(Dispatchers.IO) {
        serialDrivers(appContext).mapNotNull { it.toSerialDeviceInfo() }
    }

    // ==================== 拔出广播 ====================

    /** 连接建立后开始监听拔出（v2 的 `registerDetachReceiver`）。 */
    protected override fun onOpened(device: SerialDeviceInfo) {
        registerDetachReceiver(device.deviceId?.toInt() ?: device.id.toIntOrNull() ?: NO_DEVICE)
    }

    /** 连接关闭后注销监听（幂等；v2 的 `unregisterDetachReceiver`）。 */
    protected override fun onClosed() {
        unregisterDetachReceiver()
    }

    private fun registerDetachReceiver(deviceId: Int) {
        synchronized(detachLock) {
            watchedDeviceId = deviceId
            if (detachReceiver != null) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent == null || intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                    val detached = IntentCompat.getParcelableExtra(
                        intent,
                        UsbManager.EXTRA_DEVICE,
                        UsbDevice::class.java
                    )
                    // v2：广播带设备但不是当前设备 -> 忽略；没带设备且当前未打开 -> 忽略
                    if (!shouldHandleDetach(detached?.deviceId, watchedDeviceId)) return
                    onUsbDetached()
                }
            }
            try {
                registerUsbBroadcastReceiver(
                    context = appContext,
                    receiver = receiver,
                    filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
                    handler = UsbBroadcastLooper.handler()
                )
                detachReceiver = receiver
            } catch (failure: Throwable) {
                // 注册失败不让 open 失败：读线程随后会因连接失效而走 read-failure 路径收尾
                recordError("注册 USB 拔出广播失败", failure)
            }
        }
    }

    private fun unregisterDetachReceiver() {
        val receiver = synchronized(detachLock) {
            detachReceiver.also { detachReceiver = null }
        } ?: return
        watchedDeviceId = NO_DEVICE
        try {
            appContext.unregisterReceiver(receiver)
        } catch (ignored: IllegalArgumentException) {
            // 未注册或已注销时忽略（v2 同样）
        }
    }

    /** 拔出 → 关连接 + 只通知一次（幂等与「只通知一次」由 [SerialPortBase] 保证）。 */
    private fun onUsbDetached() {
        handleExternalDisconnect("USB 设备已拔出")
    }

    companion object {

        /** 未打开任何设备时的 deviceId 哨兵（v2 的 `openedDeviceId = -1`）。 */
        private const val NO_DEVICE: Int = -1

        /**
         * 这条拔出广播是否该处理（纯函数，可离线单测）—— 逐条对应 v2 `detachReceiver.onReceive`：
         *
         * * 广播里带了设备：只处理「就是当前打开的那台」；
         * * 广播里没带设备：当前未打开（[NO_DEVICE]）则忽略。
         *
         * @param detachedDeviceId 广播里 `EXTRA_DEVICE` 的 deviceId；null 表示没带。
         * @param currentDeviceId 当前监听的设备编号（未打开时为 [NO_DEVICE]）。
         */
        fun shouldHandleDetach(detachedDeviceId: Int?, currentDeviceId: Int): Boolean =
            if (detachedDeviceId != null) {
                detachedDeviceId == currentDeviceId
            } else {
                currentDeviceId >= 0
            }
    }
}

/**
 * USB 连接工厂：`PortConnectionFactory` 的真机实现，在 [create] 里完成
 * 「找驱动 → 权限门禁 → `openDevice` → `port.open` → 8N1 → DTR/RTS」。
 *
 * 顺序逐条照抄 v2 `UsbSerialPlugin.open()`；**失败即释放**是这个接口的硬性契约
 * （见 `PortConnection.kt` 的说明）：`port.open` / `setParameters` / DTR-RTS 之后任何异常，
 * 都会先 `port.close()` + `connection.close()` 再把异常抛出，绝不把半开句柄留给 `SerialPortBase`。
 *
 * 整个 [create] 包在 `runBlocking(Dispatchers.IO)` 里（USB 枚举、`openDevice`、权限等待都是阻塞
 * 调用），线程模型的完整说明见 [UsbSerialTransport] 的类文档。
 *
 * @param appContext `applicationContext`（工厂活得比任何 Activity 久）。
 * @param permissionTimeoutMillis USB 权限等待上限（默认 20 s，见 [UsbPermissionGate]）。
 */
internal class UsbSerialConnectionFactory(
    private val appContext: Context,
    private val permissionTimeoutMillis: Long
) : PortConnectionFactory {

    override fun create(device: SerialDeviceInfo, baudRate: Int): PortConnection =
        runBlocking(Dispatchers.IO) { openOnIoThread(device, baudRate) }

    private suspend fun openOnIoThread(device: SerialDeviceInfo, baudRate: Int): PortConnection {
        val manager = requireUsbManager(appContext)
        val deviceId = device.deviceId?.toInt() ?: device.id.toIntOrNull()
            ?: throw IOException("USB 设备编号无效：${device.id}")

        // v2 的 findDriver(deviceId)：重新枚举一遍再按 deviceId 匹配（不信任调用方传来的名字）
        val driver = serialDrivers(appContext).firstOrNull { it.device?.deviceId == deviceId }
            ?: throw IOException("未找到可用的串口驱动 (deviceId=$deviceId)")
        val usbDevice = driver.device
            ?: throw IOException("未找到可用的串口驱动 (deviceId=$deviceId)")

        // v2 的 acquirePermission：已授权直接过，否则弹窗并最多等 20 s
        val gate = UsbPermissionGate(appContext, manager, UsbBroadcastLooper.handler())
        if (!gate.acquire(usbDevice, permissionTimeoutMillis)) {
            throw IOException("未获得 USB 设备权限")
        }

        val port = driver.ports.firstOrNull()
            ?: throw IOException("该 USB 设备没有可用串口")
        val connection = manager.openDevice(usbDevice)
            ?: throw IOException("打开 USB 设备连接失败")

        try {
            port.open(connection)
            // 8N1（v2 的 setParameters）
            port.setParameters(
                baudRate,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            // GRBL 常见需要 DTR/RTS；部分芯片不支持，忽略异常（v2 同样）
            try {
                port.setDTR(true)
                port.setRTS(true)
            } catch (ignored: IOException) {
                // 芯片不支持 DTR/RTS 控制时忽略
            }
        } catch (failure: Throwable) {
            // 任何失败路径都必须释放，不许泄漏（工厂拿不到半成品的兜底，见 PortConnectionFactory 契约）
            runCatching { port.close() }
            runCatching { connection.close() }
            throw if (failure is IOException) IOException("初始化串口失败: ${failure.message}") else failure
        }
        return UsbSerialPortConnection(port, connection)
    }
}

/** 取 `UsbManager`；取不到时按 v2 的文案报错。 */
private fun requireUsbManager(context: Context): UsbManager =
    context.getSystemService(Context.USB_SERVICE) as? UsbManager
        ?: throw IOException("无法获取 UsbManager")

/** 用默认 prober 枚举串口驱动（v2 的 `UsbSerialProber.getDefaultProber().findAllDrivers`）。 */
private fun serialDrivers(context: Context): List<UsbSerialDriver> =
    UsbSerialProber.getDefaultProber().findAllDrivers(requireUsbManager(context))

/** `UsbSerialDriver` → [SerialDeviceInfo]：只做字段搬运，规则全在 [usbSerialDeviceInfo] 里。 */
private fun UsbSerialDriver.toSerialDeviceInfo(): SerialDeviceInfo? {
    val device = this.device ?: return null
    return usbSerialDeviceInfo(
        deviceId = device.deviceId,
        vendorId = device.vendorId,
        productId = device.productId,
        productName = device.productName,
        manufacturerName = device.manufacturerName,
        deviceName = device.deviceName
    )
}

/**
 * v2 `UsbSerialPlugin.list()` 的字段拼装规则，抽成**纯函数**（不碰任何 Android 类型），
 * 这样这条命名规则可以在 JVM 单测里被真实覆盖 —— `UsbDevice` 是 final 且无法构造，
 * 规则留在驱动对象上就永远测不到。
 *
 * 逐条对应 v2：
 * ```java
 * String name = (productName != null && !productName.isEmpty()) ? productName : device.getDeviceName();
 * if (manufacturer != null && !manufacturer.isEmpty()) name = manufacturer + " " + name;
 * info.put("vendor", manufacturer != null ? manufacturer : String.format("0x%04X", device.getVendorId()));
 * info.put("product", productName != null ? productName : "");
 * ```
 *
 * 与 v2 的唯一有意差异：`String.format` 固定用 [Locale.US]（v2 用默认 Locale）。`%04X` 在部分
 * 语言环境会输出非 ASCII 数字，固定 Locale 只会让输出更稳定，`"0x1A86"` 这类结果与 v2 相同。
 *
 * @param deviceId USB 数字设备编号（`:core` 侧作为稳定 id 的字符串形式）。
 * @param productName `UsbDevice.getProductName()`，可能为 null/空串（此时退回 deviceName）。
 * @param manufacturerName `UsbDevice.getManufacturerName()`，非空时作为 name 前缀与 vendor。
 * @param deviceName `UsbDevice.getDeviceName()`，兜底名称（`/dev/bus/usb/001/002` 形式）。
 */
fun usbSerialDeviceInfo(
    deviceId: Int,
    vendorId: Int,
    productId: Int,
    productName: String?,
    manufacturerName: String?,
    deviceName: String?
): SerialDeviceInfo {
    val baseName = if (!productName.isNullOrEmpty()) productName else deviceName
    val name = if (!manufacturerName.isNullOrEmpty()) "$manufacturerName $baseName" else baseName
    return SerialDeviceInfo(
        // id 用 deviceId 的字符串形式：`:core` 的 SerialDeviceInfo.id 是「稳定唯一标识」
        id = deviceId.toString(),
        kind = TransportKind.Usb,
        name = name.orEmpty(),
        deviceId = deviceId.toDouble(),
        address = null,
        vendor = manufacturerName ?: String.format(Locale.US, "0x%04X", vendorId),
        product = productName ?: "",
        vendorId = vendorId.toDouble(),
        productId = productId.toDouble()
    )
}

/**
 * 广播投递用的后台 Looper：惰性创建、USB 路径内共享、**不主动退出**。
 *
 * 为什么不退出：每次 `open()` 都要用它（权限结果 + 拔出广播），退出了下次还得重建；空闲的
 * `HandlerThread` 阻塞在 `epoll` 上，不占 CPU。线程名固定，ANR trace 里一眼能认出是谁。
 *
 * 权限结果与拔出广播共用同一条投递线程：两者都不会长时间占用它（`onReceive` 里最重的是拔出
 * 之后的收尾，join 有 1 s 上限），共用比多起一条线程更省。
 */
private object UsbBroadcastLooper {

    @Volatile
    private var handler: Handler? = null

    /** 取得投递用 Handler（首次调用时起线程；多线程同时首调也只会创建一个）。 */
    fun handler(): Handler {
        handler?.let { return it }
        synchronized(this) {
            handler?.let { return it }
            val thread = HandlerThread(THREAD_NAME)
            thread.start()
            // HandlerThread.getLooper() 会等到 Looper 就绪，不会拿到 null
            val created = Handler(thread.looper)
            handler = created
            return created
        }
    }

    /** 广播投递线程名（ANR trace / systrace 里能看到，便于排查）。 */
    private const val THREAD_NAME: String = "UsbSerial-broadcast"
}
