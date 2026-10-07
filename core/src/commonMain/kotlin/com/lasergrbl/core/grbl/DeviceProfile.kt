package com.lasergrbl.core.grbl

import com.lasergrbl.core.internal.jsNumberToString
import com.lasergrbl.core.internal.jsParseFloat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 设备参数档案 —— 逐行移植自 v2 `src/core/grbl/DeviceProfile.ts`。
 *
 * 与 v2 的差异（平台必需）：v2 直接读写 `localStorage`；这里改成 [DeviceProfiles.persistence] 注入。
 * 其余逻辑（规整、合并、激活唯一性、行程自适应、超行程校验、中文提示文案）逐字对齐。
 */
data class DeviceProfile(
    val id: String,
    /** 设备名称/型号。 */
    val name: String,
    /** X 行程 (mm)。 */
    val travelX: Double,
    /** Y 行程 (mm)。 */
    val travelY: Double,
    /** 固件类型名，如 `Grbl` / `Marlin` / `Smoothie`。 */
    val firmware: String,
    /** 最大激光功率 S 值 (S-MAX)。 */
    val maxPower: Double,
    /** 最小激光功率 S 值。 */
    val minPower: Double,
    /** 默认雕刻速度 mm/min。 */
    val markSpeed: Double,
    /** 默认空移速度 mm/min。 */
    val travelSpeed: Double,
    /** 默认每毫米线数 (LPC/quality)。 */
    val quality: Double,
    /** 默认波特率。 */
    val baud: Double,
    /** 是否为当前选中设备。 */
    val active: Boolean,
    val createdAt: Double,
    /** 关联的 USB 设备编号（首次连接时自动绑定）。 */
    val deviceId: Double? = null
)

/** 行程/尺寸的最小值，避免出现 0 或负数。 */
private const val MIN_SIZE_MM = 1.0

/** 尺寸计算保留的小数位（避免浮点噪声）。 */
internal fun round3(v: Double): Double = round(v * 1000.0) / 1000.0

private const val PROFILES_KEY = "lasergrbl.devices"
private const val SETUP_KEY = "lasergrbl.setupDone"
private const val KNOWN_DEVICES_KEY = "lasergrbl.knownDevices"

/** 默认值集中定义（与 AppSettings 的 DEFAULT_SETTINGS 保持一致）。 */
private object Defaults {
    const val travelX = 300.0
    const val travelY = 200.0
    const val firmware = "Grbl"
    const val maxPower = 1000.0
    const val minPower = 0.0
    const val markSpeed = 1000.0
    const val travelSpeed = 3000.0
    const val quality = 3.0
    const val baud = 115200.0
}

private fun num(v: Any?, def: Double): Double {
    val d = when (v) {
        is Double -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Float -> v.toDouble()
        else -> return def
    }
    return if (d.isFinite()) d else def
}

private fun str(v: Any?, def: String): String =
    if (v is String && v.isNotEmpty()) v else def

/** 把任意来源的对象规整为完整、字段类型正确的档案（防御损坏数据）。 */
internal fun normalizeProfile(obj: Map<String, Any?>): DeviceProfile {
    val rawId = obj["id"]
    val id = str(rawId, "dev-${nowMillisValue()}-${randomSuffix(obj)}")
    val deviceIdRaw = obj["deviceId"]
    val deviceId = when (deviceIdRaw) {
        is Double -> if (deviceIdRaw.isFinite()) deviceIdRaw else null
        is Int -> deviceIdRaw.toDouble()
        is Long -> deviceIdRaw.toDouble()
        else -> null
    }
    return DeviceProfile(
        id = id,
        name = str(obj["name"], "未命名设备"),
        travelX = num(obj["travelX"], Defaults.travelX),
        travelY = num(obj["travelY"], Defaults.travelY),
        firmware = str(obj["firmware"], Defaults.firmware),
        maxPower = num(obj["maxPower"], Defaults.maxPower),
        minPower = num(obj["minPower"], Defaults.minPower),
        markSpeed = num(obj["markSpeed"], Defaults.markSpeed),
        travelSpeed = num(obj["travelSpeed"], Defaults.travelSpeed),
        quality = num(obj["quality"], Defaults.quality),
        baud = num(obj["baud"], Defaults.baud),
        active = obj["active"] == true,
        createdAt = num(obj["createdAt"], nowMillisValue().toDouble()),
        deviceId = deviceId
    )
}

private fun nowMillisValue(): Long = com.lasergrbl.core.internal.nowMillis()

