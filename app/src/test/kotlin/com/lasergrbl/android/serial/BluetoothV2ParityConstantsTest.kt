package com.lasergrbl.android.serial

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 与 v2 `BluetoothSerialPlugin.java` 逐字对齐的**常量与文案**回归。
 *
 * 这些文案是用户可见的（`:core` 侧会把它当错误消息展示），也是"改代码顺手改了文案"这类回归
 * 最容易漏掉的地方；常量都在 [BluetoothSerialTransport] 同一个文件里，这里与 v2 的字面量逐一比对。
 */
class BluetoothV2ParityConstantsTest {

    /** v2 `BluetoothSerialPlugin.java:52`：经典蓝牙串口（SPP）标准 UUID。 */
    @Test
    fun sppUuidMatchesV2() {
        assertEquals(
            "00001101-0000-1000-8000-00805F9B34FB",
            BluetoothSerialTransport.SPP_UUID.toString().uppercase()
        )
    }

    /** v2 `BluetoothSerialPlugin.java:58`：SPP 不区分波特率，仅保持接口一致。 */
    @Test
    fun defaultBaudRateMatchesV2() {
        assertEquals(115200, BluetoothSerialTransport.DEFAULT_BAUD_RATE)
    }

    /** v2 `BluetoothSerialPlugin.java:247` / `:294`。 */
    @Test
    fun unsupportedMessageMatchesV2() {
        assertEquals("设备不支持蓝牙", MESSAGE_BLUETOOTH_UNSUPPORTED)
    }

    /** v2 `BluetoothSerialPlugin.java:251` / `:298`。 */
    @Test
    fun disabledMessageMatchesV2() {
        assertEquals("蓝牙未开启，请先开启蓝牙", MESSAGE_BLUETOOTH_DISABLED)
    }

    /** v2 `BluetoothSerialPlugin.java:259` / `:341`。 */
    @Test
    fun missingPermissionPrefixMatchesV2() {
        assertEquals("缺少蓝牙权限: ", PREFIX_BLUETOOTH_PERMISSION_MISSING)
        assertEquals(
            "缺少蓝牙权限: 用户拒绝",
            PREFIX_BLUETOOTH_PERMISSION_MISSING + "用户拒绝"
        )
    }

    /** v2 `BluetoothSerialPlugin.java:344`。 */
    @Test
    fun connectFailedPrefixMatchesV2() {
        assertEquals("连接蓝牙设备失败: ", PREFIX_BLUETOOTH_CONNECT_FAILED)
        assertEquals(
            "连接蓝牙设备失败: read failed, socket might closed",
            PREFIX_BLUETOOTH_CONNECT_FAILED + "read failed, socket might closed"
        )
    }

    /** v2 `BluetoothSerialPlugin.java:309`：地址非法时把地址原样带进文案。 */
    @Test
    fun invalidAddressPrefixMatchesV2() {
        assertEquals("无效的蓝牙地址: ", PREFIX_BLUETOOTH_INVALID_ADDRESS)
        assertEquals(
            "无效的蓝牙地址: 00:11:22:33:44:55",
            PREFIX_BLUETOOTH_INVALID_ADDRESS + "00:11:22:33:44:55"
        )
    }

    /** v2 `BluetoothSerialPlugin.java:185`。 */
    @Test
    fun openSettingsFailedPrefixMatchesV2() {
        assertEquals("无法打开蓝牙设置: ", PREFIX_BLUETOOTH_OPEN_SETTINGS_FAILED)
    }

    /** 断开原因（写进 `lastError`，Phase 4 的日志会展示）。 */
    @Test
    fun disconnectedReasonMatchesSpec() {
        assertEquals("蓝牙连接已断开", MESSAGE_BLUETOOTH_DISCONNECTED)
    }
}
