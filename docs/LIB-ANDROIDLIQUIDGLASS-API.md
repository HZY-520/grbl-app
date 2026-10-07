# Kyant0/AndroidLiquidGlass (`kmp`) — `io.github.kyant0:backdrop:2.0.1` API inventory

Sources: GitHub tree `api.github.com/repos/Kyant0/AndroidLiquidGlass/git/trees/kmp?recursive=1` (289 entries, `truncated=false`), raw files under `raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/kmp/…`, docs `kyant.gitbook.io/backdrop` (`.md` endpoints), plus `repo1.maven.org/.../backdrop-2.0.1.module` for published variants. Every claim below is from one of those.

---

## 1. Modules & publication

`:backdrop` is the **only** module published to Maven Central.

`backdrop/build.gradle.kts` — full publication block:

```kotlin
plugins { … id("com.vanniktech.maven.publish") }        // L3-9
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.kyant0", "backdrop", "2.0.1")   // L80-84
    pom { licenses { license { name.set("The Apache License, Version 2.0") … } } }  // L91-97
}
```

`app/build.gradle.kts` (L4-9) declares **no** `com.vanniktech.maven.publish` plugin and has no `mavenPublishing` block.
`androidApp/build.gradle.kts` (L448-451) uses only `android-application` + `kotlin-compose`; the root `build.gradle.kts`
has no publishing config; `settings.gradle.kts` (L25-28) includes `:backdrop`, `:app`, `:androidApp`.
**Conclusion:** one artifact, group `io.github.kyant0`, name `backdrop`, version `2.0.1`, Apache-2.0.
**The demo-app UI components are NOT published as an artifact — they exist only as source** in the `:app` module
(`app/src/commonMain/kotlin/com/kyant/backdrop/catalog/**`, namespace `com.kyant.backdrop.catalog.common` per
`app/build.gradle.kts` L16). The README states this explicitly: *"The library does not include any high-level components;
you will need to create your own."* (`README.md`).

### Kotlin targets (source of truth `backdrop/build.gradle.kts` L11-77)

| Target | Declared as | Source set | Notes |
|---|---|---|---|
| android | `android { minSdk = 21; compileSdk = 37; namespace "com.kyant.backdrop" }` | `androidMain` | JVM 11 (`JvmTarget.JVM_11`) |
| desktop / jvm | `jvm("desktop")` | `desktopMain` (dependsOn `skikoMain`) | |
| js · wasmJs | `js { browser() }`, `wasmJs { browser() }` | `jsMain`, `wasmJsMain` (both dependOn `skikoMain`) | |
| ios | `iosArm64("iosArm64")`, `iosSimulatorArm64("iosSimulatorArm64")` | `iosMain` (dependsOn `skikoMain`) | ⚠ no `iosX64` / Intel-simulator target |
| macos | `macosArm64()` | `macosArm64Main` (dependsOn `skikoMain`) | ⚠ Apple-Silicon only |
| — | — | `skikoMain` (hand-created, L48-50) | shared by desktop, macOS, iOS, JS, wasmJs (L52-76) |

**Independent confirmation from Maven Central** (`backdrop-2.0.1.module`, 18 846 bytes) — variants actually published:
`android(Api|Runtime|Sources)Elements-published`, `desktop…`, `js(Api|Runtime|Sources|Resources)…`,
`wasmJs…`, `iosArm64…`, `iosSimulatorArm64…`, `macosArm64…`, plus root `metadataApiElements`,
`metadataSourcesElements`. **No `androidApp`, `app`, catalog, or `-uikit` artifact exists.**

---

## 2. Full public API of the `backdrop` module

`internal`/`private` members (lines marked ⚙) are listed where the task asked for them, clearly flagged.

### 2.1 Core — `com.kyant.backdrop` (`Backdrop.kt`, `BackdropEffectScope.kt`, `DrawBackdropModifier.kt`, `Platform.kt`, `RuntimeShader*.kt`)

