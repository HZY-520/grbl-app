package com.lasergrbl.core.grbl

import com.lasergrbl.core.internal.jsNumberToString
import com.lasergrbl.core.internal.jsParseFloat
import com.lasergrbl.core.internal.jsTrim

/**
 * G 代码命令解析 —— 逐行移植自 v2 `src/core/grbl/GrblCommand.ts`（其本身移植自 LaserGRBL `GrblCommand.cs`）。
 */

/** 一个 G 代码字（字母 + 数值），如 `G1` / `X10.5` / `S255`。 */
class Element(
    val command: String,
    val number: Double
) {
    companion object {
        /**
         * v2：`new Element(value[0], parseFloat(value.substring(1)))`。
         *
         * ⚠️ 空字符串在 JS 里 `value[0]` 是 `undefined`（toString 会输出 "undefinedNaN"）；
         * 这里退化为空命令串 —— 实际调用方不会传空串。
         */
        fun parse(value: String): Element =
            Element(value.firstOrNull()?.toString() ?: "", jsParseFloat(value.drop(1)))
    }

    override fun equals(other: Any?): Boolean =
        other is Element && other.command == command && other.number == number

    override fun hashCode(): Int = 31 * command.hashCode() + number.hashCode()

    override fun toString(): String = "$command${formatDecimal(number)}"
}

/**
 * 与 C# `decimal.ToString(InvariantCulture)` 类似的输出。
 * v2 的实现就是 `String(v)`（两个分支相同），因此这里必须逐字复刻 JS 的数字转字符串规则。
 */
fun formatDecimal(v: Double): String = jsNumberToString(v)

/** 命令的发送/响应状态。 */
enum class CommandStatus(val value: String) {
    Queued("Queued"),
    WaitingResponse("WaitingResponse"),
    ResponseGood("ResponseGood"),
    ResponseBad("ResponseBad"),
    InvalidResponse("InvalidResponse");

    companion object {
        fun fromValue(value: String): CommandStatus? = entries.firstOrNull { it.value == value }
    }
}

/** 设置项行匹配：`$NUM=VAL`。 */
private val CONF_REGEX = Regex("^\\$(\\d+)\\s*=(.*)")

fun GrblConfSTIsSetConf(p: String): Boolean = CONF_REGEX.containsMatchIn(p)

/** 与 v2 `TRIM_CHARS = /^[\r\n ]+|[\r\n ]+$/g` 等价：**只**去掉 CR/LF/空格（不含 TAB）。 */
private fun trimCrLfSpace(value: String): String {
    var start = 0
    var end = value.length
    while (start < end && (value[start] == '\r' || value[start] == '\n' || value[start] == ' ')) start++
    while (end > start && (value[end - 1] == '\r' || value[end - 1] == '\n' || value[end - 1] == ' ')) end--
    return value.substring(start, end)
}

/**
 * 一条待发送的 G 代码命令。
 *
 * ⚠️ 两个与 JS 逐字对齐的细节（夹具已钉住）：
 *  1. 构造函数用 JS 的 `trim()`（会去掉 BOM/NBSP），因此这里用 `jsTrim`；
 *  2. `buildHelper()` 会把 `mLine` 重写成"压缩空格后的"形式，重复地址字**后者胜**（`Map.set` 语义）。
 */
