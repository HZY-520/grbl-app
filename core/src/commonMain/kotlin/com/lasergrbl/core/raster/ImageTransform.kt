package com.lasergrbl.core.raster

import com.lasergrbl.core.vector.PotraceImage
import kotlin.math.floor

/**
 * 图像变换 —— 逐行移植自 v2 `src/core/raster/ImageTransform.ts`（174 行），
 * 后者又移植自 LaserGRBL 的 `RasterConverter/ImageTransform.cs`。
 *
 * ### 移植范围
 *
 * | v2 导出 | 本文件 | 备注 |
 * | --- | --- | --- |
 * | `Formula` / `FORMULA_LABELS` | [Formula] / [FORMULA_LABELS] | 数值 0..3 与中文标签逐字一致 |
 * | `grayScale` | [grayScale] | 权重表 + 亮度/对比度 + 夹取 |
 * | `whitenize` | [whitenize] | 严格不等号的白色窗口 |
 * | `threshold` | [threshold] | 先合成白底再二值化 |
 * | `dither` | [dither] | 直接委托 [ditherImage]，不重新实现 |
 * | `flipVertical` | [flipVertical] | 返回新数组，不修改入参 |
 * | `testGrayScale` | [testGrayScale] | 逐像素判 `r != g 或 g != b` |
 * | `resizeImage` | 仅留接缝 [ResizeSampler] | 需要真实 canvas，见下 |
 * | `toDataURL` | **不移植** | 预览用的 PNG 编码，属于平台层 |
 *
 * ### 为什么 `resizeImage` / `toDataURL` 不能纯 Kotlin 复刻
 *
 * v2 的 `resizeImage` 走的是 `document.createElement('canvas')` + `ctx.drawImage(...)` +
 * `ctx.imageSmoothingQuality = 'high'`：**重采样内核是浏览器实现的**，既没有公开算法，
 * 也不在 ECMAScript 规范里；Node 环境同样没有 Canvas，所以 `tools/golden/generate.ts`
 * 刻意没有捕获这条路径（见该文件的 excludes 说明）。同理 `toDataURL` 依赖 PNG 编码器。
 * 把这两者硬写成"看起来像"的 Kotlin 实现，会得到一个无法用黄金样本判定的近似品，
 * 违背本项目"逐行复刻 + 黄金样本证明一致"的判据；因此这里只声明可注入的接缝
 * [ResizeSampler]（`:app` 侧已有实现，见 `com.lasergrbl.android.platform.ImageResampler`），
 * 由 :core 的算法消费"重采样后的 RGBA"，而不关心它怎么来的。
 *
 * ### JS 语义要点（`Uint8ClampedArray` 的写入）
 *
 * v2 的像素缓冲是 `Uint8ClampedArray`，写入浮点数时执行 ECMA-262 的 **ToUint8Clamp**：
 * 先夹到 0..255，再"四舍五入到最近、**并列取偶**"。这与 Kotlin 常见的两种写法都不同：
 *
 *  * `value.toInt()` 是**朝零截断**（`254.7` → `254`），小数部分 ≥ 0.5 时会差 1；
 *  * `Math.round` / `kotlin.math.round` 是 `floor(value + 0.5)`，**并列一律进位**
 *    （`0.5` → `1`、`2.5` → `3`），与并列取偶（`0.5` → `0`、`2.5` → `2`）不同。
 *
 * 所以本文件用显式的 [jsToUint8Clamp] 复刻规范，并在每个"写入浮点值"的位置调用它。
 * 注意 v2 的 `grayScale` 自己先做了 `v < 0 ? 0 : v > 255 ? 255 : v`，因此那里进入
 * clamp 的一定落在 0..255 区间内（只有取整规则起作用）；`threshold` 的非二值化分支
 * 则把合成值直接交给缓冲，夹取与取整都交给 [jsToUint8Clamp]。
 */

/** 灰度化权重公式（对应 v2 的 `Formula` 枚举，数值 0..3 逐一对齐）。 */
enum class Formula(val value: Int) {
    /** 0：简单平均（0.333 / 0.333 / 0.333）。 */
    SimpleAverage(0),

    /** 1：加权平均（0.333 / 0.444 / 0.222）。 */
    WeightAverage(1),

