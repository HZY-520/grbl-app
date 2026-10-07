package com.lasergrbl.core.golden

import com.lasergrbl.core.grbl.GrblVersionInfo
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GrblVersionInfo` 对照 v2 的黄金样本。
 *
 * 样本里的 banner 解析（`parseVersionBanner` / `parseVerMessage`）属于 GrblCore 的职责，等那部分移植时再验；
 * 这里只验**构造函数派生**的所有字段（厂商判定、固件号、比较运算），这些现在就能全量比对。
 */
class GoldenGrblVersionTest {

    @Test
    fun constructorDerivedFieldsMatchOracle() {
        val banners = Golden.array("grbl-version", "banners")
        assertTrue(banners.isNotEmpty())

        var checked = 0
        for (case in banners) {
            val node = case.asObject()
            val args = node["constructorArgs"]?.asObject() ?: continue
            val expected = node["version"]?.asObject() ?: continue

            val version = GrblVersionInfo(
                major = Golden.int(args, "major"),
                minor = Golden.int(args, "minor"),
                build = Golden.stringOrNull(args, "build") ?: "",
                vendorInfo = Golden.stringOrNull(expected, "vendorInfo"),
                vendorVersion = Golden.stringOrNull(expected, "vendorVersion"),
                isHAL = Golden.boolean(expected, "isHAL")
            )

            val label = "${version} (${Golden.string(node, "line").take(40)})"
            assertEquals(Golden.int(expected, "major"), version.major, "major @ $label")
            assertEquals(Golden.int(expected, "minor"), version.minor, "minor @ $label")
            assertEquals(Golden.stringOrNull(expected, "build") ?: "", version.build, "build @ $label")
            assertEquals(Golden.boolean(expected, "isOrtur"), version.isOrtur, "isOrtur @ $label")
            assertEquals(Golden.boolean(expected, "isLonger"), version.isLonger, "isLonger @ $label")
            assertEquals(Golden.boolean(expected, "isLuckyWiFi"), version.isLuckyWiFi, "isLuckyWiFi @ $label")
            assertEquals(
                Golden.int(expected, "orturFWVersionNumber"),
                version.orturFWVersionNumber,
                "orturFWVersionNumber @ $label"
            )
            assertEquals(Golden.stringOrNull(expected, "machineName"), version.machineName, "machineName @ $label")
            assertEquals(Golden.string(expected, "toString"), version.toString(), "toString @ $label")
            checked++
        }
        assertTrue(checked > 0, "没有任何 banner 带 constructorArgs")
    }

    @Test
    fun comparisonsMatchOracle() {
        val comparisons = Golden.array("grbl-version", "comparisons")
        assertTrue(comparisons.isNotEmpty())

        for (case in comparisons) {
            val node = case.asObject()
            val left = versionOf(Golden.string(node, "left"))
            val vs = node["vs"]?.jsonArray ?: error("样本缺少 vs 数组")

            for (entry in vs) {
                val cmp = entry.asObject()
                val rightText = Golden.string(cmp, "right")
                val right = versionOf(rightText)
                assertEquals(
                    Golden.int(cmp, "compareTo"),
                    left.compareTo(right),
                    "${left}.compareTo($rightText)"
                )
                assertEquals(Golden.boolean(cmp, "gte"), left.gte(right), "${left}.gte($rightText)")
                assertEquals(Golden.boolean(cmp, "lt"), left.lt(right), "${left}.lt($rightText)")
                assertEquals(Golden.boolean(cmp, "equals"), left == right, "${left} == $rightText")
            }

            assertEquals(Golden.int(node, "compareToNull"), left.compareTo(null), "${left}.compareTo(null)")
            assertEquals(Golden.boolean(node, "equalsNull"), left == null, "${left} == null")
        }
    }

    /**
     * 样本里的版本写法是 `GrblVersionInfo.toString()`（即 `major.minor+build` 拼起来的 key），
     * 例如 `"1.120190825"` 其实是 `GrblVersionInfo(1, 1, "20190825")`。
     * 这组映射照抄 `tools/golden/generate.ts` 的 refs 定义，不要用正则去猜。
     */
    private fun versionOf(spec: String): GrblVersionInfo = when (spec) {
        "1.1" -> GrblVersionInfo(1, 1)
        "1.1h" -> GrblVersionInfo(1, 1, "h")
        "1.120190825" -> GrblVersionInfo(1, 1, "20190825")
        "1.0c" -> GrblVersionInfo(1, 0, "c")
        "0.9" -> GrblVersionInfo(0, 9)
        "2.0" -> GrblVersionInfo(2, 0)
        else -> error("样本里出现了未登记的版本写法：$spec（请同步 tools/golden/generate.ts）")
    }
}
