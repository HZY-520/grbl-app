# 平台相关模块 + 编排层记录（ImageTransform / ImageVector / SmartVector / RasterConverter）

> 本文件记录 `docs/UI-3.0-PLAN.md` §12.4 里「平台相关模块 → 编排层」这一段的执行情况。
> 判据永远是 **v2 的 TypeScript 源码**（`src/core/**`），不是计划书；
> 计划书在细节上已经错过两次（见 [PHASE3-PERIPHERALS.md](PHASE3-PERIPHERALS.md) §5）。

---

## 1. 模块与"能不能上黄金样本"的判定

`tools/golden/generate.ts` 的注释里原本写着 5 个模块**无法**捕获（需要真实浏览器 canvas 或
Capacitor SDK）：`raster/ImageTransform.ts`、`vector/ImageVector.ts`、`vector/SvgVector.ts`、
`grbl/GrblCore.ts`、`serial/SerialTransport.ts`、`raster/RasterConverter.ts`。

实际逐行看下来，这个判定**过于保守**：这些文件里只有极少数函数真的碰 DOM/Canvas，
其余全是纯像素/纯几何运算。逐个函数的判定：

| v2 函数 | 需要浏览器？ | 处置 |
| --- | --- | --- |
| `ImageTransform.grayScale` / `whitenize` / `threshold` / `dither` / `flipVertical` / `testGrayScale` | ❌ 纯像素 | ✅ 已加入黄金样本 `image-transform.json` |
| `ImageTransform.resizeImage` | ✅ `canvas.drawImage` 的重采样内核 | 做成 `:app` 接缝 `ImageResampler`（见 §3） |
| `ImageTransform.toDataURL` | ✅ canvas → PNG | 纯展示用途，`:app` 用 `Bitmap.compress` 顶替 |
| `ImageVector.convertImageVector` / `buildPreview` / `hasNonAscii` | ❌ 纯像素 + 纯几何 | ✅ 已加入黄金样本 `image-vector.json` |
| `ImageVector.renderTextToImage` / `convertTextVector` | ✅ `ctx.fillText` 文字光栅化 | 做成 `:app` 接缝（见 §3） |
| `SmartVector.convertImageVectorSmart` / `hasNonHersheyChar` / `decideTextEngine` | ❌ 纯逻辑 | ✅ 已加入黄金样本 `image-vector.json` |
| `RasterConverter.convertImageToGcode` | ⚠️ 只有开头一次 `resizeImage` 需要 canvas，其余（`getSegments` / `segToGCodeNumber` / `optimizeLine2Line`）全是纯逻辑 | 把 `resizeImage` 抽成入参后**整个搬进 `:core`**（见 §4） |
| `SvgVector.ts` | 见下 | 待评估 |

**结论**：`resizeImage` 与 `renderTextToImage` 是这 4 个文件里**仅有的**两个真平台依赖。
把它们做成接缝之后，`ImageTransform` / `ImageVector` / `SmartVector` / `RasterConverter`
的全部算法都能进 `:core`，也都能用黄金样本逐像素验证。

---

## 2. 本轮扩展的黄金样本（Lead 亲自做）

`tools/golden/generate.ts` 新增两个 case，并把三个模块加进动态 import：

| 夹具 | 体积 | 条目 | 内容 |
| --- | --- | --- | --- |
| `image-transform.json` | 424 KB | 22 | 灰度化 6 组（四种 Formula + 负亮度 + Custom>100%）、`whitenize` 6 档阈值、`threshold` 4 组（含 `apply=false` 只合成）、`dither` 3 个模式、`flipVertical`、`testGrayScale`（彩色图 + 纯灰图） |
| `image-vector.json` | 390 KB | 33 | `convertImageVector` 8 组（6 Outline + 2 Centerline，含 tuned/inverted/no-optimize 与 0×0 退化输入）、`convertImageVectorSmart` 3 组、`hasNonAscii` / `hasNonHersheyChar` / `decideTextEngine` 三张判定表、`defaultOptions` 全字段、`toolLabels` |

生成器两次运行 **16 个夹具 sha256 完全一致**（已实测）。

**合成图的设计要点**（都是为了让边界条件真的被覆盖）：

* `imageTransformSampleImage()`：24×16，循环出现 10 种像素形态 —— 纯黑/纯白/饱和 RGB /
  **近白 `250,251,249`**（用来区分 `whitenize` 的严格不等号）/ **全透明但 RGB 非零**
  （用来验证"先合成到白底"）/ 半透明 α=128 / 中灰 / 近黑。
* `imageVectorSampleImage()`：128×128，黑圆盘 + **半透明黑矩形** + 黑环 + **全透明带非零 RGB 的长条**。
  半透明/全透明两块专门用来钉住两条不同的 alpha 语义：
  * `Potrace.binarize`：`alpha < 128` **直接当背景**（不做合成）；
  * `ImageVector.buildPreview`：**先合成到白底**再比 `r+g+b < threshold*3`。
  两者不一致是 v2 的既有行为，黄金样本把两边都钉住了。

---

## 3. `:app` 侧的两个平台接缝