| File | Public API | Purpose |
|---|---|---|
| `commonMain/…/Backdrop.kt` L8-17 | `interface Backdrop { val isCoordinatesDependent: Boolean; fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)? = null) }` | A copyable "background" that can be re-drawn inside any `DrawScope`. |
| `commonMain/…/BackdropEffectScope.kt` L10-21 | `sealed interface BackdropEffectScope : Density, RuntimeShaderCache { val size: Size; val layoutDirection: LayoutDirection; val shape: Shape; var padding: Float; var renderEffect: RenderEffect? }` | Receiver of the `effects = { … }` lambda; collects one chained `RenderEffect` + padding. |
| `commonMain/…/Platform.kt` L3-5 | `expect fun isRenderEffectSupported(): Boolean` / `expect fun isRuntimeShaderSupported(): Boolean` (actuals in `androidMain` L6-10 and `skikoMain` L3-5 — see §3) | Platform capability probes (Android S / Tiramisu; always `true` on Skiko). |
| `commonMain/…/RuntimeShader.kt` L7-26 | `expect fun RuntimeShader(@Language("AGSL") shaderString: String): RuntimeShader` · `expect fun RuntimeShader.asComposeShader(): Shader` · `interface RuntimeShader { setFloatUniform(name,Float/[2..4]/FloatArray); setIntUniform(name,Int/[2..4]/IntArray); setColorUniform(name: String, color: Color) }` | Cross-platform AGSL/SkSL runtime-shader handle + uniform setters. |
| `commonMain/…/DrawBackdropModifier.kt` L80-93 | `fun Modifier.drawBackdrop(backdrop: Backdrop, shape: () -> Shape, effects: BackdropEffectScope.() -> Unit, highlight: (() -> Highlight?)? = DefaultHighlight /* { Highlight.Default } */, shadow: (() -> Shadow?)? = DefaultShadow /* { Shadow.Default } */, innerShadow: (() -> InnerShadow?)? = null, layerBlock: (GraphicsLayerScope.() -> Unit)? = null, exportedBackdrop: LayerBackdrop? = null, onDrawBehind: (DrawScope.() -> Unit)? = null, onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = { it() }, onDrawSurface: (DrawScope.() -> Unit)? = null, onDrawFront: (DrawScope.() -> Unit)? = null): Modifier` | **The main entry point**: renders the glass surface, effects, highlight/shadows and optional exported layer. |
| `commonMain/…/DrawBackdropModifier.kt` L45-55 | `fun Modifier.drawPlainBackdrop(backdrop: Backdrop, shape: () -> Shape, effects: BackdropEffectScope.() -> Unit, layerBlock: (GraphicsLayerScope.() -> Unit)? = null, exportedBackdrop: LayerBackdrop? = null, onDrawBehind: (DrawScope.() -> Unit)? = null, onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = { it() }, onDrawSurface: (DrawScope.() -> Unit)? = null, onDrawFront: (DrawScope.() -> Unit)? = null): Modifier` | Same glass pipeline **without** highlight/shadow/inner-shadow. |
| `androidMain/…/RuntimeShader.kt` L20-22 | `fun RuntimeShader.asAndroidRuntimeShader(): android.graphics.RuntimeShader` | Unwrap for Android-only APIs (e.g. `setInputBuffer`). `@RequiresApi(TIRAMISU)` on the factory (L10-11). |
| `skikoMain/…/RuntimeShader.kt` L20-22 | `fun RuntimeShader.asSkikoRuntimeShader(): RuntimeShaderBuilder` | Unwrap for Skia-only APIs (e.g. `.child(...)`). |

### 2.2 `backdrops/*`

| File | Public API | Purpose |
|---|---|---|
| `backdrops/Backdrop.kt` L15-18 | `@Composable fun rememberBackdrop(backdrop: Backdrop, onDraw: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit): Backdrop` | Remembers a backdrop that wraps another and intercepts its drawing commands. |
| `backdrops/CanvasBackdrop.kt` L54-56 | `@Composable fun rememberCanvasBackdrop(onDraw: DrawScope.() -> Unit): Backdrop` | Coordinates-independent backdrop drawn by arbitrary `DrawScope` commands. |
| `backdrops/LayerBackdrop.kt` L35-41 | `@Stable class LayerBackdrop internal constructor(val graphicsLayer: GraphicsLayer, internal val onDraw: ContentDrawScope.() -> Unit) : Backdrop` — `override val isCoordinatesDependent = true` (L246) | Coordinates-dependent backdrop backed by a recorded `GraphicsLayer` (constructor is `internal`; obtain via `rememberLayerBackdrop`/`exportedBackdrop`). |
| `backdrops/LayerBackdrop.kt` L230-238 | `@Composable fun rememberLayerBackdrop(graphicsLayer: GraphicsLayer = rememberGraphicsLayer(), onDraw: ContentDrawScope.() -> Unit = { drawContent() }): LayerBackdrop` | Creates the layer backdrop that other modifiers draw on top of. |
| `backdrops/LayerBackdropModifier.kt` L295-296 | `fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier` | Marks a composable as the **source** whose pixels become the backdrop (required by the FAQ when "the effect doesn't work"). |
| `backdrops/CombinedBackdrop.kt` | `@Composable fun rememberCombinedBackdrop(backdrop1: Backdrop, backdrop2: Backdrop): Backdrop` (L91-94) · `@Composable fun rememberCombinedBackdrop(backdrop1: Backdrop, backdrop2: Backdrop, backdrop3: Backdrop): Backdrop` (L101-105) · `@Composable fun rememberCombinedBackdrop(vararg backdrops: Backdrop): Backdrop` (L112-114) | Merges several backdrops into one (used for tab/slider optics). |
| `backdrops/EmptyBackdrop.kt` L190-191 | `@Stable fun emptyBackdrop(): Backdrop` | A backdrop that draws nothing. |

⚙ `internal class ShapeProvider` — see §2.5.

### 2.3 `effects/*`