    /** 2：光学校正 BT.601（0.299 / 0.587 / 0.114）。 */
    OpticalCorrect(2),

    /** 3：自定义 R/G/B（各自按百分比取 0.333 的份额）。 */
    Custom(3);

    companion object {
        /** 按 v2 的数值取枚举；越界返回 null（v2 的 `else` 分支只在 3 之外不会出现）。 */
        fun fromValue(value: Int): Formula? = entries.firstOrNull { it.value == value }
    }
}

/** 公式中文标签，与 v2 `FORMULA_LABELS` 逐字一致（含空格）。 */
val FORMULA_LABELS: Map<Formula, String> = linkedMapOf(
    Formula.SimpleAverage to "简单平均",
    Formula.WeightAverage to "加权平均",
    Formula.OpticalCorrect to "光学校正 (BT.601)",
    Formula.Custom to "自定义 R/G/B"
)

/**
 * 灰度化（对应 v2 `grayScale`，即 `ImageTransform.GrayScale` 的 ColorMatrix 效果）。
 *
 * 逐行语义：
 *  1. 按 [formula] 选权重 `rf/gf/bf`；[Formula.Custom] 用 `0.333 * (R / 100)` 等等；
 *  2. 三个权重各自乘 [contrast]（`rf *= contrast` …）；
 *  3. `brightOffset = brightness * 255`；
 *  4. 每个像素 `v = rf*r + gf*g + bf*b + brightOffset`（float64，左结合，与 JS 一致），
 *     再 `c = v < 0 ? 0 : v > 255 ? 255 : v`，然后把 R/G/B 写成同一个 `c`；
 *  5. **alpha 保持不变**。
 *
 * [red]/[green]/[blue] 仅在 [Formula.Custom] 下参与运算，对应 v2 的 `R`/`G`/`B` 参数
 * （用法是 `0.333 * (R / 100)`，因此 100 表示"1 份 0.333"，200 表示 0.666）。
 *
 * [data] 长度为 `width * height * 4`，RGBA 顺序，每元素 0..255（对应 `Uint8ClampedArray`）。
 */
fun grayScale(
    data: IntArray,
    red: Double,
    green: Double,
    blue: Double,
    brightness: Double,
    contrast: Double,
    formula: Formula
) {
    var rf = 0.0
    var gf = 0.0
    var bf = 0.0
    when (formula) {
        Formula.SimpleAverage -> {
            rf = 0.333
            gf = 0.333
            bf = 0.333
        }

        Formula.WeightAverage -> {
            rf = 0.333
            gf = 0.444
            bf = 0.222
        }

        Formula.OpticalCorrect -> {
            rf = 0.299
            gf = 0.587
            bf = 0.114
        }

        Formula.Custom -> {
            rf = 0.333 * (red / 100.0)
            gf = 0.333 * (green / 100.0)
            bf = 0.333 * (blue / 100.0)
        }
    }

    rf *= contrast
    gf *= contrast
    bf *= contrast
    val brightOffset = brightness * 255.0

    var i = 0
    while (i < data.size) {
        val v = rf * data[i] + gf * data[i + 1] + bf * data[i + 2] + brightOffset
        val c = if (v < 0.0) 0.0 else if (v > 255.0) 255.0 else v
        // ⚠️ 必须走 ToUint8Clamp，**不能** `c.toInt()`：
        // v2 写的是 `Uint8ClampedArray`，取整是"四舍五入到最近、并列取偶"。
        // `(0.333*(200+100+50)) = 116.55` 这种值用 toInt() 会得到 116，
        // 而浏览器/V8 给 117（黄金样本 `grayScale/custom-rgb` 当场把它抓出来了）。
        val packed = jsToUint8Clamp(c)
        data[i] = packed
        data[i + 1] = packed
        data[i + 2] = packed
        // alpha 保持不变（v2 的循环体里没有触碰 px[i + 3]）
        i += 4
    }
}

