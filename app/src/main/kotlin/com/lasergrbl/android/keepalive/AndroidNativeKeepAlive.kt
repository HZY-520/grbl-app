package com.lasergrbl.android.keepalive

import android.content.Context
import com.lasergrbl.core.native.KeepAliveNotification
import com.lasergrbl.core.native.KeepAliveResult
import com.lasergrbl.core.native.NativeKeepAlive

/**
 * `:core` 的 [NativeKeepAlive] 接缝在 Android 侧的实现 —— 把「开始/更新/停止保活」
 * 翻译成 [EngraveService] 的启停与通知刷新。
 *
 * 设计要点：
 *  * 只做**翻译**，不做节流：节流（1% 阈值、`active` 状态）在 `:core` 的
 *    `KeepAliveController` 里，因此可以在 JVM 单测里逐条断言（见 `KeepAliveControllerTest`）；
 *  * 持 `applicationContext`，不持有 Activity（避免泄漏）；所有方法都是幂等的，
 *    服务被系统回收时 `update` / `stop` 静默忽略，绝不让保活失败拖垮雕刻流程。
 */
class AndroidNativeKeepAlive(context: Context) : NativeKeepAlive {

    private val appContext: Context = context.applicationContext

    override suspend fun start(title: String, text: String, progress: Int): KeepAliveResult {
        EngraveService.start(appContext, title, text, progress)
        val visible = NotificationPermissionGate.isNotificationVisible(appContext)
        return KeepAliveResult(
            running = true,
            notification = if (visible) KeepAliveNotification.Shown else KeepAliveNotification.Denied,
            reason = if (visible) null else "未获得通知权限，前台服务已启动但通知不可见"
        )
    }

    override suspend fun update(text: String, progress: Int): KeepAliveResult {
        EngraveService.update(text, progress)
        val visible = NotificationPermissionGate.isNotificationVisible(appContext)
        return KeepAliveResult(
            running = EngraveService.isRunning(),
            notification = if (visible) KeepAliveNotification.Shown else KeepAliveNotification.Denied
        )
    }

    override suspend fun stop(): KeepAliveResult {
        EngraveService.stop(appContext)
        return KeepAliveResult(running = false, notification = KeepAliveNotification.Shown)
    }
}
