package com.lasergrbl.android.serial

import com.lasergrbl.core.serial.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `UsbSerialPlugin.list` 的字段拼装规则（v2 `UsbSerialPlugin.java:114-139`）的逐条回归。
 *
 * 这些规则被拆成纯函数 [usbSerialDeviceInfo]，所以能在 JVM 上直接比对 —— 不需要真机。
 * （本文件由旧的 `platform/UsbSerialDeviceInfoTest` 迁移而来：架构统一后只保留 `serial/` 这一套。）
 */
class UsbSerialDeviceInfoTest {

    @Test
    fun fullDeviceInfoMatchesV2List() {
        val info = usbSerialDeviceInfo(
            deviceId = 7,
            vendorId = 0x1A86,
            productId = 0x7523,
            productName = "USB Serial",
            manufacturerName = "wch.cn",
            deviceName = "/dev/bus/usb/001/002"
        )

        assertEquals("7", info.id)
        assertEquals(TransportKind.Usb, info.kind)
        assertEquals("wch.cn USB Serial", info.name)
        assertEquals(7.0, info.deviceId!!, 0.0)
        assertEquals("wch.cn", info.vendor)
        assertEquals("USB Serial", info.product)
        assertEquals(0x1A86.toDouble(), info.vendorId!!, 0.0)
        assertEquals(0x7523.toDouble(), info.productId!!, 0.0)
        assertNull(info.address)
    }

    /** 没有 productName 时用 deviceName（v2 的三元表达式）。 */
    @Test
    fun nameFallsBackToDeviceNameWithoutProductName() {
        val info = usbSerialDeviceInfo(3, 0x0403, 0x6001, null, "FTDI", "/dev/bus/usb/001/003")
        assertEquals("FTDI /dev/bus/usb/001/003", info.name)
        assertEquals("", info.product)
    }

    /** 空字符串同样触发回退（v2 判的是 `isEmpty()`，不是只判 null）。 */
    @Test
    fun emptyProductNameAlsoFallsBack() {
        val info = usbSerialDeviceInfo(3, 0x0403, 0x6001, "", "FTDI", "/dev/bus/usb/001/003")
        assertEquals("FTDI /dev/bus/usb/001/003", info.name)
        assertEquals("", info.product)
    }

    /** 没有厂商名时 vendor 退化成 0x%04X 形式的 VID。 */
    @Test
    fun vendorFallsBackToHexVendorId() {
        val info = usbSerialDeviceInfo(5, 0x10C4, 0xEA60, "CP2102 USB to UART Bridge", null, "/dev/x")
        assertEquals("0x10C4", info.vendor)
        assertEquals("CP2102 USB to UART Bridge", info.name)
    }

    /** v2 的原样语义：厂商名是空串（非 null）时 vendor 就是空串，不退化成 VID。 */
    @Test
    fun emptyManufacturerStaysEmptyLikeV2() {
        val info = usbSerialDeviceInfo(5, 0x10C4, 0xEA60, "CP2102", "", "/dev/x")
        assertEquals("", info.vendor)
        assertEquals("CP2102", info.name)
    }

    /** 厂商名与产品名都缺失：name 退化成空串（v2 的 JSObject 会给 JS 一个 null）。 */
    @Test
    fun missingEverythingYieldsEmptyName() {
        val info = usbSerialDeviceInfo(5, 0x10C4, 0xEA60, null, null, null)
        assertEquals("", info.name)
        assertEquals("", info.product)
        assertEquals("0x10C4", info.vendor)
    }

    /** 小 VID 也要补零到 4 位（`0x%04X`）。 */
    @Test
    fun smallVendorIdIsZeroPadded() {
        val info = usbSerialDeviceInfo(1, 0x067B, 0x2303, "PL2303", null, "/dev/x")
        assertEquals("0x067B", info.vendor)
    }
}
