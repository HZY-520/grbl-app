package com.lasergrbl.core.grbl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 应用级设置（本地持久化）—— 移植自 v2 `src/core/grbl/GrblConfig.ts`（对应 LaserGRBL 的 `Settings.cs`）。
 *
 * 与 v2 的差异（有意为之，且是平台必需）：
 *  v2 直接读写浏览器 `localStorage`；Kotlin 侧改成 [SettingsPersistence] 接口，
 *  Android 由 `:app` 用 SharedPreferences/DataStore 实现，测试用内存实现。
 *  序列化格式与 v2 一致（一个 JSON 对象），方便日后做数据迁移。
 */

const val SETTINGS_STORAGE_KEY = "lasergrbl.settings"

/** 设置项的默认值（对应 LaserGRBL Settings 的默认值，共 28 项）。 */
val DEFAULT_SETTINGS: Map<String, Any> = linkedMapOf(
    "Firmware Type" to Firmware.Grbl.value,
    "Support Hardware PWM" to true,
    "Reset Grbl On Connect" to true,
    "Unidirectional Engraving" to false,
    "Disable G0 fast skip" to false,
    "Enable Continuous Jog" to false,
    "Serial Monitor" to true,
    "Show Program Comments" to true,
    "Show Program Commands" to true,
    "Threading Mode" to "Fast",
    "Mark Speed" to 1000,
    "Border Speed" to 1000,
    "Min Power" to 0,
    "Max Power" to 1000,
    "Laser On Command" to "M4",
    "Laser Off Command" to "M5",
    // 设备行程 (mm)：生成 G 代码时用于尺寸自适应
    "Travel X" to 300,
    "Travel Y" to 200,
    // 测试激光默认参数
    "Test Laser Power" to 200,
    "Test Laser Duration" to 300,
    "Jog Speed" to 1000,
    "Jog Step" to 1,
    "Last Port" to "",
    "Last Baud" to 115200,
    "Header" to "G90\nG0 X0 Y0",
    "Footer" to "M5\nG0 X0 Y0",
    "Auto Home On Connect" to false,
    "Language" to "zh-CN"
)

/** 本地持久化后端；Android 侧由 `:app` 提供实现。 */
interface SettingsPersistence {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/** 不做任何持久化的实现（测试/桌面调试用）。 */
object NoopSettingsPersistence : SettingsPersistence {
    override fun read(key: String): String? = null
    override fun write(key: String, value: String) = Unit
}

/** 内存实现（单元测试用）。 */
class InMemorySettingsPersistence(initial: Map<String, String> = emptyMap()) : SettingsPersistence {
    private val store = LinkedHashMap<String, String>(initial)
    override fun read(key: String): String? = store[key]
    override fun write(key: String, value: String) {
        store[key] = value
    }
}

private val SETTINGS_JSON = Json { ignoreUnknownKeys = true }

class SettingsStore(private val persistence: SettingsPersistence = NoopSettingsPersistence) {

    private var data: MutableMap<String, Any?> = LinkedHashMap(DEFAULT_SETTINGS)

    /** 主题。 */
    var theme: String = "dark"

    init {
        load()
    }

    fun load() {
        try {
            val raw = persistence.read(SETTINGS_STORAGE_KEY)
            if (!raw.isNullOrEmpty()) {
                val merged = LinkedHashMap<String, Any?>(DEFAULT_SETTINGS)
                SETTINGS_JSON.parseToJsonElement(raw).jsonObject.forEach { (key, value) ->
                    merged[key] = jsonToKotlin(value)
                }
                data = merged
            }
        } catch (_: Throwable) {
            data = LinkedHashMap(DEFAULT_SETTINGS)
        }
    }

    fun save() {
        try {
            persistence.write(SETTINGS_STORAGE_KEY, kotlinToJson(data).toString())
        } catch (_: Throwable) {
            // ignore
        }
    }

    /** 与 v2 的 `get<T>(key, def)` 对应：null / undefined 都回退到默认值。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, def: T): T {
        val v = data[key]
        return (if (v == null) def else v) as T
    }

    fun set(key: String, value: Any?) {
        data[key] = value
        save()
    }

    /** 与 v2 的 `all()` 对应（返回副本）。 */
    fun all(): Map<String, Any?> = LinkedHashMap(data)

    private fun jsonToKotlin(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.boolean
            element.doubleOrNull != null -> {
                val d = element.double
                // v2 的 JSON.parse 会把 1000 还原成 number；整数值保持 Int 便于比较与展示
                if (d == d.toLong().toDouble() && kotlin.math.abs(d) < 9.007199254740992E15) {
                    d.toLong().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
                } else {
                    d
                }
            }

            else -> element.content
        }

        else -> element.toString()
    }

    private fun kotlinToJson(map: Map<String, Any?>): JsonObject = JsonObject(
        map.mapValues { (_, value) -> kotlinToJsonValue(value) }
    )

    private fun kotlinToJsonValue(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value.toDouble())
        is String -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}

/** 全局设置（与 v2 的 `AppSettings` 对应）。`:app` 启动时注入真正的持久化实现。 */
var AppSettingsPersistence: SettingsPersistence = NoopSettingsPersistence

val AppSettings: SettingsStore by lazy { SettingsStore(AppSettingsPersistence) }

/** 激光开指令。 */
fun laserOn(): String = AppSettings.get("Laser On Command", "M4")

/** 激光关指令。 */
fun laserOff(): String = AppSettings.get("Laser Off Command", "M5")
