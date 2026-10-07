package com.lasergrbl.android.keepalive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知权限门禁的**分支表**单测（纯逻辑，不碰系统状态、不需要 Robolectric）。
 *
 * 为什么值得单独测：`EngraveService` 的核心承诺是"**保活优先**"—— 即使没有通知权限，
 * 前台服务也必须照常启动（只是通知不可见）。这条承诺就落在 [NotificationPermissionGate.resolveVisibility]
 * 的判定顺序上：`Unsupported` 优先级最高、API 33 以下不做权限判断、
 * API 33 及以上缺权限时报 `MissingPermission`（而不是 `DisabledByUser`）。
 * 真实系统状态只有在真机上才有意义，所以判定被抽成纯函数，由这张表兜住。
 */
class NotificationPermissionGateTest {

    /** API 33（Android 13）= `POST_NOTIFICATIONS` 成为运行时权限的边界。 */
    private val api33 = 33

    /** API 32（Android 12L）= 最后一个不需要该权限的版本。 */
    private val api32 = 32

    @Test
    fun api32WithoutPermissionIsVisible() {
        // 33 以下没有 POST_NOTIFICATIONS 这个运行时权限，缺权限不该影响可见性
        assertEquals(
            NotificationPermissionGate.Visibility.Visible,
            NotificationPermissionGate.resolveVisibility(
                sdkInt = api32,
                permissionGranted = false,
                notificationsEnabled = true,
                hasNotificationManager = true
            )
        )
    }

    @Test
    fun api33WithoutPermissionIsMissingPermission() {
        assertEquals(
            NotificationPermissionGate.Visibility.MissingPermission,
            NotificationPermissionGate.resolveVisibility(
                sdkInt = api33,
                permissionGranted = false,
                notificationsEnabled = true,
                hasNotificationManager = true
            )
        )
    }

    @Test
    fun api33WithPermissionButDisabledNotificationsIsDisabledByUser() {
        assertEquals(
            NotificationPermissionGate.Visibility.DisabledByUser,
            NotificationPermissionGate.resolveVisibility(
                sdkInt = api33,
                permissionGranted = true,
                notificationsEnabled = false,
                hasNotificationManager = true
            )
        )
    }

    @Test
    fun api33WithPermissionAndEnabledIsVisible() {
        assertEquals(
            NotificationPermissionGate.Visibility.Visible,
            NotificationPermissionGate.resolveVisibility(
                sdkInt = api33,
                permissionGranted = true,
                notificationsEnabled = true,
                hasNotificationManager = true
            )
        )
    }

    @Test
    fun missingNotificationManagerWinsOverEverything() {
        // 极少数定制系统取不到 NotificationManager：无论权限与开关如何，都判 Unsupported
        for (sdk in listOf(api32, api33)) {
            for (granted in listOf(true, false)) {
                for (enabled in listOf(true, false)) {
                    assertEquals(
                        "sdk=$sdk granted=$granted enabled=$enabled 时应当 Unsupported",
                        NotificationPermissionGate.Visibility.Unsupported,
                        NotificationPermissionGate.resolveVisibility(
                            sdkInt = sdk,
                            permissionGranted = granted,
                            notificationsEnabled = enabled,
                            hasNotificationManager = false
                        )
                    )
                }
            }
        }
    }

    @Test
    fun api32DisabledByUserIsStillDetected() {
        // 33 以下虽然没有运行时权限，但用户可以在系统设置里关掉通知 —— 这条也要报出来
        assertEquals(
            NotificationPermissionGate.Visibility.DisabledByUser,
            NotificationPermissionGate.resolveVisibility(
                sdkInt = api32,
                permissionGranted = true,
                notificationsEnabled = false,
                hasNotificationManager = true
            )
        )
    }

    @Test
    fun permissionRequestCodeDoesNotCollideWithUsbPermissionFlow() {
        // USB 权限走 PendingIntent + 广播（不占 requestCode）；这里固定 1001 只用于通知权限
        assertEquals(1001, NotificationPermissionGate.REQUEST_CODE)
    }
}
