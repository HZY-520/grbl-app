> READ-ONLY analysis artifact for planning an iGRBL v3.0 UI refactor. Repo root `D:\工作台\iGRBL`, version `2.0.0` (`package.json:3`, `src/ui/version.ts:11` = `'2.0'`, `android/app/build.gradle:10-11` versionCode 20 / versionName "2.0").

# iGRBL v2.0 UI inventory

## 1. View inventory

13 routed views + 1 global overlay component. Routes are hash-history (`src/router.ts:86`). `meta.tab` decides whether the bottom tab bar renders and which tab is active (`src/App.vue:21-22,88`).

| View (file, lines) | Route / name | Purpose | Control types used (count) | Heavy Canvas/DOM? |
|---|---|---|---|---|
| `src/ui/views/HomeView.vue` (280) | `/home`, `home`, tab `home` | Device status, coordinates, job run/hold/abort, quick actions, log | GlassSurface 5, GlassButton 16, GlassProgress 1, GlassBadge 2, GlassDot 2, AppIcon 16, GcodePreview 1, LogList 1, lg-stat 6, lg-kv 3, lg-grid-2/3/4 3, toast calls 9 | Yes — hosts `GcodePreview` with live `progress` (line 226-232); `keep-alive`-cached (App.vue:82) |
| `src/ui/views/ConvertHubView.vue` (116) | `/convert`, `convert`, tab `convert` | Entry hub → image/text/SVG convert | GlassSurface 3, GlassButton 1, AppIcon 3, clickable GlassSurface cards (`hub-card`) 3, static `<ol>` flow list 1 | No |
| `src/ui/views/ImageConvertView.vue` (685) | `/convert/image`, `convert-image` | Bitmap → G-code: Line2Line / dithering / Potrace outline / centerline / smart | GlassSurface 8, GlassInput 17, GlassSelect 3, GlassOption 3, GlassSwitch 9, GlassSlider 5, GlassCell 9, GlassCollapse 1, GlassCollapseItem 1, GlassAlert 2, AppIcon 4, GcodePreview 1, `<img>` preview 2, toast calls 7 | Yes — synchronous rasterize+vectorize in `generate()` (lines 232-251) on the main thread; renders `ImageData` previews via `toDataURL` |
| `src/ui/views/TextConvertView.vue` (442) | `/convert/text`, `convert-text` | Text → G-code: Hershey single-stroke / outline / centerline / smart | GlassSurface 6, GlassInput 11, GlassSelect 1, GlassOption 1, GlassSwitch 4, GlassCell 4, GlassAlert 3, AppIcon 3, GcodePreview 1, `<img>` 1, toast calls 6 | Yes — `renderTextToImage` uses canvas text metrics + rasterization, then vectorize; synchronous (lines 97-195) |
| `src/ui/views/SvgConvertView.vue` (508) | `/convert/svg`, `convert-svg` | SVG → G-code: geometric outline (DOMParser) / rasterize+centerline / smart | GlassSurface 5, GlassInput 11, GlassSwitch 3, GlassSlider 2, GlassCell 3, GlassAlert 2, AppIcon 5, GcodePreview 1, `<img>` 1, `<textarea>`-equivalent 0, toast calls 6 | Yes — `convertSvgVector` rasterizes via `document.createElement('canvas')` (`src/core/vector/SvgVector.ts:196-207`); `await`ed in `generate()` (lines 179-211) |
| `src/ui/views/FileView.vue` (249) | `/file`, `file`, tab `file` | Load local G-code, saved-file CRUD, run | GlassSurface 3, GlassButton 8, AppIcon 9, GcodePreview 1, saved-file list (`v-for` + `saved-item`) 1, lg-stat 3, toast calls 7 | Yes — GcodePreview; file intake via hidden `<input type=file>` created in `pickFile` (`src/ui/utils.ts:14-18`) |
| `src/ui/views/PreviewView.vue` (217) | `/preview`, `preview` | Enlarged path preview, stats, job control | GlassSurface 3, GlassButton 7, GlassProgress 1, AppIcon 8, GcodePreview 1, stat grid (scoped `.stat`) 6, toast calls 4 | Yes — GcodePreview with `height` zoom 200–640 (lines 12-14, 39-41) |
| `src/ui/views/JogView.vue` (342) | `/jog`, `jog`, tab `jog` | 9-cell jog pad, step/speed, absolute move, overrides, laser test | GlassSurface 6, GlassButton 8 (9 rendered incl. v-for pad), GlassInput 4, GlassSlider 3, GlassBadge 1, AppIcon 5, lg-pad 9-cell grid 1, lg-kv 2, toast calls 10 | No (pure DOM grid + sliders) |
| `src/ui/views/ConnectView.vue` (302) | `/connect`, `connect` | USB/Bluetooth enumeration, baud select, connect/disconnect | GlassSurface 5, GlassButton 5, GlassSelect 1, GlassOption 1, GlassLoading 1, GlassBadge 0, AppIcon 7, custom segmented `<button>` pair (`.seg`) 2, device list (`v-for` `.dev-item`) 1, toast calls 1 | No |
| `src/ui/views/ConfigView.vue` (303) | `/config`, `config` | GRBL `$$` read/write, `.nc` preset import + apply | GlassSurface 3, GlassButton 5, GlassInput 2, AppIcon 2, parameter list (`v-for` `.cfg-item`) 1, toast calls 8 | No |
| `src/ui/views/TerminalView.vue` (135) | `/terminal`, `terminal` | Raw command terminal + quick commands | GlassSurface 3, GlassButton 4, GlassInput 1, GlassBadge 1, AppIcon 2, LogList 1, quick-command button group 6, toast calls 2 | No |
| `src/ui/views/SettingsView.vue` (421) | `/settings`, `settings`, tab `settings` | Appearance, connection, device profiles, engraving/jog defaults, nav links | GlassSurface 9, GlassInput 14, GlassSelect 2, GlassOption 2, GlassSwitch 6, GlassCell 10, AppIcon 6, GlassSwitch-in-cell 6, toast calls 4 | No |
| `src/ui/views/AboutView.vue` (150) | `/about`, `about` | Credits, feature list, repo links, licence | GlassSurface 6, GlassButton 4, AppIcon 6, lg-kv 5, feature list (`v-for` `.feat`) 1, `window.open` links 4 | No |
| `src/ui/components/SetupWizard.vue` (505) | not routed — rendered by `App.vue:97` when `state.needsSetup` | 4-step first-run device setup overlay | GlassSurface 4 (one per step), GlassButton 12, GlassInput 11, GlassSelect 2, GlassOption 2, GlassAlert 1, AppIcon 8, custom step rail (`.wiz__step` ×4, not a real tab bar), lg-kv 6 summary rows, `position:fixed` overlay + own scroll body + sticky footer, toast calls 5 | No Canvas; heavy DOM (fixed overlay + `backdrop-filter` scrim) |

Aggregate control components used anywhere in views: GlassSurface 70, GlassButton 103, GlassInput 71, GlassSelect 12, GlassOption 12, GlassSwitch 22, GlassSlider 10, GlassCell 26, GlassProgress 2, GlassAlert 9, GlassCollapse 1, GlassCollapseItem 1, GlassLoading 1, AppIcon 89.

Not used by any view: `GlassSegmented` (0 uses — segmented UIs are hand-rolled with `GlassButton` + `:type`/`:plain` toggles, e.g. `ImageConvertView.vue:346-355`, or custom `.seg` buttons in `ConnectView.vue:116-131`); `GlassNavBar`/`GlassTabBar`/`GlassTabItem`/`GlassToaster`/`GlassAurora` are App-shell only. `docs/GLASS-CONVERSION.md:152-153` explicitly forbids rewriting those button rows into `GlassSegmented`.

## 2. Glass component kit inventory (`src/ui/glass/`, 24 files, 1093 lines)

Registered globally in `src/ui/glass/index.ts:31-57` via `installGlass(app)` (`src/main.ts:13`); typed in `src/ui/glass/components.d.ts:26-48`.