| File | Public API | Purpose |
|---|---|---|
| `effects/ColorFilter.kt` L11 | `fun BackdropEffectScope.colorFilter(colorFilter: ColorFilter)` | Appends an arbitrary `ColorFilter` to the effect chain. |
| `effects/ColorFilter.kt` L17 | `fun BackdropEffectScope.opacity(@FloatRange(from = 0.0, to = 1.0) alpha: Float)` | Alpha via a color-matrix filter. |
| `effects/ColorFilter.kt` L29-33 | `fun BackdropEffectScope.colorControls(brightness: Float = 0f, contrast: Float = 1f, saturation: Float = 1f)` | Brightness/contrast/saturation filter (no-op at defaults, L418-420). |
| `effects/ColorFilter.kt` L43 | `fun BackdropEffectScope.vibrancy()` | `colorControls(saturation = 1.5f)`. |
| `effects/Blur.kt` L9-13 | `fun BackdropEffectScope.blur(@FloatRange(from = 0.0) radius: Float, edgeTreatment: TileMode = TileMode.Clamp)` | Gaussian blur; early-returns if `!isRenderEffectSupported()` or `radius <= 0f`; grows `padding` to `radius` when `edgeTreatment != Clamp` or a previous effect exists (L369-373). |
| `effects/Lens.kt` L16-21 | `fun BackdropEffectScope.lens(@FloatRange(from = 0.0) refractionHeight: Float, @FloatRange(from = 0.0) refractionAmount: Float, depthEffect: Boolean = false, chromaticAberration: Boolean = false)` | Refraction/"liquid lens" edge; needs runtime shaders; requires a rounded shape (throws otherwise, L577-581). |
| `effects/RenderEffect.kt` L13-15 | `fun BackdropEffectScope.effect(effect: RenderEffect)` | Appends a raw Compose `RenderEffect` to the chain. |
| `effects/RenderEffect.kt` L20-26 | `@OptIn(ExperimentalContracts::class) fun BackdropEffectScope.runtimeShaderEffect(key: String, @Language("AGSL") shaderString: String, uniformShaderName: String, block: RuntimeShader.() -> Unit)` | Custom RuntimeShader effect; cached by `key`. |

Platform support: Android `isRenderEffectSupported()` = `SDK_INT >= S` (31), `isRuntimeShaderSupported()` = `SDK_INT >= TIRAMISU` (33)
(`androidMain/Platform.kt` L6-10); both `true` on Skiko (`skikoMain/Platform.kt` L3-5).

⚠ **Doc/source divergence (real):** `api/backdrop-effects.md` documents `exposureAdjustment(ev: Float)` and
`gammaAdjustment(power: Float)`, and gives `lens(refractionAmount: Float = height)`. **Neither function exists anywhere in
`backdrop/src/**`** (regex search over all module sources: 0 hits), and `refractionAmount` has **no default** in source
(`Lens.kt` L18). Treat the two adjustment functions as removed/not shipped in 2.0.1.

### 2.4 `highlight/*` and `shadow/*`

