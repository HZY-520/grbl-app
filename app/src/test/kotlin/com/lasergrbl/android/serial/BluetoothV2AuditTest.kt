package com.lasergrbl.android.serial

import com.lasergrbl.core.serial.TransportKind
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 蓝牙 SPP 的**独立复核**测试（task-6）—— 由没有参与 [BluetoothSerialTransport] 实现的人写。
 *
 * 判据是 v2 的 Java 源码本身：`android/app/src/main/java/com/lasergrbl/android/BluetoothSerialPlugin.java`
 * （**不是** `docs/UI-3.0-PLAN.md`：计划书在细节上已经错过两次）。
 *
 * ### 这个文件与 [BluetoothV2ParityConstantsTest] 的区别（为什么值得重复）
 * 那份测试把 v2 的字面量**手抄**进断言，手抄错了就一起错。这里改成在运行时**读 v2 的 .java 文件**，
 * 从被引用的行号上把字面量解析出来再和 3.0 的常量比 —— 于是它同时验证两件事：
 *  1. 3.0 的常量与 v2 源码里的字面量逐字相同；
 *  2. 3.0 注释/KDoc 里引用的 v2 **行号**本身是对的（引错行号也是缺陷）。
 *
 * ### 三层证据（报告里必须区分，不许混为一谈）
 *  * **运行期断言**：常量、文案、`UUID` 数值、权限判定纯函数、设备映射纯函数 —— 真跑真比。
 *  * **源码文本级断言**（`codeLines` 那几组）：证明"顺序/条件/位置"在源码里确实成立
 *    （例如 `cancelDiscovery()` 出现在建连之前、失败路径先 `closeQuietly` 再抛）。
 *    这是**离线可判定**的，但它证的是源码形状，**不是**真机运行行为 —— 断言消息里都写明了。
 *  * 真机行为（ACL 广播送达时机、权限弹窗、实际连接、吞吐）**测不到**，见测试报告。
 *
 * 没写进这里的东西：`SerialPortBase` 的读/写/关闭状态机本身已由 `:core:jvmTest` 的 26 套件覆盖，
 * 本项目不重复测它，只用源码文本级断言确认蓝牙侧确实接到了那几条路径上。
 *
 * 源码行号一律以注释标出，格式 `v2:NNN`。
 */
class BluetoothV2AuditTest {

    // =====================================================================================
    // 工具：定位仓库文件、按行取字面量、只保留代码行
    // =====================================================================================