| File | Lines | Props / emits / v-model | Renders |
|---|---|---|---|
| `GlassSurface.vue` | 119 | props `radius`(26) `lens`('convex') `lensStrength`(1.15) `scale`(150) `frost`(0.2) `dispersion`(52) `saturation`(155) `alpha`(0.5) `lightness`(56) `quality`('low') `blur`(0) `flat`(false) `pad`(true) `angle`(0); slot default; no emits | **Wraps `<liquid-glass>`**: `<div class="g-host">` → `<div class="g-host__bg g-flat">` when `flat \|\| !glassConfig.refraction`, else `<liquid-glass class="g-host__bg">`, then `<div class="g-host__content"><slot/></div>` |
| `GlassAurora.vue` | 12 | none | 4 `<span class="g-aurora__blob g-aurora__blob--N">` on a fixed `.g-aurora`; **no `id` attribute** |
| `GlassButton.vue` | 45 | `type`('default') `size`('normal'\|'small'\|'mini') `block` `plain` `text` `round` `disabled` `loading`; slot; native `click` | `<button class="g-btn g-btn--…" type="button">` + optional `.g-btn__spinner` |
| `GlassInput.vue` | 110 | `modelValue` `type` `placeholder` `disabled` `textarea` `rows`(3) `variant`(ignored); emits `update:modelValue(string)` `blur(FocusEvent)` `focus` `keyup(KeyboardEvent)` `input`; `defineExpose({el})` | `.g-field > input` or `.g-field--textarea > textarea` |
| `GlassSelect.vue` | 96 | `modelValue` `placeholder`('请选择') `disabled` `variant`(ignored); emits `update:modelValue` `change`; `inheritAttrs:false`, attrs bound to trigger button | trigger `<button.g-select>` + chevron svg + hidden `<div style="display:none"><slot/></div>` + `<Teleport to="body">` iOS bottom sheet (`.g-sheet-mask`, `.g-sheet`, `.g-sheet__grabber/__title/__list/__opt`) |
| `GlassOption.vue` | 21 | `label` `value` | `<span hidden/>`; registers into injected `'glass-select'` provide registry on mount, unregisters on unmount |
| `GlassSwitch.vue` | 31 | `modelValue` `disabled`; emits `update:modelValue(boolean)` | `<button.g-switch role="switch" :aria-checked>` + `.g-switch__knob` |
| `GlassSlider.vue` | 68 | `modelValue` `min`(0) `max`(100) `step`(1) `disabled` `showValue` `suffix`; emits `update:modelValue(number)` `change(number)` | `.g-slider` → transparent native `<input type="range">` over drawn `.g-slider__track/__fill/__knob`; optional `.g-slider__val` |
| `GlassCell.vue` | 40 | `title` `description` `isLink`; slots `icon`, default, `extra` | `.g-cell` + `.g-cell__icon/__main/__title/__desc/__extra` + chevron svg when `isLink` |
| `GlassProgress.vue` | 31 | `value` `showLabel` `color` `trackColor`; no emits | `.g-progress[role=progressbar]` + `.g-progress__bar` width% |
| `GlassAlert.vue` | 17 | `title` `type`('info'\|'success'\|'warning'\|'error'); slot default | `.g-alert.g-alert--{type}` with `{{title}}` fallback slot |
| `GlassCollapse.vue` | 32 | `modelValue: string[]`; emits `update:modelValue(string[])`; provides `'glass-collapse'` | `<div class="g-collapse"><slot/></div>` |
| `GlassCollapseItem.vue` | 38 | `name` `title`; injects `'glass-collapse'` | `<button.g-collapse__head>` + chevron + `v-if="open"` `.g-collapse__body` (**content unmounted**, no animation) |
| `GlassLoading.vue` | 8 | `type` (ignored) | `.g-loading > .g-loading__ring` |
| `GlassNavBar.vue` | 26 | `title`; slots `left`, `right` | `<header.g-nav>` → **`GlassSurface`** with `:radius="22" :lens="'shift'" :scale="120" :frost="0.22" :blur="8" :lens-strength="1"` → `.g-nav__inner` 3-column |
| `GlassTabBar.vue` | 195 | `active`; emits `change(name)`; provides `'glass-tabbar'` (`isActive`, `select`, `scrubName`, `scrubbing`) | `<nav.g-tabbar @pointerdown @click.capture>` → **`GlassSurface`** with `:radius="26" :lens="'shift'" :scale="110" :frost="0.24" :blur="9" :lens-strength="0.9" :pad="false"` → `.g-tabbar__inner` grid |
| `GlassTabItem.vue` | 35 | `name` `label`; slot `icon` with `{active}` scope | `<button.g-tab :data-tab="name">` + icon slot + label span |
| `GlassSegmented.vue` | 48 | `modelValue` `options: SegmentOption[]` `block`(true); emits `update:modelValue` `change` | `.g-seg` row of `<button.g-seg__item>` (unused by views) |
| `GlassToaster.vue` | 20 | none | `<Teleport to="body">` → `.g-toast-wrap` → `v-for` `.g-toast.g-toast--{type}` with inline per-type svg path |
| `index.ts` | 61 | — | `installGlass(app)`, re-exports `showToast`, `toasts`, `GLASS_SOURCE_ID`, `GLASS_SOURCE_SELECTOR` |
| `register.ts` | 18 | — | Side-effect `import 'simple-liquid-glass/web-component'`; exports `GLASS_SOURCE_ID = 'glass-aurora'`, `GLASS_SOURCE_SELECTOR = '#glass-aurora'` |
| `config.ts` | 13 | — | `export const glassConfig = reactive({ refraction: true })` — the "高性能玻璃" switch in `SettingsView.vue:132-136,150-154` |
| `toast.ts` | 28 | — | `toasts` reactive array; `showToast(message, type='info', duration=2200)`; `seq` counter; `window.setTimeout` removal |
| `components.d.ts` | 51 | — | `GlobalComponents` augmentation + `'liquid-glass'` custom element type |

### `liquid-glass` attribute contract (exact)

Only `GlassSurface.vue:89-107` touches `<liquid-glass>`, plus two indirect users (`GlassNavBar`, `GlassTabBar`). Vue template binding is deliberately avoided; all attributes are pushed with `setAttribute` inside `watchEffect` because `blur` collides with `HTMLElement.blur` (comment at `GlassSurface.vue:81-88`).

Attributes written every run, in this order:
`radius`, `lens`, `lens-strength`, `scale`, `frost`, `dispersion`, `saturation`, `alpha`, `lightness`, `quality`, `blur`, `angle`.

Concrete call-site values:
- `GlassNavBar.vue:14`: radius 22, lens `shift`, scale 120, frost 0.22, blur 8, lens-strength 1 (rest default).
- `GlassTabBar.vue:183-190`: radius 26, lens `shift`, scale 110, frost 0.24, blur 9, lens-strength 0.9, pad `false`.
- Every view card: `GlassSurface class="lg-section"` with all defaults (`radius 26`, `lens convex`, `lensStrength 1.15`, `scale 150`, `frost 0.2`, `dispersion 52`, `saturation 155`, `alpha 0.5`, `lightness 56`, `quality low`, `blur 0`, `pad true`, `angle 0`).

Facts about the custom element wiring:
- **`backdrop-selector` is never set anywhere.** `GLASS_SOURCE_ID`/`GLASS_SOURCE_SELECTOR` are exported (`register.ts:15-18`, re-exported `index.ts:61`) but have zero consumers outside `index.ts`; `GlassAurora.vue` sets no `id`, so `#glass-aurora` matches nothing in the DOM.
- `renderer="webgl"` is never set either; `docs/LIQUID-GLASS-RESEARCH.md:162-164` records that this means Android silently uses the SVG `feDisplacementMap` path, not WebGL.
- `vite.config.ts:5-14` registers `liquid-glass` via `compilerOptions.isCustomElement` so Vue does not try to resolve it as a component.
- Fallback path: `glassConfig.refraction === false` (or `flat`) renders `.g-host__bg.g-flat` = `background: var(--g-solid)` + `backdrop-filter: blur(20px) saturate(180%)` (`glass.css:315-321`).

## 3. Design system & app shell

### `src/styles/glass.css` (1365 lines) — the live stylesheet

Imported once at `src/main.ts:2`. Structure:

**(a) Tokens.** `:root` block `glass.css:15-80` defines the whole palette:
- Brand: `--g-accent #ff8a2b`, `--g-accent-strong #ff6a00`, `--g-accent-soft rgba(255,138,43,.16)`.
- iOS semantic: `--g-green #30d158`, `--g-orange #ff9f0a`, `--g-red #ff453a`, `--g-blue #0a84ff`, `--g-gray #8e8e93`.
- Surfaces: `--g-bg #05060a`, `--g-text #f5f5f7`, `--g-text-dim #9b9ba6`, `--g-text-faint #6c6c78`, `--g-hairline rgba(255,255,255,.12)`, `--g-hairline-strong`, `--g-fill/-2/-3` (white .07/.11/.16), `--g-solid rgba(22,23,30,.72)`, `--g-solid-2`, `--g-scrim rgba(5,6,10,.72)`, `--g-chrome rgba(5,6,10,.55)`.
- Radii: `--g-r-card 26px`, `--g-r-ctrl 18px`, `--g-r-field 14px`, `--g-r-pill 999px`.
- Shadow: `--g-shadow`, `--g-shadow-sm`. Sizes: `--g-nav-h 52px`, `--g-tab-h 64px`, `--g-gap 12px`, `--g-pad 14px`. Motion: `--g-ease cubic-bezier(.32,.72,0,1)`, `--g-ease-out`, `--g-dur .34s`.
- **Legacy alias block, lines 68-80** (`--lg-bg`, `--lg-panel`, `--lg-panel-2`, `--lg-border`, `--lg-text`, `--lg-text-dim`, `--lg-accent`, `--lg-accent-2`, `--lg-danger`, `--lg-warn`, `--lg-nav-h`) — these exist only so views may keep `var(--lg-*)` references. Many still do (e.g. `PreviewView.vue:184,192,202,209`, `FileView.vue:187,195,200`, `JogView.vue:339`, `ConnectView.vue:229,243,251,266`, `ImageConvertView.vue:608,610,630,674`, `TextConvertView.vue:431`, `SettingsView.vue:382,415`, `SetupWizard.vue:396,403,411,428,434,448,475`), and `GcodePreview.vue:114-116` reads `--lg-accent`/`--lg-text-dim`/`--lg-border` through `getComputedStyle` in JS.

**(b) Dark/light theming.** Only one selector: `html[data-theme='light']` (`glass.css:82-104`) overrides bg/text/hairline/fill/solid/scrim/chrome/shadow values. `body` base at `glass.css:120-130`; light-mode spot overrides at `glass.css:211-214` (aurora opacity/blur), `1108-1111` (alert text colours), `1219` (log bg), `1237` (canvas wrap bg).

**(c) Aurora background.** `.g-aurora` fixed inset 0, `z-index: 0`, `pointer-events: none` (`glass.css:140-147`); four `.g-aurora__blob` spans with `filter: blur(60px)`, `will-change: transform`, radial gradients (orange `#ff8a2b`, violet `rgb(94,62,240)`, cyan `rgb(16,168,196)`, magenta `rgb(224,48,148)`) and `g-drift-1..4` keyframes at 26/32/38/30 s (`glass.css:149-209`). `@media (prefers-reduced-motion: reduce)` kills the animation and forces all transitions to 0.001 ms (`glass.css:217-225`, and again `606-608` for the tab scrub halo).

**(d) Every `lg-*` utility class** (all defined in `glass.css`; `global.css` also defines most of them but is dead — see below):

| Class | Line | Role |
|---|---|---|
| `.lg-app` / `.lg-app__main` | 228-242 | shell flex column; main region `flex:1; overflow:hidden` |
| `.lg-page` | 244-248 | per-view column, `min-height:100%` |
| `.lg-body` | 250-258 | scrolling body, `padding: var(--g-gap)`, flex column gap |
| `.lg-body--nav` | 260-262 | bottom padding `calc(var(--g-tab-h) + 34px)` |
| `.lg-section` | 310-312 | card radius only (`padding` delegated to `GlassSurface pad`) |
| `.lg-title` (+ `> span:first/last-child`) | 324-344 | section heading row, ellipsis on first span |
| `.lg-dim` | 346-349 | dim caption 12.5px |
| `.lg-mono` | 351-355 | mono + `tabular-nums` |
| `.lg-center` / `.lg-mt` / `.lg-mb` | 357-359 | text-align / margins |
| `.lg-row`, `.lg-row--between` | 361-366 | flex row / space-between |
| `.lg-grid-2` / `.lg-grid-3` / `.lg-grid-4` | 368-370 | 2/3/4-col grids; `min-width:0` guards at 372-383 |
| `.lg-kv`, `.lg-kv__k`, `.lg-kv__v` | 385-400 | key/value row |
| `.lg-empty` | 402-407 | empty-state text |
| `.lg-stat`, `.lg-stat__k`, `.lg-stat__v` | 410-418 | stat tile |
| `.lg-badge`, `.lg-badge--ok/--warn/--err/--idle` | 421-437, 434-437 | status pill |
| `.lg-dot` | 439-445 | 7px glowing dot |
| `.lg-log`, `.lg-log__line`, `--err/--warn/--ok/--cmd/--dim` | 1207-1226 | 260px mono log box |
| `.lg-canvas-wrap`, `.lg-canvas-wrap canvas` | 1229-1238 | canvas frame (dark/light bg) |
| `.lg-pad`, `.lg-pad .g-btn` | 1240-1248 | 3×3 jog grid, 54px buttons |
| `.lg-deco` | 1251 | label wrapper padding |
| `.lg-slider-row`, `.lg-slider-row__val` | 1253-1259 | slider + numeric readout |
| `.lg-status`, `.lg-status--ok/--warn/--err/--idle` | 1328-1346 | nav-bar connection pill (used by `App.vue:73`) |

Non-`lg-` shared classes also in `glass.css`: `.g-brand` (485-493), `.g-host*/g-flat` (267-321), every `g-*` control style (`.g-btn` 611-715, `.g-seg` 718-751, `.g-field` 754-805, `.g-select` 808-842, `.g-sheet*` 845-918, `.g-cell*` 921-955, `.g-switch` 958-991, `.g-slider` 994-1056, `.g-progress` 1059-1072, `.g-alert` 1075-1111, `.g-collapse` 1114-1145, `.g-loading` 1148-1161, `.g-toast*` 1164-1204, `.g-nav*` 449-493, `.g-tabbar/.g-tab*` 496-604), and view-level compat classes `.btn-text` (1264), `.num-grid`/`.num-item` (1267-1273), `.upload-box` (1276-1290), `.saved-item*` (1293-1302), `.hub-card*` (1305-1322), `.steps` (1325), `.wiz__foot-btn` (1349), `.dev-item` (1352-1365).

### `src/styles/global.css` (402 lines) — dead code

Not imported anywhere (`src/main.ts` imports only `./styles/glass.css`). It is the pre-glass stylesheet: it defines the *original* `--lg-*` token set (`global.css:1-22`, e.g. `--lg-bg #101014`, `--lg-accent #ff7a18`, `--lg-nav-h 56px`), a duplicate of most `lg-*` utilities with hard-coded values, and — critically — **Varlet-specific selectors** for the component library that has been fully replaced: `.var-input`/`.var-select` (78-85), `.lg-appbar .var-app-bar__*` (88-112, 396-398), `.lg-pad .var-button` (291-294), `.lg-deco .var-field-decorator__label` (362-365), `.lg-slider-row .var-slider` (373-375), `.var-bottom-navigation` (400-402). Its token block is superseded by the alias block in `glass.css:68-80` (which yields *different* values — e.g. `--lg-accent` is `#ff8a2b` from glass.css vs `#ff7a18` from global.css; `--lg-nav-h` is 64px vs 56px). The only `global.css`-exclusive class is `.lg-scroll-x` (391-394).

### App shell — `src/App.vue` (102 lines)

- Root: `<div class="lg-app">` containing, in order: `GlassAurora` (line 60), `GlassNavBar` (62), `<main class="lg-app__main">` with `<router-view v-slot>` (80-86), `GlassTabBar` + `GlassTabItem` (88-94), `SetupWizard` (97), `GlassToaster` (100).
- Nav bar: `#left` slot = back `GlassButton` (round, small) when `canGoBack`, else the `iGRBL` brand lockup; `#right` = a hand-rolled `<button class="lg-status lg-status--{kind}">` with `.lg-dot` + status text, routed to `/connect` (73-77).
- Tab model: frozen array of 5 tabs (home/convert/file/jog/settings) with `label` + `icon` + `path` (12-18); `showNav = !!route.meta.tab` (22); `canGoBack = !showNav && route.path !== '/home'` (23); `onTabChange` maps name→path and `router.push`es (36-39).
- Status derivation duplicated from `HomeView.vue:30-37` (connecting→warn, disconnected→idle, Alarm→err, Run/Jog→ok, Hold/Door→warn, else ok).
- **Route transitions: none.** There is no `<transition>`/`<TransitionGroup>` anywhere in the app; view switching is instant. `<keep-alive :include="['HomeView']">` caches only HomeView (line 82).
- Theme switching: `onMounted(() => applyTheme(state.theme))` + `watch(() => state.theme, applyTheme)` (50-54).
- The wizard is mounted/unmounted purely by `state.needsSetup`, with `state.setupDeviceId` as prop.

