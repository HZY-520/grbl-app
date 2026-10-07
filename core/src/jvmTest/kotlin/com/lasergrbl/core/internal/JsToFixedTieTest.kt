package com.lasergrbl.core.internal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `jsToFixed` 的**并列（exact half）行为**回归测试 —— 这是移植里最容易被"看起来像规范"骗过去的一处。
 *
 * ### 为什么必须钉住它
 * ECMAScript `Number::toFixed` 的步骤 8/9 在"两个 `n` 都满足
 * `|n / 10^f - x| <= 10^-f / 2`"时要求 **"pick the larger n"**。
 * 对正数那等于远离零；对**负数**（幅度超过一半时）"取较大的 n"会得到**朝零**的结果，
 * 而 **V8 实测给的是远离零**——也就是说 **V8 的实现与规范文本在负数并列上不一致**。
 * 黄金样本由 V8 生成、真机跑的是 V8 的产物，所以判据只能是 **V8 的实际行为**。
 *
 * 本项目曾经写成"正数 `HALF_UP`、负数 `HALF_DOWN`"（即"并列朝 +∞"），
 * 结果 `-1.5625 → "-1.562"` 而 V8 给 `"-1.563"`。这不是学术差异：
 * `G1 X.. Y-1.563` 里的 0.001 mm 是**协议输出**的一部分，
 * 而 `-1.5625` 这种二进制精确的并列值在几何换算里真的会出现
 * （例如 `y = -92.0 * (30.0 / 128.0) + 20.0`）。
 *
 * ### 期望值的来源
 * 下面每一条都出自本机 V8 的实测（`node -e` 逐个跑过），**不是从规范推的**：
 * ```
 * ( 1.5625).toFixed(3) =  "1.563"    (-1.5625).toFixed(3) = "-1.563"
 * ( 0.0625).toFixed(3) =  "0.063"    (-0.0625).toFixed(3) = "-0.063"
 * ( 1.5   ).toFixed(0) =  "2"        (-1.5   ).toFixed(0) = "-2"
 * ( 0.5   ).toFixed(0) =  "1"        (-0.5   ).toFixed(0) = "-1"
 * ( 2.5   ).toFixed(0) =  "3"        (-2.5   ).toFixed(0) = "-3"
 * ( 0.0005).toFixed(3) =  "0.001"    (-0.0005).toFixed(3) = "-0.001"
 * (34.0625).toFixed(3) = "34.063"    (-34.0625).toFixed(3) = "-34.063"
 * ```
 * ⚠️ 注意 `(-0.0625).toFixed(0) === "-0"`（V8 保留负号的零）——**本实现没有复刻**这一点，
 * 因为 v2 的 G 代码输出路径只用到 `toFixed(1..3)` 且值不会落在那个区间；
 * 这条差异登记在这里，不要在没验证调用方的前提下"顺手修"。
 */
class JsToFixedTieTest {

    @Test
    fun positiveTiesRoundAwayFromZero() {
        assertEquals("1.563", jsToFixed(1.5625, 3))
        assertEquals("0.063", jsToFixed(0.0625, 3))
        assertEquals("34.063", jsToFixed(34.0625, 3))
        assertEquals("2", jsToFixed(1.5, 0))
        assertEquals("1", jsToFixed(0.5, 0))
        assertEquals("3", jsToFixed(2.5, 0))
        assertEquals("0.001", jsToFixed(0.0005, 3))
    }

    @Test
    fun negativeTiesAlsoRoundAwayFromZero() {
        // 这一组就是本回归测试存在的理由：曾经全部差 1 个最低位
        assertEquals("-1.563", jsToFixed(-1.5625, 3))
        assertEquals("-0.063", jsToFixed(-0.0625, 3))
        assertEquals("-34.063", jsToFixed(-34.0625, 3))
        assertEquals("-2", jsToFixed(-1.5, 0))
        assertEquals("-1", jsToFixed(-0.5, 0))
        assertEquals("-3", jsToFixed(-2.5, 0))
        assertEquals("-0.001", jsToFixed(-0.0005, 3))
    }

    @Test
    fun geometryDerivedTieMatchesV8() {
        // 真实来源：ImageVector 的 `outline-tuned` 变体里那一行几何换算
        // y = -92.0 * (30.0 / 128.0) + 20.0，二进制精确等于 -1.5625
        val y = -92.0 * (30.0 / 128.0) + 20.0
        assertEquals(-1.5625, y, "前提变了：这个表达式不再精确等于 -1.5625")
        assertEquals("-1.563", jsToFixed(y, 3))
        assertEquals("G1 X16.055 Y-1.563", "G1 X${jsToFixed(16.0554, 3)} Y${jsToFixed(y, 3)}")
    }

    @Test
    fun nonTiesAreUnaffected() {
        assertEquals("0.125", jsToFixed(0.125, 3))
        assertEquals("-0.125", jsToFixed(-0.125, 3))
        assertEquals("1.234", jsToFixed(1.2344, 3))
        assertEquals("-1.234", jsToFixed(-1.2344, 3))
        assertEquals("-2.346", jsToFixed(-2.3456, 3))
    }

    @Test
    fun nonFiniteValuesBecomeZero() {
        // 与 v2 一致：非有限值不抛异常，输出 "0"（由调用方保证不会走到这里）
        assertEquals("0", jsToFixed(Double.NaN, 3))
        assertEquals("0", jsToFixed(Double.POSITIVE_INFINITY, 3))
        assertEquals("0", jsToFixed(Double.NEGATIVE_INFINITY, 3))
    }
}
