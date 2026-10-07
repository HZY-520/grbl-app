package com.lasergrbl.core.golden

import com.lasergrbl.core.grbl.AppSettings
import com.lasergrbl.core.grbl.DeviceProfiles
import com.lasergrbl.core.grbl.FitMode
import com.lasergrbl.core.grbl.InMemorySettingsPersistence
import com.lasergrbl.core.grbl.SettingsStore
import com.lasergrbl.core.grbl.checkGcodeWithinTravel
import com.lasergrbl.core.grbl.fitSizeToTravel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设备档案与行程自适应对照 v2 的黄金样本 `golden/fit-to-travel.json`。
 *
 * 覆盖：`fitSizeToTravel`（Fit/Clamp/None、四舍五入到 3 位、边界与非法值）、
 * `checkGcodeWithinTravel`（运动行识别、负机床坐标、0.001 容差）、内置机型预设、
 * 以及"未显式传行程时回退 AppSettings"这条链路。
 */
class GoldenFitToTravelTest {

    @Test
    fun fitCasesMatchOracle() {
        val cases = Golden.array("fit-to-travel", "fitCases")
        assertTrue(cases.isNotEmpty())
        for (element in cases) {
            val node = element.asObject()
            val name = Golden.string(node, "name")
            val input = Golden.objectAt(node, "input")
            val output = Golden.objectAt(node, "output")

            val widthMm = Golden.string(input, "widthMm").toDouble()
            val heightMm = Golden.string(input, "heightMm").toDouble()
            val opts = input["opts"]?.takeIf { it !is JsonNull }?.asObject()

            val result = fitSizeToTravel(
                widthMm = widthMm,
                heightMm = heightMm,
                travelX = opts?.let { Golden.doubleOrNull(it, "travelX") },
                travelY = opts?.let { Golden.doubleOrNull(it, "travelY") },
                mode = FitMode.fromValue(opts?.let { Golden.stringOrNull(it, "mode") })
            )

            assertEquals(Golden.double(output, "widthMm"), result.widthMm, 0.0, "[$name] widthMm")
            assertEquals(Golden.double(output, "heightMm"), result.heightMm, 0.0, "[$name] heightMm")
            assertEquals(Golden.boolean(output, "adjusted"), result.adjusted, "[$name] adjusted")
            assertEquals(Golden.stringOrNull(output, "message"), result.message, "[$name] message")
        }
    }

    @Test
    fun checkTravelCasesMatchOracle() {
        val cases = Golden.array("fit-to-travel", "checkTravelCases")
        assertTrue(cases.isNotEmpty())
        for (element in cases) {
            val node = element.asObject()
            val name = Golden.string(node, "name")
            val lines = Golden.strings(node, "lines")
            val opts = node["opts"]?.takeIf { it !is JsonNull }?.asObject()
            val output = Golden.objectAt(node, "output")

            val result = checkGcodeWithinTravel(
                lines = lines,
                travelX = opts?.let { Golden.doubleOrNull(it, "travelX") },
                travelY = opts?.let { Golden.doubleOrNull(it, "travelY") }
            )

            assertEquals(Golden.boolean(output, "ok"), result.ok, "[$name] ok")
            assertEquals(Golden.double(output, "maxX"), result.maxX, 0.0, "[$name] maxX")
            assertEquals(Golden.double(output, "maxY"), result.maxY, 0.0, "[$name] maxY")
            assertEquals(Golden.double(output, "minX"), result.minX, 0.0, "[$name] minX")
            assertEquals(Golden.double(output, "minY"), result.minY, 0.0, "[$name] minY")
            assertEquals(Golden.stringOrNull(output, "message"), result.message, "[$name] message")
        }
    }

    @Test
    fun builtinProfilesMatchOracle() {
        val expected = Golden.objectsAt(Golden.result("fit-to-travel"), "builtinProfiles")
        val actual = DeviceProfiles.builtinProfiles()
        assertEquals(expected.size, actual.size, "内置机型数量")

        expected.forEachIndexed { index, node ->
            val e = actual[index]
            val label = Golden.string(node, "id")
            assertEquals(label, e.id, "[$index] id")
            assertEquals(Golden.string(node, "name"), e.name, "[$label] name")
            assertEquals(Golden.double(node, "travelX"), e.travelX, 0.0, "[$label] travelX")
            assertEquals(Golden.double(node, "travelY"), e.travelY, 0.0, "[$label] travelY")
            assertEquals(Golden.string(node, "firmware"), e.firmware, "[$label] firmware")
            assertEquals(Golden.double(node, "maxPower"), e.maxPower, 0.0, "[$label] maxPower")
            assertEquals(Golden.double(node, "minPower"), e.minPower, 0.0, "[$label] minPower")
            assertEquals(Golden.double(node, "markSpeed"), e.markSpeed, 0.0, "[$label] markSpeed")
            assertEquals(Golden.double(node, "travelSpeed"), e.travelSpeed, 0.0, "[$label] travelSpeed")
            assertEquals(Golden.double(node, "quality"), e.quality, 0.0, "[$label] quality")
            assertEquals(Golden.double(node, "baud"), e.baud, 0.0, "[$label] baud")
            assertEquals(Golden.boolean(node, "active"), e.active, "[$label] active")
            assertEquals(Golden.double(node, "createdAt"), e.createdAt, 0.0, "[$label] createdAt")
        }
    }