### `src/ui/theme.ts` (12 lines)

`applyTheme(theme)` sets `document.documentElement.dataset.theme` (→ drives every `html[data-theme=…]` rule), sets `style.colorScheme`, and rewrites the `<meta name="theme-color">` content (`#eef0f6` light / `#05060a` dark). `index.html:9` hard-codes `#101014` as the initial value.

`src/ui/icons.ts` (47 lines): `ICON_PATHS: Record<string,string>` — **45 inline 24×24 stroke paths** (`home, grid, image, text, vector, file, folder, move, crosshair, settings, config, terminal, usb, play, pause, stop, refresh, back, forward, plus, minus, info, eye, power, layers, check, close, trash, target, homeZero, ruler, speed, flame, clock, save, download, edit, link, zoomIn, zoomOut, list, bluetooth`). Rendered by `AppIcon.vue` (40 lines): props `name` (unknown name falls back to `info`), `size` (22 default; number→px), `strokeWidth` (1.8); emits one `<svg class="app-icon" viewBox="0 0 24 24" stroke="currentColor">` with a single `<path>`.

## 4. Behaviour a UI refactor MUST preserve

### 4.1 Routing structure — `src/router.ts` (88 lines)

Hash history (`createWebHashHistory`, line 86). 13 routes + 1 redirect:

| path | name | meta |
|---|---|---|
| `/` | — | `redirect: '/home'` (line 4) |
| `/home` | `home` | `tab:'home'`, `title:'激光雕刻'` |
| `/convert` | `convert` | `tab:'convert'`, `title:'图案生成'` |
| `/convert/image` | `convert-image` | `title:'图片转雕刻'` |
| `/convert/text` | `convert-text` | `title:'文字转雕刻'` |
| `/convert/svg` | `convert-svg` | `title:'SVG 转雕刻'` |
| `/file` | `file` | `tab:'file'`, `title:'雕刻文件'` |
| `/preview` | `preview` | `title:'路径预览'` |
| `/jog` | `jog` | `tab:'jog'`, `title:'运动控制'` |
| `/connect` | `connect` | `title:'连接设备'` |
| `/config` | `config` | `title:'机器参数'` |
| `/terminal` | `terminal` | `title:'串口终端'` |
| `/settings` | `settings` | `tab:'settings'`, `title:'设置'` |
| `/about` | `about` | `title:'关于'` |

All components are lazy `() => import(...)`. `meta.title` is the nav-bar title source (`App.vue:20`), `meta.tab` gates the tab bar. Route names are used in `App.vue` tab list only; views navigate by **path string** (`router.push('/home')` etc.), and one place uses `router.back()` with a `window.history.length` guard (`App.vue:41-44`, `ConnectView.vue:74-76`).

### 4.2 State management — `src/ui/store.ts` (504 lines)

A single module-level `reactive()` object `state` (59-93) — **not** Pinia/Vuex. Key state:

`connected`, `connecting`, `status` (`MacStatus`), `version`, `firmware`, `pos{x,y,z}`, `wpos{x,y,z}`, `wco{x,y,z}`, `feed`, `spindle`, `overrides{feed,rapids,power}`, `targetOverrides`, `progress{sent,total,executed}`, `running`, `issue`, `devices[]`, `bluetoothDevices[]`, `scanning`, `deviceKind` ('usb'|'bluetooth'), `deviceId` (-1 when none), `baud`, `jog{step,speed}`, `log: LogItem[]`, `file: GcodeFileData|null`, `configRev` (bump-to-reload counter), `settings` (snapshot of `AppSettings.all()`), `theme` ('dark'|'light'), `needsSetup`, `setupDeviceId`.

Constants exported: `STATUS_LABELS` (14 Chinese status names, 32-47), `FIRMWARE_LABELS` (50-55).

Actions (all exported functions): `refreshDevices`, `refreshBluetoothDevices`, `connect(device)`, `openBluetoothSettings`, `openSetupWizard(deviceId=-1)`, `reloadSettings`, `clearNeedsSetup`, `readMachineLimits()` → `{travelX,travelY,maxPower,minPower}` from `$130/$131/$30/$31`, `disconnect`, `sendCommand(line)`, `softReset`, `feedHold`, `cycleStart`, `homing`, `unlock`, `setNewZero`, `resetWCO`, `jog(dir)`, `setJogParams(step?,speed?)`, `moveTo(x,y)`, `laserTest(power,durationMs):boolean`, `setTargetOverride(kind,value)`, `setStreamingMode(mode)`, `loadGcodeText(name,text)`, `loadGcodeLines(name,lines)`, `clearFile`, `runFile(resetBuffer=true):boolean`, `abortFile`, `readMachineConfig`, `writeMachineSetting(id,value)`, `refreshConfigEntries`, `updateSetting(key,value)`, `setThreadingMode(name)`, `settingInfo(id):string[]`, `configEntries()`, `setTheme(theme)`, `clearLog`, `pushLog(text,kind)`.

Event binding: `store.ts:141-198` subscribes to 10 `GrblCore` events (`status`, `connected`, `disconnected`, `position`, `fss`, `override`, `progress`, `programEnd`, `message`, `issue`, `connectTimeout`) and mirrors them into `state`. `configRev` is incremented on `connected` and on every `MessageType.Config` message; `ConfigView.vue:101` does `watch(() => state.configRev, reload)`.

Log is capped at 600 entries via `splice` (`store.ts:99`).

### 4.3 Imperative surfaces (what breaks in a declarative rewrite)

These are the non-declarative contracts — every one must be reimplemented or bridged:

1. **`grbl` singleton + event emitter** — `src/core/grbl/GrblCore.ts:891` `export const grbl = new GrblCore()`; `Emitter<CoreEvents>` (`src/core/Emitter.ts`, 30 lines) with `on/off/emit`. 12 event names typed at `GrblCore.ts:29-52`.
2. **4 ms software TX loop** — `window.setInterval(() => this.txTick(), 4)` (`GrblCore.ts:270`). Each tick: connect-timeout check (10 s), status poll per `threadingMode.statusQuery`, send one line if buffer allows, `manageOverrides()`, `detectHang()`. Buffer accounting is byte-based against `autoBufferSize` (default 127, auto-updated from `[OPT:]` / `Bf:` reports: `parseOptMessage` 561-571, `setAutoBufferSize` 573-581).
3. **Byte-level real-time commands** via `sendImmediate(byte)`: `?`=0x3f, soft-reset 0x18, feed-hold 0x21, cycle-start 0x7e, and feed/rapid/power override bytes 0x90-0x94 / 0x95-0x97 / 0x99-0x9d computed by diffing target vs current (`manageOverrides`, 751-768).
4. **RX line assembler** — string buffer split on `\n`, with a 4096-byte guard that truncates to the last 1024 bytes (`onData`, 450-462). Must survive chunk boundaries across a 4096-byte native read buffer.
5. **Streaming mode state machines** — `StreamingMode.Buffered` vs `RepeatOnError` with a `retryQueue` and max 3 repeats (`peekNext` 324-332, `manageCommandResponse` 370-406); `EEPROMSettings` writes are serialized by blocking further sends while one is pending (line 325).
6. **Handshake timing** — on open: optional `0x18` + 400 ms, then `\r\n`, `$I` + 350 ms, `$$` + 250 ms, `$#` + 150 ms, then `?` (`sendHandshake`, 231-241). Failure to get a status report within 10 s raises `connectTimeout` (`txTick`, 284-288).
7. **`GcodePreview.vue` Canvas animation** (462 lines) — the single hardest imperative surface:
   - Resizes the canvas to `devicePixelRatio` capped at 2, and skips bitmap reallocation when `bw/bh/dpr` are unchanged (`draw()`, 162-175).
   - Caches a colour palette read from CSS custom properties via `getComputedStyle(document.documentElement)` (`cssVar` 106-109, `getPalette` 111-120) and invalidates it from a `MutationObserver` watching `document.documentElement` attributes `['data-theme','class']` (387-397).
   - Builds a segment index (`rapids[]`, `cuts[]`, `bounds`) in a `computed` so drawing never re-walks the move array (57-89).
   - Progress-driven partial stroke: fraction `total*rendered/100` → draw `full` segments solid, the tail at `globalAlpha 0.18`, then the fractional last segment up to an interpolated point, then a glowing laser head (`drawHead` 139-153) painted last (`draw()` 224-299).
   - Exponential-approach tween: `shown += diff * (1 - Math.exp(-dt/120))`, self-scheduling `requestAnimationFrame`, converges when `|diff| < 0.05`; `dt` clamped to ≤100 ms (324-351).
   - Two independent rAF handles (`raf` for redraw, `tweenRaf` for tween) with mutual cancellation so a frame is never drawn twice (311-317, 330-333).
   - `ResizeObserver` on the wrapper resets the cached bitmap size and redraws (379-386).
   - `matchMedia('(prefers-reduced-motion: reduce)')` with a `change` listener that snaps to the target progress (`reducedMotion` 130-136, `onMotionChange` 373-375); `animated:false` and `instant` also snap.
   - Watchers: `[preview, bbox, height]` → `requestDraw()`; `[progress, animated]` → `syncProgress()` (416-424).
   - Cleanup in `onBeforeUnmount` cancels both rAFs and disconnects all three observers (404-414).
