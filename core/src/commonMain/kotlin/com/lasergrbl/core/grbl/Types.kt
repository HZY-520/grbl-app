package com.lasergrbl.core.grbl

/**
 * GRBL 核心类型 —— 逐字段移植自 v2 `src/core/grbl/types.ts`（其本身移植自 LaserGRBL `Core/GrblCore.cs`）。
 *
 * ⚠️ 属性名故意保持 v2 的 `X/Y/Z`、`major/minor/build` 写法，方便与 TS 源码逐行对照；
 * Kotlin 风格检查在这几个移植文件里让位于「可对照性」。
 */

/** 固件类型。 */
enum class Firmware(val value: String) {
    Grbl("Grbl"),
    Smoothie("Smoothie"),
    Marlin("Marlin"),
    Vigo("Vigo");

    companion object {
        fun fromValue(value: String): Firmware? = entries.firstOrNull { it.value == value }
    }
}

/** 机器状态（对应 `<Idle|...>` 里的状态字）。 */
enum class MacStatus(val value: String) {
    Disconnected("Disconnected"),
    Connecting("Connecting"),
    Idle("Idle"),
    Run("Run"),
    Hold("Hold"),
    Door("Door"),
    Home("Home"),
    Alarm("Alarm"),
    Check("Check"),
    Jog("Jog"),
    Queue("Queue"),
    Cooling("Cooling"),
    AutoHold("AutoHold"),
    Tool("Tool");

    companion object {
        fun fromValue(value: String): MacStatus? = entries.firstOrNull { it.value == value }
    }
}

/** 点动方向。 */
enum class JogDirection(val value: String) {
    Abort("Abort"),
    Home("Home"),
    N("N"),
    S("S"),
    W("W"),
    E("E"),
    NW("NW"),
    NE("NE"),
    SW("SW"),
    SE("SE"),
    Zup("Zup"),
    Zdown("Zdown"),
    Position("Position");

    companion object {
        fun fromValue(value: String): JogDirection? = entries.firstOrNull { it.value == value }
    }
}

/** 流式发送模式。 */
enum class StreamingMode(val value: String) {
    Buffered("Buffered"),
    Synchronous("Synchronous"),
    RepeatOnError("RepeatOnError");

    companion object {
        fun fromValue(value: String): StreamingMode? = entries.firstOrNull { it.value == value }
    }
}

/** 检测到的问题（数值与 v2 一致，含负值）。 */
enum class DetectedIssue(val value: Int) {
    Unknown(0),
    ManualReset(-1),
    ManualDisconnect(-2),
    ManualAbort(-3),
    StopResponding(1),
    UnexpectedReset(3),
    UnexpectedDisconnect(4),
    MachineAlarm(5);

    companion object {
        fun fromValue(value: Int): DetectedIssue = entries.firstOrNull { it.value == value } ?: Unknown
    }
}

/** 状态查询 / 通讯速度模式。 */
data class ThreadingMode(
    val statusQuery: Int,
    val txLong: Int,
    val txShort: Int,
    val rxLong: Int,
    val rxShort: Int,
    val name: String
) {
    companion object {
        val Slow = ThreadingMode(2000, 15, 4, 2, 1, "Slow")
        val Quiet = ThreadingMode(1000, 10, 2, 1, 1, "Quiet")
        val Fast = ThreadingMode(500, 5, 1, 1, 0, "Fast")
        val UltraFast = ThreadingMode(250, 1, 0, 0, 0, "UltraFast")
        val Insane = ThreadingMode(200, 1, 0, 0, 0, "Insane")

        /** 与 v2 `ThreadingMode.all()` 同序。 */
        val all: List<ThreadingMode> get() = listOf(Slow, Quiet, Fast, UltraFast, Insane)

        /**
         * 按名字查线程模式。
         *
         * ⚠️ 未登记的名字回退到 **`Fast`** —— 与 TS `GrblCore.ts:148` 的
         * `ThreadingMode.all().find(...) ?? ThreadingMode.Fast` 一致。
         * 这里**不能**回退到列表首项 `Slow`：那会把状态查询节拍从 500 ms 拉到 2000 ms，
         * 卡死检测阈值（TS `statusQuery * 10`）也跟着变。`GrblCore.kt` 已就地写死了同样的回退。
         */
        fun fromName(name: String): ThreadingMode = all.firstOrNull { it.name == name } ?: Fast
    }
}

