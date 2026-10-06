# Apple "Liquid Glass" for Vue 3 + Capacitor 6 Android — ecosystem research

Researched: **2026-10-06**. All npm data fetched live from `registry.npmjs.org`, `api.npmjs.org/downloads`,
and `unpkg.com` file listings. GitHub data from `api.github.com`. Nothing was installed.

---

## 0. The one technical fact that decides everything

Every real "liquid glass" web library uses the same pipeline:

```
canvas SDF (rounded-rect) → R/G channels biased at 128 → <feImage> → <feDisplacementMap
   xChannelSelector="R" yChannelSelector="G"> → applied as backdrop-filter: url(#filter)
```

Verified in source of `@mizuui/liquid-glass-core`, `liquid-glass-react`, `liquid-glass-js`, `liquid-web`,
`@samasante/liquid-glass`, `@avenra/liquid-glass`.

**No package needs an external displacement-map PNG.** The string `.png` appears **zero** times in every
bundle inspected; the map is generated at runtime via `canvas.getContext('2d')` + `createImageData` +
`putImageData`, then inlined as a `data:image/...` URI. Any claim that you must buy or ship a PNG is false.

**`backdrop-filter: url(#filter)` renders only in Chromium.** Confirmed by three independent primary sources
(the packages' own docs):

- `@mizuui/liquid-glass-core` README: *"`backdrop-filter: url(#filter)` currently works in Chromium. Safari and Firefox fall back to `fallbackBlur` plus the specular rim."*
- `liquid-glass-react` README: *"Safari and Firefox only partially support the effect (displacement will not be visible)"*
- `deepika-builds/liquid-glass` README: *"Real refraction in Chromium browsers; automatic frosted-blur fallback in Safari and Firefox."*

**Consequence for this app: Android WebView is Chromium, so true refraction works on our target** — this is
the rare case where Android is *easier* than iOS/desktop-Safari. If an iOS Capacitor build is ever added,
it degrades to plain blur unless a WebGL/capture path is used.

---

## 1. Displacement-refraction libraries (verified)

| Package | Ver | Last publish | dl/wk | License | Framework req. | How the effect is made | Ext. PNG | Unpacked |
|---|---|---|---|---|---|---|---|---|
| **`simple-liquid-glass`** | 5.3.0 | 2026-09-09 | 1,195 | MIT | React 16.8–19 **or** custom element (**no React**) | WebGL (opt-in) **+** SVG `feImage`/`feDisplacementMap` + explicit backdrop capture | No | 4.45 MB total; `web-component` bundle 73 KB raw / ~22 KB br |
| `liquid-glass-react` | 1.1.1 | 2025-06-11 | **45,152** | MIT | **React-only** (peer) | SVG `feImage`+`feDisplacementMap`, canvas map, `backdrop-filter:url()` | No | 180 KB (ESM 63.5 KB) |
| **`@mizuui/liquid-glass-vue`** | **0.1.4** | **2026-09-30** | 801 | MIT | **Vue 3** (peer) | SVG `feImage`+`feDisplacementMap`, canvas SDF, `backdrop-filter:url()` | No | **20 KB** |
| **`@mizuui/liquid-glass-core`** | **0.1.4** | **2026-09-30** | 823 | MIT | **none** (plain ESM, 0 deps) | same | No | **16 KB** |
| `@samasante/liquid-glass` | 0.1.1 | 2026-06-23 | 9,427 | MIT | **React-only** (peer) | SVG displacement + backdrop capture, canvas maps | No | 418 KB |
| `@avenra/liquid-glass` | 1.2.0 | 2026-06-14 | 5,337 | MIT | none (ESM/CJS/UMD) | `feDisplacementMap` + Snell's-law maps + canvas | No | 1.03 MB |
| `react-liquid-glass-svg` | 1.0.5 | 2026-06-19 | 2,119 | MIT | **React-only** | pure SVG filters + backdrop-blur, no canvas/WebGL | No | **3.0 MB** |
| `@wxperia/liquid-glass-vue` | 1.0.9 | 2025-06-18 | 413 | MIT in pkg — **repo has NO LICENSE file** | Vue 3 (hard dep) | byte-similar clone of `liquid-glass-react` | No | 176 KB |
| `@nkzw/liquid-glass` | 1.3.0 | 2025-06-25 | 1,177 | MIT | **React-only** | same family | No | 71 KB |
| `@liquid-dom/core` | 0.1.1 | 2026-05-29 | 1,657 | **none declared** | none | **WebGPU renderer** (not SVG) | n/a | 1.24 MB |
| `liquid-glass-js` | 0.1.0 | 2026-06-10 | 196 | MIT | vanilla + web component | `feDisplacementMap`×5, `feImage`×8 | No | 293 KB |
| `liquid-web` | 1.1.1 | 2025-06-14 | — | MIT | Vue/React/vanilla | `feImage`×10 maps | No | 235 KB |
| `glasscn-ui` | 0.7.1 | 2024-10-19 | 59 | MIT | React + 30 Radix deps | **glassmorphism only — no refraction**, repo **archived** | n/a | 683 KB |
| `glass-ui-vue` | 1.3.18 | 2025-08-09 | 5 | MIT | Vue 3 + vue-router + mitt | glassmorphism, no refraction | n/a | 101 KB |
| `vue-glass` | 0.1.9 | **2018-05-11** | — | MIT | Vue 2 | unrelated ("vue dynamic css") | n/a | — |
| `liquid-glass-vue` (unscoped) | 0.0.1 | 2025-06-13 | 2 | MIT | — | placeholder, no real content | — | — |

### Packages that DO NOT EXIST (404 on registry)
- **`liquid-dom`** — 404. The real thing is the **scoped** `@liquid-dom/*` (`core`, `react`, `layout`, `r3f`, `three`).
- `@liquid-glass/core` — 404. `amazing-glass` (repo exists, npm publish pending) — 404. `hyalite` — 404. `shuding-liquid-glass` — 404.

---

## 2. Vue-3-native liquid glass libraries

| Package | Ver | Published | dl/wk | Real? | Maintained? | Note |
|---|---|---|---|---|---|---|
| **`@mizuui/liquid-glass-vue`** | 0.1.4 | 2026-09-30 | 801 | ✅ yes | ✅ 6 days old | MIT, 20 KB, `vue` peer, `<LiquidGlass>`. **No public GitHub repo** (`mizuui` → 0 repos on GitHub). Bus-factor/supply-chain risk. |
| `@wxperia/liquid-glass-vue` | 1.0.9 | 2025-06-18 | 413 | ✅ yes | ❌ stale 16 mo | 288 ★ repo, but **no LICENSE file**; ships stray `favicon.ico` + a `shader-worker-*.js` build leak. |
| `@aslanonur/liquid-glass-vue` | 1.1.3 | 2025-06-11 | 44 | ✅ | ❌ | fork-family, negligible use |
| `@tinymomentum/liquid-glass-vue` | 1.0.9 | 2025-06-17 | 38 | ✅ | ❌ | fork-family |
| `@zaosoula/liquid-glass-vue` | 1.1.2 | 2025-06-12 | 22 | ✅ | ❌ | "Based on liquid-glass-react by @rdev" |
| `@chencu/liquid-glass-vue` | 1.0.1 | 2025-08-12 | 10 | ✅ | ❌ | "fork with 70% better performance" (unverified) |
| `glass-ui-vue` | 1.3.18 | 2025-08-09 | 5 | ✅ | ❌ effectively dead | glassmorphism, not liquid glass; adds vue-router dep |
| `vue-glass` | 0.1.9 | 2018 | — | ✅ | ❌ | unrelated to liquid glass entirely |
| `liquid-glass-vue` | 0.0.1 | 2025-06-13 | 2 | ✅ | ❌ | empty placeholder |

**`liquid-glass-vue` / `glass-ui-vue` / `vue-glass`: not viable.** `@mizuui/*` is the only current Vue-3-native option.

### Varlet / VueUse
- **`@varlet/ui` 3.20.6** (published 2026-08-19) — actively maintained Vue 3 Material Design 2/3 library.
  **No liquid-glass / displacement-refraction component found.** Verified via registry + npm search; no
  liquid-glass entry appears in the Varlet scope.
- **`@vueuse/core` 15.0.0** (2026-09-16) — no glass/refraction composable exists. *(Inferred from npm search
  results; I did not enumerate every export of `@vueuse/core`.)* VueUse is not a source of this effect.

---

## 3. Reference implementations worth vendoring

| Repo | ★ | License | Tech | Core file — vendorable? |
|---|---|---|---|---|
| [shuding/liquid-glass](https://github.com/shuding/liquid-glass) | 1,194 | MIT | JS, SVG filters | ✅ **`liquid-glass.js` = 9,397 bytes**, self-contained single file. README: "paste into any website console". Also `liquid-diamond.js` (35 KB). **Best minimal vendor.** |
| [deepika-builds/liquid-glass](https://github.com/deepika-builds/liquid-glass) | 268 | MIT | JS, SVG displacement | ✅ **`liquid-glass.js` = 8,695 bytes**, zero deps, clean `liquidGlass(el, opts)` API, ResizeObserver + fallback. |
| [VII-Cae/hyalite--liquid-glass](https://github.com/VII-Cae/hyalite--liquid-glass) | 228 | MIT | JS, SDF lens maps + SVG | ✅ `hyalite.js` = 51,559 bytes + `hyalite.d.ts` + package.json. Most sophisticated single-file (bevel, chromatic dispersion, resize/materialize). |
| [rdev/liquid-glass-react](https://github.com/rdev/liquid-glass-react) | 6,339 | MIT | React + TS | ❌ React-locked, stale since 2025-06-13. |
| [WXperia/liquid-glass-vue](https://github.com/WXperia/liquid-glass-vue) | 288 | ⚠️ **none** | Vue 3 + TS | ⚠️ usable but **no license file** — legally unsafe to vendor. |
| [Muggleee/liquid-glass](https://github.com/Muggleee/liquid-glass) | 163 | ⚠️ **none** | Vue + WebGL shader | ⚠️ no license. |
| [mkj0kjay/vue-web-liquid-glass](https://github.com/mkj0kjay/vue-web-liquid-glass) | 50 | ⚠️ **none** | Vue 3, CSS+SVG | ⚠️ no license; a demo/experiment, not a package. |
| [martin65536/liquid-glass-svg](https://github.com/martin65536/liquid-glass-svg) | 1 | ⚠️ **none** | SVG `feDisplacementMap` + canvas SDF, **no WebGL** | Port of `Kyant0/AndroidLiquidGlass` — conceptually the closest to our case, but **no license** → unusable. |
| [tomacco/amazing-glass](https://github.com/tomacco/amazing-glass) | 26 | MIT | TS web components + **optional Vue 3 binding** | Repo is MIT and `package.json` declares a `./vue` export + `vue >=3.3` optional peer, **but it is NOT published to npm** (404). Would have to be vendored/built from source. |

---

## 4. Honest verdict

### The most practical for a Vue 3 app, in order

1. **`simple-liquid-glass` web component** (`<liquid-glass>`) — the strongest overall *if* you need broad
   device coverage and real WebGL refraction.
   - **Verified: the `./web-component` entry has zero React.** `dist/web-component.esm.js` (73,370 bytes):
     **0 occurrences of the string "react"**, no `import` statements at all (fully bundled), and
     `peerDependenciesMeta` marks `react`/`react-dom` **optional**. Their `size-limit` config literally
     names it *"web-component — `<liquid-glass>` (Brotli, no React)"*, 23 KB budget.
   - MIT, actively maintained (5.3.0 on 2026-09-09), ships an `llms.txt` + `skills/` doc written for agents.
   - Trade-off: 4.45 MB unpacked install (multiple bundles + maps); the runtime payload you actually ship is
     ~22 KB Brotli.

2. **`@mizuui/liquid-glass-vue` + `@mizuui/liquid-glass-core`** — the smallest, cleanest, most "Vue-native"
   option and the newest (2026-09-30). MIT, 20 KB + 16 KB, zero dependencies, plain ESM, ResizeObserver,
   graceful fallback. Use `core` directly in a composable if you'd rather not depend on the wrapper.
   - **Risk to weigh: no public source repository.** This is a genuine supply-chain and longevity concern
     for a dependency you'd ship.

3. **Vendor `shuding/liquid-glass`'s 9.4 KB file** (or `deepika-builds/liquid-glass`'s 8.7 KB) — MIT, tiny,
   self-contained, no dependency at all. Lowest dependency risk; you own the code. Best choice if you want
   *minimal code you control* rather than *minimal code you write*.

### Traps — avoid these

| Trap | Why |
|---|---|
| `liquid-glass-react` (45k dl/wk, the famous one) | **React-only and stale ~16 months** (last publish 2025-06-11, last commit 2025-06-13). Popularity ≠ usability here. |
| `liquid-dom` | **Does not exist.** The real `@liquid-dom/*` is **WebGPU-only**, 1.24 MB, and declares **no license**. WebGPU on Android is hardware/driver-dependent and is being restricted under Android's Advanced Protection Mode ([report](https://www.androidauthority.com/android-advanced-protection-mode-disable-chrome-webgpu-apk-teardown-3647502/)). Wrong bet for a WebView app. |
| `glasscn-ui` | **Repo archived**; it is glassmorphism (frosted blur), **not refraction**. Name is misleading. |
| `simple-liquid-glass` used as a **React** component | Fine package, but then you'd be shipping React inside a Vue app. Only the `/web-component`, `/webgl`, `/backdrop` entries are React-free. |
| `react-liquid-glass-svg` (3.0 MB), `@liquid-dom/core` (1.24 MB), `@specy/liquid-glass-react` (6.8 MB), `simple-liquid-glass` (4.45 MB install) | Heavy installs; React-locked except `@liquid-dom`. |
| `@wxperia/liquid-glass-vue` + its 4 forks | All frozen at June–Aug 2025; the upstream repo has **no LICENSE file** (package.json claims MIT) and leaks build junk. |
| `glass-ui-vue`, `vue-glass`, `liquid-glass-vue` | Dead / irrelevant / placeholder. 2–59 downloads a week. |
| Anything requiring a **paid displacement-map PNG** | No real package does this. If a vendor sells you one, it is unnecessary — the map is generatable in ~40 lines. |

### Perf caveats for Android (inference, not measured)
- SVG `feDisplacementMap` inside `backdrop-filter` is GPU-heavy; on mid-range Android, large glass surfaces
  or many instances will drop frames. `simple-liquid-glass` mitigates this with `quality="low"` (default) and
  an IntersectionObserver that drops `backdrop-filter` when off-screen; `@mizuui` exposes `resolution` (512 default).
- Re-capturing a **live canvas backdrop** every frame is the most expensive configuration. Prefer a static or
  slowly-changing backdrop, or `renderer="webgl"` where a sibling source exists.
- `backdrop-filter` requires hardware acceleration in WebView; verify on a real low-end device, not just an emulator.

---

## 5. Note on the existing `poc-liquid-glass.html` in this repo

The PoC is **architecturally correct** and matches the libraries' own constraints: it registers
`simple-liquid-glass/web-component`, points `backdrop-selector="#aurora"` at a **sibling** canvas (the docs
explicitly require a sibling, *"never an ancestor of the glass"*), and probes `data-glass-strategy` /
`data-glass-reason` — the library's own strategy telemetry. Attributes used (`radius`, `frost`, `lens`,
`lens-strength`, `scale`, `dispersion`, `saturation`) are all real per `llms.txt`.

Two gaps worth fixing:

1. **It does not set `renderer`, so on Android it will silently use the SVG path, not WebGL.**
   `llms.txt`: *"iOS/iPadOS automatically uses WebGL... `renderer="webgl"` opts Android and desktop into the
   same pipeline."* Add `renderer="webgl"` (keep an SVG/blur fallback) if you want the WebGL pipeline on Android.
2. **Pointer-interaction props are React-only.** `followPointer`, `clickRipple`, `rippleIntensity`,
   `liquidTrigger`, and elasticity exist only on `LiquidGlassInteractive` (`/interactive`, React). In Vue you
   would reimplement pointer elasticity yourself. The `/web-component` entry exposes neither.

---

## Citation index

npm registry (verified live): `https://registry.npmjs.org/<pkg>` · downloads `https://api.npmjs.org/downloads/point/last-week/<pkg>` · file listings `https://unpkg.com/<pkg>@<ver>/?meta`
- `https://registry.npmjs.org/simple-liquid-glass` · `https://unpkg.com/simple-liquid-glass@5.3.0/llms.txt` · `https://unpkg.com/simple-liquid-glass@5.3.0/dist/web-component.esm.js`
- `https://registry.npmjs.org/liquid-glass-react` · `https://registry.npmjs.org/@mizuui%2Fliquid-glass-vue` · `https://registry.npmjs.org/@mizuui%2Fliquid-glass-core`
- `https://unpkg.com/@mizuui/liquid-glass-core@0.1.4/src/displacement.js` · `.../src/filter.js` · `.../README.md`
- `https://registry.npmjs.org/@liquid-dom%2Fcore` · `https://registry.npmjs.org/glasscn-ui`
- GitHub: `https://api.github.com/repos/<owner>/<repo>` · https://github.com/shuding/liquid-glass · https://github.com/deepika-builds/liquid-glass · https://github.com/VII-Cae/hyalite--liquid-glass · https://github.com/rdev/liquid-glass-react · https://github.com/itsjavi/glasscn-ui (archived) · https://github.com/tomacco/amazing-glass
- Browser support: https://caniuse.com/webgpu · https://www.androidauthority.com/android-advanced-protection-mode-disable-chrome-webgpu-apk-teardown-3647502/