/**
 * 白色裁剪（对应 v2 `whitenize`，即 `ImageTransform.Whitenize` 的 ColorSubstitution）：
 * 把"接近白色"的像素 RGB 取反并置为全透明（从雕刻路径中剔除）。
 *
 * 逐行语义（窗口是 **[min, max] 的开区间**，四个通道条件全部满足才生效）：
 *  * `min = 255 - threshold`、`max = 255 + threshold`
 *    （[threshold] 对应 v2 的 `threshold: number`，所以用 `Double` 承载；
 *    调用方 `RasterConverter` 传的是整数 `whiteClip`，此时 `min`/`max` 也是整数）；
 *  * `alpha === 0` 的像素**直接跳过**（不是"必然不满足"，而是提前 continue）；
 *  * 满足 `r < max && r > min && g < max && g > min && b < max && b > min` 时：
 *    `r = 255 - r`、`g = 255 - g`、`b = 255 - b`、`alpha = 0`。
 *
 * ⚠️ 边界必须用**严格**不等号：`threshold = 0` 时窗口是 `(255, 255)`，任何像素都不满足
 * （含纯白 255）；写成 `<=` 会把纯白也裁掉，黄金样本 `whitenize[threshold=0]` 会立刻变红。
 */
fun whitenize(data: IntArray, threshold: Double) {
    val min = 255.0 - threshold
    val max = 255.0 + threshold
    var i = 0
    while (i < data.size) {
        val a = data[i + 3]
        if (a != 0) {
            val r = data[i]
            val g = data[i + 1]
            val b = data[i + 2]
            if (r < max && r > min && g < max && g > min && b < max && b > min) {
                data[i] = 255 - r
                data[i + 1] = 255 - g
                data[i + 2] = 255 - b
                data[i + 3] = 0
            }
        }
        i += 4
    }
}

/**
 * 阈值化（对应 v2 `threshold`，即 `ImageTransform.Threshold`）：先合成到白色背景，再二值化。
 *
 * 逐行语义：
 *  * `t = threshold01 * 255`；
 *  * 每个像素 `a = alpha / 255`，`r = R * a + 255 * (1 - a)`（G/B 同理，float64，左结合）；
 *  * [apply] 为 true：`R = r < t ? 0 : 255`（比较用的是**未取整**的 `r`）；
 *    为 false：把合成值写回（写入 `Uint8ClampedArray` → [jsToUint8Clamp]）；
 *  * **无论哪条分支，alpha 一律置 255**。
 *
 * [threshold01] 是 0..1 的归一化阈值（v2 调用方传 `options.threshold / 100`）。
 */
fun threshold(data: IntArray, threshold01: Double, apply: Boolean) {
    val t = threshold01 * 255.0
    var i = 0
    while (i < data.size) {
        val a = data[i + 3] / 255.0
        // 合成到白色背景（非预乘空间，与 v2 逐字一致）
        val r = data[i] * a + 255.0 * (1.0 - a)
        val g = data[i + 1] * a + 255.0 * (1.0 - a)
        val b = data[i + 2] * a + 255.0 * (1.0 - a)
        if (apply) {
            data[i] = if (r < t) 0 else 255
            data[i + 1] = if (g < t) 0 else 255
            data[i + 2] = if (b < t) 0 else 255
        } else {
            data[i] = jsToUint8Clamp(r)
            data[i + 1] = jsToUint8Clamp(g)
            data[i + 2] = jsToUint8Clamp(b)
        }
        data[i + 3] = 255
        i += 4
    }
}

/**
 * 抖动（对应 v2 `dither`）：**直接委托** [ditherImage]，不重新实现任何误差扩散逻辑。
 *
 * [DitheringMode.Random] 的随机种子来自 [com.lasergrbl.core.internal.nowMillis]，
 * 与 v2 的 `Date.now()` 对应；需要确定性时请自行调用 [ditherImage] 并传入 `nowMillis`。
 */
fun dither(data: IntArray, width: Int, height: Int, mode: DitheringMode) {
    ditherImage(data, width, height, mode)
}

/**
 * 垂直翻转（对应 v2 `flipVertical`，即 `Bitmap.RotateFlip(RotateNoneFlipY)`）。
 *
 * 逐行语义：新建等长缓冲，把第 `y` 行（`width * 4` 字节）拷到第 `height - 1 - y` 行，
 * 返回**新数组**（v2 也是 `new ImageData(out, width, height)`，入参保持不变）。
 */
