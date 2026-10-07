package com.lasergrbl.core.serial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Utf8StreamDecoder] 的对抗性单测 —— 验收清单第 2 条里的「UTF-8 跨块拼接（增量解码器）」。
 *
 * 为什么必须逐字节喂：串口按块返回字节，一个多字节字符完全可能被切在两次 `read` 之间。
 * v2 的 Android 插件是「每块 `new String(bytes, UTF_8)`」，块边界上会吐 `\uFFFD`；
 * 本文件的用例就是钉死「复刻 WHATWG `TextDecoder.decode(value, { stream: true })` 语义」这件事。
 *
 * 判据来源：WHATWG Encoding Standard 的 UTF-8 decoder 算法（非法序列在**首个**非法字节处
 * 重新同步，每个非法字节输出一个 U+FFFD，解析器状态跨块保留）。
 */
class Utf8StreamDecoderTest {

    /** 把 [text] 的 UTF-8 字节**逐字节**喂给解码器，返回「解码输出」与「flush 输出」。 */
    private fun decodeByteByByte(text: String): Pair<String, String> {
        val decoder = Utf8StreamDecoder()
        val out = StringBuilder()
        for (b in text.encodeToByteArray()) {
            out.append(decoder.decode(byteArrayOf(b)))
        }
        return out.toString() to decoder.flush()
    }

    // =========================================================================
    // 1. 跨块拼接：合法序列
    // =========================================================================

    @Test
    fun 逐字节喂入时中文与度数符号跨块拼接_不得出现替换符() {
        val text = "温度: 25°C\n"
        val (out, tail) = decodeByteByByte(text)
        assertEquals(text, out, "逐字节喂入后必须逐字符还原：$out")
        assertEquals("", tail, "完整序列不应在 flush 时吐东西")
        assertFalse(out.contains('\uFFFD'), "跨块拼接不得出现替换符：$out")
    }

    @Test
    fun 三字节汉字被切成两块_先两块再一块() {
        val decoder = Utf8StreamDecoder()
        val bytes = "温".encodeToByteArray()
        assertEquals(3, bytes.size, "「温」在 UTF-8 里是 3 字节")
        assertEquals("", decoder.decode(bytes.copyOfRange(0, 2)), "不足一个字符时不得输出替换符")
        assertEquals("温", decoder.decode(bytes.copyOfRange(2, 3)), "补齐后应解出完整字符")
        assertEquals("", decoder.flush())
    }

    @Test
    fun 四字节emoji被切成一块加三块() {
        val decoder = Utf8StreamDecoder()
        val bytes = "🎉".encodeToByteArray()
        assertEquals(4, bytes.size, "emoji 在 UTF-8 里是 4 字节")
        assertEquals("", decoder.decode(bytes.copyOfRange(0, 1)))
        assertEquals("🎉", decoder.decode(bytes.copyOfRange(1, 4)))
        assertEquals("", decoder.flush())
    }

    @Test
    fun 四字节emoji被切成两块加两块() {
        val decoder = Utf8StreamDecoder()
        val bytes = "🎉".encodeToByteArray()
        assertEquals("", decoder.decode(bytes.copyOfRange(0, 2)))
        assertEquals("🎉", decoder.decode(bytes.copyOfRange(2, 4)))
        assertEquals("", decoder.flush())
    }

    @Test
    fun 汉字与ASCII混合分块() {
        val decoder = Utf8StreamDecoder()
        assertEquals("G1X0", decoder.decode("G1X0".encodeToByteArray()))
        assertEquals("温", decoder.decode("温".encodeToByteArray()))
        assertEquals("ok", decoder.decode("ok".encodeToByteArray()))
        assertEquals("", decoder.flush())
    }

    @Test
    fun length参数只处理前n个字节_其余留给下一次() {
        val decoder = Utf8StreamDecoder()
        val buffer = "ABC".encodeToByteArray()
        assertEquals("A", decoder.decode(buffer, 1), "length=1 时只应处理第一个字节")
        assertEquals("ABC", decoder.decode(buffer), "不传 length 时处理整块")
        assertEquals("", decoder.flush())
    }

    // =========================================================================
    // 2. 非法序列：每个非法字节恰好一个替换符，且重新同步
    // =========================================================================

    @Test
    fun 非法首字节0xFF_后随合法文本照常解出() {
        val decoder = Utf8StreamDecoder()
        val input = byteArrayOf(0xFF.toByte()) + "ok\r\n".encodeToByteArray()
        assertEquals("\uFFFDok\r\n", decoder.decode(input), "0xFF 恰好一个替换符，后续 ASCII 必须照常解出")
        assertEquals("", decoder.flush())
    }

    @Test
    fun 孤立续字节0x80_恰好一个替换符且重新同步() {
        val decoder = Utf8StreamDecoder()
        assertEquals("\uFFFD", decoder.decode(byteArrayOf(0x80.toByte())))
        assertEquals("A", decoder.decode("A".encodeToByteArray()))
    }

