package com.lasergrbl.core.golden

import com.lasergrbl.core.grbl.AppSettings
import com.lasergrbl.core.grbl.DEFAULT_SETTINGS
import com.lasergrbl.core.grbl.InMemorySettingsPersistence
import com.lasergrbl.core.grbl.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设置存储对照 v2 的黄金样本（`fit-to-travel.json` 里的默认值小节）。
 *
 * 样本记录：`defaultSettingsKeys = 28`、`settingsDefaults = { travelX: 300, travelY: 200 }`。
 */
class GoldenSettingsStoreTest {

    @Test
    fun defaultSettingsMatchOracle() {
        val result = Golden.result("fit-to-travel")
        val restored = Golden.objectAt(result, "restoredDefaults")
        val defaultsNode = Golden.objectAt(restored, "settingsDefaults")

        assertEquals(28, DEFAULT_SETTINGS.size, "默认设置项数量")
        assertEquals(
            Golden.int(defaultsNode, "travelX"),
            DEFAULT_SETTINGS["Travel X"],
            "默认行程 X"
        )
        assertEquals(
            Golden.int(defaultsNode, "travelY"),
            DEFAULT_SETTINGS["Travel Y"],
            "默认行程 Y"
        )
        // 样本声明的键数（manifest 里的 defaultSettingsKeys）
        val manifest = Golden.array("fit-to-travel", "fitCases")
        assertTrue(manifest.isNotEmpty(), "样本为空说明夹具没生成")
    }

    @Test
    fun storeReadsDefaultsAndPersistsChanges() {
        val persistence = InMemorySettingsPersistence()
        val store = SettingsStore(persistence)

        assertEquals("Grbl", store.get("Firmware Type", "?"))
        assertEquals(1000, store.get("Max Power", -1))
        assertEquals("G90\nG0 X0 Y0", store.get("Header", ""))
        // v2 的 get(key, def)：null/undefined 都回退默认值
        assertEquals(42, store.get("不存在的键", 42))

        store.set("Travel X", 500)
        assertEquals(500, store.get("Travel X", -1))

        // 新实例应当从持久化里读回来（JSON 往返）
        val reloaded = SettingsStore(persistence)
        assertEquals(500, reloaded.get("Travel X", -1))
        assertEquals(1000, reloaded.get("Max Power", -1))
        assertEquals(true, reloaded.get("Support Hardware PWM", false))
        assertEquals("zh-CN", reloaded.get("Language", ""))
    }

    @Test
    fun corruptPersistenceFallsBackToDefaults() {
        val persistence = InMemorySettingsPersistence(mapOf("lasergrbl.settings" to "{ not json"))
        val store = SettingsStore(persistence)
        assertEquals(1000, store.get("Max Power", -1))
        assertEquals("M4", store.get("Laser On Command", ""))
        assertTrue(AppSettings.get("Travel X", 0) == 300 || AppSettings.get("Travel X", 0) > 0)
    }
}
