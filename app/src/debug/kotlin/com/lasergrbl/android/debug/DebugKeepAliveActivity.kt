package com.lasergrbl.android.debug

import android.app.Activity
import android.os.Bundle
import com.lasergrbl.android.IgRblApplication
import com.lasergrbl.android.keepalive.EngraveService
import com.lasergrbl.core.native.formatText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * **仅 debug 构建**的保活/通知验证入口（`app/src/debug/`，不会进 release APK）。
 *
 * 为什么需要它：`EngraveService` 是 `exported="false"` 的（这是正确的安全配置），
 * 所以 `adb shell am start-foreground-service` 会被系统挡掉（"Requires permission not exported"），
 * 于是模拟器上**无法**从外部触发前台服务做验收。这个 Activity 在 debug 变体里
 * `exported="true"`，把 `KeepAliveController` 的真实调用链暴露给 adb：
 *
 * ```
 * adb shell am start -n com.lasergrbl.android/.debug.DebugKeepAliveActivity \
 *     -a com.lasergrbl.android.debug.KEEPALIVE_START --es name "job.nc" --ei percent 0
 * adb shell am start -n com.lasergrbl.android/.debug.DebugKeepAliveActivity \
 *     -a com.lasergrbl.android.debug.KEEPALIVE_PROGRESS --ei executed 42 --ei total 100
 * adb shell am start -n com.lasergrbl.android/.debug.DebugKeepAliveActivity \
 *     -a com.lasergrbl.android.debug.KEEPALIVE_STOP
 * ```
 *
 * 它走的路径与 Phase 4 的界面**完全一致**：`IgRblApplication.keepAlive`（= `KeepAliveController`
 * + `AndroidNativeKeepAlive`）→ `EngraveService` → 通知。因此模拟器上验证到的行为就是真机行为
 * （唯一的差别是通知权限是否已授予）。
 *
 * ⚠️ Phase 5 发布前请连同 `androidTest`/debug 目录一起复核：release 变体不包含本文件。
 */
class DebugKeepAliveActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as IgRblApplication
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val intent = intent

        when (intent?.action) {
            ACTION_START -> {
                val name = intent.getStringExtra(EXTRA_NAME) ?: "debug.nc"
                val percent = intent.getIntExtra(EXTRA_PERCENT, 0).toDouble()
                scope.launch { app.keepAlive.start(name, percent) }
            }
            ACTION_PROGRESS -> {
                val executed = intent.getIntExtra(EXTRA_EXECUTED, 0).toDouble()
                val total = intent.getIntExtra(EXTRA_TOTAL, 100).toDouble()
                scope.launch { app.keepAlive.updateProgress(executed, total) }
            }
            ACTION_FORMAT -> {
                // 纯格式化探针：验证 24 字符截断与 `文件名 · 百分比%` 文案（不需要前台服务）
                val name = intent.getStringExtra(EXTRA_NAME) ?: "debug.nc"
                val percent = intent.getIntExtra(EXTRA_PERCENT, 0).toDouble()
                android.util.Log.i(TAG, "formatText -> ${formatText(name, percent)}")
            }
            ACTION_STOP -> scope.launch { app.keepAlive.stop() }
            ACTION_LIST_DEVICES -> {
                // Phase 3 接线探针：走**真实**链路 IgRblApplication.grblCore → transportFactory
                // → UsbSerialTransport/BluetoothSerialTransport → Android 系统 API。
                // 模拟器没有 USB Host 串口设备，所以期望结果是"枚举成功但列表为空 / 蓝牙未开启报错"——
                // 重点验证的是**这条链路不崩、异常文案正确**，而不是真机识别结果。
                scope.launch {
                    val usb = runCatching { app.grblCore.listDevices() }
                    android.util.Log.i(
                        TAG,
                        "listDevices -> ${usb.fold({ "OK size=${it.size} $it" }, { "ERR ${it::class.simpleName}: ${it.message}" })}"
                    )
                    val bt = runCatching { app.grblCore.listBluetoothDevices() }
                    android.util.Log.i(
                        TAG,
                        "listBluetoothDevices -> ${bt.fold({ "OK size=${it.size} $it" }, { "ERR ${it::class.simpleName}: ${it.message}" })}"
                    )
                }
            }
            else -> android.util.Log.w(TAG, "未知 action: ${intent?.action}")
        }

        // 立刻结束自己：不进入任务栈，也不影响前台服务的生命周期
        finish()
    }

    companion object {
        const val TAG = "DebugKeepAlive"

        /** 通知 id（与 `EngraveService.NOTIFICATION_ID` 一致，便于 adb 断言时引用）。 */
        const val NOTIFICATION_ID = EngraveService.NOTIFICATION_ID

        const val ACTION_START = "com.lasergrbl.android.debug.KEEPALIVE_START"
        const val ACTION_PROGRESS = "com.lasergrbl.android.debug.KEEPALIVE_PROGRESS"
        const val ACTION_STOP = "com.lasergrbl.android.debug.KEEPALIVE_STOP"
        const val ACTION_FORMAT = "com.lasergrbl.android.debug.KEEPALIVE_FORMAT"

        /** 走真实链路枚举 USB / 蓝牙设备（Phase 3 接线探针）。 */
        const val ACTION_LIST_DEVICES = "com.lasergrbl.android.debug.LIST_DEVICES"

        const val EXTRA_NAME = "name"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_EXECUTED = "executed"
        const val EXTRA_TOTAL = "total"
    }
}