    @Test
    fun 过长编码C0_80_每个非法字节一个替换符() {
        val decoder = Utf8StreamDecoder()
        assertEquals(
            "\uFFFD\uFFFD",
            decoder.decode(byteArrayOf(0xC0.toByte(), 0x80.toByte())),
            "C0 是过长编码的首字节，C0 与 80 各算一个非法字节"
        )
        assertEquals("A", decoder.decode("A".encodeToByteArray()), "重新同步后合法字符照常解出")
    }

    @Test
    fun 代理区ED_A0_80_合计三个替换符() {
        val decoder = Utf8StreamDecoder()
        val part = decoder.decode(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))
        val tail = decoder.flush()
        val all = part + tail
        assertEquals(3, all.count { it == '\uFFFD' }, "三个非法字节应合计三个替换符，实际：$all")
        assertTrue(all.all { it == '\uFFFD' }, "不该混入别的字符：$all")
    }

    @Test
    fun 代理区序列之后的合法字节不得被吞掉() {
        val decoder = Utf8StreamDecoder()
        val input = byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) + "AB".encodeToByteArray()
        val decoded = decoder.decode(input) + decoder.flush()
        assertEquals(
            "\uFFFD\uFFFD\uFFFDAB",
            decoded,
            "WHATWG 语义：ED A0 80 三个非法字节各一个替换符，随后的 AB 必须照常解出（实测：$decoded）"
        )
    }

    @Test
    fun 首字节合法但续字节非法_合法字节不得被吞掉() {
        val decoder = Utf8StreamDecoder()
        // E4 是「温」的首字节，后面跟的不是续字节而是 ASCII 的 'A'
        val input = byteArrayOf(0xE4.toByte()) + "A".encodeToByteArray()
        val decoded = decoder.decode(input) + decoder.flush()
        assertEquals("\uFFFDA", decoded, "E4 非法（续字节缺失）→ 一个替换符，且 'A' 必须照常解出（实测：$decoded）")
    }

    @Test
    fun 非法序列表_每个非法字节一个替换符且后续合法字节一个不丢() {
        // 判据与 WHATWG / CPython 的 UTF-8 解码器一致（已用 Python 交叉核对过期望值）：
        // 每个非法字节产出恰好一个 U+FFFD，在首个非法字节处重新同步，后续合法字节一个不丢。
        val cases: List<Pair<ByteArray, String>> = listOf(
            // E4 后跟非续字节：只吃 E4，'A' 照常解出
            byteArrayOf(0xE4.toByte(), 0x41) to "\uFFFDA",
            // 过长编码 C0 80：C0 与 80 各一个替换符
            byteArrayOf(0xC0.toByte(), 0x80.toByte()) to "\uFFFD\uFFFD",
            byteArrayOf(0xC0.toByte(), 0x80.toByte(), 0x41) to "\uFFFD\uFFFDA",
            // 代理区 ED A0 80：三个非法字节三个替换符
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) to "\uFFFD\uFFFD\uFFFD",
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x41, 0x42) to "\uFFFD\uFFFD\uFFFDAB",
            // 非法首字节 FF / F5（超出 U+10FFFF）/ 孤立续字节 80
            byteArrayOf(0xFF.toByte(), 0x41) to "\uFFFDA",
            byteArrayOf(0xFF.toByte()) to "\uFFFD",
            byteArrayOf(0xF5.toByte(), 0x41) to "\uFFFDA",
            byteArrayOf(0x80.toByte(), 0x41) to "\uFFFDA"
        )
        for ((input, want) in cases) {
            val decoder = Utf8StreamDecoder()
            val got = decoder.decode(input) + decoder.flush()
            val hex = input.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            assertEquals(want, got, "输入 [$hex] 的解码结果不符（合法字节不允许被吞掉）")
        }
    }

    @Test
    fun 四字节emoji被切成一块加三块_后续合法字节照常() {
        val decoder = Utf8StreamDecoder()
        val bytes = "💩".encodeToByteArray() // F0 9F 92 A9 ⇒ U+1F4A9
        assertEquals(4, bytes.size, "emoji 在 UTF-8 里是 4 字节")
        assertEquals("", decoder.decode(bytes.copyOfRange(0, 1)), "不足一个字符时不得输出替换符")
        assertEquals("💩", decoder.decode(bytes.copyOfRange(1, 4)))
        assertEquals("A", decoder.decode("A".encodeToByteArray()), "补齐后合法字节照常解出")
        assertEquals("", decoder.flush())
    }

    // =========================================================================
    // 3. flush：流结束时的截断序列
    // =========================================================================

    @Test
    fun flush对挂起的截断序列输出替换符_且幂等() {
        val decoder = Utf8StreamDecoder()
        val bytes = "温".encodeToByteArray()
        assertEquals("", decoder.decode(bytes.copyOfRange(0, 2)), "挂起的截断序列不能在 decode 时吐替换符")
        assertEquals("\uFFFD\uFFFD", decoder.flush(), "每个挂起字节一个替换符")
        assertEquals("", decoder.flush(), "flush 必须幂等（重复调用不再输出）")
    }
}