fun flipVertical(data: IntArray, width: Int, height: Int): IntArray {
    val out = IntArray(data.size)
    val rowBytes = width * 4
    for (y in 0 until height) {
        val srcStart = y * rowBytes
        val dstStart = (height - 1 - y) * rowBytes
        data.copyInto(out, dstStart, srcStart, srcStart + rowBytes)
    }
    return out
}

/**
 * 检测图片是否为灰度图（对应 v2 `testGrayScale`）：任一像素 `r != g 或 g != b` 即返回 false。
 *
 * 空数组返回 true（循环体不执行，与 v2 一致 —— 这一点是读码推断，黄金样本未覆盖）。
 * alpha 不参与判定。
 */
fun testGrayScale(data: IntArray): Boolean {
    var i = 0
    while (i < data.size) {
        if (data[i] != data[i + 1] || data[i + 1] != data[i + 2]) return false
        i += 4
    }
    return true
}

/**
 * 重采样质量（对应 v2 `resizeImage` 的 `interpolation: 'high' | 'low'` 联合类型）。
 *
 * 与 `:app` 侧 `com.lasergrbl.android.platform.ImageResampler.Interpolation` 取值一一对应，
 * 但两者是各自独立的声明：`:core` 不能依赖 `:app`。接线时用一层适配器把枚举映射过去即可。
 */
enum class Interpolation(val value: String) {
    /** 高质量（v2：`imageSmoothingEnabled = true` + `imageSmoothingQuality = 'high'`）。 */
    High("high"),

    /** 低质量（v2：`imageSmoothingEnabled = false`）。 */
    Low("low");

    companion object {
        fun fromValue(value: String): Interpolation? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 重采样接缝 —— 对应 v2 `resizeImage()`（本文件不实现，原因见文件头 KDoc）。
 *
 * 实现方（`:app` 侧，默认 `TriangleResampler`）的契约：
 *  1. 目标尺寸必须先做 v2 的 `Math.max(1, sizeW/sizeH)` 夹取，返回数组长度必须是
 *     `maxOf(1, sizeW) * maxOf(1, sizeH) * 4`，元素 0..255、RGBA 顺序；
 *  2. [fillWhite] 对应 v2 的 `killAlpha`（也就是 `:app` 侧 `resample` 的同名参数）：
 *     为 true 时先把画布填白再画图（透明像素合成到白底、alpha 置 255），
 *     为 false 时保留 alpha（后续由 [threshold] 负责合成）；
 *  3. [interpolation] 为 [Interpolation.Low] 时应关闭插值（最近邻）。
 *
 * 参数 [source] 用 [PotraceImage]（`data`/`width`/`height`，RGBA 0..255），
 * 它取代了 v2 的 `source: CanvasImageSource` + 显式 `srcW/srcH`：
 * Kotlin 侧的图像数据自带尺寸，不需要再传一遍。
 */
fun interface ResizeSampler {
    fun sample(
        source: PotraceImage,
        sizeW: Int,
        sizeH: Int,
        interpolation: Interpolation,
        fillWhite: Boolean
    ): IntArray
}

/**
 * 复刻 ECMA-262 的 **ToUint8Clamp**（`Uint8ClampedArray` 写入时对浮点值的处理），
 * 规范步骤逐条对应：
 *
 *  1. `NaN` → `+0`；
 *  2. `value ≤ 0` → `+0`（注意这一步在取整**之前**，所以 `-0.5` → `0`）；
 *  3. `value ≥ 255` → `255`；
 *  4. 否则令 `f = floor(value)`：
 *     * `f + 0.5 < value` → `f + 1`；
 *     * `value < f + 0.5` → `f`；
 *     * 恰好 `value == f + 0.5`（并列）→ `f` 为奇数则 `f + 1`，否则 `f`（**并列取偶**）。
 *
 * 也就是说：四舍五入到最近、并列取偶、越界夹到 0/255。
 * 这是本移植里唯一不能靠 `toInt()` 或 `Math.round` 替代的取整规则，
 * 调用点见 [grayScale] 与 [threshold]。
 */
internal fun jsToUint8Clamp(value: Double): Int {
    if (value.isNaN() || value <= 0.0) return 0
    if (value >= 255.0) return 255
    val lower = floor(value)
    val base = lower.toInt()
    return when {
        lower + 0.5 < value -> base + 1
        value < lower + 0.5 -> base
        base % 2 != 0 -> base + 1
        else -> base
    }
}
