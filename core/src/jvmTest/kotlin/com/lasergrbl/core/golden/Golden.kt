package com.lasergrbl.core.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 黄金样本加载器。
 *
 * 样本由 `tools/golden/generate.ts` 用 Node 直接跑 v2 的 TypeScript 实现生成，
 * 落在 `core/src/commonTest/resources/golden/` 下的 json 文件里，是移植正确性的唯一判据。
 */
object Golden {

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, JsonObject>()

    fun load(caseName: String): JsonObject = cache.getOrPut(caseName) {
        val stream = Golden::class.java.getResourceAsStream("/golden/$caseName.json")
            ?: error(
                "找不到黄金样本 golden/$caseName.json —— " +
                    "它应位于 core/src/commonTest/resources/golden/，可用 `node tools/golden/generate.ts` 重新生成"
            )
        json.parseToJsonElement(stream.bufferedReader(Charsets.UTF_8).readText()).jsonObject
    }

    /** 取 `result` 节点。 */
    fun result(caseName: String): JsonObject =
        load(caseName)["result"]?.jsonObject ?: error("golden/$caseName.json 没有 result 节点")

    /** 取 `result.<key>` 数组。 */
    fun array(caseName: String, key: String): JsonArray =
        result(caseName)[key]?.jsonArray ?: error("golden/$caseName.json 的 result.$key 不是数组")

    fun string(node: JsonObject, key: String): String =
        node[key]?.jsonPrimitive?.content ?: error("节点缺少字符串字段 $key")

    fun double(node: JsonObject, key: String): Double =
        node[key]?.jsonPrimitive?.content?.toDouble() ?: error("节点缺少数值字段 $key")

    fun int(node: JsonObject, key: String): Int =
        node[key]?.jsonPrimitive?.content?.toInt() ?: error("节点缺少整数字段 $key")

    fun boolean(node: JsonObject, key: String): Boolean =
        node[key]?.jsonPrimitive?.content?.toBoolean() ?: error("节点缺少布尔字段 $key")

    fun booleanOrNull(node: JsonObject, key: String): Boolean? {
        val element = node[key] ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.content.toBoolean()
    }

    /** 可空数值（样本里用 null 表示"没有该选项"）。 */
    fun doubleOrNull(node: JsonObject, key: String): Double? {
        val element = node[key] ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.content.toDoubleOrNull()
    }

    fun intOrNull(node: JsonObject, key: String): Int? {
        val element = node[key] ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.content.toIntOrNull()
    }

    fun stringOrNull(node: JsonObject, key: String): String? {
        val element = node[key] ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.content
    }

    /** 取字符串数组。 */
    fun strings(node: JsonObject, key: String): List<String> =
        (node[key]?.jsonArray ?: error("节点缺少数组字段 $key")).map { it.jsonPrimitive.content }

    /** 取子对象（缺失或类型不对时给出清晰报错）。 */
    fun objectAt(node: JsonObject, key: String): JsonObject {
        val element = node[key] ?: error("节点缺少对象字段 $key")
        return element as? JsonObject ?: error("字段 $key 不是 JSON 对象")
    }

    /** 取对象数组。 */
    fun objectsAt(node: JsonObject, key: String): List<JsonObject> {
        val array = node[key]?.jsonArray ?: error("节点缺少数组字段 $key")
        return array.map { it.asObject() }
    }
}

/** `JsonElement` → `JsonObject`（带清晰报错）。 */
fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: error("期望 JSON 对象，实际是 $this")
