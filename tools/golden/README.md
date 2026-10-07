# Golden-sample oracle (TypeScript core → JSON fixtures)

The v2 TypeScript core in `src/core/**` is the **oracle**: it must not be modified.
This tool runs a fixed set of deterministic cases against it and freezes the exact
outputs as JSON fixtures, so the Kotlin port (`:core`) can be asserted against them
byte-for-byte / double-for-double.

* generator: [`tools/golden/generate.ts`](./generate.ts)
* fixtures: `core/src/commonTest/resources/golden/*.json`
* index: `core/src/commonTest/resources/golden/manifest.json`

## Reproduce everything (exact command)

```powershell
# from the repository root (D:\工作台\iGRBL)
npx --yes tsx tools/golden/generate.ts
```

Verified working route: **`npx --yes tsx`** (tsx v4.23.15 resolved from the npx cache on
Node v24.14.0, Windows). No `package.json` change was needed — `tsx` is *not* a
devDependency of this repo. If a pinned toolchain is preferred:

```powershell
npx --yes tsx@4.23.15 tools/golden/generate.ts
```

`npm i -D tsx` was **not** required and the file is designed to be run the same way on
any Node ≥ 20 (the script uses only `node:crypto`, `node:fs`, `node:path`, `node:url`
plus dynamic `import()` of the core).