| File | Public API | Purpose |
|---|---|---|
| `highlight/Highlight.kt` L10-17 | `@Immutable data class Highlight(val width: Dp = 0.5f.dp, val blurRadius: Dp = width / 2f, @param:FloatRange(0.0, 1.0) val alpha: Float = 1f, val style: HighlightStyle = HighlightStyle.Default)`; companion `Default`, `Ambient`, `Plain` (L20-26) | Rim-light spec passed to `drawBackdrop(highlight = …)`. |
| `highlight/HighlightStyle.kt` L21-31 | `@Immutable interface HighlightStyle { val color: Color; val blendMode: BlendMode; fun DrawScope.createShader(shape: Shape, runtimeShaderCache: RuntimeShaderCache): RuntimeShader? }` | Pluggable highlight rendering strategy. |
| `highlight/HighlightStyle.kt` L33-43 | `data class Plain(override val color: Color = Color.White.copy(alpha = 0.38f), override val blendMode: BlendMode = BlendMode.Plus) : HighlightStyle` | Flat, shader-free highlight (returns `null` shader). |
| `highlight/HighlightStyle.kt` L45-54 | `data class Default(override val color: Color = Color.White.copy(alpha = 0.5f), override val blendMode: BlendMode = BlendMode.Plus, val angle: Float = 45f, @param:FloatRange(from = 0.0) val falloff: Float = 1f) : HighlightStyle` | Directional highlight shader (e.g. driven by gravity). |
| `highlight/HighlightStyle.kt` L74-77 | `data class Ambient(@param:FloatRange(0.0, 1.0) val intensity: Float = 0.38f) : HighlightStyle` | Uniform ambient rim light. |
| `highlight/HighlightStyle.kt` L105-113 | `companion object { val Default: Default; val Ambient: Ambient; val Plain: Plain }` (`@Stable` each) | Singletons. |
| ⚙ `highlight/HighlightModifier.kt` L679-682, L717-720 | `internal class HighlightElement(shapeProvider: ShapeProvider, highlight: () -> Highlight?) : ModifierNodeElement<HighlightNode>` · `internal class HighlightNode(var shapeProvider, var highlight) : DrawModifierNode` | No public `Modifier.highlight` exists — highlight is only reachable through `drawBackdrop(highlight = …)`. |
| `shadow/Shadow.kt` L14-21 | `@Immutable data class Shadow(val radius: Dp = 24f.dp, val offset: DpOffset = DpOffset(0f.dp, radius / 6f), val color: Color = Color.Black.copy(alpha = 0.1f), @param:FloatRange(0.0, 1.0) val alpha: Float = 1f, val blendMode: BlendMode = DrawScope.DefaultBlendMode)`; `companion object { val Default: Shadow }` (L25) | Outer drop shadow spec. |
| `shadow/InnerShadow.kt` L17-23 | `@Immutable data class InnerShadow(val radius: Dp = 24f.dp, val offset: DpOffset = DpOffset(0f.dp, radius), val color: Color = Color.Black.copy(alpha = 0.15f), @param:FloatRange(0.0, 1.0) val alpha: Float = 1f, val blendMode: BlendMode = DrawScope.DefaultBlendMode)`; `companion object { val Default }` (L28) | Inner shadow spec. |
| `shadow/InnerShadow.kt` L33 | `@Stable fun lerp(start: InnerShadow, stop: InnerShadow, fraction: Float): InnerShadow` | Interpolates inner shadows (only `InnerShadow` has such a helper — `Shadow` has none). |
| ⚙ `shadow/ShadowModifier.kt` L1010-1013, L1048-1051 | `internal class ShadowElement(shapeProvider, shadow: () -> Shadow?)` · `internal class ShadowNode(var shapeProvider, var shadow) : DrawModifierNode` | Reached only via `drawBackdrop(shadow = …)`. |
| ⚙ `shadow/InnerShadowModifier.kt` L1194-1197, L1232-1235 | `internal class InnerShadowElement(shapeProvider, shadow: () -> InnerShadow?)` · `internal class InnerShadowNode(var shapeProvider, var shadow) : DrawModifierNode` | Reached only via `drawBackdrop(innerShadow = …)`. `InnerShadowNode.draw` returns early when `!isRenderEffectSupported()` (L1249). |

### 2.5 Internal `ShapeProvider` (asked for explicitly)

`commonMain/…/internal/ShapeProvider.kt` L19-23:

```kotlin
@Immutable
internal class ShapeProvider(val shapeBlock: () -> Shape) {
    val innerShape get() = shapeBlock()
    val shape = object : Shape {   // memoising wrapper
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline
    }
}
```

Caches the `Outline` and invalidates it when shape/size/layoutDirection/density change (L31-42) — an internal
memoisation helper for the highlight/shadow/draw modifiers, **not** part of the public API.

---

## 3. Rendering per platform

### Android — `backdrop/src/androidMain/kotlin/com/kyant/backdrop/RuntimeShader.kt`

```kotlin
@RequiresApi(Build.VERSION_CODES.TIRAMISU)              // L10
actual fun RuntimeShader(@Language("AGSL") shaderString: String): RuntimeShader {
    val shader = android.graphics.RuntimeShader(shaderString)   // L12 — throws below API 33
    return AndroidRuntimeShader(shader)
}
```
and `androidMain/Platform.kt`:

```kotlin
@ChecksSdkIntAtLeast(Build.VERSION_CODES.S)         actual fun isRenderEffectSupported() = SDK_INT >= S          // L6-7
@ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)  actual fun isRuntimeShaderSupported() = SDK_INT >= TIRAMISU // L9-10
```

### Skiko (desktop / macOS / iOS / JS / wasmJs) — `backdrop/src/skikoMain/…/RuntimeShader.kt`

```kotlin
actual fun RuntimeShader(@Language("AGSL") shaderString: String): RuntimeShader {
    val shader = RuntimeShaderBuilder(RuntimeEffect.makeForShader(shaderString))  // L12 — SkSL, no API gate
    return SkikoRuntimeShader(shader)
}
```

**Minimum Android API level implied:** the library declares `minSdk = 21` (`backdrop/build.gradle.kts` L13), and hard
guards are `S` (API 31) for `RenderEffect` and `TIRAMISU` (API 33) for `RuntimeShader`/AGSL. The demo app ships a higher
floor: `androidApp/build.gradle.kts` L460 `minSdk = 23`. So the *effect* feature floor is **API 31, with full fidelity
(lens/blur/shaders) at API 33**; below 31 the effects silently do nothing (see below).

**Is AGSL/RuntimeShader required?** No — not for the effect chain as a whole (`RenderEffect`-only effects work from API 31),
but **yes** for the refraction `lens(...)` path, the `Default`/`Ambient` highlight shaders, and any
`runtimeShaderEffect(...)`: those are gated on `isRuntimeShaderSupported()`.