8. **`GlassTabBar` pointer-scrub gesture** (`GlassTabBar.vue:38-173`) — 360 ms hold timer, 10 px move tolerance to cancel as a scroll, `window`-level `pointermove/pointerup/pointercancel` listeners (deliberately *not* `setPointerCapture`, documented at lines 12-15), hit-testing by `getBoundingClientRect()` + `el.dataset.tab` (`nameAtX` 56-72), `navigator.vibrate?.(8)` haptic (95-102), and a 320 ms click-swallow window with `@click.capture` prevention (122-136, 162-167).
9. **`GlassInput` focus/draft reconciliation** — native `FocusEvent` is re-emitted verbatim because *every* numeric field does `(ev.target as HTMLInputElement).value` in its handler (documented `GlassInput.vue:5-11`; contract restated `docs/GLASS-CONVERSION.md:114-115`). A `focused` guard stops external writes from clobbering in-progress typing, and `onBlur` reverts `draft` to `modelValue` so rejected parses snap back (51-76).
10. **`GlassSelect` provide/inject option registry + `<Teleport to="body">` bottom sheet** (`GlassSelect.vue:38-46,73-95`) — options are registered by mounted `GlassOption` children rather than passed as data.
11. **`pickFile`** hidden-`<input type=file>` injection into `document.body` with `FileReader` dataURL/text branching and `input.oncancel` cleanup (`src/ui/utils.ts:12-65`); `loadImage` builds an `Image` from a dataURL (68-75). Both are used by 5 views.
12. **`LogList` autoscroll** — `watch(() => state.log.length)` → `await nextTick()` → `el.scrollTop = el.scrollHeight` (`LogList.vue:36-44`).
13. **Keep-alive start/stop calls embedded in the run lifecycle** — `runFile()` calls `beginKeepAlive(name, 0)`; `abortFile()`, `softReset()`, `disconnected` and `programEnd` handlers all call `endKeepAlive()`; `progress` handler calls the throttled `refreshKeepAlive` (`store.ts:102-124, 162, 176, 183, 332, 444, 454`). All three wrappers swallow errors so a missing foreground service cannot break engraving.
14. **`laserTest` uses `window.setTimeout`** to fire the laser-off command after the requested duration, clamped to 50–10000 ms, and refuses when disconnected or already running (`store.ts:383-401`).
15. **`toast()`** is a plain imperative function (`src/ui/utils.ts:96-98` → `showToast`), called ~64 times across views; `GlassToaster` is the only renderer and must stay mounted at App root (`App.vue:100`).
16. **`GcodePreview` consumes `ImageData` previews** produced by core and converted with `toDataURL` (`src/core/raster/ImageTransform.ts:168-175`) for `<img>` display — a canvas→dataURL round trip in the convert views.

### 4.4 Persistence keys (localStorage — 5 keys total)

| Key | Owner | Shape |
|---|---|---|
| `lasergrbl.settings` | `src/core/grbl/GrblConfig.ts:6`, class `SettingsStore` (42-81) | flat `Record<string, unknown>` merged over `DEFAULT_SETTINGS` (`GrblConfig.ts:9-40`, 29 keys: `Firmware Type`, `Support Hardware PWM`, `Reset Grbl On Connect`, `Unidirectional Engraving`, `Disable G0 fast skip`, `Enable Continuous Jog`, `Serial Monitor`, `Show Program Comments`, `Show Program Commands`, `Threading Mode`, `Mark Speed`, `Border Speed`, `Min Power`, `Max Power`, `Laser On Command`, `Laser Off Command`, `Travel X`, `Travel Y`, `Test Laser Power`, `Test Laser Duration`, `Jog Speed`, `Jog Step`, `Last Port`, `Last Baud`, `Header`, `Footer`, `Auto Home On Connect`, `Language`). Written on every `set()` (73-76). Singleton `export const AppSettings = new SettingsStore()` (83). |
| `lasergrbl.files` | `src/ui/storage.ts:19` | `SavedFile[]` = `{id, name, lines: string[], createdAt, meta?{kind,widthMm,heightMm,note}}`; sorted desc by `createdAt`, capped at the newest 50 (line 52); quota-overflow silently ignored (36-38). |
| `lasergrbl.devices` | `src/core/grbl/DeviceProfile.ts:45` | `DeviceProfile[]` (interface at line 15). |
| `lasergrbl.setupDone` | `src/core/grbl/DeviceProfile.ts:47` | `'1'`/`'0'`. |
| `lasergrbl.knownDevices` | `src/core/grbl/DeviceProfile.ts:49` | `number[]` of USB device ids already onboarded. |

**Note:** the theme is *not* persisted. `src/ui/theme.ts` only writes `document.documentElement.dataset.theme`; `GrblConfig.theme` (line 45) is an in-memory field defaulting to `'dark'` and `setTheme` (`store.ts:495-498`) never calls `AppSettings.set`. A reload resets the theme to dark.

### 4.5 Theming mechanism

`state.theme` → `App.vue` watcher → `applyTheme()` → `html[data-theme]` → CSS variable overrides in `glass.css:82-104`. Two consumers add imperative coupling: `GcodePreview`'s `MutationObserver` on `data-theme`/`class` (line 393-396) and the `<meta name="theme-color">` rewrite. There is no `prefers-color-scheme` query anywhere; dark is the default.

## 5. Native Android bridge surface

Three Capacitor plugins, registered **before** `super.onCreate` in `MainActivity.java:12-15`.

### 5.1 Plugin registry

| Plugin name (`@CapacitorPlugin`) | Java class | JS registration |
|---|---|---|
| `UsbSerial` | `android/app/src/main/java/com/lasergrbl/android/UsbSerialPlugin.java` (476 lines) | `registerPlugin('UsbSerial')` — `src/core/serial/SerialTransport.ts:41` |
| `BluetoothSerial` | `BluetoothSerialPlugin.java` (456 lines) | `registerPlugin('BluetoothSerial')` — `SerialTransport.ts:42` |
| `KeepAlive` | `KeepAlivePlugin.java` (166 lines) | `registerPlugin('KeepAlive')` — `src/core/native/KeepAlive.ts:39` |

### 5.2 JS-callable methods