/** v2 用 `Math.random().toString(36).slice(2, 8)`；这里用对象内容做稳定后缀（测试友好）。 */
private fun randomSuffix(obj: Map<String, Any?>): String =
    (obj["name"]?.toString() ?: "profile").hashCode().toUInt().toString(36).padStart(6, '0').take(6)

/** 档案持久化 + 相关操作；v2 是模块级函数，这里收进 object 并允许注入持久化后端。 */
object DeviceProfiles {

    /** 持久化后端；Android 侧由 `:app` 注入。 */
    var persistence: SettingsPersistence = NoopSettingsPersistence

    /** 读取已保存的档案；无数据或损坏时返回空数组。 */
    fun readStored(): List<DeviceProfile> {
        try {
            val raw = persistence.read(PROFILES_KEY) ?: return emptyList()
            if (raw.isEmpty()) return emptyList()
            val parsed = PROFILE_JSON.parseToJsonElement(raw)
            val array = parsed as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                normalizeProfile(obj.mapValues { (_, v) -> jsonValueToKotlin(v) })
            }
        } catch (_: Throwable) {
            return emptyList()
        }
    }

    private fun persist(profiles: List<DeviceProfile>) {
        try {
            persistence.write(PROFILES_KEY, profileListToJson(profiles).toString())
        } catch (_: Throwable) {
            // 超出配额时静默忽略
        }
    }

    /** 内置的常见机型预设（稳定 id，供首次初始化向导作为模板）。 */
    fun builtinProfiles(): List<DeviceProfile> = listOf(
        DeviceProfile(
            id = "builtin-300x200-s1000",
            name = "300×200 桌面入门机（S1000）",
            travelX = 300.0,
            travelY = 200.0,
            firmware = "Grbl",
            maxPower = 1000.0,
            minPower = 0.0,
            markSpeed = 1000.0,
            travelSpeed = 3000.0,
            quality = 3.0,
            baud = 115200.0,
            active = false,
            createdAt = 0.0
        ),
        DeviceProfile(
            id = "builtin-400x400-s1000",
            name = "400×400 中端机（S1000）",
            travelX = 400.0,
            travelY = 400.0,
            firmware = "Grbl",
            maxPower = 1000.0,
            minPower = 0.0,
            markSpeed = 1000.0,
            travelSpeed = 3000.0,
            quality = 3.0,
            baud = 115200.0,
            active = false,
            createdAt = 0.0
        ),
        DeviceProfile(
            id = "builtin-500x500-s1000",
            name = "500×500 大幅面机（S1000）",
            travelX = 500.0,
            travelY = 500.0,
            firmware = "Grbl",
            maxPower = 1000.0,
            minPower = 0.0,
            markSpeed = 1000.0,
            travelSpeed = 3000.0,
            quality = 3.0,
            baud = 115200.0,
            active = false,
            createdAt = 0.0
        ),
        DeviceProfile(
            id = "builtin-300x180-s255",
            name = "300×180 简易机（S255）",
            travelX = 300.0,
            travelY = 180.0,
            firmware = "Grbl",
            maxPower = 255.0,
            minPower = 0.0,
            markSpeed = 1000.0,
            travelSpeed = 3000.0,
            quality = 3.0,
            baud = 115200.0,
            active = false,
            createdAt = 0.0
        )
    )

    fun setSetupDone(done: Boolean) {
        try {
            persistence.write(SETUP_KEY, if (done) "1" else "0")
        } catch (_: Throwable) {
            // ignore
        }
    }

    /** 仅返回用户真实保存过的档案（不含内置只读预设）。 */
    fun listSavedProfiles(): List<DeviceProfile> = readStored()

    /** 生成一个新的空档案（供初始化向导使用）。 */
    fun newProfile(partial: Map<String, Any?> = emptyMap()): DeviceProfile {
        val base = linkedMapOf<String, Any?>(
            "id" to "dev-${nowMillisValue()}-${partial["name"]?.toString()?.hashCode()?.toUInt()?.toString(36)?.padStart(8, '0')?.take(8) ?: "00000000"}",
            "name" to "我的雕刻机",
            "travelX" to Defaults.travelX,
            "travelY" to Defaults.travelY
        )
        base.putAll(partial)
        return normalizeProfile(base)
    }

    /** 已完成初始化向导的设备编号。 */
    fun listKnownDeviceIds(): List<Double> {
        try {
            val raw = persistence.read(KNOWN_DEVICES_KEY) ?: return emptyList()
            if (raw.isEmpty()) return emptyList()
            val parsed = PROFILE_JSON.parseToJsonElement(raw)
            val array = parsed as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                val p = element as? kotlinx.serialization.json.JsonPrimitive ?: return@mapNotNull null
                val d = p.content.toDoubleOrNull() ?: return@mapNotNull null
                if (d.isFinite()) d else null
            }
        } catch (_: Throwable) {
            return emptyList()
        }
    }

    fun isDeviceKnown(deviceId: Double): Boolean = listKnownDeviceIds().contains(deviceId)

    fun markDeviceKnown(deviceId: Double) {
        if (!deviceId.isFinite()) return
        val list = listKnownDeviceIds().toMutableList()
        if (!list.contains(deviceId)) list.add(deviceId)
        try {
            persistence.write(
                KNOWN_DEVICES_KEY,
                kotlinx.serialization.json.JsonArray(list.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()
            )
        } catch (_: Throwable) {
            // ignore
        }
    }

    /** 按 USB 设备编号查找已绑定的档案。 */
    fun findProfileByDevice(deviceId: Double): DeviceProfile? =
        readStored().firstOrNull { it.deviceId == deviceId }

    /**
     * 新增或更新（按 id），并返回保存后的档案。
     * 变更只写入"已保存的档案"；内置预设仅作只读回退，不会被无意中固化到存储里。
     */
    fun saveProfile(p: DeviceProfile): DeviceProfile {
        val list = readStored().toMutableList()
        val saved = normalizeProfile(profileToMap(p))
        val idx = list.indexOfFirst { it.id == saved.id }
        if (idx >= 0) list[idx] = saved else list.add(saved)
        // 同一时刻最多只有一个激活档案
        if (saved.active) {
            for (i in list.indices) if (list[i].id != saved.id) list[i] = list[i].copy(active = false)
        }
        persist(list)
        return saved
    }

    fun deleteProfile(id: String) {
        val list = readStored().filter { it.id != id }.toMutableList()
        // 若删除的是当前激活设备，自动激活剩余的第一个，避免出现"无激活设备"
        if (list.isNotEmpty() && list.none { it.active }) list[0] = list[0].copy(active = true)
        persist(list)
    }

    fun setActiveProfile(id: String) {
        // 存储为空时，选中内置预设意味着把它固化下来（首次初始化向导场景）
        val stored = readStored()
        val list = (if (stored.isNotEmpty()) stored else builtinProfiles()).toMutableList()
        var found = false
        for (i in list.indices) {
            val active = list[i].id == id
            list[i] = list[i].copy(active = active)
            if (active) found = true
        }
        if (found) persist(list)
    }

    /** 把档案名称映射到 [Firmware] 枚举（未知名称回退 Grbl）。 */
    private fun toFirmware(name: String): String =
        Firmware.fromValue(name)?.value ?: Firmware.Grbl.value

    /**
     * 用档案写入/同步 `AppSettings`，使现有转换流程直接生效。
     * 写入的键：`Firmware Type`、`Max Power`、`Min Power`、`Mark Speed`、
     * `Travel X`、`Travel Y`、`Last Baud`、`Jog Speed`（借用空移速度）。
     */
    fun applyProfileToSettings(p: DeviceProfile, settings: SettingsStore = AppSettings) {
        settings.set("Firmware Type", toFirmware(p.firmware))
        settings.set("Max Power", p.maxPower.toInt())
        settings.set("Min Power", p.minPower.toInt())
        settings.set("Mark Speed", p.markSpeed.toInt())
        settings.set("Travel X", p.travelX.toInt())
        settings.set("Travel Y", p.travelY.toInt())
        settings.set("Last Baud", p.baud.toInt())
        // 空移速度没有独立设置键，借用点动速度承载（两者都是非雕刻运动速度）
        settings.set("Jog Speed", p.travelSpeed.toInt())
    }
}