    /**
     * 未显式传行程时回退 AppSettings（样本的 `viaAppSettings` / `restoredDefaults`）。
     *
     * 样本只记录了**输出**，没有记录当时的输入行与传入尺寸，所以这里用能唯一复现该输出的输入：
     * 行程 500×400 时 (600,450) 等比缩放 → (500,375)；限幅 → (500,400)。
     */
    @Test
    fun travelFallsBackToAppSettings() {
        val fixture = Golden.result("fit-to-travel")
        val via = Golden.objectAt(fixture, "viaAppSettings")
        val restored = Golden.objectAt(fixture, "restoredDefaults")
        val defaults = Golden.objectAt(restored, "settingsDefaults")
        val store = SettingsStore(InMemorySettingsPersistence())

        // 样本声明的默认行程
        assertEquals(Golden.int(defaults, "travelX"), AppSettings.get("Travel X", -1), "默认行程 X")
        assertEquals(Golden.int(defaults, "travelY"), AppSettings.get("Travel Y", -1), "默认行程 Y")

        store.set("Travel X", 500)
        store.set("Travel Y", 400)

        val fit = fitSizeToTravel(600.0, 450.0, settings = store)
        val fitNode = Golden.objectAt(via, "fit")
        assertEquals(Golden.double(fitNode, "widthMm"), fit.widthMm, 0.0)
        assertEquals(Golden.double(fitNode, "heightMm"), fit.heightMm, 0.0)
        assertEquals(Golden.boolean(fitNode, "adjusted"), fit.adjusted)
        assertEquals(Golden.string(fitNode, "message"), fit.message, "走 AppSettings 的提示文案")

        val clamp = fitSizeToTravel(600.0, 450.0, mode = FitMode.Clamp, settings = store)
        val clampNode = Golden.objectAt(via, "clamp")
        assertEquals(Golden.double(clampNode, "widthMm"), clamp.widthMm, 0.0)
        assertEquals(Golden.double(clampNode, "heightMm"), clamp.heightMm, 0.0)
        assertEquals(Golden.string(clampNode, "message"), clamp.message)

        val check = checkGcodeWithinTravel(listOf("G0 X450 Y10", "G1 X600 Y350"), settings = store)
        val checkNode = Golden.objectAt(via, "check")
        assertEquals(Golden.boolean(checkNode, "ok"), check.ok)
        assertEquals(Golden.double(checkNode, "maxX"), check.maxX, 0.0)
        assertEquals(Golden.double(checkNode, "maxY"), check.maxY, 0.0)
        assertEquals(Golden.string(checkNode, "message"), check.message)

        // 显式传入的行程优先于 AppSettings（仍是 300×200 的文案）
        val explicit = fitSizeToTravel(400.0, 300.0, travelX = 300.0, travelY = 200.0, settings = store)
        val explicitNode = Golden.objectAt(via, "explicitOverridesSettings")
        assertEquals(Golden.double(explicitNode, "widthMm"), explicit.widthMm, 0.0)
        assertEquals(Golden.double(explicitNode, "heightMm"), explicit.heightMm, 0.0)
        assertEquals(Golden.string(explicitNode, "message"), explicit.message)

        // 恢复默认后再跑一次（样本的 restoredDefaults）
        store.set("Travel X", 300)
        store.set("Travel Y", 200)
        val afterRestore = fitSizeToTravel(400.0, 300.0, settings = store)
        val restoredFit = Golden.objectAt(restored, "fit")
        assertEquals(Golden.double(restoredFit, "widthMm"), afterRestore.widthMm, 0.0)
        assertEquals(Golden.double(restoredFit, "heightMm"), afterRestore.heightMm, 0.0)
        assertEquals(Golden.string(restoredFit, "message"), afterRestore.message)
    }

    @Test
    fun freshStorageStaysEmpty() {
        val expectation = Golden.objectAt(Golden.result("fit-to-travel"), "storageAfterRun")
        val previous = DeviceProfiles.persistence
        try {
            DeviceProfiles.persistence = InMemorySettingsPersistence()
            assertEquals(0, DeviceProfiles.listSavedProfiles().size, "savedProfiles")
            assertEquals(0, DeviceProfiles.listKnownDeviceIds().size, "knownDeviceIds")
            assertEquals(
                Golden.boolean(expectation, "deviceKnown"),
                DeviceProfiles.isDeviceKnown(7.0),
                "deviceKnown"
            )
        } finally {
            DeviceProfiles.persistence = previous
        }
    }
}