| Plugin | Method | Args (JSObject keys) | Resolve payload | Reject messages |
|---|---|---|---|---|
| `UsbSerial` | `list()` | — | `{devices: [{deviceId:number, name:string, vendor:string, product:string, vendorId:number, productId:number}]}` (`UsbSerialPlugin.java:104-139`) | `无法获取 UsbManager` |
| `UsbSerial` | `open({deviceId, baudRate?})` | `deviceId:number` (required), `baudRate:number` (default 115200) | `void` | `缺少 deviceId 参数`, `无法获取 UsbManager`, `未找到可用的串口驱动 (deviceId=N)`, `未获得 USB 设备权限`, `该 USB 设备没有可用串口`, `打开 USB 设备连接失败`, `初始化串口失败: …` (`145-232`) |
| `UsbSerial` | `close()` | — | `void` | — |
| `UsbSerial` | `write({data, encoding?})` | `data:string`, `encoding:'utf8'\|'base64'` (default `utf8`) | `void` | `缺少 data 参数`, `串口未打开`, `base64 解码失败: …`, `写入串口失败: …` (`249-289`) |
| `BluetoothSerial` | `list()` | — | `{devices:[{address:string, name:string}]}` (`244-283`); permission-gated | `未获得蓝牙权限`, `设备不支持蓝牙`, `蓝牙未开启，请先开启蓝牙`, `缺少蓝牙权限: …` |
| `BluetoothSerial` | `open({address, baudRate})` | `address:string` (required), `baudRate` (accepted, **ignored** — SPP has no baud) | `void`, resolved from a background thread (`285-347`) | `缺少 address 参数`, `设备不支持蓝牙`, `蓝牙未开启，请先开启蓝牙`, `无效的蓝牙地址: …`, `缺少蓝牙权限: …`, `连接蓝牙设备失败: …` |
| `BluetoothSerial` | `close()` | — | `void` | — |
| `BluetoothSerial` | `write({data, encoding?})` | same as USB | `void` | `缺少 data 参数`, `蓝牙串口未打开`, `base64 解码失败: …`, `写入蓝牙失败: …` (`130-172`) |
| `BluetoothSerial` | `openSettings()` | — | `void`; fires `Settings.ACTION_BLUETOOTH_SETTINGS` with `FLAG_ACTIVITY_NEW_TASK` (`178-187`) | `无法打开蓝牙设置: …` |
| `KeepAlive` | `start({title?, text?, progress?})` | `title:string` (default `iGRBL 正在雕刻`), `text:string`, `progress:number` (0-100) | `{running:true, foreground:true, notification:'shown'\|'denied', reason?:'未获得通知权限，前台服务已启动但通知不可见'}` (`KeepAlivePlugin.java:52-61, 118-140`) | — (permission flow, never rejects) |
| `KeepAlive` | `update({text?, progress?})` | `text:string`, `progress:number` | `{running:boolean, notification:'shown'\|'denied'}` (`69-81`) | — |
| `KeepAlive` | `stop()` | — | `{running:false, notification:'shown'}` (`86-94`) | — |

### 5.3 Serial data-transfer contract

There is **no bulk/chunked upload protocol**. Every G-code line is a separate `write` call driven by the 4 ms TX loop in `GrblCore.ts`; backpressure is the JS-side byte accounting (`usedBuffer` vs `autoBufferSize`), not a native ack.

- Outbound: UTF-8 text (all G-code) or base64 decoded to bytes (only the 5 single-byte real-time commands via `writeBytes`, `SerialTransport.ts:97-99,150-152`).
- Inbound: **always UTF-8 text**, decoded per native read chunk (`new String(buffer, 0, len, UTF_8)`), pushed as `{data: string}`. Read buffer 4096 bytes (`UsbSerialPlugin.java:43`, `BluetoothSerialPlugin.java:55`).
- USB read blocks with `READ_TIMEOUT_MS = 0` (infinite) on a daemon thread `UsbSerial-Read` (`377-398`). Writes are serialized by a `writeLock` with a 2000 ms timeout (`WRITE_TIMEOUT_MS`, line 49). Port parameters fixed at 8N1 (`192-196`), DTR/RTS asserted best-effort (`198-203`).
- Bluetooth connects on a background thread `BluetoothSerial-Connect` using SPP UUID `00001101-0000-1000-8000-00805F9B34FB` (`52`, `325-326`), after `adapter.cancelDiscovery()`.

### 5.4 Native → JS events

| Event | Plugin | Payload | Fired when |
|---|---|---|---|
| `data` | `UsbSerial` | `{data: string}` (UTF-8 chunk) | every successful port read (`UsbSerialPlugin.java:388`) |
| `closed` | `UsbSerial` | `{}` (empty JSObject) | device detached (`UsbManager.ACTION_USB_DEVICE_DETACHED`, `81-99`), read `IOException` (`391-397`), or `handleOnDestroy`-driven cleanup — emitted at most once per connection via the `closedNotified` flag (`441-449`) |
| `data` | `BluetoothSerial` | `{data: string}` | every successful socket read (`BluetoothSerialPlugin.java:365`) |
| `closed` | `BluetoothSerial` | `{}` | `ACTION_ACL_DISCONNECTED` / `ACTION_ACL_DISCONNECT_REQUESTED` (`81-97`), read loop exit (`370-375`) — once per connection (`414-422`) |

JS listeners: `SerialTransport.ts:101-107` (USB `data`/`closed`) and `154-160` (BT `data`/`closed`), handles stored in arrays and removed in `close()`. `GrblCore` consumes them via `transport.onData` / `transport.onClose` (`GrblCore.ts:214-215`). `KeepAlive` has **no** events.

### 5.5 Foreground service / notification / keep-alive

- Service: `com.lasergrbl.android.EngraveService` (367 lines), declared `android:foregroundServiceType="dataSync"`, `android:exported="false"` (`AndroidManifest.xml:52-55`). It performs **no serial I/O** — only keep-alive + notification (`EngraveService.java:22-23`).
- Intents: `ACTION_START` / `ACTION_UPDATE` / `ACTION_STOP` = `com.lasergrbl.android.action.ENGRAVE_START|UPDATE|STOP` (49-55); extras `title`, `text`, `progress` (58-64). Driven by the static helpers `start(Context,String,String,int)` / `update(Context,String,Integer)` / `stop(Context)` / `isRunning()` (91-132).
- Notification: channel id `igrbl_engrave`, name `雕刻进度`, `IMPORTANCE_LOW`, no badge/lights/vibration/sound, description `雕刻任务运行期间的常驻进度通知` (`338-357`). Fixed id `1001` (43). Built with `setOngoing(true)`, `setOnlyAlertOnce(true)`, `setShowWhen(false)`, category `progress`, priority LOW, visibility PUBLIC, small icon `R.drawable.ic_stat_engrave`; determinate `setProgress(100, n, false)` when progress ≥ 0, otherwise indeterminate (`304-323`).
- **The notification has no action buttons.** Its only `PendingIntent` is the content intent → `MainActivity` with `FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP`, requestCode 0, flags `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE` (`326-335`).
- Foreground declaration: API ≥ 34 uses `startForeground(id, n, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`, otherwise `startForeground(id, n)` (`237-242`). Returns `START_NOT_STICKY` (`201`) — the service is never resurrected by the system.
- Lifecycle guards: `startForegroundInternal` is idempotent (re-entry only calls `notify`), `onStartCommand` with a `null` intent re-declares foreground to avoid the 5-second ANR (`196-198`), `onDestroy` cancels the notification (`204-213`), `stopForegroundInternal` uses `ServiceCompat.STOP_FOREGROUND_REMOVE` + `cancel` + `stopSelf` (`279-288`). All native failures are swallowed via `try/catch` + `printStackTrace` — the engraving flow never crashes because of the service. `onBind` returns `null` (`215-219`).
- **No wake lock, no WifiLock, no battery-optimisation exemption request anywhere.** Keep-alive relies solely on the foreground service. Survives backgrounding only while the service is foreground; it is torn down by `stop()`, `programEnd`, `disconnected`, `abortFile`, `softReset`, connection failure, and `KeepAlivePlugin.handleOnDestroy` (`KeepAlivePlugin.java:96-101`).
- JS throttle: `startKeepAlive` / `updateKeepAliveProgress` / `stopKeepAlive` (`src/core/native/KeepAlive.ts`). Notification text is `<truncated name> · <pct>%` (24-char name cap, `truncateName` 51-55; `formatText` 72-75); updates only fire on an integer percent change (`PROGRESS_STEP = 1`, 15, 118-129). Every call is a no-op outside Android (`isAndroidNative`, 42-48) and `stopKeepAlive` always calls the plugin once to clear zombie notifications from a previous crash (144-149).

### 5.6 Permissions & declarations

Manifest (`AndroidManifest.xml`):
- `<uses-feature android:name="android.hardware.usb.host" android:required="true"/>` (5-7).
- Permissions: `INTERNET` (60); `BLUETOOTH` + `BLUETOOTH_ADMIN` with `maxSdkVersion="30"` (65-66); `BLUETOOTH_CONNECT` (67); `FOREGROUND_SERVICE` (73); `FOREGROUND_SERVICE_DATA_SYNC` (74); `POST_NOTIFICATIONS` (75). No `<queries>`.
- `MainActivity`: `launchMode="singleTask"`, `exported="true"`, `configChanges` for orientation/keyboard/screenSize/locale/uiMode (17-23); a second intent-filter for `android.hardware.usb.action.USB_DEVICE_ATTACHED` plus matching `<meta-data>` → `@xml/usb_device_filter` (31-37). Effect: plugging a matching USB device launches/foregrounds the app.
- `usb_device_filter.xml`: `vendor-id` `0x0403` FTDI, `0x1a86` WCH CH340/CH341/CH9102, `0x10c4` CP210x, `0x067b` PL2303, `0x2341` Arduino; CDC/ACM `class=2 subclass=2 protocol=1`; plus a bare `<usb-device/>` catch-all.
- `FileProvider` `androidx.core.content.FileProvider` authority `${applicationId}.fileprovider`, non-exported, grant-URI, paths from `@xml/file_paths` (`external-path "."` and `cache-path "."`).

