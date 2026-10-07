package com.lasergrbl.android.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * USB 设备权限门禁 —— 逐条对应 v2 `UsbSerialPlugin.acquirePermission`。
 *
 * v2 的语义：**每台 USB 设备**单独授权（不是清单权限），已授权就直接放行；否则注册
 * `ACTION_USB_PERMISSION` 广播、用 `UsbManager.requestPermission` 弹系统对话框，最多等 20 s，
 * 结果取「广播里的 granted 或重新 `hasPermission` 确认」。
 *
 * | 行为 | v2 `UsbSerialPlugin` | 本类 |
 * | --- | --- | --- |
 * | 已授权 | `manager.hasPermission(device)` → true | [acquire] 第一行同样短路 |
 * | action | `"com.lasergrbl.android.USB_PERMISSION"` | [ACTION_USB_PERMISSION]（逐字相同） |
 * | 广播注册 | API 33+ 带 `RECEIVER_NOT_EXPORTED` | [register] 相同（并额外指定投递 Looper，见下） |
 * | PendingIntent | `getBroadcast(ctx, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)`，`intent.setPackage(包名)` | 相同 |
 * | 等待 | `CountDownLatch.await(20000, MS)` | [acquire] 的 `withTimeoutOrNull(timeoutMillis)`（默认 20 s） |
 * | 只认目标设备 | `received == null \|\| received.deviceId == device.deviceId` | [shouldAcceptPermissionResult]（同一判据，抽成纯函数便于单测） |
 * | 结果 | `permissionGranted \|\| manager.hasPermission(device)` | [resolvePermissionResult]（同一判据） |
 * | 收尾 | `finally` 里 `unregisterReceiver`，吞 `IllegalArgumentException` | [acquire] 的 `finally` 相同 |
 *
 * ### 投递线程（与 v2 的唯一有意差异）
 * `Context.registerReceiver` 不指定 Handler 时，`onReceive` 跑在**进程主线程**。而调用本门禁的
 * `UsbSerialTransport.openConnection` 是阻塞函数（见 [UsbSerialTransport] 的线程模型）：如果它
 * 恰好在主线程被调用，主线程会一直等到权限结果 —— 而权限结果又要主线程来投递，直接死锁。
 * 所以 [deliveryHandler] 允许调用方指定一个后台 Looper：`UsbSerialTransport` 传的是自己的
 * `HandlerThread`，投递线程与阻塞线程彻底解耦，这个死锁在结构上不可能发生。
 *
 * 传 `null`（默认）时行为与 v2 完全一致：主线程投递，调用方必须自己保证不在主线程阻塞等待。
 *
 * @param context 任意 Context（内部只保留 `applicationContext`，不会泄漏 Activity）。
 * @param usbManager 已经取到的 `UsbManager`（由 [UsbSerialTransport] 统一获取，避免重复查找）。
 * @param deliveryHandler 广播投递用的 Looper 载体；null 表示主线程投递（v2 语义）。
 */