class GrblCommand @JvmOverloads constructor(
    line: String,
    repeat: Int = 0,
    preserveCase: Boolean = false
) {
    private var mLine: String
    private var mCodedResult: String? = null
    private var mTimeOffset = 0
    private var mHelper: MutableMap<String, Element>? = null

    var repeatCount: Int = repeat

    init {
        var l = jsTrim(line)
        if (!preserveCase) l = l.uppercase()
        mLine = l
    }

    companion object {
        fun fromElements(elements: List<Element>): GrblCommand {
            var line = ""
            for (e in elements) line = line + e.toString() + " "
            return GrblCommand(line.uppercase().trim())
        }

        fun combine(first: Element, toAppend: GrblCommand): GrblCommand =
            GrblCommand("${first} ${toAppend.command}".uppercase().trim())
    }

    fun clone(): GrblCommand {
        val c = GrblCommand(mLine, repeatCount, true)
        c.mCodedResult = mCodedResult
        c.mTimeOffset = mTimeOffset
        return c
    }

    var command: String
        get() = mLine
        set(v) {
            mLine = v
        }

    val justBuilt: Boolean get() = mHelper != null

    /** 解析命令中的字母/数值对。 */
    fun buildHelper() {
        if (justBuilt) return
        mHelper = linkedMapOf()
        try {
            if (!isGrblCommand) {
                var cmd = '\u0000'
                var num = ""
                var comment = false
                var oldSpace = false
                val sb = StringBuilder()
                for (c in mLine) {
                    if (c == ';') break
                    if (c == '(') comment = true
                    val space = c == ' '
                    if (!comment) {
                        if (space && !oldSpace) sb.append(' ')
                        else if (!space) sb.append(c)
                    }
                    oldSpace = space
                    if (!comment) {
                        if (c in 'A'..'Z' || c in 'a'..'z') {
                            if (cmd != '\u0000') add(Element(cmd.toString(), jsParseFloat(num)))
                            cmd = c
                            num = ""
                        } else if (c in '0'..'9' || c == '.' || c == '-') {
                            num += c
                        }
                    }
                    if (c == ')') comment = false
                }
                mLine = sb.toString()
                if (cmd != '\u0000') add(Element(cmd.toString(), jsParseFloat(num)))
            }
        } catch (_: Throwable) {
            // 与 LaserGRBL 一致：解析异常静默忽略
        }
    }

    fun deleteHelper() {
        mHelper = null
    }

    private fun add(e: Element) {
        mHelper?.set(e.command, e)
    }

    private fun getElement(key: String): Element? = mHelper?.get(key)

    fun setOffset(ms: Int) {
        mTimeOffset = ms
    }

    val timeOffset: Int get() = mTimeOffset

    /** 发送到串口的数据（含换行）。GRBL 命令（`$` 开头）不压缩空格。 */
    val serialData: String
        get() = if (canCompress) {
            trimCrLfSpace(mLine).replace(" ", "") + "\n"
        } else {
            trimCrLfSpace(mLine) + "\n"
        }

    private val canCompress: Boolean get() = !isGrblCommand

    val status: CommandStatus
        get() {
            val r = mCodedResult ?: return CommandStatus.Queued
            if (r.isEmpty()) return CommandStatus.WaitingResponse
            if (r.startsWith("OK")) return CommandStatus.ResponseGood
            if (r.startsWith("ERROR")) return CommandStatus.ResponseBad
            return CommandStatus.InvalidResponse
        }

    fun setResult(result: String) {
        mCodedResult = jsTrim(result).uppercase()
    }

    fun setSending() {
        mCodedResult = ""
    }

    fun clearResult() {
        mCodedResult = null
    }

    val codedResult: String? get() = mCodedResult

    val isGrblCommand: Boolean get() = mLine.startsWith("$")

    val isEmpty: Boolean get() = mLine.isEmpty()

    val isWriteEEPROM: Boolean get() = isGrblCommand && GrblConfSTIsSetConf(mLine)

    // ---- G 代码地址字 ----
    val G: Element? get() = getElement("G")
    val M: Element? get() = getElement("M")
    val T: Element? get() = getElement("T")
    val S: Element? get() = getElement("S")
    val P: Element? get() = getElement("P")
    val X: Element? get() = getElement("X")
    val Y: Element? get() = getElement("Y")
    val Z: Element? get() = getElement("Z")
    val I: Element? get() = getElement("I")
    val J: Element? get() = getElement("J")
    val F: Element? get() = getElement("F")
    val R: Element? get() = getElement("R")

    val isSetWCO: Boolean get() = G?.number == 92.0

    val isMovement: Boolean get() = isLinearMovement || isArcMovement

    val isLinearMovement: Boolean
        get() = !isSetWCO && (X != null || Y != null || Z != null) && I == null && J == null && R == null

    val isArcMovement: Boolean
        get() = !isSetWCO && (I != null || J != null || R != null)

    fun isCW(prev: Boolean): Boolean {
        if (G?.number == 2.0) return true
        if (G?.number == 3.0) return false
        return prev
    }

    val isPause: Boolean get() = G?.number == 4.0

    val isAbsoluteCoord: Boolean get() = G?.number == 90.0
    val isRelativeCoord: Boolean get() = G?.number == 91.0

    val isLaserON: Boolean get() = isM3 || isM4
    val isM3: Boolean get() = M?.number == 3.0
    val isM4: Boolean get() = M?.number == 4.0
    val isLaserOFF: Boolean get() = isM5
    val isM5: Boolean get() = M?.number == 5.0

    fun getDecodedMessage(): String =
        if (repeatCount == 0) command else "$command (重试 $repeatCount)"

    override fun toString(): String = mLine
}

