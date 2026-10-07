package com.lasergrbl.android.serial

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `BLUETOOTH_CONNECT` 版本门禁的判定规则回归 —— 逐条对应 v2 `withPermission` 的第一行
 * （`BluetoothSerialPlugin.java:202-204`：`SDK_INT < S || 已授权` 就直接放行）。
 *
 * 这是本次交付里**可以离线验证**的部分：`Build.VERSION.SDK_INT` 作为参数传入纯函数
 * [decideBluetoothConnectPermission]，所以 API 24 / 30 / 31 / 34 / 37 都能在这里跑。
 */
class BluetoothPermissionGateDecisionTest {

    /** API 31（Android 12）之前 `BLUETOOTH_CONNECT` 还不是运行时权限：无论是否"已授权"都放行。 */
    @Test
    fun apiBelow31NeedsNoRuntimePermission() {
        assertEquals(
            BluetoothConnectPermission.NotRequired,
            decideBluetoothConnectPermission(24, granted = false)
        )
        assertEquals(
            BluetoothConnectPermission.NotRequired,
            decideBluetoothConnectPermission(30, granted = false)
        )
    }

    /** 边界：30 → 放行，31 → 需要申请（差一个版本就换行为）。 */
    @Test
    fun api31IsTheBoundary() {
        assertEquals(
            BluetoothConnectPermission.NotRequired,
            decideBluetoothConnectPermission(30, granted = false)
        )
        assertEquals(
            BluetoothConnectPermission.MustRequest,
            decideBluetoothConnectPermission(31, granted = false)
        )
    }

    /** API >= 31 且未授权：必须弹系统权限框（v2 的 `requestPermissionForAlias`）。 */
    @Test
    fun apiAtLeast31WithoutPermissionMustRequest() {
        assertEquals(
            BluetoothConnectPermission.MustRequest,
            decideBluetoothConnectPermission(31, granted = false)
        )
        assertEquals(
            BluetoothConnectPermission.MustRequest,
            decideBluetoothConnectPermission(34, granted = false)
        )
        assertEquals(
            BluetoothConnectPermission.MustRequest,
            decideBluetoothConnectPermission(37, granted = false)
        )
    }

    /** API >= 31 且已授权：直接放行，不弹框。 */
    @Test
    fun apiAtLeast31WithPermissionIsGranted() {
        assertEquals(
            BluetoothConnectPermission.Granted,
            decideBluetoothConnectPermission(31, granted = true)
        )
        assertEquals(
            BluetoothConnectPermission.Granted,
            decideBluetoothConnectPermission(37, granted = true)
        )
    }

    /** 低版本上即使误报"已授权"也仍然走 NotRequired（`< S` 的分支在前，与 v2 的短路顺序一致）。 */
    @Test
    fun notRequiredBranchWinsOnLegacyApis() {
        assertEquals(
            BluetoothConnectPermission.NotRequired,
            decideBluetoothConnectPermission(24, granted = true)
        )
    }

    /** 权限被拒时的文案与 v2 一致（`BluetoothSerialPlugin.java:225` 的 `未获得蓝牙权限`）。 */
    @Test
    fun denialMessageMatchesV2() {
        assertEquals("未获得蓝牙权限", MESSAGE_BLUETOOTH_PERMISSION_DENIED)
    }
}