### 3.1 `ImageResampler`（替代 `resizeImage`）

`app/src/main/kotlin/com/lasergrbl/android/platform/ImageResampler.kt`

* `interface ImageResampler { resample(source: PotraceImage, sizeW, sizeH, interpolation, killAlpha): IntArray }`
* `NearestNeighborResampler`：精确、可预期，用于单测与 `interpolation = Low` 分支。
* `TriangleResampler`：默认实现，带支撑缩放的三角滤波（缩小时核变宽 ≈ 盒式平均），
  目标是贴近浏览器 `imageSmoothingQuality = 'high'`。

⚠️ **诚实标注**：`TriangleResampler` **没有**与真实浏览器逐像素比对过 ——
Node 里没有 Canvas，`tools/golden/generate.ts` 也刻意不捕获这条路径。
**待收盘**：Phase 4 用一次"无头浏览器（Chrome DevTools Protocol）抓 Canvas 输出"的
离线捕获，把同一张合成图在 `drawImage` 后的像素导出成夹具，再与 `TriangleResampler` 比对。
这是唯一能真正证明"与 v2 一致"的办法，不能靠"看起来像双线性"就宣称一致。

`ImageResamplerTest`（14 用例，纯 JVM）覆盖了：最近邻放大/缩小/`killAlpha` 合成的**精确**像素值、
0 尺寸夹到 1×1、退化源图填白、三角滤波的角点与内部加权（手算值 191）、
2×2→1×1 的盒平均（128）、`Low` 与最近邻逐像素相等、`killAlpha` 下 alpha 置 255。

### 3.2 位图接缝 `AndroidBitmapBridge`

`app/src/main/kotlin/com/lasergrbl/android/platform/AndroidBitmapBridge.kt`

`:core` 约定 `data[i*4 + 0/1/2/3] = R/G/B/A`（每元素 0..255，对应 `Uint8ClampedArray`），
而 Android 的 `Bitmap.getPixels` 返回**打包 ARGB_8888 Int**。这层负责解包/打包，
并提供 `killAlpha`（合成到白底）。

⚠️ 这条路径**离线测不了**（`Bitmap` 是 Android 类型，JVM 单测里无法构造）——
它属于"薄到读一眼就知道对不对"的翻译代码，真机/模拟器验证列在 §5。

### 3.3 文字光栅化接缝（待做）

`renderTextToImage` 是四个文件里最复杂的平台函数（测量基准线、粗体描边、竖排旋转 90°、
墨迹包围盒裁剪含 1px 边距、`pxPerMm` 换算）。已实现：
`core/src/commonMain/.../text/TextRaster.kt` 声明接缝
（`fun interface TextRasterizer` + `TextRenderOptions` / `TextRenderResult` / `TextOrientation`），
`app/src/main/.../platform/AndroidTextRasterizer.kt` 用 `Canvas` + `Paint` 实现
（逐条对齐 v2：字号/粗体描边/`drawColor` 白底/`baseline = pad + i*lineStep + ascent`/
竖排 `rotate90CW`/`inkBox` 跳过透明与近白/裁剪含 1px 边距/`pxPerMm` 的 `scaleDenom` 分支）。
`convertTextVector` 的编排在 `:core/vector/ImageVector.kt`，**接缝作为参数注入**，
于是 JVM 单测能塞一个假渲染器断言退化路径。

---

## 4. 本轮完成情况

| 模块 | 位置 | 状态 | 证据 |
| --- | --- | --- | --- |
| `ImageTransform`（7 个纯像素函数） | `:core/raster/ImageTransform.kt` | ✅ | `GoldenImageTransformTest`（10 用例）逐像素 + sha256 |
| `ResizeSampler` 接缝 | `:core/raster/ImageTransform.kt` + `:app/platform/ImageResampler.kt` | ✅ | `ImageResamplerTest`（14 用例，纯 JVM 精确断言） |
| `ImageVector`（`convertImageVector` / `buildPreview` / `hasNonAscii`） | `:core/vector/ImageVector.kt` | ✅ | `GoldenImageVectorTest`（5 用例：8 组变体 + 3 组 smart + 三张字符表） |
| `SmartVector` | `:core/vector/SmartVector.kt` | ✅ | 同上 |
| `convertTextVector` 编排 | `:core/vector/ImageVector.kt` | ✅ | 注入假 `TextRasterizer`，断言退化路径与宽高传递 |
| 文字光栅化接缝 | `:core/text/TextRaster.kt` + `:app/platform/AndroidTextRasterizer.kt` | ✅ | `Canvas`/`Paint` 是 Android 类型，离线测不了 → §6 |
| 位图打包/解包接缝 | `:app/platform/AndroidBitmapBridge.kt` | ✅ | 同上 |
| **`RasterConverter`（整条光栅管线）** | `:core/raster/RasterConverter.kt` | ✅ | **`GoldenRasterConverterTest`（3 用例 / 5 组变体）**，夹具由 v2 真实代码跑出 |
| `SvgVector.ts` | — | ⏳ 未开工 | 见 §5 |