private val PROFILE_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private fun jsonValueToKotlin(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
    is kotlinx.serialization.json.JsonNull -> null
    is kotlinx.serialization.json.JsonPrimitive -> when {
        element.content == "true" -> true
        element.content == "false" -> false
        element.content.toDoubleOrNull() != null && !element.isString -> element.content.toDouble()
        else -> element.content
    }

    else -> element.toString()
}

private fun profileToMap(p: DeviceProfile): Map<String, Any?> = linkedMapOf(
    "id" to p.id,
    "name" to p.name,
    "travelX" to p.travelX,
    "travelY" to p.travelY,
    "firmware" to p.firmware,
    "maxPower" to p.maxPower,
    "minPower" to p.minPower,
    "markSpeed" to p.markSpeed,
    "travelSpeed" to p.travelSpeed,
    "quality" to p.quality,
    "baud" to p.baud,
    "active" to p.active,
    "createdAt" to p.createdAt,
    "deviceId" to p.deviceId
)

private fun profileListToJson(profiles: List<DeviceProfile>): kotlinx.serialization.json.JsonArray =
    kotlinx.serialization.json.JsonArray(
        profiles.map { p ->
            kotlinx.serialization.json.JsonObject(
                profileToMap(p).mapValues { (_, v) ->
                    when (v) {
                        null -> kotlinx.serialization.json.JsonNull
                        is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Double -> kotlinx.serialization.json.JsonPrimitive(v)
                        is String -> kotlinx.serialization.json.JsonPrimitive(v)
                        else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
                    }
                }
            )
        }
    )