/** 日志行类型。 */
enum class MessageType(val value: String) {
    Startup("Startup"),
    Config("Config"),
    Alarm("Alarm"),
    Feedback("Feedback"),
    Position("Position"),
    Others("Others"),
    Warning("Warning"),
    Diagnostic("Diagnostic"),
    Command("Command");

    companion object {
        fun fromValue(value: String): MessageType? = entries.firstOrNull { it.value == value }
    }
}

/** 日志中的一条消息（来自机器或应用）。 */
class GrblMessage(
    message: String,
    val type: MessageType
) {
    val nativeMessage: String
    var message: String
    var tooltip: String = ""
    var leftColor: String = ""
    var rightColor: String = ""
    var imageIndex: Int = 3

    /** 对应的响应信息（用于命令日志）。 */
    var codedResult: String? = null

    init {
        this.message = jsTrim(message)
        this.nativeMessage = this.message
    }

    companion object {
        fun fromLine(message: String, decode: Boolean): GrblMessage {
            val trimmed = jsTrim(message)
            val lower = trimmed.lowercase()
            val type = when {
                lower.startsWith("$") && trimmed.contains('=') -> MessageType.Config
                lower.startsWith("grbl") -> MessageType.Startup
                lower.startsWith("alarm") -> MessageType.Alarm
                trimmed.startsWith("<") && trimmed.endsWith(">") -> MessageType.Position
                trimmed.startsWith("[") && trimmed.endsWith("]") -> MessageType.Feedback
                else -> MessageType.Others
            }

            val m = GrblMessage(trimmed, type)
            if (decode) m.decode()
            return m
        }
    }

    private fun decode() {
        try {
            if (type == MessageType.Config) {
                val eq = message.indexOf('=')
                val key = message.substring(1, if (eq >= 0) eq else message.length)
                val brief = settingLookup(key, 0)
                val unit = settingLookup(key, 1)
                val desc = settingLookup(key, 2)
                if (!brief.isNullOrEmpty()) message = "$message ($brief)"
                if (!desc.isNullOrEmpty()) tooltip = "$desc [$unit]"
            } else if (type == MessageType.Alarm) {
                val colon = message.indexOf(':')
                val key = message.substring(if (colon >= 0) colon + 1 else 0)
                val brief = alarmLookup(key, 0)
                val desc = alarmLookup(key, 1)
                if (!brief.isNullOrEmpty()) message = brief
                if (!desc.isNullOrEmpty()) tooltip = desc
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun getDecodedMessage(): String = message

    // v2 还有一个 `getNativeMessage()`，它只是返回 `nativeMessage`；
    // Kotlin 里属性已生成 getNativeMessage()，再写同名函数会 JVM 签名冲突，因此只保留属性。
}

// 延迟注入的解码表，避免循环依赖
private var settingLookupImpl: (String, Int) -> String? = { _, _ -> null }
private var alarmLookupImpl: (String, Int) -> String? = { _, _ -> null }

/** 与 v2 `installDecoders` 对应：注入 `$` 设置项与报警码的查询函数。 */
fun installDecoders(
    settings: (key: String, index: Int) -> String?,
    alarms: (key: String, index: Int) -> String?
) {
    settingLookupImpl = settings
    alarmLookupImpl = alarms
}

internal fun settingLookup(key: String, index: Int): String? = settingLookupImpl(key, index)

internal fun alarmLookup(key: String, index: Int): String? = alarmLookupImpl(key, index)
