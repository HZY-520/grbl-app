package com.lasergrbl.core.vector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 对照 v2 `src/core/vector/Paths.ts` 的行为。 */
class PathsTest {

    @Test
    fun fmtMatchesJsToFixedThenTrimsTrailingZeros() {
        assertEquals("1.5", fmt(1.5, 3))
        assertEquals("1", fmt(1.0, 3))
        assertEquals("0", fmt(0.0, 3))
        assertEquals("0", fmt(-0.0, 3))
        assertEquals("-1.25", fmt(-1.25, 3))
        assertEquals("0.333", fmt(1.0 / 3.0, 3))
        assertEquals("0", fmt(-0.0001, 3))
        assertEquals("0.001", fmt(0.0005, 3))
        // JS toFixed 的并列是**远离零**取整（V8 实测，正负都一样）：
        //   (-1.5).toFixed(0) === "-2"    (1.5).toFixed(0) === "2"
        // ⚠️ 这里原来写的是 "-1" 并注释成"并列朝 +∞"——那是**错的**，
        // 依据见 core/src/jvmMain/.../internal/JsNumber.jvm.kt 的 KDoc 与
        // core/src/jvmTest/.../internal/JsToFixedTieTest.kt（本机 node -e 逐个实测过）。
        assertEquals("-2", fmt(-1.5, 0))
        assertEquals("2", fmt(1.5, 0))
        // 非有限值按 0 处理
        assertEquals("0", fmt(Double.NaN, 3))
        assertEquals("0", fmt(Double.POSITIVE_INFINITY, 3))
    }

    @Test
    fun optimizeOrderPicksNearestAndReversesWhenCloser() {
        val a = Polyline(listOf(Pt(10.0, 0.0), Pt(20.0, 0.0)))
        val b = Polyline(listOf(Pt(0.0, 0.0), Pt(1.0, 0.0)))
        val ordered = optimizeOrder(listOf(a, b), Pt(0.0, 0.0))
        // 先接 b（头端更近），再接 a
        assertEquals(2, ordered.size)
        assertEquals(Pt(0.0, 0.0), ordered[0].pts[0])

        // 尾端更近时应反向接入
        val c = Polyline(listOf(Pt(50.0, 0.0), Pt(2.0, 0.0)))
        val ordered2 = optimizeOrder(listOf(c), Pt(0.0, 0.0))
        assertEquals(Pt(2.0, 0.0), ordered2[0].pts[0])
        assertEquals(Pt(50.0, 0.0), ordered2[0].pts[1])
    }

    @Test
    fun optimizeOrderKeepsEmptyPolylinesAtTheEnd() {
        val empty = Polyline(emptyList())
        val real = Polyline(listOf(Pt(1.0, 1.0), Pt(2.0, 2.0)))
        val ordered = optimizeOrder(listOf(empty, real), Pt(0.0, 0.0))
        assertEquals(2, ordered.size)
        assertTrue(ordered[0].pts.isNotEmpty())
        assertTrue(ordered[1].pts.isEmpty())
    }

    @Test
    fun simplifyPathKeepsEndpointsAndDropsCollinearPoints() {
        val pts = listOf(
            Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(2.0, 0.0), Pt(3.0, 0.0), Pt(4.0, 0.0)
        )
        val simplified = simplifyPath(pts, 0.1)
        assertEquals(listOf(Pt(0.0, 0.0), Pt(4.0, 0.0)), simplified)

        // 有拐点时保点
        val bent = listOf(Pt(0.0, 0.0), Pt(5.0, 5.0), Pt(10.0, 0.0))
        assertEquals(bent, simplifyPath(bent, 0.1))

        // n <= 2 原样返回
        assertEquals(listOf(Pt(0.0, 0.0)), simplifyPath(listOf(Pt(0.0, 0.0)), 0.1))
    }

    @Test
    fun polylinesToGcodeEmitsTravelMarkAndFeedChanges() {
        val paths = listOf(
            Polyline(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0)))
        )
        val result = polylinesToGcode(
            paths,
            PolylineGcodeOptions(
                pixelSizeMm = 0.1,
                offsetX = 0.0,
                offsetY = 0.0,
                markSpeed = 1000.0,
                travelSpeed = 3000.0,
                minPower = 0,
                maxPower = 1000,
                laserOn = "M3",
                laserOff = "M5",
                pwm = true,
                header = "G21\n; header",
                footer = "G0 X0 Y0"
            )
        )
        assertEquals(
            listOf(
                "G21",
                "; header",
                "G0 X0 Y0 F3000",
                "M3 S1000",
                "G1 X1 Y0 F1000",
                "M5",
                "G0 X0 Y0"
            ),
            result.lines
        )
        assertEquals(1, result.pathCount)
        assertEquals(1.0, result.lengthMm, 1e-9)
    }

    @Test
    fun polylinesToGcodeSkipsDegeneratePathsAndOmitsPowerWhenNotPwm() {
        val result = polylinesToGcode(
            listOf(
                Polyline(listOf(Pt(0.0, 0.0))),                       // 点数 < 2，跳过
                Polyline(listOf(Pt(1.0, 1.0), Pt(1.0, 1.0)))          // 去重后 < 2，跳过
            ),
            PolylineGcodeOptions(
                pixelSizeMm = 1.0,
                offsetX = 0.0,
                offsetY = 0.0,
                markSpeed = 500.0,
                minPower = 0,
                maxPower = 255,
                laserOn = "M4",
                laserOff = "M5",
                pwm = false
            )
        )
        assertTrue(result.lines.isEmpty())
        assertEquals(0, result.pathCount)
    }

    @Test
    fun polylinesToGcodeFlipYNegatesBeforeOffset() {
        val result = polylinesToGcode(
            listOf(Polyline(listOf(Pt(0.0, 0.0), Pt(0.0, 2.0)))),
            PolylineGcodeOptions(
                pixelSizeMm = 1.0,
                offsetX = 0.0,
                offsetY = 10.0,
                markSpeed = 100.0,
                minPower = 0,
                maxPower = 100,
                laserOn = "M3",
                laserOff = "M5",
                pwm = true,
                flipY = true
            )
        )
        // (0,0) → y = -0*1 + 10 = 10；(0,2) → y = -2 + 10 = 8
        assertEquals("G0 X0 Y10", result.lines[0])
        assertEquals("G1 X0 Y8 F100", result.lines[2])
    }
}