**Graceful fallback:** every shader/render-effect entry point is a soft **no-op**, but the fallback is *degraded, not
equivalent*; there is one genuine crash path (invalid shape for `lens`):

```kotlin
// effects/Blur.kt L12-13
if (!isRenderEffectSupported()) return
if (radius <= 0f) return
// effects/RenderEffect.kt L14-15
if (!isRenderEffectSupported()) return
// effects/RenderEffect.kt L27
if (!isRuntimeShaderSupported()) return
// effects/Lens.kt L22-23
if (!isRuntimeShaderSupported()) return
if (refractionHeight <= 0f || refractionAmount <= 0f) return
// highlight/HighlightStyle.kt L873 / L903 — shader==null instead of throwing
return if (isRuntimeShaderSupported()) { …obtainRuntimeShader(…)} else null
// DrawBackdropModifier.kt L362-364 — the master gate
private fun updateEffects() { if (!isRenderEffectSupported()) return; … }
// shadow/InnerShadowModifier.kt L1249
if (!isRenderEffectSupported()) return
```

Crash path — `effects/Lens.kt` L577-581:

```kotlin
private fun throwUnsupportedSDFException(): Nothing {
    throw UnsupportedOperationException("Only RoundedRectangularShape or CornerBasedShape is supported in lens effects.")
}
```

Because `isRuntimeShaderSupported()` is checked *before* shape resolution (L22), this throw is reachable **only on
platforms where runtime shaders exist** (Android 33+, or any Skiko target) **and** the shape is not
`RoundedRectangularShape`/`AbsoluteRoundedCornerShape`/`CornerBasedShape` — i.e. an unsupported custom `Shape` +
`lens(...)` crashes; on Android < 33 the same call is a silent no-op.

One more documented crash mode is in the official FAQ: a SIGSEGV in `RenderThread`
(`Fatal signal 11 … in tid __ (RenderThread)`), which the docs answer by pointing at the bottom-sheet tutorial
(`faq.md`). That is a platform-level failure mode, not handled in library code.

**Skiko caveat:** `skikoMain/…/RuntimeShader.kt` L62-64 — `setIntUniform(name: String, values: IntArray)` **throws**
`UnsupportedOperationException("Setting int array uniforms is not supported on Skia RuntimeShader.")`.

### Compose/AGP/Kotlin versions (`gradle/libs.versions.toml`)

`agp = 9.3.2`, `kotlin = 2.4.10`, `compose = 1.12.0`, `kyantShapes = 1.2.1`, `activity = 1.13.0`, `core = 1.19.0`.
`backdrop` consumes `kyant-shapes` (`io.github.kyant0:shapes`) as an `implementation` dependency (L43) — note it is
**not `api`**, so app code that uses `RoundedRectangle`/`Capsule` must declare it separately.

---

## 4. UI components in the demo app

All under `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/` unless stated. Package `com.kyant.backdrop.catalog`.
`:app` is **not published** (see §1).

### 4.1 `components/` (5 files)

| File | Signature | Renders |
|---|---|---|
| `components/LiquidButton.kt` L736-745 | `@Composable fun LiquidButton(onClick: () -> Unit, backdrop: Backdrop, modifier: Modifier = Modifier, isInteractive: Boolean = true, tint: Color = Color.Unspecified, surfaceColor: Color = Color.Unspecified, content: @Composable RowScope.() -> Unit)` | Capsule glass button, fixed `48.dp` height, `16.dp` horizontal padding; `vibrancy()+blur(2.dp)+lens(12.dp,24.dp)`; interactive mode scales/translates toward the pointer (`Role.Button`). |
| `components/LiquidToggle.kt` L1086-1092 | `@Composable fun LiquidToggle(selected: () -> Boolean, onSelect: (Boolean) -> Unit, backdrop: Backdrop, modifier: Modifier = Modifier)` | iOS-style switch: `64×28.dp` capsule track lerping `trackColor→accentColor`, `40×24.dp` glass knob; drag + tap; `semantics { role = Role.Switch }`. |
| `components/LiquidSlider.kt` L873-881 | `@Composable fun LiquidSlider(value: () -> Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, visibilityThreshold: Float, backdrop: Backdrop, modifier: Modifier = Modifier)` | `6.dp` capsule track with accent fill + `40×24.dp` glass thumb; drag and tap-to-seek; LTR/RTL aware. |
| `components/LiquidBottomTabs.kt` L470-478 | `@Composable fun LiquidBottomTabs(selectedTabIndex: () -> Int, onTabSelected: (index: Int) -> Unit, backdrop: Backdrop, tabsCount: Int, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit)` | `64.dp` glass tab bar with a `56.dp` selection pill that slides between tabs, plus an accent-tinted "selected content" layer; drag across tabs. |
| `components/LiquidBottomTab.kt` L382-387 | `@Composable fun RowScope.LiquidBottomTab(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)` + `internal val LocalLiquidBottomTabScale = staticCompositionLocalOf { { 1f } }` (L379-380) | One column of a tab bar (icon + label), `weight(1f)`, `Role.Tab`, scaled on press. |

