package com.lasergrbl.android

import android.app.Activity
import android.app.Application
import android.content.Context
import com.lasergrbl.android.app.AppContainerHolder
import com.lasergrbl.android.keepalive.AndroidNativeKeepAlive
import com.lasergrbl.android.platform.AndroidSettingsPersistence
import com.lasergrbl.android.serial.BluetoothSerialTransport
import com.lasergrbl.android.serial.UsbSerialTransport
import com.lasergrbl.core.grbl.AppSettingsPersistence
import com.lasergrbl.core.grbl.DeviceProfiles
import com.lasergrbl.core.grbl.GrblCore
import com.lasergrbl.core.native.KeepAliveController
import com.lasergrbl.core.serial.SerialTransport
import com.lasergrbl.core.serial.TransportKind
import java.lang.ref.WeakReference

/**
 * 应用入口：把平台能力注入 `:core`。
 *
 * `:core` 是纯 Kotlin（KMP），不持有 Android 依赖；三类平台能力都通过接缝在这里接上：
 *  * 设置 / 设备档案持久化 → `SettingsPersistence`（`AppSettings` 是 lazy 的，
 *    所以必须在**任何读取设置之前**完成注入，时机就是 `onCreate`）；
 *  * 后台保活 → `NativeKeepAlive` → [EngraveService][com.lasergrbl.android.keepalive.EngraveService]；
 *  * 串口传输 → [GrblCore] 的 `transportFactory`（USB / 蓝牙）。
 *
 * USB / 蓝牙传输层需要 `Context`，但只在真正连接设备时才用得到，
 * 因此不在进程启动时构造（避免冷启动就去枚举 USB/蓝牙设备）—— 见 [usbTransport] / [bluetoothTransport]。
 */
class IgRblApplication : Application() {

    /** 后台保活：`:core` 的节流策略 + Android 前台服务（惰性构造）。 */
    val keepAlive: KeepAliveController by lazy { KeepAliveController(AndroidNativeKeepAlive(this)) }

    /**
     * USB 串口传输层（惰性：只在真的要连设备时才建，冷启动不去枚举 USB 设备）。
     *
     * 用户界面通过 [grblCore] 使用它，不要自己再 new 一个 —— 同一个进程里
     * 两个 `UsbSerialTransport` 会各自注册拔出广播、争抢同一个 USB 句柄。
     */
    val usbTransport: SerialTransport by lazy { UsbSerialTransport(this) }

    /** 蓝牙 SPP 传输层（惰性，理由同上）。 */
    val bluetoothTransport: SerialTransport by lazy { BluetoothSerialTransport(this) }

    /**
     * `:core` 的 GRBL 核心实例：把两个串口传输层注入进去。
     *
     * 对应 v2 在 `GrblCore` 构造时自己 `createTransport(kind)`；3.0 改成**由平台注入**，
     * 于是 `:core` 不必知道 Android 的存在（见 `docs/UI-3.0-PLAN.md` §6）。
     */
    val grblCore: GrblCore by lazy {
        GrblCore(transportFactory = { kind ->
            when (kind) {
                TransportKind.Usb -> usbTransport
                TransportKind.Bluetooth -> bluetoothTransport
            }
        })
    }

    /** 当前前台 Activity 的弱引用（USB / 蓝牙的权限弹窗需要它；只存弱引用不会泄漏）。 */
    private var activityRef: WeakReference<Activity>? = null

    /** 当前前台 Activity（可能为 null：应用在后台，或还没走到 `onCreate`）。 */
    val currentActivity: Activity? get() = activityRef?.get()

    override fun onCreate() {
        super.onCreate()
        appContext = this

        val persistence = AndroidSettingsPersistence(this)
        AppSettingsPersistence = persistence
        DeviceProfiles.persistence = persistence

        // Phase 4：装配界面容器（状态层 + 导航器 + Toast）。
        // 必须在这里而不是 Activity 里 —— 持久化刚注入完，且进程内只应存在一个容器。
        AppContainerHolder.create(this)
    }

    /** 由 Activity 在 `onCreate` 时登记。 */
    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    /** 由 Activity 在 `onDestroy` 时清理。 */
    fun detachActivity(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    companion object {
        /** 全局 Application 引用（USB / 蓝牙传输层的 Context 来源）。 */
        @Volatile
        private var appContext: IgRblApplication? = null

        /** 进程级 Application 实例；`onCreate` 之前为 null。 */
        val instance: IgRblApplication? get() = appContext

        /** 便利方法：拿应用上下文（拿不到时抛异常，调用方应确保在 `onCreate` 之后使用）。 */
        fun requireContext(): Context = appContext ?: error("IgRblApplication 尚未初始化")
    }
}
