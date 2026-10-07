package com.lasergrbl.android.serial

import com.lasergrbl.core.serial.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 `BluetoothSerialPlugin.doList`（`BluetoothSerialPlugin.java:244-283`）的设备列表映射规则回归。
 *
 * 规则被拆成纯函数 [bondedDevicesToSerialInfos]，输入是 [BondedDeviceSnapshot]（只有 address / name
 * 两个字段），所以这里不需要真机、也不需要构造 `BluetoothDevice`（它是 final，且 `getAddress()` /
 * `getName()` 在 JVM 单测里无法 stub）。
 */
class BluetoothDeviceMappingTest {

    private companion object {
        const val HC05 = "00:11:22:33:44:55"
        const val GRBL_MODULE = "AA:BB:CC:DD:EE:FF"
    }

    /** 有名字的设备：id / address 都是 MAC，kind 是蓝牙，USB 专有字段全为空。 */
    @Test
    fun mapsAddressAndName() {
        val infos = bondedDevicesToSerialInfos(listOf(BondedDeviceSnapshot(HC05, "HC-05")))

        assertEquals(1, infos.size)
        val info = infos[0]
        assertEquals(HC05, info.id)
        assertEquals(TransportKind.Bluetooth, info.kind)
        assertEquals("HC-05", info.name)
        assertEquals(HC05, info.address)
        assertNull(info.deviceId)
        assertNull(info.vendor)
        assertNull(info.product)
        assertNull(info.vendorId)
        assertNull(info.productId)
    }

    /** v2 的三元表达式：名字为 `null` 时用地址当名字。 */
    @Test
    fun nullNameFallsBackToAddress() {
        val infos = bondedDevicesToSerialInfos(listOf(BondedDeviceSnapshot(HC05, null)))
        assertEquals(HC05, infos[0].name)
    }

    /** v2 判的是 `isEmpty()`：空串同样退化成地址。 */
    @Test
    fun emptyNameFallsBackToAddress() {
        val infos = bondedDevicesToSerialInfos(listOf(BondedDeviceSnapshot(HC05, "")))
        assertEquals(HC05, infos[0].name)
    }

    /**
     * v2 用的是 `!name.isEmpty()` 而不是 `isNotBlank()`：纯空白名字**原样保留**。
     * 这一条用来锁住"别顺手把 isEmpty 改成 isBlank"。
     */
    @Test
    fun whitespaceNameIsKeptLikeV2() {
        val infos = bondedDevicesToSerialInfos(listOf(BondedDeviceSnapshot(HC05, " ")))
        assertEquals(" ", infos[0].name)
    }

    /** 按 address 去重：重复地址只保留第一次出现的那台（对应 v2 的 `Set<BluetoothDevice>`）。 */
    @Test
    fun deduplicatesByAddressKeepingFirstOccurrence() {
        val infos = bondedDevicesToSerialInfos(
            listOf(
                BondedDeviceSnapshot(HC05, "第一次"),
                BondedDeviceSnapshot(GRBL_MODULE, "另一台"),
                BondedDeviceSnapshot(HC05, "第二次")
            )
        )

        assertEquals(2, infos.size)
        assertEquals(HC05, infos[0].address)
        assertEquals("第一次", infos[0].name)
        assertEquals(GRBL_MODULE, infos[1].address)
    }

    /** 保持系统返回顺序（v2 直接遍历 `getBondedDevices()` 的 Set）。 */
    @Test
    fun preservesSystemOrder() {
        val infos = bondedDevicesToSerialInfos(
            listOf(
                BondedDeviceSnapshot("03:00:00:00:00:03", "三"),
                BondedDeviceSnapshot("01:00:00:00:00:01", "一"),
                BondedDeviceSnapshot("02:00:00:00:00:02", "二")
            )
        )

        assertEquals(listOf("三", "一", "二"), infos.map { it.name })
        assertEquals(
            listOf("03:00:00:00:00:03", "01:00:00:00:00:01", "02:00:00:00:00:02"),
            infos.map { it.address }
        )
    }

    /** 没有已配对设备时是空列表（v2 会回一个空的 devices 数组）。 */
    @Test
    fun emptyInputYieldsEmptyResult() {
        assertTrue(bondedDevicesToSerialInfos(emptyList()).isEmpty())
    }
}