class UsbPermissionGate(
    context: Context,
    private val usbManager: UsbManager,
    private val deliveryHandler: Handler? = null
) {

    private val appContext: Context = context.applicationContext

    /**
     * 取得（必要时请求）该 USB 设备的访问权限。
     *
     * 该函数是 `suspend` 的：等待期间**不占用任何线程**（挂起在 [CompletableDeferred] 上），
     * 由广播回调唤醒；超时由 `withTimeoutOrNull` 控制。
     *
     * @param device 目标设备（`UsbDevice` 无法在 JVM 单测里构造，所以这条路径只能真机验证）。
     * @param timeoutMillis 等待上限，默认 [DEFAULT_TIMEOUT_MILLIS]（= v2 `PERMISSION_TIMEOUT_MS`）。
     * @return 是否拿到权限；超时返回 false（与 v2 的 `latch.await` 超时后返回 false 一致）。
     */
    suspend fun acquire(device: UsbDevice, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Boolean {
        // v2：已有权限直接返回，不弹窗
        if (usbManager.hasPermission(device)) return true

        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent == null || intent.action != ACTION_USB_PERMISSION) return
                val received = IntentCompat.getParcelableExtra(
                    intent,
                    UsbManager.EXTRA_DEVICE,
                    UsbDevice::class.java
                )
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                // 只处理目标设备的结果（v2 的判据；抽成纯函数是为了能离线断言）
                if (!shouldAcceptPermissionResult(received?.deviceId, device.deviceId)) return
                // 重复广播时 complete 返回 false，无副作用
                result.complete(granted)
            }
        }

        try {
            register(receiver)
            val permissionIntent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                0,
                permissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, pendingIntent)
            val granted = withTimeoutOrNull(timeoutMillis) { result.await() } ?: false
            return resolvePermissionResult(granted, usbManager.hasPermission(device))
        } finally {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (ignored: IllegalArgumentException) {
                // 未注册或已注销（重复注销、注册失败后注销）时忽略 —— v2 同样
            }
        }
    }

    /** 注册接收器：API 33+ 带 `RECEIVER_NOT_EXPORTED`（本应用自己的 PendingIntent 才算数）。 */
    private fun register(receiver: BroadcastReceiver) {
        registerUsbBroadcastReceiver(
            context = appContext,
            receiver = receiver,
            filter = IntentFilter(ACTION_USB_PERMISSION),
            handler = deliveryHandler
        )
    }

    companion object {

        /**
         * USB 权限广播的唯一 action —— **与 v2 逐字相同**（`UsbSerialPlugin.ACTION_USB_PERMISSION`）。
         * 必须与 `UsbManager.requestPermission` 的 PendingIntent 里用的一致。
         */
        const val ACTION_USB_PERMISSION: String = "com.lasergrbl.android.USB_PERMISSION"

        /** v2 `PERMISSION_TIMEOUT_MS`：等待用户点「允许」的最长时间。 */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 20_000L

        /**
         * 是否采信这条权限广播（纯函数，可离线单测）。
         *
         * v2 的判据是 `received == null || received.getDeviceId() == device.getDeviceId()`：
         * 广播里没带设备（`received == null`）时按目标设备处理，带了别的设备则忽略。
         *
         * @param receivedDeviceId 广播里 `EXTRA_DEVICE` 的 deviceId；null 表示没带。
         * @param targetDeviceId 当前正在申请权限的设备。
         */
        fun shouldAcceptPermissionResult(receivedDeviceId: Int?, targetDeviceId: Int): Boolean =
            receivedDeviceId == null || receivedDeviceId == targetDeviceId

        /**
         * 权限结果的最终判据（纯函数，可离线单测）：广播说 granted，**或** `hasPermission` 复核为真。
         *
         * v2 就是 `permissionGranted || manager.hasPermission(device)`：有的机型广播里 granted
         * 会是 false，但权限其实已经授下来了，所以必须用 `hasPermission` 兜底。
         */
        fun resolvePermissionResult(granted: Boolean, hasPermission: Boolean): Boolean =
            granted || hasPermission
    }
}

/**
 * 注册一个「只接收本应用广播」的接收器，**并显式指定投递 Looper**。
 *
 * 与 v2 的写法对应：API 33（TIRAMISU）起带 `Context.RECEIVER_NOT_EXPORTED`，以下是普通注册。
 * 多出来的 `handler` 参数就是这条路径的关键：不指定 Handler 时 `onReceive` 在主线程执行，
 * 而调用方可能正阻塞在主线程等权限结果（死锁）；指定后台 Looper 后投递与阻塞互不相干。
 *
 * @param handler 投递 Looper 的载体；null 表示主线程投递（v2 语义）。
 */
internal fun registerUsbBroadcastReceiver(
    context: Context,
    receiver: BroadcastReceiver,
    filter: IntentFilter,
    handler: Handler?
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
    } else {
        // API 33 以下没有 flag 参数的重载，只能不带 flags 注册（v2 同样）
        context.registerReceiver(receiver, filter, null, handler)
    }
}