The script rewrites all 14 fixtures + `manifest.json` in place and prints
`sha256[:12]`, byte size and entry count per case. Two consecutive runs produce
**byte-identical** files (verified: all 15 sha256 values equal; see
[Determinism](#determinism--verification) for the exact procedure).

## What the manifest gives the Kotlin tests

`manifest.json` is machine-readable, so a Kotlin test can drive the whole suite:

| field | meaning |
| --- | --- |
| `cases[].case` | case name (matches the fixture's `case` field and the file name) |
| `cases[].outputFile` | file name inside this directory |
| `cases[].bytes` | exact byte size of the fixture file |
| `cases[].sha256` | sha256 of the whole fixture file (cheap integrity check) |
| `cases[].entryCount` | primary item count ("same number of items" assertion) |
| `cases[].entryCounts` | per-case breakdown (variants / points / polylines / lines / cases …) |
| `cases[].modules` | which `src/core/**` module(s) the fixture pins |
| `cases[].input` | human description of the input |

Recommended Kotlin test shape: read `manifest.json`, sha256-check each fixture, then
per case assert `entryCount`/`entryCounts` and deep-compare the `result` payload.

## Fixture inventory

14 cases, 575 793 fixture bytes, 242 entries. `sha256` shown truncated to 12 chars.

| case | fixture file | bytes | sha256 | entries | TS module(s) pinned |
| --- | --- | --- | --- | --- | --- |
| potrace | `potrace.json` | 43512 | `d1e85754d7e1` | 17 | `vector/Potrace.ts`, `vector/Paths.ts` |
| centerline | `centerline.json` | 15645 | `ee139d4caeca` | 13 | `vector/Centerline.ts`, `vector/Paths.ts` |
| stroke-analysis | `stroke-analysis.json` | 89001 | `83da1b6a52b3` | 5 | `vector/StrokeAnalysis.ts`, `vector/Centerline.ts` |
| dithering | `dithering.json` | 73955 | `84b64813fb3e` | 9 | `raster/dithering.ts` |
| hershey | `hershey.json` | 23352 | `e5d4691cde24` | 5 | `text/Hershey.ts`, `text/hersheyData.ts` |
| polylines-to-gcode | `polylines-to-gcode.json` | 19495 | `925233c9e1ad` | 2 | `vector/Paths.ts`, `vector/Potrace.ts` |
| svg-to-gcode | `svg-to-gcode.json` | 11009 | `9678d3ba8933` | 2 | `vector/SvgToGcode.ts` |
| gcode-analysis | `gcode-analysis.json` | 58639 | `5439497e081b` | 23 | `gcode/GrblFile.ts`, `grbl/GrblCommand.ts` |
| grbl-messages | `grbl-messages.json` | 189688 | `e263d7d685f7` | 11 | `grbl/GrblCommand.ts`, `grbl/csvData.ts`, `grbl/types.ts` |
| grbl-version | `grbl-version.json` | 22189 | `a10c0e9abe11` | 19 | `grbl/types.ts` (+ transcribed banner parsing from `grbl/GrblCore.ts`) |
| settings-preset | `settings-preset.json` | 2952 | `e3d193d218aa` | 19 | `grbl/SettingsPreset.ts` |
| format-decimal | `format-decimal.json` | 2889 | `c0b31086cce5` | 24 | `grbl/GrblCommand.ts` |
| fit-to-travel | `fit-to-travel.json` | 15283 | `dea1c71c4797` | 35 | `grbl/DeviceProfile.ts`, `grbl/GrblConfig.ts` |
| keepalive-format | `keepalive-format.json` | 8184 | `ecf5711bc943` | 58 | `native/KeepAlive.ts` |

`manifest.json` itself (not hashed inside itself): sha256
`f86e2efe27c3f3ff38daeadee5097f64619004a17b3e79682de446af2dda2496`.
It changes only when a fixture changes.

### Per-case coverage

1. **potrace** — one synthetic 64×64 RGBA bitmap (white background, filled black circle
   r=11, black rectangle, hollow black ring) traced with 4 option sets (`default`,
   `tuned`, `inverted`, `coarse-flatten`). Captures `Polyline[]` (points + `closed`),
   per-variant polyline/point counts and the input bitmap.
2. **centerline** — 40×40 bitmap with a 3-px plus sign, a 3-px diagonal and a 1-px stub.
   `centerlineTrace` with default options and with `closed/minBranchPx:2/tolerance 0.5`
   (the stub disappears / reappears, pinning spur pruning) plus `skeletonize` with
   `maxIterations` 60 (converged) and 2 (not converged) — skeleton stored as rows of
   `0`/`1` with a raw-bytes sha256.
3. **stroke-analysis** — 5 bitmaps (1-px grid → Centerline, 4-px bars → Outline
   non-fallback, r=22 solid disc → Outline *not-converged* fallback, 60.9 % ink →
   Outline *maxInkRatio* fallback, single pixel → *degenerate* fallback). Each run
   through `analyzeStrokes` + `decideVectorMode` twice (defaults and strict options), so
   all five decision paths and their Chinese `summary` strings are pinned.
4. **dithering** — one 32×32 grayscale input (diagonal ramp modulated by an 8-px block
   checker) dithered once per entry of `DITHERING_MODES` **in array order**. Output is
   stored as `#`/`.` rows plus the sha256 of the raw RGBA bytes (all 9 outputs are
   provably binary 0/255 — the generator asserts this).
5. **hershey** — 5 option sets (`"iGRBL"` PWM, `"A1"` bold + offsets + no PWM,
   3-line multi-line, vertical orientation, non-ASCII/empty-`laserOn`) with every
   emitted G-code line and the bounding box. Also pins the font tables by sha256 of
   `HERSHEY_HORIZONTAL` / `HERSHEY_VERTICAL` and `HERSHEY_SPACE_BETWEEN`, so the Kotlin
   font data can be verified independently of the glyph placement code.
6. **polylines-to-gcode** — 2 option sets over (a) the potrace case-1 default polylines
   and (b) a hand-authored 5-path list (closed square with duplicate endpoint,
   sub-epsilon duplicate pair, single point, open 3-point segment, empty path), pinning
   nearest-neighbour ordering, mm conversion, de-duplication, `F` bookkeeping,
   header/footer trimming, `pwm` on/off and `flipY`.
7. **svg-to-gcode** — a hand-written SVG (rect, transformed rect, circle, cubic +
   quadratic/`T` Bézier paths, `line` with `mm`/`pt` lengths, polyline, polygon, nested
   `<g>`, `<defs>`, `viewBox`, `width="40mm"`) converted with 2 option sets, plus
   `parseLength` (16 inputs) and `parseViewBox` (7 inputs) sweeps and 3 error paths
   (malformed XML, non-`<svg>` root, empty string) with their thrown messages.
8. **gcode-analysis** — a fixed 24-line program (comments `;`/`()`, `G0/G1/G2`, `S`
   values, `$H`, `G92/G91`, `F`-only line, `T`/`M`, blank line) through
   `parseGcode` + `analyze` (stats, bbox, preview moves) and, per command, every
   predicate/getter (`isMovement`, `isArcMovement`, `isSetWCO`, `isLaserON`, `isCW`,
   `status`, `serialData` *before and after* `buildHelper`, `getDecodedMessage`, all
   `Element` accessors). Extra sweeps: command lifecycle (`setSending`/`setResult`/
   `clearResult`), `Element.parse` (incl. `NaN`), `GrblConfSTIsSetConf`,
   raw un-stripped lines (comments/spacing/lower case), duplicate-word semantics
   (`buildHelper` keeps the **last** `G`/`X`, `GrblFile.parseElements` keeps the
   **first**) and `stripComments` edge cases.
9. **grbl-messages** — 25 realistic GRBL response lines decoded with the real
   `GrblMessage.fromLine(line, true)` (and `false`) under **11 version contexts**
   (no version, v1.1h, v0.9, v1.0c, Ortur 1.4/1.5/1.7/HAL, Aufero, Longer Nano,
   NanoDuo) via `installDecoders` + the real `csvData.ts` tables. Per context it also
   pins the selected decoder group, a 13-key × 3-index setting lookup, a 3-key alarm
   lookup and an error-code lookup. The module's decoders are restored to `() => null`
   afterwards.
10. **grbl-version** — 19 banner strings (`Grbl 1.1h [...]`, `[VER:1.1h.20190825:]`,
    Ortur 1.4/1.5/1.7/`1.7.3`/`v1.4`/`unknown`, Aufero, Longer Nano/Ray5, NanoDuo, mixed
    case, missing letter) with the constructor args, every derived field
    (`isOrtur/isLonger/isHAL/isLuckyWiFi/orturFWVersionNumber/machineName/toString`) and
    the decoder group each one selects, plus a 6×6 `compareTo/gte/lt/equals` matrix and
    the `statusReportVersion` fallbacks.
11. **settings-preset** — one preset text with blank lines, `;`/`()` comments, duplicate
    ids, junk lines, negative/`+`/leading-dot/exponent/exponent-truncated values,
    trailing junk; captures `entries` (id, value, `raw`) and `skipped`, plus empty and
    comment-only inputs.
12. **format-decimal** — 24-value sweep through `formatDecimal` (0, -0, 0.5, 1/3, 1e-7,
    12345.6789, -0.0001, 1e21, 1e-21, subnormal, MAX_VALUE, NaN, ±Infinity) and
    `Element.parse(...).toString()` for 7 words. Inputs that JSON cannot carry are
    written as the strings `"NaN"`, `"Infinity"`, `"-Infinity"`, `"-0"`.
13. **fit-to-travel** — 20 `fitSizeToTravel` cases (Fit/Clamp/None, exact fit, NaN,
    Infinity, zero/negative sizes, custom travel, round3-epsilon cases) and 15
    `checkGcodeWithinTravel` line sets (comments, arcs, lowercase/no-space, exponent
    mis-parse, epsilon boundary, axis-only lines), plus an `AppSettings`-backed travel
    variant (500×400 then restored), `builtinProfiles()` and the storage state.
14. **keepalive-format** — `truncateName` over 25 names (lengths 0/1/23/24/25/40, CJK,
    emoji surrogate pairs, `\t`, `\n`, NBSP, BOM, U+3000, leading/trailing whitespace),
    `percentOf` over 20 pairs (0/33.3/100/negative/over-total/NaN/±Infinity/zero total)
    and `formatText` over 13 combos, plus `NOTIFICATION_TITLE`.

## Shims (platform globals the core expects)

All shims are installed **before** any core module is imported (every core import in the
generator is a dynamic `await import(...)`).

| global | implementation | needed by |
| --- | --- | --- |
| `ImageData` | `{ data: Uint8ClampedArray; width: number; height: number }` | potrace / centerline / stroke-analysis / dithering |
| `localStorage` | in-memory `Map`-backed `Storage`; **empty at the start of every run** | `grbl/GrblConfig.ts`, `grbl/DeviceProfile.ts` |
| `DOMParser` | minimal well-formed-XML DOM: `documentElement`, `children`, `tagName`, `getAttribute`, `querySelector` (`tag`, `#id`, `.class`, comma lists) | `vector/SvgToGcode.ts` |

The `DOMParser` shim exists because Node has no DOM and the SVG *tokenizer* is not part of
iGRBL; the module under test (`convertSvgToGcode`, its transform/path/flattening/G-code
pipeline) runs unchanged. It was cross-checked against a real DOM implementation
(`@xmldom/xmldom` 0.9.12, already present in `node_modules`): both parsers produce
**byte-identical** `convertSvgToGcode` output for the fixture SVG under both option sets.
Nothing in iGRBL's own behaviour is stubbed.

## Encoding conventions inside the fixtures

| concept | encoding |
| --- | --- |
| bitmap (input) | `{ width, height, layout: "RGBA", encoding: "palette-rle", palette: [[r,g,b,a],…], runs: [[count, paletteIndex],…], sha256 }` |
| binary image (skeleton, dithering) | `rows: ["0101…", …]` plus a sha256 of the raw byte array (dithering: `#` = white, `.` = black) |
| polyline | `{ pts: [[x, y], …], closed: bool }` |
| numbers | full `JSON.stringify` fidelity — shortest round-trip double, **never rounded or formatted** |
| non-finite / negative-zero inputs | name strings `"NaN"`, `"Infinity"`, `"-Infinity"`, `"-0"` (JSON cannot carry them); the captured output sits next to the input |
| non-finite outputs | a `number` field that can be `NaN` becomes the string `"NaN"` (e.g. `Element.parse("F").number`); negative zero never appears as a number |
| `Map`/`Set`-derived data | always emitted in insertion order, so array order is meaningful |

Each fixture is a self-describing object:

```json
{
  "case": "potrace",
  "generator": "tools/golden/generate.ts",
  "generatorCommand": "npx --yes tsx tools/golden/generate.ts",
  "modules": ["src/core/vector/Potrace.ts", "..."],
  "input": "synthetic 64x64 RGBA bitmap (…); traced with 4 option sets",
  "entryCount": 17,
  "entryCounts": { "variants": 4, "polylines": 17, "points": 334 },
  "result": { }
}
```

## Determinism & verification

Nothing reachable from the captured cases uses `Math.random`. The one real
nondeterminism source was found and pinned:

* `ditherImage(data, w, h, 'Random')` seeds its LCG with `Date.now()`. The generator
  overrides `Date.now` **for that single call only** with the constant
  `1700000000000` and records it in the fixture as `modes[].dateNowOverride`
  (the value is `null` for the other 8 modes). The fixture therefore pins the exact LCG
  sequence for a known seed instead of being run-dependent.

Other determinism guarantees:

* `localStorage` starts empty, so `AppSettings` begins at `DEFAULT_SETTINGS`
  (300×200 mm travel); the only case that mutates settings restores them inside the case
  and captures the restored value, so case order cannot leak state.
* All cases run in a fixed order in a single process; every object is built with a fixed
  key order, so `JSON.stringify(…, null, 2) + "\n"` is byte-stable.
* Two consecutive generator runs produced **identical sha256 for all 15 files**
  (`Compare-Object` over `Get-FileHash -Algorithm SHA256` is empty). A third, in-process
  re-run performed during the DOMParser cross-check produced an identical
  `svg-to-gcode.json` as well.
* The generator throws if any serialized number is non-finite, instead of silently
  writing `null`.

Reproduce the check:

```powershell
$dir = 'core\src\commonTest\resources\golden'
npx --yes tsx tools/golden/generate.ts
$a = Get-ChildItem $dir -Filter *.json | Sort-Object Name | ForEach-Object { "$($_.Name)|$((Get-FileHash $_.FullName -Algorithm SHA256).Hash)" }
npx --yes tsx tools/golden/generate.ts
$b = Get-ChildItem $dir -Filter *.json | Sort-Object Name | ForEach-Object { "$($_.Name)|$((Get-FileHash $_.FullName -Algorithm SHA256).Hash)" }
Compare-Object $a $b   # empty output = byte-identical
```

## What is deliberately NOT captured

Excluded modules (need a real browser canvas / Capacitor SDK / device):
`raster/ImageTransform.ts`, `raster/RasterConverter.ts`, `vector/ImageVector.ts`,
`vector/SvgVector.ts`, `vector/SmartVector.ts`, `grbl/GrblCore.ts`,
`serial/SerialTransport.ts`.

Consequences and workarounds, all explicit:

* **`GrblCore.ts` is not imported** (it builds the Capacitor serial transport at module
  load). Two *pure* helpers that live inside it are **transcribed verbatim** into
  `generate.ts` and marked `TRANSCRIBED`:
  `parseVersionBanner` / `parseVerMessage` (→ `grbl-version`) and the decoder-group
  selection inside `lookupCode` (→ `grbl-messages`). The lookups themselves still run
  through the real `installDecoders` + real `csvData.ts` tables, and the fixture records
  the resulting group name for every context so the Kotlin port can pin the selection
  logic directly.
* **`DeviceProfile.newProfile()` / `normalize()` with a missing id** use `Date.now()` +
  `Math.random()` for id generation and are therefore not captured; the deterministic
  parts of that module (`builtinProfiles`, `fitSizeToTravel`, `checkGcodeWithinTravel`,
  `listSavedProfiles`, `listKnownDeviceIds`, `isDeviceKnown`) are captured instead.
* `grbl/GrblConfig.ts` beyond the pure helpers is not captured (no browser persistence),
  but the `AppSettings` read path *is* exercised through `fitSizeToTravel`'s
  `resolveTravel`.

## Porting hazards observed in the oracle (worth a Kotlin test each)

Number formatting — the single biggest risk:

1. `formatDecimal(v)` is literally `String(v)` (the `Number.isInteger` branch returns the
   same text). JS switches to exponential at `|v| ≥ 1e21` and `< 1e-6`
   (`1e21 → "1e+21"`, `1e-7 → "1e-7"`, `1e-6 → "0.000001"`, `5e-324 → "5e-324"`), while
   Java/Kotlin `Double.toString` prints `1.0E21`, `1.0E-7`, `4.9E-324` and switches at
   `1e7`/`1e-3`. A Kotlin port **must not** use `Double.toString`/`String.format` here.
2. `String(-0.0)` in JS is `"0"`; Kotlin's `(-0.0).toString()` is `"-0.0"`. `format-decimal`
   pins `-0 → "0"` (this also matters for `Paths.fmt`, `SvgToGcode.fmt`, `Hershey.fmt`
   which all end with JS `String(...)` / `toFixed` + trailing-zero stripping).
3. `Paths.fmt` uses `toFixed(decimals)` then strips trailing zeros and normalises `-0`;
   JS `toFixed` rounds the *exact binary* value (e.g. `(2.675).toFixed(2) === "2.67"`),
   so Kotlin's `String.format("%.3f")` (HALF_UP on the decimal expansion) can differ.
4. `fitSizeToTravel` and friends use `Math.round(v * 1000) / 1000`; JS `Math.round`
   is `floor(x + 0.5)` and returns `-0` for `-0.4`, which then prints as `"0"`.
5. Accumulated lengths use `Math.hypot`, which is *scaled* (overflow-safe) and not
   bit-identical to `sqrt(dx*dx + dy*dy)` for extreme inputs. `lengthMm`, `pathLengthMm`,
   `estimatedSeconds` are compared as exact doubles in these fixtures — either replicate
   `Math.hypot` (scale by the max magnitude) or compare those fields with an epsilon.
6. `ditherImage` uses `>>` (int32 arithmetic shift, operands truncated toward zero) for
   the shifting matrices and `Math.trunc` for the non-shifting ones; `Uint8ClampedArray`
   assignment clamps to 0..255 with **round-half-to-even**. In Kotlin use
   `Int shr` after `toInt()` and clamp manually.
7. `Number.parseFloat` is more lenient than `toDoubleOrNull` (`"" → NaN`, `"1.5.5" → 1.5`,
   `"+2" → 2`, `"1e3" → 1000`); `Element.parse('F')` must yield `NaN` (the fixture stores
   it as the string `"NaN"`), and `NaN` element numbers make `Element.equals` `false`.

String handling:

8. `truncateName` measures and slices **UTF-16 code units** (`String.length`), and can
   therefore cut an emoji in half: `"a"×23 + "😀"` (25 units) → 23 `a`s + a lone high
   surrogate + `"…"`. Kotlin's `String.length` matches, but any code-point-based
   implementation will not.
9. JS `String.prototype.trim` removes NBSP (`U+00A0`) and BOM (`U+FEFF`); Kotlin's
   `trim()` (`Char.isWhitespace`) does **not**. Fixtures include names wrapped in NBSP/BOM
   and a 24-char name with a trailing NBSP that must *not* be truncated after trimming.
10. `GrblCommand` uppercases and trims in the constructor; `buildHelper` then rewrites
    `mLine` keeping a single leading/trailing space in some cases (e.g. `"(c) G1  X1"` →
    `" G1 X1"`). `serialData` strips spaces **only for non-`$` commands**, and strips the
    comment text only *after* `buildHelper` has run (before that, `"(C)G1X1\n"`; after,
    `"G1X1\n"`). All four combinations are pinned.
11. Regex classes: JS `\s` includes NBSP/BOM/figure space, Kotlin/Java `\s` does not
    (`UNICODE_CHARACTER_CLASS` differs again). The parser regexes
    (`GrblFile.parseElements`, `DeviceProfile.extractWords`, `SettingsPreset.LINE_RE`)
    all use `\s`/whitespace assumptions.
12. `Hershey.textToGcode` iterates `Array.from(text)` (code points) and skips any code
    outside 32..126, then replaces only the **first** `"M3"`/`"M5"` inside each fragment.

Ordering / algorithm details:

13. `GrblCommand.buildHelper` keeps the **last** occurrence of a duplicated address word
    (`Map.set` overwrite) while `GrblFile.parseElements` keeps the **first**
    (`if (!map.has(key))`). Fixture: `"G1 X1 Y2 X3 G0"` → `G0 X3 Y2` vs `X=1`.
14. `DeviceProfile.extractWords` mis-parses exponents: `"X1.5e2"` yields the words
    `X1.5` **and** `E2`, so `x = 1.5` (not 150).
15. `fitSizeToTravel` `Clamp` decides `adjusted` by comparing **round3** values:
    `300.0004` clamped to `300` reports `adjusted: false` while still shrinking the size;
    `Fit` uses `scale < 1` and reports `adjusted: true` for the same input. Non-finite
    sizes are replaced by the 1 mm floor, not clamped.
16. `checkGcodeWithinTravel` judges `ok` only from **non-negative** work coordinates
    (negative machine coordinates are ignored for the verdict but still returned in
    `minX/minY`), uses an `EPS` of 0.001, returns `0` for axes that never appeared, and
    returns the all-zero result when no motion line parsed.
17. `parseSettingsPreset` dedupes by id but keeps the **first position** with the **last
    value**; the `raw` text is the comment-stripped/normalised line; `LINE_RE` has no
    exponent support so `$35=1e3` parses as `1`, and trailing junk (`$37=100 extra`) is
    ignored; blank lines are not counted in `skipped`.
18. `optimizeOrder` is a greedy nearest-neighbour with strict `<` comparisons over a
    shrinking array — ties keep the earlier path. Changing `<` to `<=` or the copy order
    changes the emitted G-code.
19. `simplifyPath` (Douglas-Peucker, iterative) keeps the **first** maximal-distance point
    and only splits when `maxD > tol && idx > 0`; `Centerline` then drops paths whose
    endpoints coincide within `1e-6` and snaps cluster endpoints to the junction centroid.
20. `polylinesToGcode` de-duplicates consecutive mm points with a *relative* threshold of
    `1e-6` per axis (not a distance), skips paths with `< 2` mm points (so a sub-epsilon
    duplicate pair silently disappears), and only emits `F` when the feed actually
    changes.
21. Potrace uses `Math.trunc` (toward zero) for its `tdiv` integer division, `Int32Array`
    wraparound semantics for `po/lon/pivk`, and XOR-based path decomposition — Kotlin
    must use `IntArray` + truncating division, not floor division.
22. `GrblVersionInfo.compareTo` compares `build` **lexicographically as a string**, so
    `"1.1h"` sorts *after* `"1.120190825"`; `orturFWVersionNumber` uses the unanchored
    `/(\d+)\.(\d+)/` so `"1.7.3" → 170` and `"v1.4" → 140` but `"unknown" → 0`.
23. `GrblMessage.fromLine` classifies by shape only: `[VER:…]`/`[OPT:…]` become
    `Feedback` (not `Startup`), `$H` without `=` is `Others`, and decoding only ever
    rewrites `Config` (append `" (brief)"` + tooltip `"desc [unit]"`) and `Alarm`
    (replace the message with the brief). Decoders are module-global state installed via
    `installDecoders` — the Kotlin port needs the same indirection or a different design,
    but the fixture pins the same results either way.
24. Chinese text in `decideVectorMode().summary` is part of the fixture (rounding via
    `Math.round(v*100)/100` then default number→string). It must be reproduced verbatim,
    including the `×`, `≈`, `→` characters.
