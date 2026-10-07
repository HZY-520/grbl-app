package com.lasergrbl.core.golden

import com.lasergrbl.core.raster.DitheringMode
import com.lasergrbl.core.raster.FORMULA_LABELS
import com.lasergrbl.core.raster.Formula
import com.lasergrbl.core.raster.dither
import com.lasergrbl.core.raster.flipVertical
import com.lasergrbl.core.raster.grayScale
import com.lasergrbl.core.raster.jsToUint8Clamp
import com.lasergrbl.core.raster.testGrayScale
import com.lasergrbl.core.raster.threshold
import com.lasergrbl.core.raster.whitenize
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 图像变换（`src/core/raster/ImageTransform.ts`）对照 v2 的黄金样本。
 *
 * 覆盖 `result` 的每一个分支：`formulaEnum` / `formulaLabels` / `input` / 6 组 `grayScale` /
 * 6 组 `whitenize` / 4 组 `threshold` / 3 组 `dither` / `flipVertical` / `grayScaleThenFlip` /
 * `testGrayScale`，并且**每一组都比 palette-rle 逐像素 + sha256**。
 *
 * `resizeImage` / `toDataURL` 不在样本里（需要真实 canvas），由 `:app` 侧的
 * `ImageResampler` 接缝承担，见 `core/.../raster/ImageTransform.kt` 的 [ResizeSampler]。
 */
class GoldenImageTransformTest {

    private val result: JsonObject get() = Golden.result("image-transform")

    private val width: Int get() = Golden.int(Golden.objectAt(result, "input"), "width")

    private val height: Int get() = Golden.int(Golden.objectAt(result, "input"), "height")

    /** 24×16 合成底图（每个测试都从它的副本开始，保证互不影响）。 */
    private fun template(): IntArray = GoldenBitmap.decode(Golden.objectAt(result, "input"))

    // ------------------------------------------------------------------
    // 枚举与标签
    // ------------------------------------------------------------------

    @Test
    fun formulaEnumAndLabelsMatchOracle() {
        val enumNode = Golden.objectAt(result, "formulaEnum")
        val labelNode = Golden.objectAt(result, "formulaLabels")

        assertEquals(4, Formula.entries.size, "Formula 枚举条目数")
        assertEquals(4, FORMULA_LABELS.size, "FORMULA_LABELS 条目数")

        for (formula in Formula.entries) {
            val key = formula.value.toString()
            // v2 的 TS enum 是双向映射：{"0":"SimpleAverage", ..., "SimpleAverage":0, ...}
            assertEquals(
                Golden.string(enumNode, key),
                formula.name,
                "Formula 名称不一致（数值 $key）"
            )
            assertEquals(
                key,
                Golden.string(enumNode, formula.name),
                "Formula 数值不一致（${formula.name}）"
            )
            assertEquals(
                Golden.string(labelNode, key),
                FORMULA_LABELS[formula],
                "FORMULA_LABELS 文案不一致（数值 $key）"
            )
            assertEquals(formula, Formula.fromValue(formula.value), "fromValue($key) 往返失败")
        }

        // 越界值没有对应枚举（v2 的 else 分支只在 0..3 之外不会出现）
        assertEquals(null, Formula.fromValue(4), "fromValue(4) 应为 null")
        assertEquals(null, Formula.fromValue(-1), "fromValue(-1) 应为 null")
    }

    // ------------------------------------------------------------------
    // 底图解码自检 + 灰度化
    // ------------------------------------------------------------------

    @Test
    fun inputBitmapDecodeMatchesOracleHash() {
        val inputNode = Golden.objectAt(result, "input")
        val data = GoldenBitmap.decode(inputNode)
        assertEquals(24, Golden.int(inputNode, "width"))
        assertEquals(16, Golden.int(inputNode, "height"))
        assertEquals(width * height * 4, data.size, "解码长度应为 w*h*4")
        assertEquals(
            Golden.string(inputNode, "sha256"),
            GoldenBitmap.sha256Rgba(data),
            "底图 palette-rle 解码结果与样本自带 sha256 不符（解码器或 runs 顺序问题）"
        )
    }

