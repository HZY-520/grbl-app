package com.lasergrbl.android.keepalive

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 通知权限门禁（`POST_NOTIFICATIONS`，Android 13 / API 33 起为运行时权限）。
 *
 * 与 v2 `KeepAlivePlugin` 的语义一致，核心原则是**保活优先**：
 *  * API < 33：无需权限，直接视为可见；
 *  * API >= 33：未授权时**照常启动前台服务**（保活不依赖通知），只是通知不可见，
 *    由 [AndroidNativeKeepAlive] 把 `notification = denied` 报给 `:core`，
 *    是否提示用户交给 Phase 4 的连接/首页界面决定。
 *
 * 判定逻辑拆成纯函数 [resolveVisibility]（版本门禁 + 权限状态 + 通知开关的组合），
 * 于是可以在 **JVM 单测**里逐条断言 `api33NoPermission_denied` 这类分支，不必起模拟器。
 */
object NotificationPermissionGate {

    /** 通知可见性判定结果。 */
    enum class Visibility {
        /** 通知会显示。 */
        Visible,

        /** 缺少 `POST_NOTIFICATIONS` 运行时权限（服务仍保活）。 */
        MissingPermission,

        /** 用户在系统设置里关闭了本应用的通知（服务仍保活）。 */
        DisabledByUser,

        /** 系统不支持（取不到通知管理器）。 */
        Unsupported
    }

    /**
     * 纯逻辑判定（可单测）。
     *
     * @param sdkInt                 `Build.VERSION.SDK_INT`
     * @param permissionGranted      是否已授予 `POST_NOTIFICATIONS`
     * @param notificationsEnabled   系统/应用的通知开关是否开启
     * @param hasNotificationManager 是否能取到 `NotificationManager`
     */
    fun resolveVisibility(
        sdkInt: Int,
        permissionGranted: Boolean,
        notificationsEnabled: Boolean,
        hasNotificationManager: Boolean
    ): Visibility = when {
        !hasNotificationManager -> Visibility.Unsupported
        sdkInt >= Build.VERSION_CODES.TIRAMISU && !permissionGranted -> Visibility.MissingPermission
        !notificationsEnabled -> Visibility.DisabledByUser
        else -> Visibility.Visible
    }

    /** 运行时判定（读系统状态）。 */
    fun visibility(context: Context): Visibility {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return resolveVisibility(
            sdkInt = Build.VERSION.SDK_INT,
            permissionGranted = granted,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            hasNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) != null
        )
    }

    /** 通知当前是否可见（[AndroidNativeKeepAlive] 用它填 `KeepAliveResult`）。 */
    fun isNotificationVisible(context: Context): Boolean = visibility(context) == Visibility.Visible

    /**
     * 需要时发起权限申请（**只在有前台 Activity 时**；结果由系统回调，本方法不阻塞）。
     *
     * v2 走 Capacitor 的 `requestPermissionForAlias`，3.0 直接用平台 API；
     * Phase 4 的首页/连接页在开始雕刻前调用它即可。拿不到 Activity 时什么也不做 ——
     * 前台服务照常启动（保活优先），通知可能不可见。
     */
    fun requestIfNeeded(activity: Activity?) {
        val target = activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (visibility(target) != Visibility.MissingPermission) return
        ActivityCompat.requestPermissions(target, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
    }

    /** 权限申请请求码（v2 由 Capacitor 分配，这里固定一个不与其它功能冲突的值）。 */
    const val REQUEST_CODE = 1001
}