/** 读取当前有效行程：显式传入优先，否则回退 AppSettings，再回退默认 300×200。 */
private fun resolveTravel(
    travelX: Double? = null,
    travelY: Double? = null,
    settings: SettingsStore = AppSettings
): Pair<Double, Double> {
    val tx = travelX ?: settingsNumber(settings, "Travel X", Defaults.travelX)
    val ty = travelY ?: settingsNumber(settings, "Travel Y", Defaults.travelY)
    return Pair(
        max(MIN_SIZE_MM, if (tx.isFinite()) tx else Defaults.travelX),
        max(MIN_SIZE_MM, if (ty.isFinite()) ty else Defaults.travelY)
    )
}

/** 从设置里取一个数值（容忍 Int/Long/Double/Float/字符串数字），非数值时回退默认。 */
private fun settingsNumber(settings: SettingsStore, key: String, def: Double): Double {
    val v: Any? = settings.get<Any?>(key, null) ?: return def
    return when (v) {
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Double -> if (v.isFinite()) v else def
        is Float -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: def
        else -> def
    }
}

enum class FitMode(val value: String) {
    Fit("Fit"), Clamp("Clamp"), None("None");

    companion object {
        fun fromValue(v: String?): FitMode = entries.firstOrNull { it.value == v } ?: Fit
    }
}

data class FitResult(
    val widthMm: Double,
    val heightMm: Double,
    val adjusted: Boolean,
    val message: String? = null
)

/**
 * 尺寸自适应：把期望尺寸 (mm) 限制/缩放到行程内。
 *  - [FitMode.Fit]：等比缩放，保证两个方向都放进行程内（以最紧的一边为准），保持宽高比
 *  - [FitMode.Clamp]：仅把各轴分别限制到行程上限，不保持宽高比
 *  - [FitMode.None]：不处理
 */
fun fitSizeToTravel(
    widthMm: Double,
    heightMm: Double,
    travelX: Double? = null,
    travelY: Double? = null,
    mode: FitMode = FitMode.Fit,
    settings: SettingsStore = AppSettings
): FitResult {
    val rawW = if (widthMm.isFinite()) widthMm else MIN_SIZE_MM
    val rawH = if (heightMm.isFinite()) heightMm else MIN_SIZE_MM
    val w = max(MIN_SIZE_MM, rawW)
    val h = max(MIN_SIZE_MM, rawH)

    val (tx, ty) = resolveTravel(travelX, travelY, settings)

    if (mode == FitMode.None) {
        return FitResult(round3(w), round3(h), adjusted = false)
    }

    if (mode == FitMode.Clamp) {
        val cw = min(w, tx)
        val ch = min(h, ty)
        val adjusted = round3(cw) != round3(w) || round3(ch) != round3(h)
        return FitResult(
            widthMm = round3(cw),
            heightMm = round3(ch),
            adjusted = adjusted,
            message = if (adjusted) {
                "尺寸超出 ${jsNumberToString(tx)}×${jsNumberToString(ty)}mm 行程，已分别限制为 X${jsNumberToString(round3(cw))}×Y${jsNumberToString(round3(ch))}mm（未保持比例）"
            } else {
                null
            }
        )
    }

    // Fit：以最紧的一边计算缩放比，只缩小不放大
    val scale = min(1.0, min(tx / w, ty / h))
    val fw = max(MIN_SIZE_MM, w * scale)
    val fh = max(MIN_SIZE_MM, h * scale)
    val adjusted = scale < 1.0
    return FitResult(
        widthMm = round3(fw),
        heightMm = round3(fh),
        adjusted = adjusted,
        message = if (adjusted) {
            "尺寸超出 ${jsNumberToString(tx)}×${jsNumberToString(ty)}mm 行程，已等比缩放至 X${jsNumberToString(round3(fw))}×Y${jsNumberToString(round3(fh))}mm"
        } else {
            null
        }
    )
}

