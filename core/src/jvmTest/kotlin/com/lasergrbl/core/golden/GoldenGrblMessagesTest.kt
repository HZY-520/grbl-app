package com.lasergrbl.core.golden

import com.lasergrbl.core.grbl.GrblMessage
import com.lasergrbl.core.grbl.GrblVersionInfo
import com.lasergrbl.core.grbl.MessageType
import com.lasergrbl.core.grbl.alarmLookup
import com.lasergrbl.core.grbl.errorLookup
import com.lasergrbl.core.grbl.installDecodersForVersion
import com.lasergrbl.core.grbl.lookupGroupName
import com.lasergrbl.core.grbl.settingsLookup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GRBL 报文解码对照 v2 的黄金样本 `golden/grbl-messages.json`。
 *
 * 11 个「版本上下文」× 25 条真实响应行：分类（`MessageType`）、`nativeMessage`、解码前后的正文与提示、
 * `imageIndex`，以及三张表（设置项 / 报警码 / 错误码）在该上下文下选中的**解码组**与查表结果。
 *
 * 这条链路串起了 `parseVersionBanner` → `GrblVersionInfo` → `lookupGroupName` → `csvData` 三张表 → `GrblMessage.decode()`，
 * 是协议层最容易出错的地方（同一个 `$30` 在不同固件族下说明文字不同）。
 */
class GoldenGrblMessagesTest {

    @Test
    fun messageTypesMatchOracle() {
        val types = Golden.objectAt(Golden.result("grbl-messages"), "messageTypes")
        for (type in MessageType.entries) {
            assertEquals(
                Golden.string(types, type.value),
                type.value,
                "MessageType.${type.name}"
            )
        }
    }

    @Test
    fun everyContextMatchesOracle() {
        val contexts = Golden.objectsAt(Golden.result("grbl-messages"), "contexts")
        assertTrue(contexts.isNotEmpty())

        var messageChecks = 0
        var lookupChecks = 0

        for (context in contexts) {
            val label = Golden.string(context, "context")
            val version = versionOf(context["constructorArgs"])
            val expectedGroup = Golden.string(context, "lookupGroup")
            assertEquals(expectedGroup, lookupGroupName(version), "[$label] 解码组选择")

            // 1) 不解码：分类与原始字段
            val messages = context["messages"]?.let { jsonArrayOf(it) } ?: error("[$label] 缺少 messages")
            assertTrue(messages.isNotEmpty(), "[$label] messages 为空")
            assertTrue(messages.size == 25, "[$label] 期望 25 条响应行，实际 ${messages.size}")

            installDecodersForVersion(version)

            for (element in messages) {
                val node = element.asObject()
                val line = Golden.string(node, "line")

                val plain = GrblMessage.fromLine(line, decode = false)
                assertEquals(Golden.string(node, "type"), plain.type.value, "[$label] $line 分类")
                assertEquals(Golden.string(node, "nativeMessage"), plain.nativeMessage, "[$label] $line nativeMessage")
                assertEquals(Golden.string(node, "messageWithoutDecode"), plain.message, "[$label] $line 未解码正文")
                assertEquals(Golden.string(node, "tooltipWithoutDecode"), plain.tooltip, "[$label] $line 未解码提示")
                assertEquals(Golden.int(node, "imageIndex"), plain.imageIndex, "[$label] $line imageIndex")

                val decoded = GrblMessage.fromLine(line, decode = true)
                assertEquals(Golden.string(node, "decodedMessage"), decoded.message, "[$label] $line 解码正文")
                assertEquals(Golden.string(node, "decodedTooltip"), decoded.tooltip, "[$label] $line 解码提示")
                assertEquals(
                    Golden.string(node, "decodedGetDecodedMessage"),
                    decoded.getDecodedMessage(),
                    "[$label] $line getDecodedMessage()"
                )
                assertEquals(
                    Golden.string(node, "decodedGetNativeMessage"),
                    decoded.nativeMessage,
                    "[$label] $line getNativeMessage()"
                )
                messageChecks++
            }

            // 2) 设置项查表（3 个字段）
            for (element in context["settingLookup"]?.let { jsonArrayOf(it) } ?: emptyList()) {
                val node = element.asObject()
                val key = Golden.string(node, "key")
                val values = nullableStrings(node, "values")
                values.forEachIndexed { index, expected ->
                    assertEquals(expected, settingsLookup(key, index, version), "[$label] \$$key[$index]")
                    lookupChecks++
                }
            }

            // 3) 报警码查表（2 个字段）
            for (element in context["alarmLookup"]?.let { jsonArrayOf(it) } ?: emptyList()) {
                val node = element.asObject()
                val key = Golden.string(node, "key")
                val values = nullableStrings(node, "values")
                values.forEachIndexed { index, expected ->
                    assertEquals(expected, alarmLookup(key, index, version), "[$label] ALARM:$key[$index]")
                    lookupChecks++
                }
            }

            // 4) 错误码查表（样本取的是 index 1 = 长描述；index 0 是简短文案）
            for (element in context["errorLookup"]?.let { jsonArrayOf(it) } ?: emptyList()) {
                val node = element.asObject()
                val code = Golden.string(node, "code")
                assertEquals(
                    Golden.stringOrNull(node, "description"),
                    errorLookup(code, 1, version),
                    "[$label] error:$code"
                )
                lookupChecks++
            }
        }

        // 自检：覆盖规模（样本声明 totalMessageDecodings=275 / settingLookups=143 / alarmLookups=33）
        assertEquals(11 * 25, messageChecks, "解码消息总数")
        assertTrue(lookupChecks > 0, "查表次数")
    }

    /**
     * 样本里的 `constructorArgs` 是**位置数组**（对应生成器的 `makeVersion(args)`）：
     * `[major, minor, build?, vendorInfo?, vendorVersion?, isHAL?]`，或 `null`（表示无版本上下文）。
     */
    private fun versionOf(node: kotlinx.serialization.json.JsonElement?): GrblVersionInfo? {
        if (node == null || node is JsonNull) return null
        val arr = node as? JsonArray ?: error("constructorArgs 期望数组，实际是 $node")

        fun text(index: Int): String? {
            val element = arr.getOrNull(index) ?: return null
            if (element is JsonNull) return null
            return element.jsonPrimitive.content
        }

        return GrblVersionInfo(
            major = arr[0].jsonPrimitive.content.toInt(),
            minor = arr[1].jsonPrimitive.content.toInt(),
            build = text(2) ?: "",
            vendorInfo = text(3),
            vendorVersion = text(4),
            isHAL = text(5) == "true"
        )
    }
}

/** 把 `JsonElement` 收窄成列表（带清晰报错）。 */
internal fun jsonArrayOf(element: kotlinx.serialization.json.JsonElement): List<kotlinx.serialization.json.JsonElement> =
    element as? kotlinx.serialization.json.JsonArray ?: error("期望 JSON 数组，实际是 $element")

/**
 * 读字符串数组，**保留 JSON null 语义**。
 *
 * 夹具里“该固件族没有这一项”是用 `null` 表示的（例如 `$999` 在 v1.1 里查不到），
 * 而 `JsonPrimitive.content` 会把 `JsonNull` 读成字符串 `"null"` —— 必须区分开。
 */
private fun nullableStrings(node: kotlinx.serialization.json.JsonObject, key: String): List<String?> =
    (node[key] as? kotlinx.serialization.json.JsonArray ?: error("缺少数组字段 $key")).map { element ->
        if (element is JsonNull) null else element.jsonPrimitive.content
    }