Runtime permission requests in Java:
- `POST_NOTIFICATIONS` (KeepAlive, alias `notifications`) — requested on the first `start()` when API ≥ 33 and not granted (`KeepAlivePlugin.java:54-59`); the callback **always starts the service** and only reports whether the notification is visible (`109-113`).
- `BLUETOOTH_CONNECT` (BluetoothSerial, alias `bluetoothConnect`) — requested for `list`/`open` when API ≥ 31 (`BluetoothSerialPlugin.java:201-227`); denial rejects the pending call with `未获得蓝牙权限`. API < 31 skips the check.
- USB device access — not a runtime permission: `UsbManager.requestPermission` with a `PendingIntent` broadcast bound to action `com.lasergrbl.android.USB_PERMISSION` and `setPackage(ctx.getPackageName())`, awaited on a `CountDownLatch` for up to 20 000 ms (`UsbSerialPlugin.java:40, 317-374`); receivers registered with `RECEIVER_NOT_EXPORTED` on API ≥ 33.
- `VIBRATE` is **not** declared, yet `GlassTabBar.vue:98` calls `navigator.vibrate?.(8)` — silently a no-op (the code comments this as acceptable).

`capacitor.config.ts` (13 lines): `appId 'com.lasergrbl.android'`, `appName 'iGRBL'`, `webDir 'dist'`, `android.allowMixedContent: true`, `android.webContentsDebuggingEnabled: true`. Native dep: `com.github.mik3y:usb-serial-for-android:3.7.0` (`android/app/build.gradle:49`); Java 5 files / 1429 lines total.

### 5.7 Native-UI rewrite impact — per item