**测试口径**：`:core:jvmTest` **31 套件 / 203 用例 / 0 失败**；`:app:testDebugUnitTest` **8 套件 / 84 用例 / 0 失败**。
证据归档 `build/verify-20261007-141942/`（含 17 个夹具的 manifest）。

### 4.1 黄金样本：14 → 17 个 case

| 夹具 | 体积 | 条目 |
| --- | --- | --- |
| `image-transform.json` | 424 KB | 22 |
| `image-vector.json` | 390 KB | 33 |
| `raster-converter.json` | 557 KB | 5 |

生成器两次运行的 **17 个夹具 sha256 完全一致**，且本次扩展**没有改动任何已有夹具的哈希**。

### 4.2 `raster-converter` 夹具的关键设计

它没有"在生成器里重写一遍管线"——那样只是拿一个自己的实现去对另一个。
做法是**只替换 `document.createElement('canvas')` 这一层**（一个确定性最近邻的画布 shim），
让 v2 真实的 `convertImageToGcode` 原样跑完。于是夹具里从
`testGrayScale → grayScale → whitenize → dither/threshold → flipVertical → getSegments →
segToGCodeNumber → optimizeLine2Line → 头尾拼装` 全部是 v2 的真实输出。
Kotlin 侧把夹具的 `resampled`（= v2 `resizeImage` 的输出）当作**输入**再跑自己那一段，
从而把"重采样"与"其余管线"干净地分开验证。

`lines` 只存 `lineCount` + `linesSha256`（`join("\n")`）+ 首尾各 12 行（最大一组 1302 行），
既钉死输出又不让夹具膨胀到几 MB。

### 4.3 本轮抓到的真 bug（都改在实现里）

| 缺陷 | 影响 | 修法 |
| --- | --- | --- |
| **`jsToFixed` 的并列取整方向错**：原实现"正数 `HALF_UP`、负数 `HALF_DOWN`"（并列朝 +∞） | `-1.5625 → "-1.562"` 而 V8 给 `"-1.563"`；0.001 mm 直接进 G 代码（协议差异） | 正负统一 `RoundingMode.HALF_UP`（= 远离零）。**判据是本机 V8 实测**：规范步骤 8/9 说"pick the larger n"，而 V8 在负数并列上与规范文本不一致 |
| **`grayScale` 用 `toInt()` 截断**而没用同文件的 `jsToUint8Clamp` | `0.333*(200+100+50) = 116.55` 被截成 116，V8 给 117 | 改走 `jsToUint8Clamp`（ToUint8Clamp：四舍五入、并列取偶） |
| `PathsTest` 的断言本身是错的（`fmt(-1.5, 0)` 期望 "-1" 并注释成"并列朝 +∞"） | 会永远掩盖上面那个 bug | 改为 `-2` 并写明 V8 实测依据 |
| `GrblCoreSerialOrderingTest.closeFlushesQueuedWritesEvenWhenWritesAreSlow` 是**脆弱测试** | 它通过 `GrblCore.sendImmediate` 驱动，会掺入 4 ms 状态查询定时器多写的一个 `?` 字节，在并发构建时偶发假红（实测 201/200） | 改为在传输层直接数 `transport.writeBytes` 的调用，计数完全可控；`GrblCore` 与传输层的**接线**顺序由同文件另外两条用例覆盖 |

---

## 5. 待办与顺序

1. **`SvgVector.ts` 评估**：判断它是否只是"SVG → 光栅化 → 复用 `convertImageVector`"。
   若其中的光栅化确实依赖 `androidsvg`，同样做成接缝（计划书 §10 已批准引入该库）。
2. **无头浏览器捕获**（§3.1 的待收盘项）：用 Chrome DevTools Protocol 抓 `drawImage` 后的像素，
   与 `TriangleResampler` 比对。这是唯一能证明"与浏览器一致"的办法。
3. **把 `:app` 平台件接到 `:core` 编排上**：`RasterOptions.interpolation`（`:core` 的 `Interpolation`）
   与 `:app` 的 `ImageResampler.Interpolation` 目前是**两个独立枚举**（`:core` 不能依赖 `:app`），
   接线时需要一个适配器。这条约束是**故意的**（保持 `:core` 平台无关），
   但必须在 Phase 4 显式写出来，否则容易写成两套。

---

## 6. 无法离线验证的条目（不要当作已验证）

1. `TriangleResampler` 与真实浏览器 `drawImage` 的逐像素一致性（见 §3.1）。
2. `AndroidBitmapBridge` 的 `Bitmap` 打包/解包、`AndroidTextRasterizer` 的 `Canvas`/`Paint` 路径
   —— 都是 Android 类型，JVM 单测里无法构造，需真机或模拟器。
3. 文字光栅化的字体度量 —— **WebView 的字体栈与 Android 平台的字体不同**，
   同一段中文的墨迹包围盒必然不同。这意味着"文字转雕刻"的像素级输出
   **不可能与 v2 逐像素一致**，只能保证算法与参数一致。
4. 大图性能（1600 px 上限、22000×22000 像素上限的寄存器压力）。