    @Test
    fun grayScaleVariantsMatchOracle() {
        val variants = Golden.array("image-transform", "grayScale")
        assertEquals(6, variants.size, "grayScale 变体数量")

        for (element in variants) {
            val node = element.asObject()
            val name = Golden.string(node, "name")
            val args = node["args"]?.jsonArray?.map { it.jsonPrimitive.content.toDouble() }
                ?: error("grayScale/$name 缺少 args")
            assertEquals(6, args.size, "grayScale/$name 的 args 应为 [R,G,B,brightness,contrast,formula]")
            val formula = Formula.fromValue(args[5].toInt())
                ?: error("grayScale/$name 的 formula=${args[5]} 不是合法枚举值")

            val source = template()
            val data = source.copyOf()
            grayScale(data, args[0], args[1], args[2], args[3], args[4], formula)

            assertBitmapMatches(Golden.objectAt(node, "output"), data, "grayScale/$name")

            // v2 明确"alpha 保持不变"：循环体只写 R/G/B
            for (i in data.indices step 4) {
                assertEquals(
                    source[i + 3],
                    data[i + 3],
                    "grayScale/$name 不应改动 alpha（像素 ${i / 4}）"
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 白裁 / 阈值化
    // ------------------------------------------------------------------

    @Test
    fun whitenizeThresholdsMatchOracle() {
        val variants = Golden.array("image-transform", "whitenize")
        assertEquals(6, variants.size, "whitenize 变体数量")

        for (element in variants) {
            val node = element.asObject()
            val thresholdValue = Golden.double(node, "threshold")
            val label = "whitenize/threshold=$thresholdValue"

            val source = template()
            val data = source.copyOf()
            whitenize(data, thresholdValue)

            assertBitmapMatches(Golden.objectAt(node, "output"), data, label)

            // 被裁掉的像素 alpha 变 0，其余像素 alpha 原样保留（v2 只在这两处写 alpha）
            for (i in data.indices step 4) {
                val a = data[i + 3]
                assertTrue(
                    a == 0 || a == source[i + 3],
                    "$label 的 alpha 只能是 0 或原值（像素 ${i / 4}：${source[i + 3]} → $a）"
                )
            }
        }
    }

    @Test
    fun thresholdVariantsMatchOracle() {
        val variants = Golden.array("image-transform", "threshold")
        assertEquals(4, variants.size, "threshold 变体数量")

        for (element in variants) {
            val node = element.asObject()
            val name = Golden.string(node, "name")
            val threshold01 = Golden.double(node, "threshold01")
            val apply = Golden.boolean(node, "apply")
            val label = "threshold/$name"

            val data = template().copyOf()
            threshold(data, threshold01, apply)

            assertBitmapMatches(Golden.objectAt(node, "output"), data, label)

            for (i in data.indices step 4) {
                assertEquals(255, data[i + 3], "$label 必须把 alpha 一律置 255（像素 ${i / 4}）")
                if (apply) {
                    for (c in 0 until 3) {
                        val v = data[i + c]
                        assertTrue(v == 0 || v == 255, "$label 二值化后只允许 0/255（像素 ${i / 4}，通道 $c：$v）")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 抖动（委托 ditherImage）
    // ------------------------------------------------------------------

    @Test
    fun ditherModesMatchOracle() {
        val variants = Golden.array("image-transform", "dither")
        assertEquals(3, variants.size, "dither 变体数量")

        for (element in variants) {
            val node = element.asObject()
            val modeValue = Golden.string(node, "mode")
            val mode = DitheringMode.fromValue(modeValue) ?: error("未知抖动模式 $modeValue")
            val label = "dither/$modeValue"

            val source = template()
            val data = source.copyOf()
            dither(data, width, height, mode)

            assertBitmapMatches(Golden.objectAt(node, "output"), data, label)

            // v2 注释"保留 alpha"：ditherImage 只写 R/G/B
            for (i in data.indices step 4) {
                assertEquals(source[i + 3], data[i + 3], "$label 不应改动 alpha（像素 ${i / 4}）")
            }
        }
    }

    // ------------------------------------------------------------------
    // 垂直翻转 / 组合 / 灰度判定
    // ------------------------------------------------------------------

    @Test
    fun flipVerticalMatchesOracle() {
        val source = template()
        val before = source.copyOf()
        val out = flipVertical(source, width, height)

        assertBitmapMatches(Golden.objectAt(result, "flipVertical"), out, "flipVertical")

        // v2 返回新 ImageData，入参不被修改
        assertContentEquals(before, source, "flipVertical 不应修改输入数组")
        assertFalse(out === source, "flipVertical 必须返回新数组")

        // 结构性质：第 y 行 == 原图第 height-1-y 行
        val rowBytes = width * 4
        for (y in 0 until height) {
            assertContentEquals(
                source.copyOfRange((height - 1 - y) * rowBytes, (height - y) * rowBytes),
                out.copyOfRange(y * rowBytes, (y + 1) * rowBytes),
                "flipVertical 第 $y 行应等于原图第 ${height - 1 - y} 行"
            )
        }
    }

    @Test
    fun grayScaleThenFlipMatchesOracle() {
        val grayData = template().copyOf()
        // 生成器：grayScale(grayImage, 100, 100, 100, 0, 1, Formula.OpticalCorrect) 然后 flipVertical
        grayScale(grayData, 100.0, 100.0, 100.0, 0.0, 1.0, Formula.OpticalCorrect)
        val flipped = flipVertical(grayData, width, height)

        assertBitmapMatches(Golden.objectAt(result, "grayScaleThenFlip"), flipped, "grayScaleThenFlip")

        // 光学校正后 r == g == b，因此 testGrayScale 必须为 true（读码推断的组合性质）
        assertTrue(testGrayScale(grayData), "grayScale(OpticalCorrect) 之后应为灰度图")
        assertTrue(testGrayScale(flipped), "翻转不改变灰度性质")
    }

    @Test
    fun testGrayScaleMatchesOracle() {
        val node = Golden.objectAt(result, "testGrayScale")
        val colorful = Golden.boolean(node, "colorful")
        val grayOnly = Golden.boolean(node, "grayOnly")

        // 样本自报的两个期望值（防止"对照物本身写反"）
        assertFalse(colorful, "样本里的彩色合成图应为 false")
        assertTrue(grayOnly, "样本里的纯灰图应为 true")

        assertEquals(colorful, testGrayScale(template()), "testGrayScale(彩色合成图)")

        val grayNode = Golden.objectAt(node, "grayOnlyImage")
        val grayData = GoldenBitmap.decode(grayNode)
        assertEquals(8, Golden.int(grayNode, "width"), "纯灰图宽度")
        assertEquals(4, Golden.int(grayNode, "height"), "纯灰图高度")
        assertEquals(
            Golden.string(grayNode, "sha256"),
            GoldenBitmap.sha256Rgba(grayData),
            "纯灰图 palette-rle 解码结果与样本自带 sha256 不符"
        )
        for (i in grayData.indices step 4) {
            assertTrue(
                grayData[i] == grayData[i + 1] && grayData[i + 1] == grayData[i + 2],
                "纯灰图第 ${i / 4} 个像素应满足 r == g == b"
            )
        }
        assertEquals(grayOnly, testGrayScale(grayData), "testGrayScale(纯灰图)")

        // 空数组返回 true（v2 的 for 循环不执行）—— 读码推断，样本未覆盖
        assertTrue(testGrayScale(IntArray(0)), "空图的 testGrayScale 应为 true")
    }

    // ------------------------------------------------------------------
    // 取整规则（黄金样本之外的边界兜底）
    // ------------------------------------------------------------------

    @Test
    fun clampHelperFollowsEcmaScriptToUint8Clamp() {
        // 与 ECMA-262 ToUint8Clamp 的步骤一一对应
        assertEquals(0, jsToUint8Clamp(Double.NaN), "NaN → +0")
        assertEquals(0, jsToUint8Clamp(-1.0e9), "远小于 0 → 0")
        assertEquals(0, jsToUint8Clamp(-0.5), "≤ 0 先夹取，-0.5 → 0（不是 -1）")
        assertEquals(0, jsToUint8Clamp(0.0))
        assertEquals(255, jsToUint8Clamp(255.0))
        assertEquals(255, jsToUint8Clamp(1.0e9), "远大于 255 → 255")

        // 并列取偶：0.5→0、1.5→2、2.5→2、3.5→4、254.5→254
        assertEquals(0, jsToUint8Clamp(0.5))
        assertEquals(2, jsToUint8Clamp(1.5))
        assertEquals(2, jsToUint8Clamp(2.5))
        assertEquals(4, jsToUint8Clamp(3.5))
        assertEquals(254, jsToUint8Clamp(254.5))

        // 非并列：四舍五入到最近
        assertEquals(255, jsToUint8Clamp(254.5000001))
        assertEquals(254, jsToUint8Clamp(254.4999999))
        assertEquals(255, jsToUint8Clamp(254.745), "朝零截断会得到 254，必须得到 255")
        assertEquals(1, jsToUint8Clamp(0.5000001))
        assertEquals(0, jsToUint8Clamp(0.4999999))
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 逐像素 + sha256 比对：先比长度，再逐通道找出全部不一致（失败信息里给出前 5 个的
     * 像素坐标与通道名），最后比 sha256（双向：样本自带 vs 解码、解码 vs 实际）。
     */
    private fun assertBitmapMatches(node: JsonObject, actual: IntArray, label: String) {
        val w = Golden.int(node, "width")
        val h = Golden.int(node, "height")
        assertEquals(width, w, "$label 的输出宽度")
        assertEquals(height, h, "$label 的输出高度")

        val expected = GoldenBitmap.decode(node)
        assertEquals(expected.size, actual.size, "$label 的 RGBA 长度")

        var mismatches = 0
        val details = StringBuilder()
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                mismatches++
                if (mismatches <= 5) {
                    val pixel = i / 4
                    details.append(" #$pixel(x=").append(pixel % w).append(",y=").append(pixel / w)
                        .append(",通道 ").append("RGBA"[i % 4])
                        .append(" 期望 ").append(expected[i]).append(" 实际 ").append(actual[i]).append(';')
                }
            }
        }
        assertEquals(
            0,
            mismatches,
            "$label 有 $mismatches 个通道与样本不一致（共 ${expected.size} 个通道）：$details"
        )

        val expectedSha = GoldenBitmap.sha256Rgba(expected)
        assertEquals(
            Golden.string(node, "sha256"),
            expectedSha,
            "$label 的样本 sha256 与解码结果不符（样本或解码器问题）"
        )
        assertEquals(expectedSha, GoldenBitmap.sha256Rgba(actual), "$label 的 RGBA sha256 不一致")
    }
}