    /**
     * 从单测工作目录向上找仓库根下的相对路径文件。
     *
     * 单测的 `user.dir` 由 Gradle 设成模块目录（`:app` 是 `app/`），不同 Gradle/AGP 版本可能不同，
     * 所以这里向上最多找 8 层；**找不到就跳过**（assume）而不是失败：脱离本仓库的构建环境里
     * 可能没有 v2 源码树，那种情况下"逐字比对"本身不可判定。
     */
    private fun repoFileOrNull(relativePath: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
            depth++
        }
        return null
    }

    private fun repoFile(relativePath: String): File {
        val file = repoFileOrNull(relativePath)
        assumeTrue(
            "找不到 $relativePath（从 ${System.getProperty("user.dir")} 向上 8 层），跳过逐字比对",
            file != null
        )
        return file!!
    }

    private fun v2Lines(): List<String> = repoFile(V2_PLUGIN).readLines()

    private fun transportSource(): String = repoFile(BT_TRANSPORT).readText()

    private fun coreSource(): String = repoFile(CORE_SERIAL_BASE).readText()

    private fun connectionSource(): String = repoFile(BT_CONNECTION).readText()

    /** 取出一行 Java 里所有双引号字面量的内容（本插件里的字面量不含转义，够用）。 */
    private fun literalsOn(line: String): List<String> =
        Regex("\"([^\"]*)\"").findAll(line).map { it.groupValues[1] }.toList()

    /** 断言 v2 的某一行上确实有某个字面量（用于验证 3.0 注释里引用的行号）。 */
    private fun assertLiteralOnV2Line(lineNumber: Int, expected: String) {
        val line = v2Lines().getOrNull(lineNumber - 1)
        assertTrue("v2 没有第 $lineNumber 行（v2 源码被改过？）", line != null)
        assertTrue(
            "v2:$lineNumber 上找不到字面量「$expected」，实际该行是：$line",
            literalsOn(line!!).contains(expected)
        )
    }

    /** 读出 v2 某一行第 [index] 个字面量（0 起）。 */
    private fun v2LiteralAt(lineNumber: Int, index: Int = 0): String {
        val line = v2Lines().getOrNull(lineNumber - 1)
        assumeTrue("v2 没有第 $lineNumber 行", line != null)
        val literals = literalsOn(line!!)
        assertTrue("v2:$lineNumber 上没有第 ${index + 1} 个字面量：$line", literals.size > index)
        return literals[index]
    }

    /** 取 v2 某一行（1 起）。 */
    private fun v2Line(lineNumber: Int): String {
        val line = v2Lines().getOrNull(lineNumber - 1)
        assertTrue("v2 没有第 $lineNumber 行", line != null)
        return line!!
    }

    /**
     * 只保留**代码行**：丢掉空行、KDoc/块注释的续行（归一化后以 `*` 开头）与行注释，
     * 并把行内连续空白压成一个空格。
     *
     * 为什么需要：`BluetoothSerialTransport.kt` 的 KDoc 里也写着
     * `createRfcommSocketToServiceRecord(...)` / `closeQuietly(socket)` 这些名字，
     * 直接对全文做 `indexOf` 会命中注释、把"顺序"断言假阳性。
     */
    private fun codeLines(source: String): List<String> = source.lines()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filterNot { it.isEmpty() || it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }

    // =====================================================================================
    // 第 1 组：3.0 注释里引用的 v2 行号本身是否正确
    // =====================================================================================

    /**
     * `BluetoothSerialTransport.kt` / `BluetoothPermissionGate.kt` 的 KDoc 与常量注释逐条写明了
     * v2 行号；这里把每条行号回到 v2 源码里验一遍（引错行号 = 复核依据失真）。
     *
     * ⚠️ 审计发现（不是本测试的错，报告里也写了）：3.0 的 `MESSAGE_BLUETOOTH_DISABLED` 注释写的是
     * `v2 BluetoothSerialPlugin.java:252 / :298`，但 doList 那处实际在 **v2:251**（v2:252 是
     * `return;`）；`:298` 是对的。本测试断言的是**文件里的事实**（251），不迁就注释里的错行号。
     */
    @Test
    fun citedV2LineNumbersPointAtTheClaimedLiterals() {
        assertLiteralOnV2Line(185, "无法打开蓝牙设置: ")     // v2 openSettings 失败前缀
        assertLiteralOnV2Line(225, "未获得蓝牙权限")         // v2 permissionCallback 被拒
        assertLiteralOnV2Line(247, "设备不支持蓝牙")         // v2 doList：适配器不存在
        assertLiteralOnV2Line(251, "蓝牙未开启，请先开启蓝牙") // v2 doList：适配器未开（注释里写的 252 是错的）
        assertLiteralOnV2Line(259, "缺少蓝牙权限: ")         // v2 doList：getBondedDevices 抛 SecurityException
        assertLiteralOnV2Line(294, "设备不支持蓝牙")         // v2 doOpen：适配器不存在
        assertLiteralOnV2Line(298, "蓝牙未开启，请先开启蓝牙") // v2 doOpen：适配器未开
        assertLiteralOnV2Line(309, "无效的蓝牙地址: ")       // v2 doOpen：getRemoteDevice 抛 IllegalArgumentException
        assertLiteralOnV2Line(341, "缺少蓝牙权限: ")         // v2 doOpen：connect 抛 SecurityException
        assertLiteralOnV2Line(344, "连接蓝牙设备失败: ")      // v2 doOpen：connect 抛 IOException
        assertLiteralOnV2Line(52, "00001101-0000-1000-8000-00805F9B34FB") // v2 SPP_UUID
    }

    // =====================================================================================
    // 第 2 组：错误文案逐字一致（3.0 常量 ↔ v2 源码字面量）
    // =====================================================================================

    /** v2:247 / v2:294 —— `设备不支持蓝牙`（3.0 里 list 与建连工厂各用一次）。 */
    @Test
    fun unsupportedMessageMatchesV2SourceLiterals() {
        assertEquals("v2:247 与 3.0 文案不一致", v2LiteralAt(247), MESSAGE_BLUETOOTH_UNSUPPORTED)
        assertEquals("v2:294 与 3.0 文案不一致", v2LiteralAt(294), MESSAGE_BLUETOOTH_UNSUPPORTED)
    }

    /** v2:251 / v2:298 —— `蓝牙未开启，请先开启蓝牙`（注意 doList 那处是 251，不是 3.0 注释写的 252）。 */
    @Test
    fun disabledMessageMatchesV2SourceLiterals() {
        assertEquals("v2:251 与 3.0 文案不一致", v2LiteralAt(251), MESSAGE_BLUETOOTH_DISABLED)
        assertEquals("v2:298 与 3.0 文案不一致", v2LiteralAt(298), MESSAGE_BLUETOOTH_DISABLED)
    }

    /** v2:259 / v2:341 —— `缺少蓝牙权限: `（前缀，后面接异常消息）。 */
    @Test
    fun missingPermissionPrefixMatchesV2SourceLiterals() {
        assertEquals("v2:259 与 3.0 前缀不一致", v2LiteralAt(259), PREFIX_BLUETOOTH_PERMISSION_MISSING)
        assertEquals("v2:341 与 3.0 前缀不一致", v2LiteralAt(341), PREFIX_BLUETOOTH_PERMISSION_MISSING)
    }

    /** v2:344 / v2:309 —— 建连失败与无效地址两个前缀。 */
    @Test
    fun connectFailedAndInvalidAddressPrefixesMatchV2SourceLiterals() {
        assertEquals("v2:344 与 3.0 前缀不一致", v2LiteralAt(344), PREFIX_BLUETOOTH_CONNECT_FAILED)
        assertEquals("v2:309 与 3.0 前缀不一致", v2LiteralAt(309), PREFIX_BLUETOOTH_INVALID_ADDRESS)
    }

    /** v2:185 —— `无法打开蓝牙设置: `。 */
    @Test
    fun openSettingsFailedPrefixMatchesV2SourceLiteral() {
        assertEquals("v2:185 与 3.0 前缀不一致", v2LiteralAt(185), PREFIX_BLUETOOTH_OPEN_SETTINGS_FAILED)
    }

    /** v2:225 —— 权限被拒时的 `未获得蓝牙权限`（3.0 的门禁抛 SecurityException 用同一句）。 */
    @Test
    fun permissionDeniedMessageMatchesV2SourceLiteral() {
        assertEquals("v2:225 与 3.0 文案不一致", v2LiteralAt(225), MESSAGE_BLUETOOTH_PERMISSION_DENIED)
    }

    /**
     * 「看起来一样」的陷阱：冒号/逗号/空格是全角还是半角，肉眼看不出差别，但用户可见文案会变。
     * v2 的标点全是 ASCII（除了那一个全角逗号），这里按**码点**钉死。
     */
    @Test
    fun frozenMessagesUseAsciiPunctuationAndNoLookalikes() {
        // v2:259 / :341 / :344 / :309 / :185 —— 冒号都是 ASCII ':'（U+003A）后跟**一个** ASCII 空格。
        listOf(
            PREFIX_BLUETOOTH_PERMISSION_MISSING,
            PREFIX_BLUETOOTH_CONNECT_FAILED,
            PREFIX_BLUETOOTH_INVALID_ADDRESS,
            PREFIX_BLUETOOTH_OPEN_SETTINGS_FAILED
        ).forEach { prefix ->
            assertEquals("前缀结尾必须是 ASCII 冒号 + 单个空格：$prefix", listOf(':', ' '), prefix.takeLast(2).toList())
        }
        // 全角冒号 U+FF1A / 全角空格 U+3000 / 不换行空格 U+00A0 一律不允许出现在任何文案里。
        val allMessages = listOf(
            MESSAGE_BLUETOOTH_UNSUPPORTED,
            MESSAGE_BLUETOOTH_DISABLED,
            PREFIX_BLUETOOTH_PERMISSION_MISSING,
            PREFIX_BLUETOOTH_CONNECT_FAILED,
            PREFIX_BLUETOOTH_INVALID_ADDRESS,
            PREFIX_BLUETOOTH_OPEN_SETTINGS_FAILED,
            MESSAGE_BLUETOOTH_PERMISSION_DENIED,
            MESSAGE_BLUETOOTH_DISCONNECTED
        )
        allMessages.forEach { message ->
            assertFalse("出现全角冒号（U+FF1A）：$message", message.contains('\uFF1A'))
            assertFalse("出现全角空格（U+3000）：$message", message.contains('\u3000'))
            assertFalse("出现不换行空格（U+00A0）：$message", message.contains('\u00A0'))
        }
        // v2:252 / :298 的逗号是**全角**『，』（U+FF0C），不是 ASCII ','：逐字符固定。
        assertEquals(
            "v2:252 的逗号必须是与 v2 相同的全角逗号",
            listOf('蓝', '牙', '未', '开', '启', '\uFF0C', '请', '先', '开', '启', '蓝', '牙'),
            MESSAGE_BLUETOOTH_DISABLED.toList()
        )
        assertFalse("禁用文案里出现了 ASCII 逗号", MESSAGE_BLUETOOTH_DISABLED.contains(','))
        // v2:247 / :294 四个字，逐字符固定。
        assertEquals(listOf('设', '备', '不', '支', '持', '蓝', '牙'), MESSAGE_BLUETOOTH_UNSUPPORTED.toList())
    }

    // =====================================================================================
    // 第 3 组：SPP UUID 与默认波特率（清单第 1、2 条）
    // =====================================================================================

    /**
     * 清单第 2 条：SPP UUID。v2:52 的字面量解析出来后再比 —— 大小写写法不影响 `UUID` 数值比较，
     * 所以这里额外把 msb/lsb 与规范字符串都钉死（防止"看着像"的 UUID 被 `equals` 放过）。
     */
    @Test
    fun sppUuidMatchesV2SourceLiteralBitForBit() {
        val v2UuidText = v2LiteralAt(52)
        assertEquals("00001101-0000-1000-8000-00805F9B34FB", v2UuidText)

        val expected = UUID.fromString(v2UuidText)
        assertEquals("3.0 的 SPP_UUID 与 v2:52 不等价", expected, BluetoothSerialTransport.SPP_UUID)
        // 逐位固定：msb = 0x00001101_00001000，lsb = 0x80000080_5F9B34FB。
        // lsb 的最高位是 1，写成 16 进制字面量会超出 Long 范围（Kotlin 会报 Value out of range），
        // 所以按"无符号 16 进制 → 位模式"的方式解析，避免手算补码出错。
        assertEquals(0x0000110100001000L, BluetoothSerialTransport.SPP_UUID.mostSignificantBits)
        assertEquals(
            java.lang.Long.parseUnsignedLong("800000805F9B34FB", 16),
            BluetoothSerialTransport.SPP_UUID.leastSignificantBits
        )
        // `UUID.toString()` 的规范形式是小写。
        assertEquals("00001101-0000-1000-8000-00805f9b34fb", BluetoothSerialTransport.SPP_UUID.toString())
        // v2 写字面量时用大写，`UUID.fromString` 大小写不敏感：两种写法必须得到同一个 UUID。
        assertEquals(
            BluetoothSerialTransport.SPP_UUID,
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        )
        // 反向对照：只差最后一位的 UUID 不能被判等（证明上面的 equals 有牙齿）。
        assertNotEquals(
            BluetoothSerialTransport.SPP_UUID,
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fc")
        )
    }

    /**
     * 清单第 1 条：默认波特率 115200。
     *
     * 关键结论（这里用源码文本给证据）：v2 的 `DEFAULT_BAUD_RATE`（v2:58）在 v2 全文**只出现一次**
     * （就是那行声明），建连调用 v2:325 只传 `SPP_UUID` 一个参数 —— 也就是说 v2 的 `open` 根本没把
     * baudRate 传给 socket。3.0 的工厂同样把 baudRate 丢掉（lambda 第二个参数写成 `_`），
     * 因此"接受并忽略"这一语义与 v2 一致；3.0 的常量也只是为了让接口/调用方保持一致的占位。
     */
    @Test
    fun defaultBaudRateMatchesV2AndIsIgnoredLikeV2Open() {
        val lines = v2Lines()
        val declaration = Regex("DEFAULT_BAUD_RATE\\s*=\\s*(\\d+)").find(v2Line(58)) // v2:58
        assumeTrue("v2:58 不再是 DEFAULT_BAUD_RATE 的声明行", declaration != null)
        assertEquals("v2:58 声明的波特率不是 115200", 115200, declaration!!.groupValues[1].toInt())
        assertEquals(
            "3.0 的 DEFAULT_BAUD_RATE 与 v2:58 不一致",
            declaration.groupValues[1].toInt(),
            BluetoothSerialTransport.DEFAULT_BAUD_RATE
        )
        // v2 全文只有声明这一处引用 ⇒ v2 的 open 没有使用它。
        assertEquals(
            "v2 里 DEFAULT_BAUD_RATE 出现了多次，说明它被用在了别处，本条结论需要重审",
            1,
            lines.count { it.contains("DEFAULT_BAUD_RATE") }
        )
        // v2:325 的建连调用只有一个参数（SPP_UUID），没有 baudRate 形参。
        assertTrue(
            "v2:325 的建连调用形状变了：${v2Line(325)}",
            v2Line(325).contains("createRfcommSocketToServiceRecord(SPP_UUID)")
        )
        // 3.0 的工厂显式丢弃 baudRate（`device, _ ->`）。
        assertTrue(
            "3.0 的工厂没有显式丢弃 baudRate（是不是把它传给了 socket？）",
            codeLines(transportSource()).any { it == "PortConnectionFactory { device, _ ->" }
        )
    }

    // =====================================================================================
    // 第 4 组：读循环与 len == 0（清单第 6 条）
    // =====================================================================================

    /**
     * 清单第 6 条：v2 用 `in.read(buffer)`（阻塞、无超时），`len < 0` 时 break，
     * `len == 0` 时**什么都不做**继续循环（不 sleep）。
     *
     * 3.0 在 `read == 0` 时会 `Thread.sleep(readIdleDelayMillis)`（默认 2 ms）。可观察行为是否改变？
     * 这个测试给出可离线判定的部分：
     *  1. 3.0 的蓝牙读就是 `input.read(buffer)`，**没有超时、没有 available 轮询** ⇒ read 的语义与 v2 相同；
     *  2. sleep 只在 `read == 0` 的分支里，且该分支在解码/回调之前就 `continue` ⇒ 不产生任何 `data` 事件；
     *  3. `InputStream.read(byte[])` 在 buffer 非空时的契约是"至少读一个字节或返回 -1"（不会返回 0），
     *     所以这条 sleep 分支在蓝牙上实际不可达 —— 结论是"不改变可观察行为"（推理，不是真机实测）。
     */
    @Test
    fun readLoopBufferSizeAndZeroLengthBranchMatchV2() {
        val lines = v2Lines()
        val declaration = Regex("READ_BUFFER_SIZE\\s*=\\s*(\\d+)").find(v2Line(55)) // v2:55
        assumeTrue("v2:55 不再是 READ_BUFFER_SIZE 的声明行", declaration != null)
        assertEquals("v2:55 的缓冲区不是 4096", 4096, declaration!!.groupValues[1].toInt())

        // v2:356 阻塞读；v2:351 按 READ_BUFFER_SIZE 分配。
        assertTrue("v2 的读调用形状变了：${v2Line(356)}", v2Line(356).contains("in.read(buffer)"))
        assertTrue("v2 的缓冲区分配形状变了：${v2Line(351)}", v2Line(351).contains("new byte[READ_BUFFER_SIZE]"))
        // v2:357 len < 0 判断、v2:358 break；v2:360 才是 len > 0 分支（len == 0 落到循环末尾）。
        assertTrue("v2 的 EOF 判断形状变了：${v2Line(357)}", v2Line(357).contains("if (len < 0)"))
        assertEquals("v2 的 EOF 分支必须 break", "break;", v2Line(358).trim())
        assertTrue("v2 的 len > 0 分支形状变了：${v2Line(360)}", v2Line(360).contains("if (len > 0)"))
        // v2 的整个读循环（:350-376）里没有任何 sleep —— 这是"len == 0 不 sleep"的直接证据。
        assertFalse(
            "v2 的读循环里出现了 sleep，本条结论需要重审",
            lines.subList(349, 376).any { it.contains("sleep") }
        )

        // 3.0：读缓冲区仍是 4096（SerialPortBase 的私有常量，只能做源码文本级断言）。
        assertTrue(
            "3.0 的 READ_BUFFER_SIZE 不再是 4096",
            Regex("const val READ_BUFFER_SIZE\\s*=\\s*4096").containsMatchIn(coreSource())
        )
        // 3.0：read == 0 才 sleep，并且 sleep 后立即 continue（不回调、不解码）。
        val coreCode = codeLines(coreSource())
        val zeroBranch = coreCode.indexOfFirst { it == "if (read == 0) {" }
        assertTrue("3.0 找不到 `read == 0` 分支", zeroBranch >= 0)
        assertEquals(
            "3.0 的 read == 0 分支必须先 sleep 再 continue",
            "if (readIdleDelayMillis > 0) Thread.sleep(readIdleDelayMillis)",
            coreCode[zeroBranch + 1]
        )
        assertEquals("read == 0 时不得产生任何 data 回调", "continue", coreCode[zeroBranch + 2])

        // 3.0 的蓝牙读：无限阻塞、无超时、无 available 轮询（与 v2:356 同语义）。
        val connectionCode = codeLines(connectionSource())
        assertTrue(
            "3.0 的 BluetoothSocketConnection.read 不再是直接阻塞读",
            connectionCode.any { it == "override fun read(buffer: ByteArray): Int = input.read(buffer)" }
        )
        assertFalse(
            "3.0 的蓝牙读出现了超时/available 轮询（会改变 v2 的阻塞语义）",
            connectionCode.any { it.contains("timeout", ignoreCase = true) || it.contains("available()") }
        )
    }

    // =====================================================================================
    // 第 5 组：建连顺序与失败清理（清单第 4、5 条）
    // =====================================================================================

    /**
     * 清单第 4 条：`cancelDiscovery()` 必须在 `createRfcommSocketToServiceRecord` **之前**，
     * 且 `SecurityException` 被吞掉（取消发现失败不影响连接）。
     *
     * 这是源码文本级断言（离线可判定"顺序与吞异常"），不是真机调用序实测。
     */
    @Test
    fun cancelDiscoveryPrecedesSocketCreationAndSwallowsSecurityException() {
        val v2Lines = v2Lines()
        // v2 自己的顺序：:320 cancelDiscovery → :325 createRfcommSocketToServiceRecord。
        val v2Cancel = v2Lines.indexOfFirst { it.contains("adapter.cancelDiscovery()") }
        val v2Create = v2Lines.indexOfFirst { it.contains("createRfcommSocketToServiceRecord(SPP_UUID)") }
        assertTrue("v2 里 cancelDiscovery 不在建连之前（v2:320/325）", v2Cancel in 0 until v2Create)

        val code = codeLines(transportSource())
        val cancel = code.indexOfFirst { it.contains("adapter.cancelDiscovery()") }
        val create = code.indexOfFirst { it.contains("remote.createRfcommSocketToServiceRecord(") }
        assertTrue("3.0 里找不到 cancelDiscovery()", cancel >= 0)
        assertTrue("3.0 里找不到建连调用", create >= 0)
        assertTrue("3.0 的 cancelDiscovery() 必须在建连之前（对齐 v2:320-325）", cancel < create)

        // cancelDiscovery 与建连之间只允许有 try/catch，不允许出现 throw（即 SecurityException 被吞掉）。
        val between = code.subList(cancel, create)
        assertTrue(
            "3.0 的 cancelDiscovery() 没有 catch SecurityException：$between",
            between.any { it.contains("catch (ignored: SecurityException)") }
        )
        assertFalse("3.0 在 cancelDiscovery() 失败路径上抛了异常（v2:321-323 是忽略）", between.any { it.contains("throw ") })
    }

    /**
     * 清单第 5 条：建连失败**必须先把 socket 关掉再抛**（v2 `closeQuietly(socket)`），
     * 且 `SecurityException` / `IOException` 两条路径分别映射到 v2 的文案前缀。
     *
     * 源码文本级断言：3.0 的工厂里 `closeQuietly(socket)` 恰好出现两次（两条 catch），
     * 且都排在对外的 `throw` 之前；catch 的先后顺序与 v2:339/342 相同。
     */
    @Test
    fun connectFailureClosesSocketBeforeThrowing() {
        val v2Lines = v2Lines()
        // v2:340 closeQuietly → :341 reject（SecurityException）；v2:343 closeQuietly → :344 reject（IOException）。
        assertTrue("v2 安全异常路径没先关 socket", v2Line(340).contains("closeQuietly(socket)"))
        assertTrue("v2 安全异常路径文案变了", v2Line(341).contains("缺少蓝牙权限: "))
        assertTrue("v2 IO 异常路径没先关 socket", v2Line(343).contains("closeQuietly(socket)"))
        assertTrue("v2 IO 异常路径文案变了", v2Line(344).contains("连接蓝牙设备失败: "))
        // v2:308-309 无效地址走 IllegalArgumentException + 前缀。
        assertTrue("v2 无效地址的捕获变了", v2Line(308).contains("catch (IllegalArgumentException e)"))
        assertTrue("v2 无效地址文案变了", v2Line(309).contains("无效的蓝牙地址: "))

        val code = codeLines(transportSource())
        // 只看「建连之后、类声明之前」这一段：文件前面的 isAdapterEnabled 辅助函数里也有
        // 一句同文案的 `throw SecurityException(...)`，对全文用 indexOf 会命中它而得出错误结论。
        val create = code.indexOfFirst { it.contains("remote.createRfcommSocketToServiceRecord(") }
        val classStart = code.indexOfFirst { it.startsWith("class BluetoothSerialTransport(") }
        assertTrue("3.0 找不到建连调用", create >= 0)
        assertTrue("3.0 找不到类声明（用于圈定工厂范围）", classStart > create)
        val block = code.subList(create, classStart)
        val closes = block.withIndex().filter { it.value == "closeQuietly(socket)" }.map { it.index }
        assertEquals("3.0 的工厂里应当恰好两条失败路径各关一次 socket", 2, closes.size)
        val securityThrow = block.indexOfFirst { it.contains("throw SecurityException(PREFIX_BLUETOOTH_PERMISSION_MISSING") }
        val ioThrow = block.indexOfFirst { it.contains("throw IOException(PREFIX_BLUETOOTH_CONNECT_FAILED") }
        assertTrue("3.0 缺少 SecurityException → 缺少蓝牙权限 的映射", securityThrow >= 0)
        assertTrue("3.0 缺少 IOException → 连接蓝牙设备失败 的映射", ioThrow >= 0)
        assertTrue("先关 socket 再抛（SecurityException 路径，对齐 v2:340-341）", closes[0] < securityThrow)
        assertTrue("先关 socket 再抛（IOException 路径，对齐 v2:343-344）", closes[1] < ioThrow)
        assertTrue("catch 顺序必须与 v2:339/342 相同（Security 在前）", securityThrow < ioThrow)
        // 且必须是**紧邻**的下一行（v2:340-341 / :343-344 就是相邻两行，中间不允许插别的动作）。
        assertEquals("关 socket 与抛异常必须相邻（v2:340-341）", closes[0] + 1, securityThrow)
        assertEquals("关 socket 与抛异常必须相邻（v2:343-344）", closes[1] + 1, ioThrow)
        // 无效地址：3.0 走 getRemoteDevice 的 IllegalArgumentException，文案是 v2:309 的前缀。
        assertTrue(
            "3.0 的无效地址映射与 v2:308-309 不一致",
            code.any { it.contains("catch (e: IllegalArgumentException)") } &&
                code.any { it.contains("throw IllegalArgumentException(PREFIX_BLUETOOTH_INVALID_ADDRESS + address)") }
        )
    }

    // =====================================================================================
    // 第 6 组：ACL 断开广播（清单第 8 条）
    // =====================================================================================

    /**
     * 清单第 8 条：v2 同时监听 `ACTION_ACL_DISCONNECTED` 与 `ACTION_ACL_DISCONNECT_REQUESTED`，
     * 忽略条件是 `device != null && openedAddress != null && !openedAddress.equals(device.getAddress())`。
     *
     * 3.0 的等价条件写在一行里：`if (device != null && current != null && current != device.address) return`。
     * 三个合取项逐一对应，且 `current == null`（= v2 的 `openedAddress == null`）时**不** return
     * —— 与 v2 一样会走断开处理。这是源码文本级等价，不是广播时序实测。
     */
    @Test
    fun aclDisconnectActionsAndFilterConditionMatchV2() {
        val v2Lines = v2Lines()
        // v2:85-86 接收器里先判 action；v2:435-436 注册时挂两个 action。
        assertTrue("v2 接收器少判了 DISCONNECTED", v2Line(85).contains("BluetoothDevice.ACTION_ACL_DISCONNECTED"))
        assertTrue("v2 接收器少判了 DISCONNECT_REQUESTED", v2Line(86).contains("BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED"))
        assertTrue("v2 注册少挂了 DISCONNECTED", v2Line(435).contains("ACTION_ACL_DISCONNECTED"))
        assertTrue("v2 注册少挂了 DISCONNECT_REQUESTED", v2Line(436).contains("ACTION_ACL_DISCONNECT_REQUESTED"))
        // v2:89 从 EXTRA_DEVICE 取设备；v2:90 忽略条件（逐字）。
        assertTrue("v2 取 EXTRA_DEVICE 的方式变了", v2Line(89).contains("intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)"))
        assertEquals(
            "v2:90 的忽略条件变了，本条复核需要重做",
            "if (device != null && openedAddress != null && !openedAddress.equals(device.getAddress())) {",
            v2Line(90).trim()
        )

        val code = codeLines(transportSource())
        // 注册的两个 action 与 v2:435-436 相同（顺序也保持）。
        assertEquals(
            "3.0 注册的 action 与 v2:435-436 不一致",
            listOf(
                "addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)",
                "addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)"
            ),
            code.filter { it.contains("addAction(") }
        )
        // 接收器内部重新判 action（对齐 v2:85-86）。
        assertTrue(
            "3.0 的接收器没有重新判 action（对齐 v2:85-86）",
            code.any { it.contains("if (action != BluetoothDevice.ACTION_ACL_DISCONNECTED") } &&
                code.any { it.contains("action != BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED") }
        )
        // 取 EXTRA_DEVICE：v2:89 用 getParcelableExtra，3.0 用 IntentCompat 的同名封装。
        // 这次调用在 3.0 里跨了多行，所以拼成一行再找（逐行找会漏）。
        val joined = code.joinToString(" ")
        assertTrue(
            "3.0 没有从 EXTRA_DEVICE 取设备",
            joined.contains("IntentCompat.getParcelableExtra( intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java )")
        )
        // 忽略条件逐字等价（三个合取项 + return）。
        assertTrue(
            "3.0 的 ACL 忽略条件与 v2:90 不等价（注意 openedAddress == null 时不得忽略）",
            code.any { it == "if (device != null && current != null && current != device.address) return" }
        )
        // 处理动作：其余情况一律走 handleExternalDisconnect（关连接 + 只通知一次，由基类保证）。
        assertTrue(
            "3.0 没有把 ACL 断开交给 handleExternalDisconnect",
            code.any { it == "handleExternalDisconnect(MESSAGE_BLUETOOTH_DISCONNECTED)" }
        )
        // 3.0 的 openedAddress 赋值（`?: device.id` 是新增的兜底，见报告的差异条目）。
        assertTrue(
            "3.0 的 openedAddress 赋值方式变了",
            code.any { it == "openedAddress = device.address?.takeIf { it.isNotEmpty() } ?: device.id" }
        )
        // 注销接收器时必须幂等（v2:445-455 的 unregisterDisconnectReceiver）。
        assertTrue(
            "3.0 注销接收器不再幂等（对齐 v2:445-455）",
            code.any { it == "if (!disconnectReceiverRegistered) return" }
        )
    }

    // =====================================================================================
    // 第 7 组：关闭语义（清单第 7 条）
    // =====================================================================================

    /**
     * 清单第 7 条：手动 `close()` **不**通知 `closed`；"读 EOF"与"ACL 断开"两条路径都只通知一次。
     *
     * 通知语义本身由 `:core:jvmTest` 的 26 套件覆盖，这里只做两件事：
     *  1. 用 v2 源码确认基准（v2:118-122 的 close 不调 notifyClosedOnce；v2:370-374 读循环 finally 才通知）；
     *  2. 用 3.0 源码确认蓝牙确实接在"notify=false 的手动关闭"与"notify=true 的外部断开"两条路上，
     *     并且自 join 有防护（v2:393）。
     */
    @Test
    fun closeSemanticsMatchV2OnBothNotifyPaths() {
        val v2Lines = v2Lines()
        // v2:120 closePortInternal(); / :121 call.resolve(); —— 没有 notifyClosedOnce。
        assertEquals("closePortInternal();", v2Line(120).trim())
        assertEquals("call.resolve();", v2Line(121).trim())
        assertFalse(
            "v2 的手动 close() 竟然通知了 closed，本条结论需要重审",
            v2Lines.subList(118, 123).any { it.contains("notifyClosedOnce") }
        )
        // v2:371-374 读循环 finally：只有 reading 仍为真才 close + 通知（EOF/异常路径）。
        assertTrue("v2 读循环 finally 的形状变了", v2Line(371).contains("if (reading)"))
        assertTrue("v2 读循环 finally 的形状变了", v2Line(372).contains("closePortInternal()"))
        assertTrue("v2 读循环 finally 的形状变了", v2Line(373).contains("notifyClosedOnce()"))
        // v2:393 不能 join 自己；v2:395 join(1000)。
        assertTrue("v2 的自 join 防护变了", v2Line(393).contains("thread != null && thread != Thread.currentThread()"))
        assertTrue("v2 的 join 超时变了", v2Line(395).contains("thread.join(1000)"))
        // v2:380 reading = false（关闭时先落标志位）。
        assertTrue("v2 的关闭标志位置位变了", v2Line(380).contains("reading = false"))

        val coreCode = codeLines(coreSource())
        // 3.0：手动 close() 就是 teardown(notify = false) —— 与 v2:118-122 一致。
        val manualClose = coreCode.indexOfFirst { it == "final override suspend fun close() {" }
        assertTrue("3.0 找不到 close() 覆盖", manualClose >= 0)
        assertEquals(
            "3.0 的手动 close() 不得通知 closed（对齐 v2:118-122）",
            "teardown(notify = false, reason = null, awaitFlush = true)",
            coreCode[manualClose + 1]
        )
        // 3.0：ACL 广播路径 → handleExternalDisconnect → teardown(notify = true)。
        assertTrue(
            "3.0 的 handleExternalDisconnect 不再通知 closed",
            coreCode.any { it == "teardown(notify = true, reason = reason, awaitFlush = false)" }
        )
        // 3.0：读 EOF（蓝牙读返回 -1）与读异常两条路径都通知一次。
        assertTrue(
            "3.0 的读 EOF 路径不再通知 closed（对齐 v2:357-358 + :370-374）",
            coreCode.any { it == "if (bound) teardown(notify = true, reason = \"串口读取结束\", awaitFlush = false)" }
        )
        assertTrue(
            "3.0 的读异常路径不再通知 closed（对齐 v2:368-374）",
            coreCode.any { it == "if (bound) teardown(notify = true, reason = \"读取串口失败\", awaitFlush = false)" }
        )
        // 3.0：teardown 的幂等闸门 + 自 join 防护 + 只在 notify 时通知一次。
        assertTrue("3.0 的 teardown 幂等闸门变了", coreCode.any { it == "if (closing || (!bound && connection == null)) {" })
        assertTrue("3.0 缺少自 join 防护（对齐 v2:393）", coreCode.any { it == "if (reader != null && reader !== Thread.currentThread()) {" })
        assertTrue("3.0 的 teardown 结尾不再按 notify 决定是否通知", coreCode.any { it == "if (notify) notifyClosedOnce()" })
        // 手动 close 期间被唤醒的读线程不得把"我们自己关端口导致的 EOF"当成拔出：
        // 停止判据用 bound（teardown 里 bound = false 在 conn.close() 之前）。
        assertTrue(
            "3.0 的读线程停止判据不再是 bound（会多发一次 closed）",
            coreCode.count { it == "val conn = if (bound) connection else null" } == 1
        )
    }

    // =====================================================================================
    // 第 8 组：Android 12+ 权限门禁（清单第 9 条）
    // =====================================================================================

    /**
     * 清单第 9 条：v2 的 `withPermission` 是"未授权就请求，回调后再 dispatch"。版本判定与 v2 一致，
     * 但**接线范围不同**：v2 的 `list`（v2:104）与 `open`（v2:112）各自都过 `withPermission`，
     * 而 3.0 的传输层只在 `list()` 与 `ensureBluetoothPermission()` 里过门禁。
     *
     * 这个测试把"基准"和"接线范围"都钉住（源码文本级），差异的影响面见报告。
     */
    @Test
    fun permissionBoundaryMatchesV2AndGateWiringIsNarrowerThanV2() {
        // v2:104 / :112 —— list 与 open 各自都过 withPermission（这正是 3.0 的差异点）。
        assertTrue("v2 的 list 不再过权限门禁", v2Line(104).contains("withPermission(call, \"list\")"))
        assertTrue("v2 的 open 不再过权限门禁", v2Line(112).contains("withPermission(call, \"open\")"))
        // v2:202-203 判定；v2:209 申请；v2:223 回调里再 dispatch。
        assertTrue("v2 的版本判定变了", v2Line(202).contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.S"))
        assertTrue("v2 的授权判定变了", v2Line(203).contains("PermissionState.GRANTED"))
        assertTrue("v2 不再请求权限", v2Line(209).contains("requestPermissionForAlias("))
        assertTrue("v2 的权限回调不再 dispatch", v2Line(223).contains("dispatch(saved, action)"))

        // 版本边界：`Build.VERSION_CODES.S` == 31；30 放行、31 未授权必须申请、31 已授权放行。
        assertEquals(31, BLUETOOTH_CONNECT_MIN_SDK)
        assertEquals(BluetoothConnectPermission.NotRequired, decideBluetoothConnectPermission(30, granted = false))
        assertEquals(BluetoothConnectPermission.MustRequest, decideBluetoothConnectPermission(31, granted = false))
        assertEquals(BluetoothConnectPermission.Granted, decideBluetoothConnectPermission(31, granted = true))

        // 接线范围：3.0 全文件只有两处调用门禁（list() 与 ensureBluetoothPermission()）。
        val transportCode = transportSource()
        assertEquals(
            "3.0 的蓝牙传输层调用门禁的次数变了，权限接线需要重新复核",
            2,
            Regex("ensureGranted\\(\\)").findAll(transportCode).count()
        )
        assertTrue(
            "3.0 没有暴露 ensureBluetoothPermission()（Phase 4 在 open 之前唯一的补门禁入口）",
            codeLines(transportCode).any { it == "suspend fun ensureBluetoothPermission() {" }
        )
        // `open` 在 :core 里是 final，子类无法在 open 路径上插入门禁 —— 这是该差异的根因。
        assertFalse(
            "3.0 竟然覆盖了 open()，权限差异的结论需要重审",
            codeLines(transportCode).any { it.contains("override suspend fun open(") }
        )
        assertTrue(
            "SerialTransport.open 在 :core 里不再是 final：接线方式需要重审",
            codeLines(coreSource()).any { it == "final override suspend fun open(device: SerialDeviceInfo, baudRate: Int) {" }
        )
    }

    // =====================================================================================
    // 第 9 组：设备列表映射（v2 doList 的纯规则，清单第 3 条的"未配对"语境）
    // =====================================================================================

    /**
     * v2:263-278 的映射规则（纯函数部分）：
     *  * `id` / `address` 都是 MAC（v2 用 address 同时当标识与展示兜底）；
     *  * 名字为 null 或空串时退化成 MAC；v2:275 判的是 `isEmpty()`，**纯空白名字原样保留**；
     *  * 顺序即系统顺序、按地址去重（对应 `Set<BluetoothDevice>`）；
     *  * USB 专有字段全为 null。
     *
     * 注意：v2 里**没有**"未配对"这个独立文案 —— 打开一台未配对设备会走到 v2:344 的
     * `连接蓝牙设备失败: `（配对是用户在系统设置里完成的，v2:177-187 只负责跳转设置页）。
     */
    @Test
    fun bondedDeviceMappingFollowsV2Rules() {
        // v2:275 用的是 isEmpty 而不是 isBlank。
        assertTrue("v2:275 的名字判空规则变了", v2Line(275).contains("!name.isEmpty()"))

        val infos = bondedDevicesToSerialInfos(
            listOf(
                BondedDeviceSnapshot("00:11:22:33:44:55", null),
                BondedDeviceSnapshot("AA:BB:CC:DD:EE:FF", ""),
                BondedDeviceSnapshot("11:22:33:44:55:66", " "),
                BondedDeviceSnapshot("00:11:22:33:44:55", "重复地址只保留第一次")
            )
        )
        assertEquals("v2:275 的三元规则没复刻（null/空串退化成 MAC、纯空白保留）",
            listOf("00:11:22:33:44:55", "AA:BB:CC:DD:EE:FF", " "), infos.map { it.name })
        assertEquals(
            listOf("00:11:22:33:44:55", "AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"),
            infos.map { it.id }
        )
        assertEquals("id 与 address 必须都是 MAC", infos.map { it.address }, infos.map { it.id })
        assertTrue("kind 必须是蓝牙", infos.all { it.kind == TransportKind.Bluetooth })
        assertTrue(
            "USB 专有字段必须为 null",
            infos.all { it.deviceId == null && it.vendor == null && it.product == null && it.vendorId == null && it.productId == null }
        )
        assertTrue("空输入必须给空列表", bondedDevicesToSerialInfos(emptyList()).isEmpty())
    }

    private companion object {
        /** v2 的 Capacitor 插件源码（判据文件）。 */
        const val V2_PLUGIN = "android/app/src/main/java/com/lasergrbl/android/BluetoothSerialPlugin.java"

        /** 3.0 的蓝牙传输层。 */
        const val BT_TRANSPORT = "app/src/main/kotlin/com/lasergrbl/android/serial/BluetoothSerialTransport.kt"

        /** 3.0 的蓝牙字节通道。 */
        const val BT_CONNECTION = "app/src/main/kotlin/com/lasergrbl/android/serial/BluetoothSocketConnection.kt"

        /** 3.0 的共有状态机。 */
        const val CORE_SERIAL_BASE = "core/src/commonMain/kotlin/com/lasergrbl/core/serial/SerialPortBase.kt"
    }
}