### 4.2 Top-level catalog files

| File | Signature / content | Purpose |
|---|---|---|
| `Block.kt` L21-24 | `@Composable fun Block(content: @Composable () -> Unit)` | Pure pass-through wrapper; used as a demo section marker. |
| `Ripple.kt` (AOSP Apache-2.0 copy) L216-236 | `@Stable fun ripple(bounded: Boolean = true, radius: Dp = Dp.Unspecified, color: Color = Color.Unspecified): IndicationNodeFactory` · `@Stable fun ripple(color: ColorProducer, bounded: Boolean = true, radius: Dp = Dp.Unspecified): IndicationNodeFactory` | Material-ripple-compatible indication factory; plus `object RippleDefaults { val RippleAlpha }` (L238-247) and `@Immutable class RippleConfiguration(val color: Color = Color.Unspecified, val rippleAlpha: RippleAlpha? = null)` (L250-253). |
| `FlightIcon.kt` L58 | `internal val FlightIcon: ImageVector` | Airplane `ImageVector` (lazy, cached in `private var _FlightIcon`); the demo's only icon asset. |
| `BackdropDemoScaffold.kt` L10-14 (+ actuals) | `@Composable expect fun BackdropDemoScaffold(modifier: Modifier = Modifier, content: @Composable BoxScope.(backdrop: LayerBackdrop) -> Unit)`; `actual`s: `app/src/androidMain/…/BackdropDemoScaffold.kt` L41-99 and `app/src/skikoMain/…/BackdropDemoScaffold.kt` L124-149 (signature identical) | Wallpaper host: draws the wallpaper `Image`, registers it via `layerBackdrop`, hands the `LayerBackdrop` to `content`. Android actual adds a "Pick an image" `LiquidButton` + `ActivityResultContracts.PickVisualMedia` + `BitmapFactory`; skiko actual has no picker. |
| `MainContent.kt` L147-180 | `@Composable fun MainContent()` | Demo root: provides `LocalIndication = ripple(...)`, holds `var destination by rememberSaveable`, `when (destination)` dispatch to the 14 destination screens, `BackHandler` to go Home. Navigation is a plain `enum` switch — **no navigation library**. |
| `CatalogDestination.kt` L29-47 | `enum class CatalogDestination { Home, Buttons, Toggle, Slider, BottomTabs, Dialog, LockScreen, ControlCenter, Magnifier, GlassPlayground, AdaptiveLuminanceGlass, ProgressiveBlur, ScrollContainer, LazyScrollContainer }` | The demo's entire route table. |

### 4.3 `destinations/*.kt` (14 files) — one line each

| File | `fun` | Demonstrates | Reusable component code inside? |
|---|---|---|---|
| `HomeContent.kt` | `HomeContent(onNavigate: (CatalogDestination) -> Unit)` | Scrollable index list (private `Subtitle`, `ListItem`). | ⚠ private only (`private fun Subtitle(label: String)`, `private fun ListItem(onClick, text)`) — a list-row idiom, not exported. |
| `ButtonsContent.kt` | `ButtonsContent()` | 3 `LiquidButton` variants (transparent / surface / tinted). | no |
| `ToggleContent.kt` | `ToggleContent()` | `LiquidToggle` on/off with `rememberCanvasBackdrop` demo background. | no |
| `SliderContent.kt` | `SliderContent()` | two `LiquidSlider`s (0..100 and a custom range). | no |
| `BottomTabsContent.kt` | `BottomTabsContent()` | `LiquidBottomTabs` + `LiquidBottomTab` with the airplane icon. | no |
| `DialogContent.kt` | `DialogContent()` | Alert-style glass dialog card (title / 5-line body / Cancel + Okay), dim overlay via `drawWithContent`. | ⚠ **demo-only markup** — `BackdropDemoScaffold(Modifier.drawWithContent { … drawRect(dimColor) })`, no `Dialog`/`Popup` usage, no focus/back/scrim handling; not a reusable dialog. |
| `LockScreenContent.kt` | `LockScreenContent()` | Draggable SDF-based clock glass using `rememberSdfShader` + `drawPlainBackdrop`. | `utils/SdfShader.kt` is a reusable utility (SDF texture → refraction effect). |
| `ControlCenterContent.kt` | `ControlCenterContent()` | iOS Control Center grid: `68.dp` tiles / 2-span tiles, gravity-driven `HighlightStyle.Default(angle = uiSensor.gravityAngle)`, spring enter/exit with drag, gravity sensor. | ⚠ **demo-only tiles** — colors, sizes and the tile grid are inlined in one 443-line composable; no exported tile/panel composable. |
| `MagnifierContent.kt` | `MagnifierContent()` | Draggable magnifier loupe over text using `rememberCombinedBackdrop(backdrop, contentBackdrop, cursorBackdrop)`, `lens(…, depthEffect = true, chromaticAberration = true)` and a `1.5×` scale in `onDrawBackdrop`. | ⚠ **demo-only** — magnifier logic is inline in the composable; not a reusable `Magnifier` component. |
| `GlassPlaygroundContent.kt` | `GlassPlaygroundContent()` | Interactive playground: pinch-zoom/pan `detectTransformGestures` + 5 `LiquidSlider`s live-editing blur/lens parameters. | ⚠ demo-only control panel. |
| `AdaptiveLuminanceGlassContent.kt` | `AdaptiveLuminanceGlassContent()` | Rotating glass card that adapts highlight/luminance to orientation. | ⚠ demo-only. |
| `ProgressiveBlurContent.kt` | `ProgressiveBlurContent()` | Progressive blur: `drawPlainBackdrop` + `blur(4.dp)` + a custom inline AGSL `runtimeShaderEffect("AlphaMask", …)`. | ⚠ the AGSL string is inline in the composable (not exported). |
| `ScrollContainerContent.kt` | `ScrollContainerContent()` | 20 glass cards in a `verticalScroll` column. | no (uses foundation scrolling) |
| `LazyScrollContainerContent.kt` | `LazyScrollContainerContent()` | 100 glass cards in a `LazyColumn` with system-bar insets. | no (uses `androidx.compose.foundation.lazy.LazyColumn`) |