| Item | Kotlin/Compose rewrite must… |
|---|---|
| `UsbSerial.list/open/close/write` | Reimplement natively: `UsbManager` + `usb-serial-for-android` (or a Kotlin serial lib), 8N1 params, DTR/RTS, infinite-blocking read on a worker thread, 2000 ms write timeout, `writeLock` serialization. No bridge needed. |
| USB permission flow | Reimplement the `PendingIntent` broadcast + 20 s latch (or switch to `UsbManager.requestPermission` with an Activity result). |
| USB detach detection | Reimplement the `ACTION_USB_DEVICE_DETACHED` receiver and the "notify closed exactly once" latch. |
| `usb_device_filter.xml` + `USB_DEVICE_ATTACHED` intent filter | Keep as-is (pure manifest resources) if the same attach-to-launch behaviour is wanted. |
| `BluetoothSerial.list/open/close/write/openSettings` | Reimplement native SPP: bonded-device enumeration, `createRfcommSocketToServiceRecord(00001101-…)`, connect off the main thread, cancel-discovery, `BLUETOOTH_CONNECT` runtime gate. |
| `ACTION_ACL_DISCONNECTED` receiver | Reimplement. |
| `data` / `closed` events | Replace with a native callback/`Flow`/`StateFlow`; the JS listener bookkeeping (`PluginListenerHandle[]`) disappears. |
| Chunked `{data: string}` framing | Reproduce UTF-8 chunk delivery with the same line-buffering tolerance (`GrblCore.onData`'s 4096-byte guard) or move the line assembler native. |
| `blur`/`setAttribute` custom-element contract | Nothing to bridge — the liquid-glass effect is a WebView-only rendering path; a native UI can only approximate it (Compose blur/`RenderEffect`, `Modifier.graphicsLayer`, or a native port of the SVG displacement map). |
| `KeepAlive.start/update/stop` + result shape | Reimplement as an in-process service controller returning the same `{running, foreground, notification, reason?}` status enum semantics (`shown`/`denied`/`unsupported`), or keep a thin bridge. |
| `EngraveService` foreground service + channel + fixed-id notification | Reimplement: channel `igrbl_engrave`, IMPORTANCE_LOW, id 1001, ongoing/only-alert-once/PROGRESS category, content intent to MainActivity with SINGLE_TOP+CLEAR_TOP, `START_NOT_STICKY`, the API-34 `dataSync` type, and the null-intent re-foreground guard. Keep manifest entries (`foregroundServiceType`, `FOREGROUND_SERVICE_DATA_SYNC`). |
| `POST_NOTIFICATIONS` gate | Reimplement with the same semantics: start the service regardless, report visibility. |
| Progress notification throttle | Reimplement the 1-%-step, name-truncated-to-24-chars, `<name> · <pct>%` text format if notification parity matters. |

## 6. Constraints from existing docs

- **Language and copy rules.** The app is Chinese-first (`README.md:5` "应用以中文为主语言"; `index.html:2` `lang="zh-CN"`). `docs/GLASS-CONVERSION.md:14-21` states five 铁律: only `<template>`/`<style>` may change — `<script>` logic, variable names, handlers, computed properties and imports stay byte-identical; **all Chinese copy must be preserved character-for-character**; all `lg-*` utility class names must be preserved; no new dependencies and no new components unless the spec names them.
- **Component-mapping contract (superseded by v3 scope, but it defines the current API).** `docs/GLASS-CONVERSION.md:26-40` is the authoritative Varlet→Glass attribute/event equivalence table (`var-button`→`GlassButton`, `var-input`→`GlassInput`, …, `<section class="lg-section">`→`<GlassSurface class="lg-section">`); lines 114-115 pin the `@blur="onNum('x', $event)"` native-`FocusEvent` contract, and 178-181 forbid touching `src/ui/glass/**`, `src/styles/glass.css`, `src/App.vue`, `src/main.ts`, the router, store or core.
- **Licence: GPL-3.0, inherited.** `README.md:88-90` — ported from LaserGRBL, and this project is released under GPL-3.0; `src/ui/views/AboutView.vue:110` surfaces "遵循 LaserGRBL 的开源许可协议发布（GPL-3.0）". Obligations attach to any derived redistribution.
- **Build environment: a full JDK is mandatory.** `README.md:44-49` — AGP needs `jlink`; a JRE `JAVA_HOME` (e.g. IntelliJ's bundled JBR) fails `assembleDebug` with `JdkImageTransform` / `jlink executable ... does not exist`; use a real JDK such as `C:\Program Files\Java\jdk-17.0.18`. The same block records that **the repo path contains non-ASCII characters** and that `android.overridePathCheck=true` is set in `android/gradle.properties` to bypass AGP's non-ASCII path check (the project uses no NDK).
- **Rendering-target dependency.** `docs/LIQUID-GLASS-RESEARCH.md:24-33` establishes that `backdrop-filter: url(#filter)` renders **only in Chromium**, which is why the effect works in the Capacitor Android WebView — and that an iOS Capacitor build would degrade to plain blur. Lines 142-148 add unmeasured perf caveats: SVG `feDisplacementMap` inside `backdrop-filter` is GPU-heavy on mid-range Android, and hardware acceleration must be verified on a real low-end device, not an emulator. Lines 160-167 record two known gaps in the shipped integration: `renderer` is unset (SVG path, not WebGL, on Android) and pointer-interaction props (`followPointer`, `clickRipple`, `rippleIntensity`) exist only in the React entry, so Vue must reimplement pointer elasticity.

## 7. Porting effort signal

### Line counts per area

| Area | Files | Lines | Notes |
|---|---|---|---|
| `src/core` | 25 | **10 393** | Pure logic; largest block by far |
| `src/core/grbl/csvData.ts` | 1 | **2 960** | 28 % of `src/core` on its own — static GRBL setting/alarm/error tables |
| `src/ui/views` | 13 | **4 115** | 107–685 lines each; mean ≈ 317 |
| `src/ui/glass` | 24 | **1 093** | 19 components + `index/register/config/toast/components.d.ts` |
| `src/ui/components` | 4 | **1 077** | `SetupWizard` 505, `GcodePreview` 462, `LogList` 86, `AppIcon` 40 |
| `src/styles` | 2 | **1 757** | `glass.css` 1365 (live) + `global.css` 402 (dead) |
| `src/ui` (all, incl. top-level) | 45 | **6 999** | + `store.ts` 504, `utils.ts` 98, `icons.ts` 47, `storage.ts` 58, `theme.ts` 12, `version.ts` 11 |
| `src/` (incl. `App.vue` 102, `router.ts` 88, `main.ts` 15) | 48 | ≈ **8 600** | |
| `android` Java | 5 | **1 429** | `UsbSerialPlugin` 476, `BluetoothSerialPlugin` 456, `EngraveService` 367, `KeepAlivePlugin` 166, `MainActivity` 17 |
| docs | 2 `.md` | 314 | `GLASS-CONVERSION.md` 181 + `LIQUID-GLASS-RESEARCH.md` 179 |

Build output for scale: main bundle `dist/assets/index-*.js` = 323 265 B; `TextConvertView` chunk alone = 46 944 B; CSS = 26 257 B.

### 12 most algorithmically complex `src/core` modules for a Kotlin port (hardest first)

1. **`src/core/vector/Potrace.ts` (1 201 lines)** — a faithful port of Selinger/Nagl's `CsPotrace` (header `Potrace.ts:4-14`). Full pipeline: binarize → `findNext`/`findPath`/`xorPath` bitmap path decomposition with the `majority()` minority-turn strategy (332-347) → `calcSums` prefix sums (459-476) → `penalty3` (478-521) → `calcLon` optimal straight segments (523-624) → `bestPolygon` (626-697) → `adjustVertices` with 3×3 quadratic-form minimisation and determinant/elimination branches (698-833) → `smooth` (844-887) → `optiPenalty`/`optiCurve` with `tangent()` solving a quadratic on Bézier tangency and `COS179` guard (889-1112) → adaptive cubic Bézier flattening via `isFlat`/`triArea`/recursive subdivision (1113-1174). Numeric-sensitive throughout (`tdiv`, `mod`, `cyclic`, `quadform`).
2. **`src/core/grbl/csvData.ts` (2 960 lines)** — `SETTING_CODES` / `ALARM_CODES` / `ERROR_CODES` keyed by firmware family (`v1.1`, `v0.9`, `ortur.*`, `longer.nanoduo`, …), each entry `string[]` indexed by field. `GrblCore.lookupCode` (849-864) selects the group from `GrblVersionInfo` (Ortur HAL, Ortur FW ≥170/≥150, Longer NanoDuo, else `v<major>.<minor>`) with `v1.1`/`standard` fallbacks. Port = data + the same precedence rules.
3. **`src/core/grbl/GrblCore.ts` (879 lines)** — the streaming engine (details in §4.3 items 1-6): 4 ms TX tick, byte-budget buffer accounting against a runtime-discovered buffer size, three streaming modes with retry queue, real-time byte override laddering, dual-version (v0.9 vs v1.1) status-report parsers (`manageRealtimeStatus` 590-619), version/banner/`[VER:]`/`[OPT:]` parsing, hang detection, and the 10 s connect timeout.
4. **`src/core/vector/SvgToGcode.ts` (791 lines)** — SVG path-data interpreter. `parseNumbers` incl. scientific notation (110-119), `parseLength` with px/mm/cm/in/pt/pc/em (124-150), `parseViewBox` (155-161), `transformFromArgs` + `parseTransform` implementing the full SVG transform list with correct matrix order (162-220), all path commands incl. arcs, Bézier flattening with `MAX_SUBDIV = 20` (84, 221-…) — deliberately hand-parsed because Android WebView cannot `getCTM()` on detached elements (comment 205-206).
5. **`src/core/vector/Centerline.ts` (433 lines)** — Zhang-Suen thinning to a 1-px skeleton (`thin` 85-139) with padding and an iteration cap; degree map (140-164); branch extraction with packed pair-keys `a*size+b` (165-281); junction clustering via flood fill (282-321); spur pruning to `minBranchPx` (322-364); polyline emission (386-…). Skeleton→stroke ordering is the tricky part.
6. **`src/core/raster/RasterConverter.ts` (392 lines)** — Line2Line and dithering raster engine ported from `GrblFile.cs` (`LoadImageL2L`/`ImageLine2Line`/`GetSegments`/`OptimizeLine2Line`). `getColor` with alpha compositing and per-channel weights (161-170), `extractSegment` run-length scanning in 3 directions incl. diagonal (171-247), `segToGCodeNumber` / `segFast` / `isSeparator` for minimal-instruction output (112-160), and `optimizeLine2Line` which re-parses emitted G-code text to merge lines (249-300).
7. **`src/core/grbl/GrblCommand.ts` (385 lines)** — command model + element parser: `Element`, `formatDecimal`, `CommandStatus`, per-command `serialData` builder with `buildHelper`/`deleteHelper`, `setResult` response classification, `GrblMessage` with code decoding, and `installDecoders(settingsLookup, alarmsLookup)` dependency injection (377-385).
8. **`src/core/vector/StrokeAnalysis.ts` (264 lines)** — the "smart" line-art-vs-solid heuristic (header 1-25): ink area + skeleton length (4-neighbour = 1, diagonal = √2, right-down quadrant only), `avgStrokeWidth ≈ inkArea / skeletonLength`, normalised by `min(w,h)`, with the iteration-budget insight that coarse shapes exhaust the thinning cap and must fall back to outline (documented 11-21); produces a user-facing `summary` string.
9. **`src/core/vector/ImageVector.ts` (425 lines)** — orchestration + text rasterisation: `buildPreview` alpha-composited threshold preview (124-…), `convertImageVector` dispatching Outline/Centerline then `polylinesToGcode` (127-184), `renderTextToImage` using canvas `measureText`/`fillText` for arbitrary Unicode incl. CJK (297-361), `convertTextVector` (395-434), `hasNonAscii` (435).
10. **`src/core/raster/dithering.ts` (218 lines)** — 8 error-diffusion kernels as `{matrix, divisor, useShifting}` (`FloydSteinberg`, `Atkinson`, `Burks`, `Jarvis`, `SierraLight`, `Stucki`, plus `Random`) at `dithering.ts:49-119`; serpentine scan with per-channel error propagation and a `>> divisor` fast path when the divisor is a power of two (177-201); separate `randomDither` (203-…).
11. **`src/core/vector/SvgVector.ts` (326 lines)** — SVG rasterisation bridge: `readSvgContentSize` via `DOMParser` + `parseViewBox` (110-138), `normalizeSvgForRaster` rewriting width/height (139-150), `loadSvgImage` (151-159), canvas draw + `getImageData` with `willReadFrequently` (196-207), then Outline-vs-Centerline/Auto dispatch (219-326). This is the one core module that **cannot** be ported without a native SVG renderer (Android's `PdfRenderer`/`androidsvg` or a WebView).
12. **`src/core/gcode/GrblFile.ts` (171 lines)** — G-code parser + analytics: `stripComments` handling `;` and `(…)` (40-52), `parseElements` letter→number map (53-64), `analyze()` computing total lines, motion-command count, path length (`Math.hypot` accumulation), bounding box and an estimated duration from per-move feed rates (78-171).

Runners-up worth budgeting: `src/core/vector/Paths.ts` (235 — nearest-neighbour path ordering `optimizeOrder` at 27-72, iterative Douglas-Peucker `simplifyPath` at 82-125 to avoid recursion depth, G-code emission 174-235), `src/core/grbl/DeviceProfile.ts` (439 — profile CRUD, `fitSizeToTravel` Fit/Shrink modes 335-380, and `checkGcodeWithinTravel` which re-parses emitted G-code text with a regex word extractor 382-439), `src/core/grbl/types.ts` (213 — `Firmware`, `MacStatus` 14 states, `JogDirection`, `StreamingMode`, `DetectedIssue`, `ThreadingMode` with 5 presets and a `statusQuery` interval, `GPoint`, `GrblVersionInfo` with Ortur/Longer vendor parsing), `src/core/text/Hershey.ts` (174 — glyph stream transformed by regex `[XY]-?\d+` + `placeGlyph` scaling, bold = 4 extra offset passes, multi-line layout) over the 9-line `hersheyData.ts` glyph tables, and `src/core/raster/ImageTransform.ts` (168 — canvas resize, `Formula` enum grayscale incl. luminance/channel-weight variants, whitenize/threshold/flipVertical/testGrayScale/toDataURL).
