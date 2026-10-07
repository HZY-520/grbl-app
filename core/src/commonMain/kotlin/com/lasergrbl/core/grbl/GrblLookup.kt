package com.lasergrbl.core.grbl

/**
 * GRBL 版本解析与解码表选择 —— 移植自 v2 `src/core/grbl/GrblCore.ts` 里的三个纯函数
 * （`parseVersionBanner` / `parseVerMessage` / `lookupCode`）。
 *
 * ⚠️ 这三个函数在 `tools/golden/generate.ts` 里也有一份**逐字转录**（因为 GrblCore.ts 在 Node 里
 * 无法 import：模块加载时就会构造 Capacitor 串口传输）。两边的行为必须一致 —— `grbl-version`
 * 与 `grbl-messages` 两个夹具就是这条链路的判据。
 */

data class ParsedBanner(val major: Int, val minor: Int, val build: String)

data class ParsedVersionMessage(
    val major: Int,
    val minor: Int,
    val build: String,
    val vendorInfo: String?,
    val vendorVersion: String?
)

/** `Grbl 1.1f ['$' for help]` → (1, 1, "f")；匹配不到返回 null。 */
private val BANNER_REGEX = Regex("Grbl\\s+(\\d+)\\.(\\d+)([a-zA-Z])?", RegexOption.IGNORE_CASE)

fun parseVersionBanner(line: String): ParsedBanner? {
    val m = BANNER_REGEX.find(line) ?: return null
    return ParsedBanner(
        major = m.groupValues[1].toInt(),
        minor = m.groupValues[2].toInt(),
        build = m.groupValues[3]
    )
}

/** `(\d+)\.(\d+)([a-zA-Z])?`（用于 `[VER:...]` 的版本段）。 */
private val VER_REGEX = Regex("(\\d+)\\.(\\d+)([a-zA-Z])?")

/**
 * `[VER:1.1f.20170801:Vendor:1.7]` → (1, 1, "f.20170801"→去点, "Vendor", "1.7")。
 *
 * 细节（与 v2 一致）：版本段取前两个数字 + 可选字母，剩下的部分去掉前导 `.` 作为 build；
 * 若剩下为空则退回那个字母。
 */
fun parseVerMessage(line: String): ParsedVersionMessage? {
    if (line.length < 2) return null
    val body = line.substring(1, line.length - 1)
    val parts = body.split(':')
    if (parts.size < 2) return null
    val ver = parts[1]
    val m = VER_REGEX.find(ver) ?: return null
    val build = ver.substring(m.value.length).replace(LEADING_DOT, "")
    return ParsedVersionMessage(
        major = m.groupValues[1].toInt(),
        minor = m.groupValues[2].toInt(),
        build = build.ifEmpty { m.groupValues[3] },
        vendorInfo = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
        vendorVersion = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
    )
}

private val LEADING_DOT = Regex("^\\.")

/**
 * 选择解码表分组名（固件族）：Ortur HAL → Ortur 各固件号段 → Longer NanoDuo → `v<major>.<minor>`。
 */
fun lookupGroupName(version: GrblVersionInfo?): String {
    var groupName = "v1.1"
    if (version != null) {
        groupName = when {
            version.isOrtur && version.isHAL -> "ortur.GrblHal"
            version.isOrtur && version.orturFWVersionNumber >= 170 -> "ortur.v1.7.x"
            version.isOrtur && version.orturFWVersionNumber >= 150 -> "ortur.v1.5.x"
            version.isOrtur -> "ortur.v1.4.x"
            version.isLonger && version.vendorInfo == "NanoDuo" -> "longer.nanoduo"
            else -> "v${version.major}.${version.minor}"
        }
    }
    return groupName
}

/**
 * 查表：`表 → 分组名 → 编号 → 第 idx 个字段`，带 `v1.1` / `standard` 两级回退。
 * 与 v2 的 `lookupCode` 一致（找不到返回 null）。
 */
fun lookupCode(
    table: Map<String, Map<String, List<String>>>,
    key: String,
    index: Int,
    version: GrblVersionInfo?
): String? {
    val groupName = lookupGroupName(version)
    val group = table[groupName] ?: table["v1.1"] ?: table["standard"] ?: return null
    val entry = group[key] ?: return null
    return entry.getOrNull(index)
}

/** 设置项解码（对应 v2 里注入给 `installDecoders` 的 settings 查询）。 */
fun settingsLookup(key: String, index: Int, version: GrblVersionInfo?): String? =
    lookupCode(CsvData.SETTING_CODES, key, index, version)

/** 报警码解码。 */
fun alarmLookup(key: String, index: Int, version: GrblVersionInfo?): String? =
    lookupCode(CsvData.ALARM_CODES, key, index, version)

/** 错误码解码。 */
fun errorLookup(key: String, index: Int, version: GrblVersionInfo?): String? =
    lookupCode(CsvData.ERROR_CODES, key, index, version)

/** 把三个查询按指定版本上下文装进全局解码器（对应 v2 的 `installDecoders`）。 */
fun installDecodersForVersion(version: GrblVersionInfo?) {
    installDecoders(
        settings = { key, index -> settingsLookup(key, index, version) },
        alarms = { key, index -> alarmLookup(key, index, version) }
    )
}