/** 三维坐标（与 LaserGRBL 的 GPoint 结构兼容）。 */
data class GPoint(
    val X: Double = 0.0,
    val Y: Double = 0.0,
    val Z: Double = 0.0
) {
    operator fun minus(o: GPoint) = GPoint(X - o.X, Y - o.Y, Z - o.Z)

    operator fun plus(o: GPoint) = GPoint(X + o.X, Y + o.Y, Z + o.Z)

    override fun toString(): String = "X${jsNumber(X)} Y${jsNumber(Y)} Z${jsNumber(Z)}"

    companion object {
        val Zero = GPoint(0.0, 0.0, 0.0)
    }
}

/**
 * GRBL 版本信息。
 *
 * 注意 `compareTo` 的 build 比较用的是**字符串字典序**（v2 里是 `a > b`），Kotlin 的 `String.compareTo`
 * 同样是按 UTF-16 code unit 比较，对 ASCII 行为一致 —— 移植时不要改成数值比较。
 */
class GrblVersionInfo(
    val major: Int,
    val minor: Int,
    val build: String = "",
    val vendorInfo: String? = null,
    val vendorVersion: String? = null,
    val isHAL: Boolean = false
) : Comparable<GrblVersionInfo?> {

    val isOrtur: Boolean =
        vendorInfo != null && (vendorInfo.contains("Ortur") || vendorInfo.contains("Aufero"))

    val isLonger: Boolean =
        vendorInfo != null && (vendorInfo.contains("Longer") || vendorInfo.contains("NanoDuo"))

    val machineName: String? get() = vendorInfo

    val isLuckyWiFi: Boolean
        get() = (isOrtur && vendorInfo == "Ortur Laser Master 3") ||
            (isLonger && (vendorInfo == "Longer Nano" || vendorInfo == "NanoDuo"))

    /** Ortur 固件版本号，例如 1.7 → 170。 */
    val orturFWVersionNumber: Int
        get() {
            val v = vendorVersion ?: return 0
            val m = ORTUR_FW_REGEX.find(v) ?: return 0
            return m.groupValues[1].toInt() * 100 + m.groupValues[2].toInt() * 10
        }

    private fun key(): String = "$major.$minor$build"

    override fun compareTo(other: GrblVersionInfo?): Int {
        if (other == null) return 1
        if (major != other.major) return if (major > other.major) 1 else -1
        if (minor != other.minor) return if (minor > other.minor) 1 else -1
        val a = build
        val b = other.build
        if (a == b) return 0
        return if (a > b) 1 else -1
    }

    fun gte(o: GrblVersionInfo): Boolean = compareTo(o) >= 0

    fun lt(o: GrblVersionInfo): Boolean = compareTo(o) < 0

    override fun equals(other: Any?): Boolean = other is GrblVersionInfo && compareTo(other) == 0

    override fun hashCode(): Int = key().hashCode()

    override fun toString(): String = key()

    private companion object {
        val ORTUR_FW_REGEX = Regex("(\\d+)\\.(\\d+)")
    }
}

val GCODE_EXTENSIONS: List<String> = listOf(".nc", ".cnc", ".tap", ".gcode", ".ngc", ".txt")

/**
 * 复刻 JS `Number.prototype.toString()` 的常见输出：整数不带 `.0`。
 * v2 里有若干拼字符串的地方（例如 `GPoint.toString()`），Kotlin 的 `Double.toString()` 会输出 `0.0` 造成差异。
 */
internal fun jsNumber(v: Double): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
    if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
    return v.toString()
}