/** 从一行 G 代码中提取所有"字母+数值"地址字（容忍大小写、无空格、小数）。 */
private val WORD_REGEX = Regex("[A-Z][-+]?(?:\\d+\\.?\\d*|\\.\\d+)")
private val PAREN_COMMENT = Regex("\\([^)]*\\)")

internal fun extractWords(line: String): List<String> {
    // 去掉分号注释与圆括号注释，避免把注释里的数字当作坐标
    val noSemicolon = line.split(';')[0]
    val noParen = noSemicolon.replace(PAREN_COMMENT, " ")
    return WORD_REGEX.findAll(noParen.uppercase()).map { it.value }.toList()
}

/** 运动行判定：`/^G0{0,2}[0-3]$/`（G0/G00/G000 … G3/G03/G003）。 */
private val MOVE_REGEX = Regex("^G0{0,2}[0-3]$")

data class TravelCheckResult(
    val ok: Boolean,
    val maxX: Double,
    val maxY: Double,
    val minX: Double,
    val minY: Double,
    val message: String? = null
)

/**
 * 校验一段 G 代码是否超出行程（解析 X/Y 最大最小值）。
 *
 * 关于负坐标：部分机器（如原点在右上的机型）使用负的机床坐标，因此"是否超出行程(ok)"
 * 只依据非负的工作坐标来判定；而返回的 minX/minY/maxX/maxY 仍包含负值在内的原始边界。
 */
fun checkGcodeWithinTravel(
    lines: List<String>,
    travelX: Double? = null,
    travelY: Double? = null,
    settings: SettingsStore = AppSettings
): TravelCheckResult {
    val (tx, ty) = resolveTravel(travelX, travelY, settings)

    var minX = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    var workMaxX = Double.NEGATIVE_INFINITY
    var workMaxY = Double.NEGATIVE_INFINITY
    var parsed = false
    var hasX = false
    var hasY = false

    for (line in lines) {
        if (line.isEmpty()) continue
        val words = extractWords(line)
        if (words.isEmpty()) continue
        val isMove = words.any { MOVE_REGEX.matches(it) }
        if (!isMove) continue

        val mx = words.firstOrNull { it[0] == 'X' }
        val my = words.firstOrNull { it[0] == 'Y' }
        val x = mx?.let { jsParseFloat(it.substring(1)) }
        val y = my?.let { jsParseFloat(it.substring(1)) }
        if (x == null && y == null) continue
        parsed = true

        if (x != null && x.isFinite()) {
            hasX = true
            minX = min(minX, x)
            maxX = max(maxX, x)
            if (x >= 0) workMaxX = max(workMaxX, x)
        }
        if (y != null && y.isFinite()) {
            hasY = true
            minY = min(minY, y)
            maxY = max(maxY, y)
            if (y >= 0) workMaxY = max(workMaxY, y)
        }
    }

    if (!parsed) {
        return TravelCheckResult(ok = true, maxX = 0.0, maxY = 0.0, minX = 0.0, minY = 0.0)
    }

    val diameterX = if (workMaxX == Double.NEGATIVE_INFINITY) 0.0 else workMaxX
    val diameterY = if (workMaxY == Double.NEGATIVE_INFINITY) 0.0 else workMaxY
    val eps = 0.001
    val ok = diameterX <= tx + eps && diameterY <= ty + eps

    return TravelCheckResult(
        ok = ok,
        maxX = if (hasX) round3(maxX) else 0.0,
        maxY = if (hasY) round3(maxY) else 0.0,
        minX = if (hasX) round3(minX) else 0.0,
        minY = if (hasY) round3(minY) else 0.0,
        message = if (ok) {
            null
        } else {
            "G 代码超出 ${jsNumberToString(tx)}×${jsNumberToString(ty)}mm 行程：X 最大 ${jsNumberToString(round3(diameterX))}mm、Y 最大 ${jsNumberToString(round3(diameterY))}mm"
        }
    )
}