Supporting `utils/` (commonMain, 3 files): `SdfShader.kt` (see above), `DampedDragAnimation.kt`, `InteractiveHighlight.kt`
(the latter two are required by `LiquidButton`/`LiquidSlider`/`LiquidToggle`/`LiquidBottomTabs`); plus platform `utils/`
(`SdfShader.android.kt` / `.skiko.kt`, `Bitmap`, `Coroutines`, `BackHandler`, `UISensor`, `DragGestureInspector`,
`LoremIpsum`, `ProgressConverter`).

---

## 5. Explicit GAP LIST — verified against the repo

Method: exact-token search across **every** `.kt` file of `backdrop/**` and `app/**` (all downloaded from
`raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/kmp/…`). `:androidApp` contains only `MainActivity.kt`.

| Need | Status | Evidence |
|---|---|---|
| Text field / input | **NOT PRESENT** | `BasicTextField`, `TextFieldValue`, `OutlinedTextField`: 0 hits repo-wide. |
| Multi-line text area | **NOT PRESENT** | same; demo shows long copy with `BasicText` only (`DialogContent.kt` L82-97). |
| Dropdown / select / picker | **NOT PRESENT** | `DropdownMenu`, `ExposedDropdownMenuBox`: 0 hits. |
| List row / cell | **PRESENT-ONLY-AS-DEMO-CODE** | `destinations/HomeContent.kt` L77-78 `private fun ListItem(onClick: () -> Unit, text: String)` — `private`, hard-coded 28 sp/16 dp style, not an API. |
| Checkbox | **NOT PRESENT** | `Checkbox`: 0 hits. |
| Radio group | **NOT PRESENT** | `RadioButton`, `selectableGroup`: 0 hits. |
| Segmented control | **NOT PRESENT** | `SegmentedButton`, `SegmentedControl`: 0 hits. |
| Progress bar / spinner | **NOT PRESENT** | `Linear/CircularProgressIndicator`: 0 hits. (`utils/ProgressConverter.kt` is an easing helper, not a UI component.) |
| Slider | **PRESENT AS-IS (demo module only)** | `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidSlider.kt` L873-881 — full signature in §4.1. Not published; must be copied. |
| Switch / toggle | **PRESENT AS-IS (demo module only)** | `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidToggle.kt` L1086-1092 — signature-driven `(selected: () -> Boolean, onSelect: (Boolean) -> Unit)`; no `Modifier.toggleable` semantics beyond `role = Role.Switch`. |
| Dialog / bottom sheet / popup API | **NOT PRESENT** | `Dialog`, `AlertDialog`, `ModalBottomSheet`, `BottomSheet`, `Popup`: 0 hits. `destinations/DialogContent.kt` is a static glass card inside the scaffold, not a dialog API. (The docs' "Glass Bottom Sheet" tutorial is prose-only, not code in this repo.) |
| Alert | **NOT PRESENT** | same as above. |
| Toast / snackbar | **NOT PRESENT** | `Snackbar`, `Toast`, `SnackbarHost`: 0 hits. |
| Top app bar / navigation bar | **NOT PRESENT** | `TopAppBar`, `BottomAppBar`, `NavigationBar` (exact word): 0 hits. Only `Modifier.navigationBarsPadding()` (insets) appears. |
| Tab bar | **PRESENT AS-IS (demo module only)** | `components/LiquidBottomTabs.kt` L470-478 + `components/LiquidBottomTab.kt` L382-387. `TabRow`/`ScrollableTabRow`: absent. |
| Scroll container / lazy list | **NOT PRESENT** (no wrapper) | No library/catalog wrapper exists; demos call `androidx.compose.foundation` directly (`ScrollContainerContent.kt`, `LazyScrollContainerContent.kt`). Glass-on-scroll works via `Modifier.drawBackdrop` + `verticalScroll`/`LazyColumn`. |
| Navigation / routing | **NOT PRESENT** | `NavHost`, `rememberNavController`, any nav dependency: 0 hits. `MainContent.kt` L156-174 uses `when (destination)` on `CatalogDestination`. |
| Date-time picker | **NOT PRESENT** | `DatePicker`, `TimePicker`: 0 hits. |
| Color picker | **NOT PRESENT** | `ColorPicker`: 0 hits (only `ColorFilter` APIs). |
| Tooltip | **NOT PRESENT** | `TooltipBox`, `TooltipArea`: 0 hits. |
| Badge | **NOT PRESENT** | `Badge`: 0 hits. |
| Empty state | **NOT PRESENT** | No empty-state composable or string resource. |
| File picker | **PRESENT-ONLY-AS-DEMO-CODE (Android only)** | `app/src/androidMain/…/BackdropDemoScaffold.kt` L52-66, L82-97 — `ActivityResultContracts.PickVisualMedia(ImageOnly)` + `LiquidButton`; **images only**, Android-only, no skiko equivalent (`app/src/skikoMain/…/BackdropDemoScaffold.kt` has none). |
| Canvas / image viewer | **NOT PRESENT** | No image-viewer/zoom composable; wallpaper `Image` + `ContentScale.Crop` only in the scaffolds. |
| Zoom-pan | **PRESENT-ONLY-AS-DEMO-CODE** | `destinations/AdaptiveLuminanceGlassContent.kt` L12 + `GlassPlaygroundContent.kt` L12 use `detectTransformGestures` inline; `destinations/MagnifierContent.kt` uses `draggable2D`. No exported zoom/pan container. |

**Net:** the only genuinely reusable UI controls are the 5 in `components/`. Everything "dialog-like", "control-center-like",
"magnifier-like", "zoom/pan-like" or "file-picker-like" is inline demo code.

---

## 6. Reusable-as-is component count

**5 UI components** can be copied verbatim today (all from the unpublished `:app` module):

1. `LiquidButton` — `components/LiquidButton.kt` L736
2. `LiquidToggle` — `components/LiquidToggle.kt` L1086
3. `LiquidSlider` — `components/LiquidSlider.kt` L873
4. `LiquidBottomTabs` — `components/LiquidBottomTabs.kt` L470
5. `LiquidBottomTab` — `components/LiquidBottomTab.kt` L382

Caveats on "verbatim":
- (4) and (5) are a matched pair (a tab bar without tabs renders nothing useful); the repo's own README lists exactly
  `LiquidButton`, `LiquidToggle`, `LiquidSlider`, `LiquidBottomTabs` as the example components.
- All five import `com.kyant.shapes.Capsule`/`RoundedRectangle` (`io.github.kyant0:shapes:1.2.1`) and call
  `com.kyant.backdrop.drawBackdrop` / `effects.*`, so app code must add `io.github.kyant0:shapes` explicitly
  (it is an `implementation`, not `api`, dependency of `backdrop` — `backdrop/build.gradle.kts` L43).
- All five require two utilities: `utils/DampedDragAnimation.kt`, `utils/InteractiveHighlight.kt`
  (`LiquidBottomTab` additionally has `internal val LocalLiquidBottomTabScale` in its own file). `LiquidButton`'s
  non-interactive path uses `LocalIndication`, which the demo supplies via `Ripple.kt`'s `ripple(...)`.
- Marginal extras that are arguably reusable but are **not** UI controls: `ripple(...)` / `RippleDefaults` /
  `RippleConfiguration` (`Ripple.kt` — an Apache-2.0 AOSP `androidx.compose.material.ripple` reimplementation),
  `SdfShader` + `rememberSdfShader` (`utils/SdfShader.kt`), and the `BackdropDemoScaffold` wallpaper host
  (`expect`/`actual`; Android actual adds a photo picker).

---

## UNVERIFIED / not checked

- Only `:backdrop`, `:app`, `:androidApp` sources were read; `:androidApp` contains just
  `src/main/java/com/kyant/backdrop/catalog/MainActivity.kt` (not read line-by-line).
- GitBook tutorial pages (`tutorials/glass-bottom-bar`, `interactive-glass-bottom-bar`, `glass-bottom-sheet`,
  `smoother-rounded-corners`, `progressive-blur`) were **not** fetched in full — only `get-started`, `api/backdrops`,
  `api/backdrop-effects`, `faq` and `llms.txt`. They may contain code not on the `kmp` branch.
- Whether the demo APK in `androidApp/release/` matches this source revision: UNVERIFIED (binary not inspected).
- Maven Central runtime resolution was inferred from `backdrop-2.0.1.module` variant names only; the `.jar`/`.klib`
  binaries were not downloaded or decompiled.
