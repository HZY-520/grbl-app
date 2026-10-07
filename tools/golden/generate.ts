/**
 * ============================================================================
 *  iGRBL — golden-sample oracle generator (TypeScript core → JSON fixtures)
 * ============================================================================
 *
 *  PURPOSE
 *  -------
 *  Captures the exact, byte-stable outputs of the v2 TypeScript core
 *  (`src/core/**`, which is READ-ONLY / the oracle) as JSON fixtures, so the
 *  Kotlin port (`:core`) can be asserted against them.
 *
 *  RUN
 *  ---
 *      npx --yes tsx tools/golden/generate.ts
 *
 *  Writes:
 *      core/src/commonTest/resources/golden/<case>.json   (one file per case)
 *      core/src/commonTest/resources/golden/manifest.json (case list + sha256)
 *
 *  WHY SHIMS ARE NEEDED
 *  --------------------
 *  The core is written for a browser. Only three platform globals are needed by
 *  the modules captured here; this file installs them before importing the core
 *  (all core imports are dynamic `await import(...)` so the shims win the race):
 *
 *    * ImageData          — tiny { data, width, height } class. The captured
 *                           modules only read `.data/.width/.height` and
 *                           construct it.
 *    * localStorage       — in-memory Map backed store (GrblConfig /
 *                           DeviceProfile). Starts EMPTY on every run, which is
 *                           what makes the settings-dependent fixtures stable.
 *    * DOMParser          — minimal well-formed-XML DOM used by
 *                           `convertSvgToGcode`. Node has no DOMParser; the
 *                           browser actually parses the SVG, and iGRBL's logic
 *                           under test is the *path/transform/G-code* pipeline,
 *                           not the XML tokenizer, so a small DOM that
 *                           faithfully implements the exact surface the core
 *                           touches (`documentElement`, `children`, `tagName`,
 *                           `getAttribute`, `querySelector`) is provided here.
 *                           Nothing in iGRBL's own behaviour is stubbed: the
 *                           real `convertSvgToGcode` runs unchanged.
 *
 *  Excluded modules (need a real browser canvas / Capacitor SDK) are never
 *  imported: raster/ImageTransform.ts, vector/ImageVector.ts, vector/SvgVector.ts,
 *  grbl/GrblCore.ts, serial/SerialTransport.ts, raster/RasterConverter.ts.
 *  Two small *pure* helpers that live inside the otherwise-unimportable
 *  GrblCore.ts (version-banner parsing, decoder-table group selection) are
 *  transcribed verbatim in this file; they are marked `TRANSCRIBED` below.
 *
 *  DETERMINISM
 *  -----------
 *  * No Math.random anywhere in the captured paths.
 *  * `ditherImage(..., 'Random')` seeds its LCG from `Date.now()`; the only
 *    Date.now override in this file pins that seed to a constant for the
 *    duration of that one call, and the fixture records the seed used.
 *  * localStorage starts empty; all cases run in a fixed order in one process.
 *  * Object keys are always built in a fixed order, so `JSON.stringify` output
 *    is byte-stable. Run the script twice and the sha256 values are identical.
 *  * Numbers are serialized with full `JSON.stringify` fidelity (shortest
 *    round-trip double representation) — no rounding, no formatting, so the
 *    Kotlin side can compare doubles exactly.
 *
 *  ENCODING CONVENTIONS (see README.md for the full table)
 *  * Bitmaps          → { width, height, palette, runs } palette-RLE over RGBA.
 *  * Binary images    → rows of "0"/"1" plus a sha256 of the raw byte array.
 *  * Polylines        → { pts: [[x,y], ...], closed }.
 *  * Non-finite input numbers are written as the strings "NaN" / "Infinity" /
 *    "-Infinity" / "-0" (JSON cannot carry them) and always carry the captured
 *    output alongside, so the Kotlin test knows what to feed in.
 */

import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// ===========================================================================
// 1. Platform shims — installed BEFORE any core module is imported
// ===========================================================================

/** Minimal ImageData replacement: the pure core only reads/constructs these fields. */
class ImageDataShim {
  data: Uint8ClampedArray
  width: number
  height: number

  constructor(dataOrWidth: Uint8ClampedArray | number, widthOrHeight: number, height?: number) {
    if (typeof dataOrWidth === 'number') {
      this.width = dataOrWidth
      this.height = widthOrHeight
      this.data = new Uint8ClampedArray(dataOrWidth * widthOrHeight * 4)
    } else {
      this.data = dataOrWidth
      this.width = widthOrHeight
      this.height = height as number
    }
  }
}

/** In-memory localStorage. Starts empty on every run (deterministic defaults). */
class MemoryStorage implements Storage {
  private store = new Map<string, string>()

  get length(): number {
    return this.store.size
  }
  clear(): void {
    this.store.clear()
  }
  getItem(key: string): string | null {
    const v = this.store.get(String(key))
    return v === undefined ? null : v
  }
  key(index: number): string | null {
    return Array.from(this.store.keys())[index] ?? null
  }
  removeItem(key: string): void {
    this.store.delete(String(key))
  }
  setItem(key: string, value: string): void {
    this.store.set(String(key), String(value))
  }
}

// ---------------------------------------------------------------------------
// Minimal XML DOM (DOMParser shim).
// Implements exactly the surface `convertSvgToGcode` uses, plus tag/#id/.class
// selector support so a future selector change cannot silently pass.
// ---------------------------------------------------------------------------

const XML_ENTITIES: Record<string, string> = {
  amp: '&',
  lt: '<',
  gt: '>',
  quot: '"',
  apos: "'"
}

function decodeXmlEntities(s: string): string {
  return s.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (whole, body: string) => {
    if (body[0] === '#') {
      const code =
        body[1] === 'x' || body[1] === 'X' ? Number.parseInt(body.slice(2), 16) : Number.parseInt(body.slice(1), 10)
      return Number.isFinite(code) ? String.fromCodePoint(code) : whole
    }
    const hit = XML_ENTITIES[body]
    return hit === undefined ? whole : hit
  })
}

class MiniElement {
  readonly nodeType = 1
  readonly tagName: string
  readonly childElements: MiniElement[] = []
  private readonly attrs: Map<string, string>

  constructor(tagName: string, attrs: Map<string, string>) {
    this.tagName = tagName
    this.attrs = attrs
  }

  get children(): MiniElement[] {
    return this.childElements
  }

  getAttribute(name: string): string | null {
    const v = this.attrs.get(name)
    return v === undefined ? null : v
  }

  getAttributeNames(): string[] {
    return Array.from(this.attrs.keys())
  }

  /** Only used to make the parsererror probe truthy; never inspected further. */
  private matches(selector: string): boolean {
    for (const part of selector.split(',')) {
      const sel = part.trim()
      if (!sel) continue
      if (sel.startsWith('#')) {
        if (this.getAttribute('id') === sel.slice(1)) return true
        continue
      }
      if (sel.startsWith('.')) {
        const cls = (this.getAttribute('class') ?? '').split(/\s+/)
        if (cls.includes(sel.slice(1))) return true
        continue
      }
      if (this.tagName === sel || this.tagName.toLowerCase() === sel.toLowerCase()) return true
    }
    return false
  }

  querySelector(selector: string): MiniElement | null {
    for (const child of this.childElements) {
      if (child.matches(selector)) return child
      const deep = child.querySelector(selector)
      if (deep) return deep
    }
    return null
  }

  querySelectorAll(selector: string): MiniElement[] {
    const out: MiniElement[] = []
    for (const child of this.childElements) {
      if (child.matches(selector)) out.push(child)
      out.push(...child.querySelectorAll(selector))
    }
    return out
  }
}

class MiniDocument {
  readonly documentElement: MiniElement | null
  readonly parseError: boolean

  constructor(documentElement: MiniElement | null, parseError: boolean) {
    this.documentElement = documentElement
    this.parseError = parseError
  }

  /** A failed parse yields a document holding a <parsererror>, like a browser. */
  querySelector(selector: string): MiniElement | null {
    if (this.parseError && selector.split(',').some((s) => s.trim().toLowerCase() === 'parsererror')) {
      return new MiniElement('parsererror', new Map())
    }
    return this.documentElement ? this.documentElement.querySelector(selector) : null
  }
}

interface XmlParseOutcome {
  root: MiniElement | null
  error: boolean
}

/** Parse a well-formed XML document. Anything malformed sets `error` (never throws). */
function parseXmlDocument(text: string): XmlParseOutcome {
  let i = text.charCodeAt(0) === 0xfeff ? 1 : 0
  const n = text.length
  const stack: MiniElement[] = []
  let root: MiniElement | null = null
  let error = false

  while (i < n) {
    const lt = text.indexOf('<', i)
    if (lt < 0) break // trailing text: ignored
    i = lt

    if (text.startsWith('<!--', i)) {
      const end = text.indexOf('-->', i + 4)
      if (end < 0) return { root, error: true }
      i = end + 3
      continue
    }
    if (text.startsWith('<![CDATA[', i)) {
      const end = text.indexOf(']]>', i + 9)
      if (end < 0) return { root, error: true }
      i = end + 3
      continue
    }
    if (text.startsWith('<?', i)) {
      const end = text.indexOf('?>', i + 2)
      if (end < 0) return { root, error: true }
      i = end + 2
      continue
    }
    if (text.startsWith('<!', i)) {
      // DOCTYPE / declaration; tolerate one level of internal subset
      let depth = 0
      let j = i + 2
      for (; j < n; j++) {
        const c = text[j]
        if (c === '[') depth++
        else if (c === ']') depth--
        else if (c === '>' && depth <= 0) break
      }
      if (j >= n) return { root, error: true }
      i = j + 1
      continue
    }
    if (text.startsWith('</', i)) {
      const end = text.indexOf('>', i + 2)
      if (end < 0) return { root, error: true }
      const name = text.slice(i + 2, end).trim()
      const top = stack.pop()
      if (!top || top.tagName !== name) return { root, error: true }
      i = end + 1
      continue
    }

    // start tag
    let j = i + 1
    let name = ''
    while (j < n && !/[\s/>]/.test(text[j])) name += text[j++]
    if (!name) return { root, error: true }

    const attrs = new Map<string, string>()
    let selfClosing = false
    for (;;) {
      while (j < n && /\s/.test(text[j])) j++
      if (j >= n) return { root, error: true }
      if (text[j] === '/') {
        if (text[j + 1] !== '>') return { root, error: true }
        selfClosing = true
        j += 2
        break
      }
      if (text[j] === '>') {
        j += 1
        break
      }
      let aname = ''
      while (j < n && !/[\s=/>]/.test(text[j])) aname += text[j++]
      if (!aname) return { root, error: true }
      while (j < n && /\s/.test(text[j])) j++
      if (text[j] !== '=') return { root, error: true }
      j++
      while (j < n && /\s/.test(text[j])) j++
      const quote = text[j]
      if (quote !== '"' && quote !== "'") return { root, error: true }
      const end = text.indexOf(quote, j + 1)
      if (end < 0) return { root, error: true }
      attrs.set(aname, decodeXmlEntities(text.slice(j + 1, end)))
      j = end + 1
    }

    const el = new MiniElement(name, attrs)
    if (stack.length > 0) stack[stack.length - 1].childElements.push(el)
    else if (root === null) root = el
    else error = true // more than one root element
    if (!selfClosing) stack.push(el)
    i = j
  }

  if (stack.length > 0) error = true
  if (root === null) error = true
  return { root, error }
}

class MiniDOMParser {
  parseFromString(text: string, _mimeType?: string): MiniDocument {
    const { root, error } = parseXmlDocument(text)
    return new MiniDocument(root, error)
  }
}

// ---- install shims ----
const g = globalThis as unknown as Record<string, unknown>
g.ImageData = ImageDataShim
g.localStorage = new MemoryStorage()
g.DOMParser = MiniDOMParser

export { ImageDataShim, MiniDOMParser, MemoryStorage, parseXmlDocument }

// ===========================================================================
// 2. Core imports (dynamic so the shims above are installed first)
// ===========================================================================

const Potrace = await import('../../src/core/vector/Potrace')
const Centerline = await import('../../src/core/vector/Centerline')
const StrokeAnalysis = await import('../../src/core/vector/StrokeAnalysis')
const Dithering = await import('../../src/core/raster/dithering')
const Hershey = await import('../../src/core/text/Hershey')
const HersheyData = await import('../../src/core/text/hersheyData')
const Paths = await import('../../src/core/vector/Paths')
const SvgToGcode = await import('../../src/core/vector/SvgToGcode')
const GrblFile = await import('../../src/core/gcode/GrblFile')
const GrblCommandModule = await import('../../src/core/grbl/GrblCommand')
const CsvData = await import('../../src/core/grbl/csvData')
const GrblTypes = await import('../../src/core/grbl/types')
const SettingsPreset = await import('../../src/core/grbl/SettingsPreset')
const DeviceProfileModule = await import('../../src/core/grbl/DeviceProfile')
const GrblConfigModule = await import('../../src/core/grbl/GrblConfig')
const KeepAlive = await import('../../src/core/native/KeepAlive')
const ImageTransform = await import('../../src/core/raster/ImageTransform')
const ImageVector = await import('../../src/core/vector/ImageVector')
const SmartVector = await import('../../src/core/vector/SmartVector')
const RasterConverter = await import('../../src/core/raster/RasterConverter')

import type { Polyline, Pt } from '../../src/core/vector/Paths'
import type { PotraceImage, PotraceOptions } from '../../src/core/vector/Potrace'
import type { CenterlineOptions } from '../../src/core/vector/Centerline'
import type { DitheringMode } from '../../src/core/raster/dithering'
import type { HersheyOptions } from '../../src/core/text/Hershey'
import type { PolylineGcodeOptions } from '../../src/core/vector/Paths'
import type { SvgConvertOptions } from '../../src/core/vector/SvgToGcode'
import type { GrblCommand } from '../../src/core/grbl/GrblCommand'

const {
  potraceTrace
} = Potrace
const { centerlineTrace, skeletonize } = Centerline
const { analyzeStrokes, decideVectorMode } = StrokeAnalysis
const { ditherImage, DITHERING_MODES, DITHERING_LABELS } = Dithering
const { textToGcode } = Hershey
const { HERSHEY_HORIZONTAL, HERSHEY_VERTICAL, HERSHEY_SPACE_BETWEEN } = HersheyData
const { polylinesToGcode } = Paths
const { convertSvgToGcode, parseLength, parseViewBox } = SvgToGcode
const { parseGcode, analyze } = GrblFile
const { GrblCommand: GrblCommandCtor, GrblMessage, MessageType, Element, formatDecimal, installDecoders, GrblConfSTIsSetConf, CommandStatus } =
  GrblCommandModule
const { SETTING_CODES, ALARM_CODES, ERROR_CODES } = CsvData
const { GrblVersionInfo } = GrblTypes
const { parseSettingsPreset } = SettingsPreset
const {
  builtinProfiles,
  fitSizeToTravel,
  checkGcodeWithinTravel,
  listSavedProfiles,
  listKnownDeviceIds,
  isDeviceKnown
} = DeviceProfileModule
const { AppSettings, DEFAULT_SETTINGS } = GrblConfigModule
const { truncateName, percentOf, formatText, NOTIFICATION_TITLE } = KeepAlive
const { grayScale, whitenize, threshold, dither, flipVertical, testGrayScale, Formula, FORMULA_LABELS } = ImageTransform
const {
  convertImageVector,
  DEFAULT_IMAGE_VECTOR_OPTIONS,
  VECTOR_TOOL_LABELS,
  hasNonAscii
} = ImageVector
const { convertImageVectorSmart, decideTextEngine, hasNonHersheyChar } = SmartVector
const { convertImageToGcode, DEFAULT_RASTER_OPTIONS, DIRECTION_LABELS } = RasterConverter

// ===========================================================================
// 3. TRANSCRIBED pure helpers from src/core/grbl/GrblCore.ts
//
// GrblCore.ts cannot be imported in Node (it constructs the Capacitor serial
// transport at module load). These two functions are copied verbatim from it so
// the *fixture inputs* can express the version context that selects the
// csvData.ts decoder group. They are oracle-adjacent transcription, not
// re-implementation: the lookups themselves run through the real
// `installDecoders` + real `csvData.ts` tables.
// ===========================================================================

/** mirrors GrblCore.parseVersionBanner — "Grbl 1.1f ['$' for help]" */
function parseVersionBanner(line: string): { major: number; minor: number; build: string } | null {
  const m = /Grbl\s+(\d+)\.(\d+)([a-zA-Z])?/i.exec(line)
  if (!m) return null
  return { major: Number.parseInt(m[1], 10), minor: Number.parseInt(m[2], 10), build: m[3] ?? '' }
}

/** mirrors GrblCore.parseVerMessage — "[VER:1.1f.20170801:Vendor:1.7]" */
function parseVerMessage(line: string): {
  major: number
  minor: number
  build: string
  vendorInfo: string | null
  vendorVersion: string | null
} | null {
  const body = line.slice(1, -1)
  const parts = body.split(':')
  if (parts.length < 2) return null
  const ver = parts[1]
  const m = /(\d+)\.(\d+)([a-zA-Z])?/.exec(ver)
  if (!m) return null
  const build = ver.substring(m[0].length).replace(/^[.]/, '')
  return {
    major: Number.parseInt(m[1], 10),
    minor: Number.parseInt(m[2], 10),
    build: build || (m[3] ?? ''),
    vendorInfo: parts[2] || null,
    vendorVersion: parts[3] || null
  }
}

/** mirrors GrblCore.lookupCode's group selection (decoder table picker) */
function lookupGroupName(version: InstanceType<typeof GrblVersionInfo> | null): string {
  let groupName = 'v1.1'
  if (version) {
    if (version.isOrtur && version.isHAL) groupName = 'ortur.GrblHal'
    else if (version.isOrtur && version.orturFWVersionNumber >= 170) groupName = 'ortur.v1.7.x'
    else if (version.isOrtur && version.orturFWVersionNumber >= 150) groupName = 'ortur.v1.5.x'
    else if (version.isOrtur) groupName = 'ortur.v1.4.x'
    else if (version.isLonger && version.vendorInfo === 'NanoDuo') groupName = 'longer.nanoduo'
    else groupName = `v${version.major}.${version.minor}`
  }
  return groupName
}

/** mirrors GrblCore.lookupCode (table → groupName → key → index, with v1.1/standard fallbacks) */
function lookupCode(
  group: Record<string, Record<string, string[]>>,
  key: string,
  idx: number,
  version: InstanceType<typeof GrblVersionInfo> | null
): string | null {
  const groupName = lookupGroupName(version)
  const table = group[groupName] ?? group['v1.1'] ?? group['standard']
  if (!table) return null
  const entry = table[key]
  return entry && entry[idx] !== undefined ? entry[idx] : null
}

// ===========================================================================
// 4. Serialization helpers
// ===========================================================================

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = resolve(HERE, '..', '..')
const OUT_DIR = resolve(REPO_ROOT, 'core', 'src', 'commonTest', 'resources', 'golden')

const GENERATOR_REL = 'tools/golden/generate.ts'
const GENERATOR_COMMAND = 'npx --yes tsx tools/golden/generate.ts'

type JsonValue = null | boolean | number | string | JsonValue[] | { [k: string]: JsonValue }

function sha256Hex(bytes: Uint8Array | string): string {
  return createHash('sha256').update(bytes).digest('hex')
}

/** Textual token for a numeric input, keeping non-finite / negative-zero information. */
function numToken(v: number): string {
  if (Number.isNaN(v)) return 'NaN'
  if (v === Number.POSITIVE_INFINITY) return 'Infinity'
  if (v === Number.NEGATIVE_INFINITY) return '-Infinity'
  if (Object.is(v, -0)) return '-0'
  return String(v)
}

/** Fails loudly rather than letting JSON.stringify silently turn NaN into null. */
function assertJsonSafe(value: unknown, path = '$', negativeZeros: string[] = []): string[] {
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new Error(`non-finite number at ${path}: ${String(value)}`)
    if (Object.is(value, -0)) negativeZeros.push(path)
    return negativeZeros
  }
  if (Array.isArray(value)) {
    value.forEach((v, idx) => assertJsonSafe(v, `${path}[${idx}]`, negativeZeros))
    return negativeZeros
  }
  if (value && typeof value === 'object') {
    for (const [k, v] of Object.entries(value)) assertJsonSafe(v, `${path}.${k}`, negativeZeros)
  }
  return negativeZeros
}

/** JSON-safe number: finite values stay numbers, NaN/±Infinity become name strings. */
function numberOrToken(v: number): JsonValue {
  return Number.isFinite(v) ? v : numToken(v)
}

interface EncodedImage {
  width: number
  height: number
  layout: 'RGBA'
  encoding: 'palette-rle'
  palette: number[][]
  runs: number[][]
  sha256: string
}

/** Palette + run-length encoding of an RGBA bitmap (exact, compact, easy in Kotlin). */
function encodeImage(img: { data: Uint8ClampedArray; width: number; height: number }): EncodedImage {
  const palette: number[][] = []
  const index = new Map<string, number>()
  const runs: number[][] = []
  let prev = -1
  const px = img.width * img.height
  for (let i = 0; i < px; i++) {
    const key = `${img.data[4 * i]},${img.data[4 * i + 1]},${img.data[4 * i + 2]},${img.data[4 * i + 3]}`
    let idx = index.get(key)
    if (idx === undefined) {
      idx = palette.length
      index.set(key, idx)
      palette.push([img.data[4 * i], img.data[4 * i + 1], img.data[4 * i + 2], img.data[4 * i + 3]])
    }
    if (idx === prev) runs[runs.length - 1][0] += 1
    else {
      runs.push([1, idx])
      prev = idx
    }
  }
  return {
    width: img.width,
    height: img.height,
    layout: 'RGBA',
    encoding: 'palette-rle',
    palette,
    runs,
    sha256: sha256Hex(new Uint8Array(img.data.buffer.slice(0)))
  }
}

/** rows of "0"/"1" for a binary map (1 = set) */
function binaryRows(data: Uint8Array | Uint8ClampedArray, w: number, h: number): string[] {
  const rows: string[] = []
  for (let y = 0; y < h; y++) {
    let row = ''
    for (let x = 0; x < w; x++) row += data[y * w + x] ? '1' : '0'
    rows.push(row)
  }
  return rows
}

function polylineJson(paths: Polyline[]): { pts: number[][]; closed: boolean }[] {
  return paths.map((p) => ({
    pts: p.pts.map((q) => [q.x, q.y] as number[]),
    closed: p.closed === true
  }))
}

interface CaseOutcome {
  name: string
  modules: string[]
  input: string
  entryCount: number
  entryCounts: Record<string, number>
  result: JsonValue
}

// ===========================================================================
// 5. Synthetic bitmaps (all drawn from pure arithmetic — no randomness)
// ===========================================================================

function blankImage(w: number, h: number, rgba: [number, number, number, number]): ImageDataShim {
  const data = new Uint8ClampedArray(w * h * 4)
  for (let i = 0; i < w * h; i++) {
    data[4 * i] = rgba[0]
    data[4 * i + 1] = rgba[1]
    data[4 * i + 2] = rgba[2]
    data[4 * i + 3] = rgba[3]
  }
  return new ImageDataShim(data, w, h)
}

/**
 * 24×16 合成图，覆盖图像变换需要的每一种像素形态：
 *  * 纯黑 / 纯白 / 饱和红绿蓝；
 *  * 接近白（`250,251,249`）—— 用来区分 `whitenize` 的严格不等号边界；
 *  * 完全透明（alpha 0，RGB 仍非零）—— 用来验证"先合成到白底"的语义；
 *  * 半透明（alpha 128）—— 阈值化时必须按 `r*a + 255*(1-a)` 合成；
 *  * 灰度与彩色混排 —— `testGrayScale` 应当返回 false。
 */
function imageTransformSampleImage(): ImageDataShim {
  const w = 24
  const h = 16
  const img = blankImage(w, h, WHITE)
  const palette: [number, number, number, number][] = [
    [0, 0, 0, 255], // pure black
    [255, 255, 255, 255], // pure white
    [255, 0, 0, 255], // saturated red
    [0, 255, 0, 255], // saturated green
    [0, 0, 255, 255], // saturated blue
    [250, 251, 249, 255], // near-white (inside the whitenize window)
    [10, 20, 30, 0], // fully transparent, non-zero RGB
    [200, 100, 50, 128], // half transparent
    [128, 128, 128, 255], // mid gray
    [7, 7, 7, 255] // near-black
  ]
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const idx = (x * 7 + y * 5 + ((x * y) % 11)) % palette.length
      paint(img, x, y, palette[idx])
    }
  }
  return img
}

function paint(img: ImageDataShim, x: number, y: number, rgba: [number, number, number, number]): void {
  const i = (y * img.width + x) * 4
  img.data[i] = rgba[0]
  img.data[i + 1] = rgba[1]
  img.data[i + 2] = rgba[2]
  img.data[i + 3] = rgba[3]
}

const BLACK: [number, number, number, number] = [0, 0, 0, 255]
const WHITE: [number, number, number, number] = [255, 255, 255, 255]

/** 64×64: white background, filled black circle, black rectangle, hollow black ring. */
function potraceSampleImage(): ImageDataShim {
  const w = 64
  const h = 64
  const img = blankImage(w, h, WHITE)
  const circle = { cx: 18, cy: 18, r: 11 }
  const ring = { cx: 40, cy: 44, rOuter: 12, rInner: 7 }
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const px = x + 0.5
      const py = y + 0.5
      const dCircle = (px - circle.cx) ** 2 + (py - circle.cy) ** 2
      const inCircle = dCircle <= circle.r * circle.r
      const inRect = px >= 36 && px <= 60 && py >= 6 && py <= 22
      const dRing = (px - ring.cx) ** 2 + (py - ring.cy) ** 2
      const inRing = dRing <= ring.rOuter * ring.rOuter && dRing >= ring.rInner * ring.rInner
      if (inCircle || inRect || inRing) paint(img, x, y, BLACK)
    }
  }
  return img
}

/**
 * 128×128: 与 `potraceSampleImage` 同形的黑/白图案，但**额外加入 alpha 变化**
 * （半透明矩形 + 完全透明带非零 RGB 的矩形），用来覆盖 `ImageVector.buildPreview`
 * 的"先合成到白底再比较阈值"语义。两个图形相距足够远，阈值扫描不会互相干扰。
 */
function imageVectorSampleImage(): ImageDataShim {
  const w = 128
  const h = 128
  const img = blankImage(w, h, WHITE)
  const circle = { cx: 34, cy: 34, r: 24 }
  const ring = { cx: 92, cy: 92, rOuter: 24, rInner: 14 }
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const px = x + 0.5
      const py = y + 0.5
      const dCircle = (px - circle.cx) ** 2 + (py - circle.cy) ** 2
      const inCircle = dCircle <= circle.r * circle.r
      const inRect = px >= 70 && px <= 116 && py >= 10 && py <= 44
      const dRing = (px - ring.cx) ** 2 + (py - ring.cy) ** 2
      const inRing = dRing <= ring.rOuter * ring.rOuter && dRing >= ring.rInner * ring.rInner
      if (inCircle) paint(img, x, y, BLACK)
      else if (inRect) paint(img, x, y, [0, 0, 0, 128]) // 半透明黑：合成到白底后是灰
      else if (inRing) paint(img, x, y, BLACK)
      else if (px >= 10 && px <= 60 && py >= 84 && py <= 112) paint(img, x, y, [10, 20, 30, 0]) // 全透明
    }
  }
  return img
}

/** 40×40: 3-px-wide plus sign, 3-px-wide diagonal stroke, 1-px stub (tests spur pruning). */
function centerlineSampleImage(): ImageDataShim {
  const w = 40
  const h = 40
  const img = blankImage(w, h, WHITE)
  const distToSegment = (px: number, py: number, ax: number, ay: number, bx: number, by: number): number => {
    const dx = bx - ax
    const dy = by - ay
    const len2 = dx * dx + dy * dy
    let t = len2 === 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2
    t = Math.max(0, Math.min(1, t))
    const cx = ax + t * dx
    const cy = ay + t * dy
    return Math.hypot(px - cx, py - cy)
  }
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const px = x + 0.5
      const py = y + 0.5
      const inPlus = (px >= 18 && px <= 21 && py >= 5 && py <= 34) || (py >= 18 && py <= 21 && px >= 5 && px <= 34)
      const onDiagonal = distToSegment(px, py, 6, 33, 33, 6) <= 1.5
      const onStub = distToSegment(px, py, 25, 25, 25, 31) <= 0.5
      if (inPlus || onDiagonal || onStub) paint(img, x, y, BLACK)
    }
  }
  return img
}

/** Stroke-analysis samples: thin line art, medium strokes, solid blob, dense ink, lone pixel. */
function strokeSampleImages(): { label: string; image: ImageDataShim }[] {
  const size = 64

  const thin = blankImage(size, size, WHITE)
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      if (x % 8 === 0 || y % 8 === 0) paint(thin, x, y, BLACK)
    }
  }

  const medium = blankImage(size, size, WHITE)
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const onVertical = x >= 12 && x <= 15 && y >= 6 && y <= 57
      const onHorizontal = y >= 40 && y <= 43 && x >= 6 && x <= 57
      if (onVertical || onHorizontal) paint(medium, x, y, BLACK)
    }
  }

  const solid = blankImage(size, size, WHITE)
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      if ((x + 0.5 - 32) ** 2 + (y + 0.5 - 32) ** 2 <= 22 * 22) paint(solid, x, y, BLACK)
    }
  }

  const dense = blankImage(size, size, WHITE)
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      if (y < 39 && x >= 2 && x < 62) paint(dense, x, y, BLACK)
    }
  }

  const dot = blankImage(size, size, WHITE)
  paint(dot, 31, 31, BLACK)

  return [
    { label: 'thin-lines (1 px grid, expect Centerline)', image: thin },
    { label: 'medium-strokes (4 px bars, expect Outline non-fallback)', image: medium },
    { label: 'solid-blob (r=22 disc, thinning cannot converge)', image: solid },
    { label: 'dense-ink (60.9% coverage, exceeds maxInkRatio)', image: dense },
    { label: 'single-pixel (degenerate skeleton)', image: dot }
  ]
}

/** 32×32 diagonal grayscale ramp modulated by an 8-px block pattern (0..255). */
function ditherSampleImage(): ImageDataShim {
  const w = 32
  const h = 32
  const img = new ImageDataShim(w, h)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const t = (x + y) / (w + h - 2)
      const block = ((x >> 3) + (y >> 3)) & 1
      const gray = Math.round(255 * (0.75 * t + 0.25 * block))
      paint(img, x, y, [gray, gray, gray, 255])
    }
  }
  return img
}

// ===========================================================================
// 6. Cases
// ===========================================================================

const POTRACE_VARIANTS: { name: string; options: PotraceOptions }[] = [
  { name: 'default', options: {} },
  {
    name: 'tuned',
    options: {
      threshold: 200,
      turdSize: 0,
      alphaMax: 0.8,
      optTolerance: 0.05,
      curveOptimizing: false,
      flattenTolerance: 0.5
    }
  },
  { name: 'inverted', options: { invert: true } },
  { name: 'coarse-flatten', options: { flattenTolerance: 1.5, turdSize: 8 } }
]

function casePotrace(): CaseOutcome {
  const image = potraceSampleImage()
  const variants = POTRACE_VARIANTS.map((v) => {
    const polylines = potraceTrace(image, v.options)
    return {
      name: v.name,
      options: v.options as JsonValue,
      polylines: polylineJson(polylines) as unknown as JsonValue,
      polylineCount: polylines.length,
      totalPoints: polylines.reduce((a, p) => a + p.pts.length, 0),
      pointsPerPolyline: polylines.map((p) => p.pts.length),
      closedFlags: polylines.map((p) => p.closed === true)
    }
  })
  const totalPoints = variants.reduce((a, v) => a + v.totalPoints, 0)
  const totalPolylines = variants.reduce((a, v) => a + v.polylineCount, 0)
  return {
    name: 'potrace',
    modules: ['src/core/vector/Potrace.ts', 'src/core/vector/Paths.ts'],
    input:
      'synthetic 64x64 RGBA bitmap (white bg + filled black circle r=11 at (18,18) + black rect x36..60/y6..22 + hollow black ring rOuter=12/rInner=7 at (40,44)); traced with 4 option sets',
    entryCount: totalPolylines,
    entryCounts: { variants: variants.length, polylines: totalPolylines, points: totalPoints },
    result: { image: encodeImage(image) as unknown as JsonValue, variants: variants as unknown as JsonValue }
  }
}

function caseCenterline(): CaseOutcome {
  const image = centerlineSampleImage()
  const defaultPaths = centerlineTrace(image, {})
  const closedPaths = centerlineTrace(image, { closed: true, minBranchPx: 2, simplifyTolerance: 0.5, maxIterations: 60 })

  const skDefault = skeletonize(image, {})
  const skIter2 = skeletonize(image, { maxIterations: 2 })
  const skeletonInfo = (sk: ReturnType<typeof skeletonize>): JsonValue => ({
    width: sk.width,
    height: sk.height,
    inkArea: sk.inkArea,
    converged: sk.converged,
    skeletonPixels: Array.from(sk.data).reduce((a, b) => a + (b ? 1 : 0), 0),
    rows: binaryRows(sk.data, sk.width, sk.height),
    rawSha256: sha256Hex(new Uint8Array(sk.data.buffer.slice(0)))
  })

  return {
    name: 'centerline',
    modules: ['src/core/vector/Centerline.ts', 'src/core/vector/Paths.ts'],
    input:
      'synthetic 40x40 RGBA bitmap (white bg + 3-px plus sign + 3-px diagonal stroke + 1-px stub) for centerlineTrace and skeletonize',
    entryCount: defaultPaths.length + closedPaths.length,
    entryCounts: {
      defaultPolylines: defaultPaths.length,
      closedPolylines: closedPaths.length,
      defaultPoints: defaultPaths.reduce((a, p) => a + p.pts.length, 0),
      closedPoints: closedPaths.reduce((a, p) => a + p.pts.length, 0)
    },
    result: {
      image: encodeImage(image) as unknown as JsonValue,
      variants: [
        {
          name: 'default',
          options: {} as JsonValue,
          polylines: polylineJson(defaultPaths) as unknown as JsonValue,
          polylineCount: defaultPaths.length,
          totalPoints: defaultPaths.reduce((a, p) => a + p.pts.length, 0),
          closedFlags: defaultPaths.map((p) => p.closed === true)
        },
        {
          name: 'closed-minBranch2-tol0.5',
          options: { closed: true, minBranchPx: 2, simplifyTolerance: 0.5, maxIterations: 60 } as JsonValue,
          polylines: polylineJson(closedPaths) as unknown as JsonValue,
          polylineCount: closedPaths.length,
          totalPoints: closedPaths.reduce((a, p) => a + p.pts.length, 0),
          closedFlags: closedPaths.map((p) => p.closed === true)
        }
      ] as unknown as JsonValue,
      skeletons: [
        { name: 'default (maxIterations 60)', options: {} as JsonValue, ...(skeletonInfo(skDefault) as object) },
        { name: 'maxIterations 2', options: { maxIterations: 2 } as JsonValue, ...(skeletonInfo(skIter2) as object) }
      ] as unknown as JsonValue
    }
  }
}

function caseStrokeAnalysis(): CaseOutcome {
  const samples = strokeSampleImages()
  const perSample = samples.map((s) => {
    const a = analyzeStrokes(s.image, {})
    const d = decideVectorMode(s.image, {})
    const aStrict = analyzeStrokes(s.image, { strokeWidthThresholdPct: 6, maxIterations: 12 })
    const dStrict = decideVectorMode(s.image, { strokeWidthThresholdPct: 6, maxInkRatio: 0.2, maxIterations: 12 })
    return {
      label: s.label,
      image: encodeImage(s.image) as unknown as JsonValue,
      analyzeDefault: a as unknown as JsonValue,
      decideDefault: d as unknown as JsonValue,
      analyzeThreshold6Iter12: aStrict as unknown as JsonValue,
      decideStrictThreshold6Iter12maxInkRatio0p2: dStrict as unknown as JsonValue
    }
  })
  return {
    name: 'stroke-analysis',
    modules: ['src/core/vector/StrokeAnalysis.ts', 'src/core/vector/Centerline.ts'],
    input:
      '5 synthetic 64x64 bitmaps (1-px grid, 4-px bars, r=22 solid disc, 60.9% dense ink, single pixel); each run through analyzeStrokes + decideVectorMode with default and strict options',
    entryCount: samples.length,
    entryCounts: {
      samples: samples.length,
      analyses: samples.length * 2,
      decisions: samples.length * 2,
      centerlineDecisions: perSample.filter((p) => (p.decideDefault as { mode: string }).mode === 'Centerline').length,
      outlineDecisions: perSample.filter((p) => (p.decideDefault as { mode: string }).mode === 'Outline').length,
      defaultFallbacks: perSample.filter((p) => (p.decideDefault as { fallback: boolean }).fallback).length
    },
    result: { samples: perSample as unknown as JsonValue }
  }
}

function caseDithering(): CaseOutcome {
  const image = ditherSampleImage()
  const base = new Uint8ClampedArray(image.data)
  const width = image.width
  const height = image.height
  const seed = 1700000000000

  const realNow = Date.now
  const modes = DITHERING_MODES.map((mode) => {
    const data = new Uint8ClampedArray(base)
    let usedDateNow: number | null = null
    if (mode === ('Random' as DitheringMode)) {
      usedDateNow = seed
      Date.now = () => seed
    }
    try {
      ditherImage(data, width, height, mode)
    } finally {
      Date.now = realNow
    }

    // Every processed pixel is written as pure 0/255; error diffusion only ever
    // touches pixels that have not been visited yet, so the result is binary.
    let binary = true
    for (let i = 0; i < width * height; i++) {
      const r = data[4 * i]
      const gg = data[4 * i + 1]
      const b = data[4 * i + 2]
      if ((r !== 0 && r !== 255) || (gg !== 0 && gg !== 255) || (b !== 0 && b !== 255)) binary = false
      if (data[4 * i + 3] !== 255) binary = false
    }
    if (!binary) throw new Error(`ditherImage(${mode}) produced non-binary RGBA output; fixture needs raw arrays`)

    const rows: string[] = []
    for (let y = 0; y < height; y++) {
      let row = ''
      for (let x = 0; x < width; x++) row += data[(y * width + x) * 4] === 255 ? '#' : '.'
      rows.push(row)
    }
    let white = 0
    for (let i = 0; i < width * height; i++) if (data[4 * i] === 255) white++

    return {
      mode,
      label: DITHERING_LABELS[mode],
      dateNowOverride: usedDateNow,
      whitePixels: white,
      blackPixels: width * height - white,
      outputRows: rows,
      outputRgbaSha256: sha256Hex(new Uint8Array(data.buffer.slice(0)))
    }
  })

  return {
    name: 'dithering',
    modules: ['src/core/raster/dithering.ts'],
    input:
      'fixed 32x32 grayscale image (diagonal ramp 0..255 modulated by an 8-px block checker, alpha 255) dithered once per mode in DITHERING_MODES order; Random uses a pinned Date.now seed',
    entryCount: modes.length,
    entryCounts: { modes: modes.length, pixelsPerMode: width * height, totalDitheredPixels: modes.length * width * height },
    result: {
      ditheringModes: DITHERING_MODES as unknown as JsonValue,
      ditheringLabels: DITHERING_LABELS as unknown as JsonValue,
      randomSeed: seed,
      outputLegend: '# = 255 (white), . = 0 (black)',
      input: encodeImage(image) as unknown as JsonValue,
      modes: modes as unknown as JsonValue
    }
  }
}

const HERSHEY_VARIANTS: { name: string; options: HersheyOptions }[] = [
  {
    name: 'iGRBL-horizontal-pwm',
    options: {
      text: 'iGRBL',
      orientation: 'horizontal',
      sizeMm: 5,
      bold: false,
      markSpeed: 1000,
      laserOn: 'M3',
      laserOff: 'M5',
      pwm: true,
      maxPower: 1000
    }
  },
  {
    name: 'A1-horizontal-bold-nopwm-offset',
    options: {
      text: 'A1',
      orientation: 'horizontal',
      sizeMm: 10,
      bold: true,
      offsetX: 10,
      offsetY: 20,
      markSpeed: 500,
      laserOn: 'M4',
      laserOff: 'M5',
      pwm: false,
      maxPower: 255
    }
  },
  {
    name: 'multiline-horizontal-spacing',
    options: {
      text: 'iGRBL\nA1\n- 2.5mm',
      orientation: 'horizontal',
      sizeMm: 4,
      bold: false,
      lineSpacing: 2,
      markSpeed: 800,
      laserOn: 'M3',
      laserOff: 'M5',
      pwm: true,
      maxPower: 800
    }
  },
  {
    name: 'multiline-vertical-pwm',
    options: {
      text: 'iGRBL\nA1',
      orientation: 'vertical',
      sizeMm: 3.5,
      bold: false,
      markSpeed: 600,
      laserOn: ' M4 ',
      laserOff: ' M5 ',
      pwm: true,
      maxPower: 300
    }
  },
  {
    name: 'non-ascii-skipped-and-empty-laserOn',
    options: {
      text: 'iGRBL 中文 123',
      orientation: 'horizontal',
      sizeMm: 6,
      bold: true,
      markSpeed: 700,
      laserOn: '',
      laserOff: '',
      pwm: true,
      maxPower: 450
    }
  }
]

function caseHershey(): CaseOutcome {
  const variants = HERSHEY_VARIANTS.map((v) => {
    const r = textToGcode(v.options)
    return {
      name: v.name,
      options: v.options as unknown as JsonValue,
      lineCount: r.lines.length,
      lines: r.lines as unknown as JsonValue,
      widthMm: r.widthMm,
      heightMm: r.heightMm
    }
  })
  return {
    name: 'hershey',
    modules: ['src/core/text/Hershey.ts', 'src/core/text/hersheyData.ts'],
    input: '5 fixed option sets: single-line, bold+offset+no-PWM, multi-line, vertical, and non-ASCII/empty laserOn',
    entryCount: variants.length,
    entryCounts: {
      variants: variants.length,
      gcodeLines: variants.reduce((a, v) => a + v.lineCount, 0),
      horizontalGlyphs: HERSHEY_HORIZONTAL.length,
      verticalGlyphs: HERSHEY_VERTICAL.length
    },
    result: {
      fontTable: {
        spaceBetween: HERSHEY_SPACE_BETWEEN,
        horizontalCount: HERSHEY_HORIZONTAL.length,
        verticalCount: HERSHEY_VERTICAL.length,
        horizontalSha256: sha256Hex(JSON.stringify(HERSHEY_HORIZONTAL)),
        verticalSha256: sha256Hex(JSON.stringify(HERSHEY_VERTICAL))
      },
      variants: variants as unknown as JsonValue
    }
  }
}

/** Fixed hand-authored polylines used by the G-code emission case. */
function handAuthoredPolylines(): Polyline[] {
  return [
    // closed square: first point repeated at the end (must be de-duplicated in mm space)
    {
      pts: [
        { x: 0, y: 0 },
        { x: 10, y: 0 },
        { x: 10, y: 10 },
        { x: 0, y: 10 },
        { x: 0, y: 0 }
      ],
      closed: true
    },
    // two points that collapse onto the same mm position at pixelSizeMm 0.1 → path dropped
    { pts: [{ x: 20, y: 20 }, { x: 20.000001, y: 20.000001 }], closed: false },
    // single point → skipped (needs >= 2 points)
    { pts: [{ x: 30, y: 30 }], closed: false },
    // simple open segment, forces an F word on the first G1
    { pts: [{ x: 5, y: 2 }, { x: 5, y: 8 }, { x: 12, y: 8 }], closed: false },
    // empty polyline → skipped
    { pts: [], closed: false }
  ]
}

const POLYLINES_GCODE_OPTIONS: { name: string; options: PolylineGcodeOptions }[] = [
  {
    name: 'representative',
    options: {
      pixelSizeMm: 0.1,
      offsetX: 5,
      offsetY: 7,
      markSpeed: 800,
      minPower: 0,
      maxPower: 1000,
      laserPower: 800,
      laserOn: 'M4',
      laserOff: 'M5',
      pwm: true,
      header: 'G90\nG0 X0 Y0',
      footer: 'M5\nG0 X0 Y0',
      decimals: 3,
      optimize: true,
      travelSpeed: 3000,
      travelCommand: 'G0'
    }
  },
  {
    name: 'flipY-decimals1-nooptimize-nopwm',
    options: {
      pixelSizeMm: 0.25,
      offsetX: 0,
      offsetY: 100,
      markSpeed: 1200,
      minPower: 0,
      maxPower: 255,
      laserOn: 'M3',
      laserOff: 'M5',
      pwm: false,
      flipY: true,
      decimals: 1,
      optimize: false,
      travelCommand: 'G1'
    }
  }
]

function casePolylinesToGcode(): CaseOutcome {
  const potracePolylines = potraceTrace(potraceSampleImage(), {})
  const hand = handAuthoredPolylines()
  const variants = POLYLINES_GCODE_OPTIONS.map((v) => {
    const potraceResult = polylinesToGcode(potracePolylines, v.options)
    const handResult = polylinesToGcode(hand, v.options)
    return {
      name: v.name,
      options: v.options as unknown as JsonValue,
      potraceInput: {
        lines: potraceResult.lines as unknown as JsonValue,
        lineCount: potraceResult.lines.length,
        pathCount: potraceResult.pathCount,
        lengthMm: potraceResult.lengthMm
      },
      handAuthoredInput: {
        lines: handResult.lines as unknown as JsonValue,
        lineCount: handResult.lines.length,
        pathCount: handResult.pathCount,
        lengthMm: handResult.lengthMm
      }
    }
  })
  return {
    name: 'polylines-to-gcode',
    modules: ['src/core/vector/Paths.ts', 'src/core/vector/Potrace.ts'],
    input:
      '2 option sets over (a) the potrace case-1 default polylines and (b) a fixed hand-authored 5-path list (closed square with duplicate endpoint, sub-epsilon duplicate pair, single point, open 3-point segment, empty path)',
    entryCount: variants.length,
    entryCounts: {
      variants: variants.length,
      potracePathsIn: potracePolylines.length,
      handPathsIn: hand.length,
      emittedLines: variants.reduce((a, v) => a + v.potraceInput.lineCount + v.handAuthoredInput.lineCount, 0)
    },
    result: { polylinesIn: polylineJson(potracePolylines) as unknown as JsonValue, variants: variants as unknown as JsonValue }
  }
}

const SVG_SAMPLE = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<!-- mixed shapes: rect, circle, quadratic+cubic bezier, transform, viewBox, mm/px/pt lengths -->',
  '<svg xmlns="http://www.w3.org/2000/svg" width="40mm" height="30mm" viewBox="0 0 80 60">',
  '  <rect x="5" y="5" width="30" height="20"/>',
  '  <rect x="5" y="5" width="30" height="20" transform="translate(2,3) scale(1.5)"/>',
  '  <circle cx="60" cy="15" r="8"/>',
  '  <path d="M10 40 Q 20 25 30 40 C 35 45 45 55 50 40"/>',
  '  <path d="M0 0 q 5 5 10 0 t 10 0"/>',
  '  <line x1="0" y1="0" x2="10mm" y2="5pt"/>',
  '  <polyline points="1,2 3,4 5,6"/>',
  '  <polygon points="70,50 75,55 65,58"/>',
  '  <g transform="translate(1,2) scale(0.5)">',
  '    <rect x="1" y="1" width="8" height="6" rx="1.5"/>',
  '    <path d="M2 2 L 6 2" stroke="black"/>',
  '  </g>',
  '  <g>',
  '    <defs><rect x="0" y="0" width="1" height="1"/></defs>',
  '  </g>',
  '</svg>'
].join('\n')

const SVG_OPTIONS: { name: string; options: SvgConvertOptions }[] = [
  {
    name: 'default-pwm',
    options: {
      targetWidthMm: 50,
      tolerance: 0.1,
      markSpeed: 1000,
      laserOn: 'M4',
      laserOff: 'M5',
      pwm: true,
      maxPower: 1000,
      offsetX: 0,
      offsetY: 0
    }
  },
  {
    name: 'stretch-travel-nopwm-header-footer',
    options: {
      targetWidthMm: 30,
      targetHeightMm: 20,
      tolerance: 0.5,
      markSpeed: 600,
      travelSpeed: 2000,
      laserOn: 'M3',
      laserOff: 'M5',
      pwm: false,
      maxPower: 255,
      offsetX: 3,
      offsetY: 4,
      header: 'G90\n; custom header',
      footer: 'M5\nG0 X0 Y0'
    }
  }
]

function caseSvgToGcode(): CaseOutcome {
  const variants = SVG_OPTIONS.map((v) => {
    const r = convertSvgToGcode(SVG_SAMPLE, v.options)
    return {
      name: v.name,
      options: v.options as unknown as JsonValue,
      lines: r.lines as unknown as JsonValue,
      lineCount: r.lines.length,
      widthMm: r.widthMm,
      heightMm: r.heightMm,
      pathCount: r.pathCount,
      pathLengthMm: r.pathLengthMm
    }
  })

  const lengthInputs: (string | null)[] = [
    '10',
    '10px',
    '10mm',
    '2.54cm',
    '1in',
    '72pt',
    '1pc',
    '1em',
    '10%',
    '',
    null,
    '  12.5 mm ',
    'abc',
    '-3mm',
    '1e2px',
    '+2.5'
  ]
  const viewBoxInputs: (string | null)[] = ['0 0 100 50', '0,0,100,50', '10 20 0 50', '1 2 3', null, '', '1 2 3 4 5']

  const badInputs: { name: string; svg: string }[] = [
    { name: 'malformed-unterminated-tag', svg: '<svg viewBox="0 0 10 10"><rect</svg>' },
    { name: 'non-svg-root', svg: '<html><body>hi</body></html>' },
    { name: 'empty-string', svg: '' }
  ]
  const errorPaths = badInputs.map((b) => {
    try {
      const r = convertSvgToGcode(b.svg, SVG_OPTIONS[0].options)
      return { name: b.name, threw: false, lineCount: r.lines.length }
    } catch (e) {
      return { name: b.name, threw: true, errorMessage: (e as Error).message }
    }
  })

  return {
    name: 'svg-to-gcode',
    modules: ['src/core/vector/SvgToGcode.ts'],
    input:
      'hand-written SVG (rect, transformed rect, circle, cubic + quadratic/T bezier paths, line with mm/pt lengths, polyline, polygon, nested <g>, <defs>) converted with 2 option sets, plus parseLength/parseViewBox sweeps and 3 error paths',
    entryCount: variants.length,
    entryCounts: {
      variants: variants.length,
      emittedLines: variants.reduce((a, v) => a + v.lineCount, 0),
      pathsFirstVariant: variants[0].pathCount,
      parseLengthCases: lengthInputs.length,
      parseViewBoxCases: viewBoxInputs.length,
      errorPaths: errorPaths.length
    },
    result: {
      svg: SVG_SAMPLE,
      variants: variants as unknown as JsonValue,
      parseLength: lengthInputs.map((s) => ({ input: s, output: parseLength(s) })) as unknown as JsonValue,
      parseViewBox: viewBoxInputs.map((s) => ({ input: s, output: parseViewBox(s) })) as unknown as JsonValue,
      errorPaths: errorPaths as unknown as JsonValue
    }
  }
}

const GCODE_SAMPLE = [
  '; sample program for the golden oracle',
  'G90 (absolute positioning)',
  'G21',
  '$H',
  'G0 X0 Y0',
  'M4 S0',
  'G1 X10 Y0 F600',
  'G1 X10 Y5.5',
  '(corner) G2 X20 Y5.5 I5 J0',
  'S255 (full power)',
  'G1 X30 Y5.5',
  'G1 X30.0005 Y5.5',
  'M5',
  'G0 X0 Y0',
  'G92 X0 Y0',
  'G91',
  'G1 X1 Y1',
  'G90',
  '(setup) G0  X0  Y0',
  'T1 M6',
  'G4 P0.5',
  'F300',
  'S0',
  'G1 X1',
  ''
].join('\n')

function commandDetail(cmd: GrblCommand): Record<string, JsonValue> {
  const el = (e: { command: string; number: number; toString(): string } | null): JsonValue =>
    e === null ? null : { command: e.command, number: numberOrToken(e.number), toString: e.toString() }
  const serialBefore = cmd.serialData
  cmd.buildHelper()
  return {
    commandRaw: cmd.command,
    isEmpty: cmd.isEmpty,
    isGrblCommand: cmd.isGrblCommand,
    isWriteEEPROM: cmd.isWriteEEPROM,
    isSetWCO: cmd.isSetWCO,
    isMovement: cmd.isMovement,
    isLinearMovement: cmd.isLinearMovement,
    isArcMovement: cmd.isArcMovement,
    isPause: cmd.isPause,
    isAbsoluteCoord: cmd.isAbsoluteCoord,
    isRelativeCoord: cmd.isRelativeCoord,
    isLaserON: cmd.isLaserON,
    isM3: cmd.isM3,
    isM4: cmd.isM4,
    isLaserOFF: cmd.isLaserOFF,
    isM5: cmd.isM5,
    isCW_withTrue: cmd.isCW(true),
    isCW_withFalse: cmd.isCW(false),
    repeatCount: cmd.repeatCount,
    getDecodedMessage: cmd.getDecodedMessage(),
    status: cmd.status,
    codedResult: cmd.codedResult,
    serialDataBeforeBuildHelper: serialBefore,
    serialDataAfterBuildHelper: cmd.serialData,
    justBuilt: cmd.justBuilt,
    commandAfterBuildHelper: cmd.command,
    elements: {
      G: el(cmd.G),
      M: el(cmd.M),
      T: el(cmd.T),
      S: el(cmd.S),
      P: el(cmd.P),
      X: el(cmd.X),
      Y: el(cmd.Y),
      Z: el(cmd.Z),
      I: el(cmd.I),
      J: el(cmd.J),
      F: el(cmd.F),
      R: el(cmd.R)
    }
  }
}

function caseGcodeAnalysis(): CaseOutcome {
  const parsed = parseGcode('golden-sample.nc', GCODE_SAMPLE)
  const commands = parsed.commands.map((c) => commandDetail(c))

  // lifecycle / status transitions on a fresh instance
  const lifecycle: JsonValue[] = []
  for (const result of ['ok', 'error:9', 'weird', '']) {
    const c = new GrblCommandCtor('g1 x1.5 y2')
    const initial = c.status
    c.setSending()
    const sending = c.status
    c.setResult(result)
    lifecycle.push({
      setResult: result,
      initialStatus: initial,
      sendingStatus: sending,
      statusAfter: c.status,
      codedResult: c.codedResult,
      commandStored: c.command
    })
  }
  const cleared = new GrblCommandCtor('G0 X1')
  cleared.setResult('ok')
  cleared.clearResult()
  lifecycle.push({ setResult: '(cleared)', initialStatus: CommandStatus.Queued, sendingStatus: CommandStatus.Queued, statusAfter: cleared.status, codedResult: cleared.codedResult, commandStored: cleared.command })

  const elementSweep = ['G1', 'X10.5', 'S0', 'Y-2.25', 'F', 'T1', 'M3', 'Z0.001'].map((s) => {
    const e = Element.parse(s)
    return { input: s, command: e.command, number: numberOrToken(e.number), toString: e.toString(), equalsSelf: e.equals(Element.parse(s)) }
  })
  const nanEquals = Element.parse('Y')
  const elementEdge = {
    nanToString: nanEquals.toString(),
    nanEqualsSelf: nanEquals.equals(Element.parse('Y')),
    nanEqualsNull: nanEquals.equals(null),
    fromElements: GrblCommandCtor.fromElements([Element.parse('g1'), Element.parse('x1.5')]).command,
    combine: GrblCommandCtor.combine(Element.parse('G1'), new GrblCommandCtor('x2 y3')).command,
    clonePreservesResult: (() => {
      const src = new GrblCommandCtor('g1 x1', 3)
      src.setResult('ok')
      const copy = src.clone()
      return { command: copy.command, repeatCount: copy.repeatCount, codedResult: copy.codedResult, status: copy.status }
    })()
  }

  const confSweep = ['$100=250.000', '$H', '$$', '$', '$1=0', '$X', ' $32 = 1', '$100 =250']
    .map((s) => ({ input: s, isSetConf: GrblConfSTIsSetConf(s), isGrblCommand: new GrblCommandCtor(s).isGrblCommand }))

  // raw (un-stripped) lines straight into the constructor: pins trimming,
  // upper-casing, comment removal and the space-compression of serialData
  const rawCommandLines = [
    '(c) G1  X1',
    'g1 x1 ; trailing comment',
    'G1X1Y2',
    '  G0   X0    Y0  ',
    '$100=250.000  ; step resolution',
    'G1 X1 (mid-line) Y2',
    'M3 S255',
    'g91',
    'G1 X-1.5 Y+2',
    '   ',
    'G1 X1 Y2 (unterminated',
    '(only a comment)'
  ]
  const rawCommandSweep = rawCommandLines.map((line) => ({
    input: line,
    detail: commandDetail(new GrblCommandCtor(line))
  }))

  // duplicate address words: GrblCommand.buildHelper keeps the LAST occurrence
  // (Map.set overwrites) while GrblFile.parseElements keeps the FIRST
  const duplicateWords = {
    buildHelper: commandDetail(new GrblCommandCtor('G1 X1 Y2 X3 G0')),
    fileAnalyze: analyze([new GrblCommandCtor('G1 X1 Y2 X3')]),
    fileParse: parseGcode('dup.nc', 'G1 X1 Y2 X3')
  }

  // GrblFile.stripComments edge cases: ';' breaks, '(' ... ')' spans, a stray
  // ')' outside a comment is kept, and '(' inside a comment does not nest
  const commentEdgeText = ['G1 X1 ) Y2', 'G1 X1 ((nested)) Y2', '; only a comment', '(a) b', 'G1 X1(A)B Y2'].join('\n')
  const commentEdgeCases = parseGcode('comments.nc', commentEdgeText)
  const commentEdge = {
    text: commentEdgeText,
    commands: commentEdgeCases.commands.map((c) => c.command) as unknown as JsonValue,
    stats: commentEdgeCases.stats as unknown as JsonValue
  }

  return {
    name: 'gcode-analysis',
    modules: ['src/core/gcode/GrblFile.ts', 'src/core/grbl/GrblCommand.ts'],
    input:
      'fixed 24-line G-code program (comments ; and (), G0/G1/G2, S values, $H, G92/G91, F-only line, T/M, blank line) parsed with parseGcode + analyze, plus command-lifecycle, Element, GrblConfSTIsSetConf and raw-line (un-stripped, with comments/spacing/lower case) sweeps',
    entryCount: parsed.commands.length,
    entryCounts: {
      commands: parsed.commands.length,
      previewMoves: parsed.preview.length,
      motionCommands: parsed.stats.motionCommands,
      lifecycleCases: lifecycle.length,
      elementSweepCases: elementSweep.length,
      confSweepCases: confSweep.length,
      rawCommandSweepCases: rawCommandSweep.length
    },
    result: {
      name: parsed.name,
      stats: parsed.stats as unknown as JsonValue,
      preview: parsed.preview as unknown as JsonValue,
      commands: commands as unknown as JsonValue,
      lifecycle: lifecycle as unknown as JsonValue,
      elementSweep: elementSweep as unknown as JsonValue,
      elementEdge: elementEdge as unknown as JsonValue,
      confSweep: confSweep as unknown as JsonValue,
      rawCommandSweep: rawCommandSweep as unknown as JsonValue,
      duplicateWordSemantics: duplicateWords as unknown as JsonValue,
      commentEdgeCases: commentEdge as unknown as JsonValue,
      reanalyze: analyze(parsed.commands) as unknown as JsonValue
    }
  }
}

const GRBL_RESPONSE_LINES = [
  'ok',
  'error:9',
  'error:1',
  'ALARM:1',
  'ALARM:9',
  '[MSG:Check Door]',
  "[MSG:'$H'|'$X' to unlock]",
  '$100=250.000',
  '$130=300.000',
  '$30=1000',
  '$32=1',
  '$22=1',
  '$H',
  '$$',
  '[VER:1.1h.20190825:]',
  '[VER:1.1f.20170801:]',
  '[VER:1.3a.20210210:]',
  '[OPT:V,15,128]',
  '[OPT:VMZ,35,255]',
  '<Idle|MPos:0.000,0.000,0.000|FS:0,0>',
  '<Run|MPos:10.500,-2.000,0.000|FS:600,255|Ov:100,100,100>',
  "Grbl 1.1h ['$' for help]",
  "Grbl 0.9j ['$' for help]",
  "Grbl 1.1f ['$' for help]",
  '   ok   '
]

const DECODER_CONTEXTS: { name: string; args: [number, number, string?, string?, string?, boolean?] | null }[] = [
  { name: 'no-version (defaults to v1.1 group)', args: null },
  { name: 'v1.1h', args: [1, 1, 'h'] },
  { name: 'v0.9', args: [0, 9] },
  { name: 'v1.0c', args: [1, 0, 'c'] },
  { name: 'Ortur Laser Master 2 / 1.4', args: [1, 1, 'h', 'Ortur Laser Master 2', '1.4'] },
  { name: 'Ortur Laser Master 3 / 1.5', args: [1, 1, 'h', 'Ortur Laser Master 3', '1.5'] },
  { name: 'Ortur Laser Master 3 / 1.7', args: [1, 1, 'h', 'Ortur Laser Master 3', '1.7'] },
  { name: 'Ortur HAL', args: [1, 1, 'h', 'Ortur Laser Master 3', '1.7', true] },
  { name: 'Aufero Laser 1 / 1.4', args: [1, 1, 'h', 'Aufero Laser 1', '1.4'] },
  { name: 'Longer Nano', args: [1, 1, 'h', 'Longer Nano', '1.1'] },
  { name: 'NanoDuo', args: [1, 1, 'h', 'NanoDuo', '1.1'] }
]

function makeVersion(args: [number, number, string?, string?, string?, boolean?] | null) {
  if (args === null) return null
  return new GrblVersionInfo(args[0], args[1], args[2] ?? '', args[3] ?? null, args[4] ?? null, args[5] ?? false)
}

function caseGrblMessages(): CaseOutcome {
  const settingKeys = ['0', '1', '3', '4', '30', '31', '32', '100', '110', '111', '130', '131', '999']
  const alarmKeys = ['1', '9', '999']

  const contexts = DECODER_CONTEXTS.map((ctx) => {
    const version = makeVersion(ctx.args)
    installDecoders(
      (key, idx) => lookupCode(SETTING_CODES, key, idx, version),
      (key, idx) => lookupCode(ALARM_CODES, key, idx, version)
    )
    const messages = GRBL_RESPONSE_LINES.map((line) => {
      const raw = GrblMessage.fromLine(line, false)
      const decoded = GrblMessage.fromLine(line, true)
      return {
        line,
        type: raw.type,
        nativeMessage: raw.nativeMessage,
        messageWithoutDecode: raw.message,
        tooltipWithoutDecode: raw.tooltip,
        decodedMessage: decoded.message,
        decodedTooltip: decoded.tooltip,
        decodedGetDecodedMessage: decoded.getDecodedMessage(),
        decodedGetNativeMessage: decoded.getNativeMessage(),
        imageIndex: raw.imageIndex
      }
    })
    return {
      context: ctx.name,
      constructorArgs: (ctx.args ?? null) as unknown as JsonValue,
      lookupGroup: lookupGroupName(version),
      messages: messages as unknown as JsonValue,
      settingLookup: settingKeys.map((k) => ({
        key: k,
        values: [lookupCode(SETTING_CODES, k, 0, version), lookupCode(SETTING_CODES, k, 1, version), lookupCode(SETTING_CODES, k, 2, version)]
      })) as unknown as JsonValue,
      alarmLookup: alarmKeys.map((k) => ({
        key: k,
        values: [lookupCode(ALARM_CODES, k, 0, version), lookupCode(ALARM_CODES, k, 1, version)]
      })) as unknown as JsonValue,
      errorLookup: errorSweep(version)
    }
  })

  // leave the module in its pristine state for any later case
  installDecoders(() => null, () => null)

  return {
    name: 'grbl-messages',
    modules: ['src/core/grbl/GrblCommand.ts', 'src/core/grbl/csvData.ts', 'src/core/grbl/types.ts (GrblVersionInfo)'],
    input:
      '25 fixed GRBL response lines (ok / error / ALARM / [MSG] / $settings / $H / $$ / [VER] / [OPT] / status reports / banners) decoded under 11 version contexts via installDecoders + csvData tables',
    entryCount: contexts.length,
    entryCounts: {
      contexts: contexts.length,
      messagesPerContext: GRBL_RESPONSE_LINES.length,
      totalMessageDecodings: contexts.length * GRBL_RESPONSE_LINES.length,
      settingLookups: contexts.length * settingKeys.length,
      alarmLookups: contexts.length * alarmKeys.length
    },
    result: {
      responseLines: GRBL_RESPONSE_LINES as unknown as JsonValue,
      messageTypes: MessageType as unknown as JsonValue,
      contexts: contexts as unknown as JsonValue
    }
  }
}

function errorSweep(version: ReturnType<typeof makeVersion>): JsonValue {
  return ['1', '9', '20', '999'].map((code) => ({
    code,
    description: lookupCode(ERROR_CODES, code, 1, version)
  }))
}

const VERSION_BANNERS = [
  "Grbl 1.1h ['$' for help]",
  "Grbl 1.1f ['$' for help]",
  "Grbl 0.9j ['$' for help]",
  "GRBL 1.1H ['$' FOR HELP]",
  "Grbl 1.1 ['$' for help]",
  '[VER:1.1h.20190825:]',
  '[VER:1.1f.20170801:]',
  '[VER:1.3a.20210210:]',
  '[VER:0.9j:]',
  '[VER:1.1h.20190825:Ortur Laser Master 2:1.4]',
  '[VER:1.1h.20190825:Ortur Laser Master 3:1.5]',
  '[VER:1.1h.20190825:Ortur Laser Master 3:1.7]',
  '[VER:1.1h.20190825:Aufero Aufero Laser 1:1.4]',
  '[VER:1.1h.20190825:Longer Nano:1.1]',
  '[VER:1.1h.20190825:NanoDuo:1.1]',
  '[VER:1.1h.20190825:Longer Ray5:1.0]',
  '[VER:1.1h.20190825:Ortur Laser Master 3:1.7.3]',
  '[VER:1.1h.20190825:Ortur Laser Master 2:unknown]',
  '[VER:1.1h.20190825:Ortur Laser Master 2:v1.4]'
]

function versionJson(v: InstanceType<typeof GrblVersionInfo>): JsonValue {
  return {
    major: v.major,
    minor: v.minor,
    build: v.build,
    vendorInfo: v.vendorInfo,
    vendorVersion: v.vendorVersion,
    isHAL: v.isHAL,
    isOrtur: v.isOrtur,
    isLonger: v.isLonger,
    machineName: v.machineName,
    isLuckyWiFi: v.isLuckyWiFi,
    orturFWVersionNumber: v.orturFWVersionNumber,
    toString: v.toString()
  }
}

function caseGrblVersion(): CaseOutcome {
  const banners = VERSION_BANNERS.map((line) => {
    if (line.toUpperCase().startsWith('[VER:')) {
      const parsed = parseVerMessage(line)
      const version = parsed
        ? new GrblVersionInfo(parsed.major, parsed.minor, parsed.build, parsed.vendorInfo, parsed.vendorVersion)
        : null
      return {
        line,
        parsedFrom: 'parseVerMessage',
        constructorArgs: parsed as unknown as JsonValue,
        version: version ? versionJson(version) : null,
        lookupGroup: version ? lookupGroupName(version) : null
      }
    }
    const parsed = parseVersionBanner(line)
    const version = parsed ? new GrblVersionInfo(parsed.major, parsed.minor, parsed.build) : null
    return {
      line,
      parsedFrom: 'parseVersionBanner',
      constructorArgs: parsed as unknown as JsonValue,
      version: version ? versionJson(version) : null,
      lookupGroup: version ? lookupGroupName(version) : null
    }
  })

  const refs: [string, InstanceType<typeof GrblVersionInfo>][] = [
    ['1.1', new GrblVersionInfo(1, 1)],
    ['1.1h', new GrblVersionInfo(1, 1, 'h')],
    ['1.120190825', new GrblVersionInfo(1, 1, '20190825')],
    ['1.0c', new GrblVersionInfo(1, 0, 'c')],
    ['0.9', new GrblVersionInfo(0, 9)],
    ['2.0', new GrblVersionInfo(2, 0)]
  ]
  const comparisons = refs.map(([name, a]) => ({
    left: name,
    vs: refs.map(([otherName, b]) => ({
      right: otherName,
      compareTo: a.compareTo(b),
      gte: a.gte(b),
      lt: a.lt(b),
      equals: a.equals(b)
    })),
    compareToNull: a.compareTo(null),
    equalsNull: a.equals(null)
  }))

  const statusReportVersion = [
    { line: '<Idle|MPos:0.000,0.000,0.000|FS:0,0>', note: '| without Pin:' },
    { line: '<Idle|MPos:0.000,0.000,0.000|FS:0,0|Pin:XYZ>', note: '| with Pin:' },
    { line: 'no-pipe-here', note: 'no |' }
  ].map((c) => {
    let version: InstanceType<typeof GrblVersionInfo>
    if (c.line.includes('|') && !c.line.includes('Pin:')) version = new GrblVersionInfo(1, 1)
    else if (c.line.includes('|') && c.line.includes('Pin:')) version = new GrblVersionInfo(1, 0, 'c')
    else version = new GrblVersionInfo(0, 9)
    return { ...c, fallbackVersion: versionJson(version) }
  })

  return {
    name: 'grbl-version',
    modules: ['src/core/grbl/types.ts', 'src/core/grbl/GrblCore.ts (parseVersionBanner/parseVerMessage, transcribed)'],
    input: '16 real-world banner strings (Grbl x.y<letter> and [VER:...] incl. Ortur/Longer/Aufero vendor variants) + compareTo matrix + status-report fallbacks',
    entryCount: banners.length,
    entryCounts: {
      banners: banners.length,
      vendorVariants: banners.filter((b) => (b.version as { isOrtur?: boolean; isLonger?: boolean } | null)?.isOrtur || (b.version as { isLonger?: boolean } | null)?.isLonger).length,
      comparisonMatrixCells: refs.length * refs.length,
      statusFallbacks: statusReportVersion.length
    },
    result: {
      banners: banners as unknown as JsonValue,
      comparisons: comparisons as unknown as JsonValue,
      statusReportFallbacks: statusReportVersion as unknown as JsonValue
    }
  }
}

const PRESET_TEXT = [
  '; iGRBL / LaserGRBL style settings preset ($-settings export)',
  '$$',
  '$0=250.000',
  '$1=250.000 (X/Y step resolution)',
  '$30=1000 ; max spindle/laser power',
  '',
  '   ',
  '$32=1',
  '$32=0',
  '$100=250.000',
  '$101=250.000',
  '$110=500.000',
  '$111=500.000',
  '$120=30.000',
  '$130 = 400.000',
  '$131=400',
  '$22=1',
  '$22=0',
  'not a setting',
  '$=10',
  '$abc=10',
  '$100=abc',
  '$10=10.5.5',
  '$31=-0.001',
  '$33=.5',
  '$34=+1.5',
  '$35=1e3',
  '$36=1. (trailing dot)',
  '$37=100 extra',
  '$38=-',
  '; trailing comment only'
].join('\n')

function caseSettingsPreset(): CaseOutcome {
  const r = parseSettingsPreset(PRESET_TEXT)
  const empty = parseSettingsPreset('')
  const onlyComments = parseSettingsPreset('; a\n( b )\n\n')
  return {
    name: 'settings-preset',
    modules: ['src/core/grbl/SettingsPreset.ts'],
    input: 'fixed .nc-style preset text with blank lines, ; and () comments, duplicate ids, invalid/junk lines, negative/+./dot/exponent values',
    entryCount: r.entries.length,
    entryCounts: {
      entries: r.entries.length,
      skipped: r.skipped,
      emptyTextEntries: empty.entries.length,
      commentOnlyEntries: onlyComments.entries.length
    },
    result: {
      text: PRESET_TEXT,
      parse: r as unknown as JsonValue,
      emptyText: empty as unknown as JsonValue,
      commentOnlyText: onlyComments as unknown as JsonValue
    }
  }
}

const FORMAT_DECIMAL_INPUTS = [
  0,
  -0,
  0.5,
  1 / 3,
  1e-7,
  12345.6789,
  -0.0001,
  1e21,
  1e-21,
  -1e21,
  100,
  -100,
  0.1 + 0.2,
  1.005,
  9007199254740991,
  -9007199254740991,
  1e-6,
  2 ** 31,
  2 ** 53,
  1.7976931348623157e308,
  5e-324,
  Number.NaN,
  Number.POSITIVE_INFINITY,
  Number.NEGATIVE_INFINITY
]

function caseFormatDecimal(): CaseOutcome {
  const cases = FORMAT_DECIMAL_INPUTS.map((v) => ({ input: numToken(v), output: formatDecimal(v) }))
  const alsoElementToString = ['G1', 'X10.5', 'S0', 'Y-2.25', 'F', 'T1', 'M3'].map((s) => ({
    input: s,
    elementToString: Element.parse(s).toString()
  }))
  return {
    name: 'format-decimal',
    modules: ['src/core/grbl/GrblCommand.ts'],
    input:
      'fixed numeric sweep (0, -0, 0.5, 1/3, 1e-7, 12345.6789, -0.0001, 1e21, 1e-21, subnormals, MAX_VALUE, NaN, ±Infinity) through formatDecimal, plus Element.parse().toString() sweep',
    entryCount: cases.length,
    entryCounts: { cases: cases.length, elementCases: alsoElementToString.length },
    result: { cases: cases as unknown as JsonValue, elementToString: alsoElementToString as unknown as JsonValue }
  }
}

interface FitCase {
  name: string
  widthMm: number
  heightMm: number
  opts?: { travelX?: number; travelY?: number; mode?: 'Fit' | 'Clamp' | 'None' }
}

const FIT_CASES: FitCase[] = [
  { name: 'fits-exactly', widthMm: 300, heightMm: 200 },
  { name: 'fits-inside', widthMm: 120, heightMm: 80 },
  { name: 'too-wide-height-binds', widthMm: 600, heightMm: 200 },
  { name: 'too-big-both-fit', widthMm: 1200, heightMm: 900 },
  { name: 'too-big-both-clamp', widthMm: 1200, heightMm: 900, opts: { mode: 'Clamp' } },
  { name: 'clamp-one-axis-only', widthMm: 500, heightMm: 100, opts: { mode: 'Clamp' } },
  { name: 'none-mode-passthrough', widthMm: 1200, heightMm: 900, opts: { mode: 'None' } },
  { name: 'zero-size-floors-to-1', widthMm: 0, heightMm: 0 },
  { name: 'negative-size-floors-to-1', widthMm: -50, heightMm: -10 },
  { name: 'nan-width', widthMm: Number.NaN, heightMm: 100 },
  { name: 'infinite-height', widthMm: 100, heightMm: Number.POSITIVE_INFINITY },
  { name: 'custom-travel-fit', widthMm: 1000, heightMm: 500, opts: { travelX: 400, travelY: 400 } },
  { name: 'custom-travel-clamp', widthMm: 1000, heightMm: 500, opts: { travelX: 400, travelY: 400, mode: 'Clamp' } },
  { name: 'tiny-travel-1mm', widthMm: 50, heightMm: 25, opts: { travelX: 1, travelY: 1 } },
  { name: 'upscale-never-happens', widthMm: 10, heightMm: 5, opts: { travelX: 1000, travelY: 1000 } },
  { name: 'rounding-third-mm', widthMm: 333.3333, heightMm: 111.1111 },
  { name: 'rounding-third-mm-clamp', widthMm: 333.3333, heightMm: 111.1111, opts: { mode: 'Clamp' } },
  { name: 'clamp-below-round3-epsilon', widthMm: 300.0004, heightMm: 200.0004, opts: { mode: 'Clamp' } },
  { name: 'fit-below-round3-epsilon', widthMm: 300.0004, heightMm: 200.0004 },
  { name: 'clamp-exactly-travel', widthMm: 300, heightMm: 200, opts: { mode: 'Clamp' } }
]

const CHECK_TRAVEL_CASES: { name: string; lines: string[]; opts?: { travelX?: number; travelY?: number } }[] = [
  { name: 'inside', lines: ['G0 X0 Y0', 'G1 X100 Y50 F600'] },
  { name: 'overshoot-x', lines: ['G0 X350 Y20'] },
  { name: 'negative-machine-coords-ok', lines: ['G1 X-50 Y-20', 'G0 X10 Y10'] },
  { name: 'epsilon-boundary-inside', lines: ['G00 X299.9995 Y199.9995'] },
  { name: 'epsilon-boundary-outside', lines: ['G0 X300.002 Y10'] },
  { name: 'comments-only', lines: ['; G0 X9999 Y9999', '(G1 X500 Y500)'] },
  { name: 'arcs-counted', lines: ['G2 X20 Y20 I5 J0', 'G03 X30 Y30'] },
  { name: 'empty-list', lines: [] },
  { name: 'no-motion-lines', lines: ['M5', 'S0', '$H', 'not a move'] },
  { name: 'feed-only-then-x-only', lines: ['G1 F600', 'G1 X1'] },
  { name: 'lowercase-no-spaces', lines: ['g1x10y10', 'g0 X299Y199'] },
  { name: 'exponent-not-supported', lines: ['G1 X1.5e2 Y2'] },
  { name: 'custom-travel-overshoot', lines: ['G1 X500 Y10'], opts: { travelX: 400, travelY: 400 } },
  { name: 'only-y', lines: ['G1 Y250'] },
  { name: 'blank-lines', lines: ['', 'G1 X10 Y10', ''] }
]

function caseFitToTravel(): CaseOutcome {
  const fitCases = FIT_CASES.map((c) => ({
    name: c.name,
    input: { widthMm: numToken(c.widthMm), heightMm: numToken(c.heightMm), opts: (c.opts ?? null) as unknown as JsonValue },
    output: fitSizeToTravel(c.widthMm, c.heightMm, c.opts) as unknown as JsonValue
  }))

  const checkCases = CHECK_TRAVEL_CASES.map((c) => ({
    name: c.name,
    lines: c.lines as unknown as JsonValue,
    opts: (c.opts ?? null) as unknown as JsonValue,
    output: checkGcodeWithinTravel(c.lines, c.opts) as unknown as JsonValue
  }))

  // AppSettings-backed travel values (localStorage is the shim's empty store, so
  // these start at DEFAULT_SETTINGS' 300x200 and are restored afterwards).
  const settingsDefaults = {
    travelX: AppSettings.get<number>('Travel X', 300),
    travelY: AppSettings.get<number>('Travel Y', 200)
  }
  AppSettings.set('Travel X', 500)
  AppSettings.set('Travel Y', 400)
  const viaSettings = {
    args: { travelX: '(unset → AppSettings)' },
    fit: fitSizeToTravel(1200, 900) as unknown as JsonValue,
    clamp: fitSizeToTravel(1200, 900, { mode: 'Clamp' }) as unknown as JsonValue,
    check: checkGcodeWithinTravel(['G0 X450 Y350', 'G1 X600 Y10']) as unknown as JsonValue,
    explicitOverridesSettings: fitSizeToTravel(1200, 900, { travelX: 300, travelY: 200 }) as unknown as JsonValue
  }
  AppSettings.set('Travel X', settingsDefaults.travelX)
  AppSettings.set('Travel Y', settingsDefaults.travelY)
  const restored = fitSizeToTravel(1200, 900) as unknown as JsonValue

  return {
    name: 'fit-to-travel',
    modules: ['src/core/grbl/DeviceProfile.ts', 'src/core/grbl/GrblConfig.ts'],
    input:
      '17 fitSizeToTravel cases (Fit/Clamp/None, NaN/Infinity/zero/negative sizes, custom travel) + 15 checkGcodeWithinTravel line-set cases (comments, arcs, lowercase, exponents, epsilon boundary) + AppSettings-backed travel variant',
    entryCount: fitCases.length + checkCases.length,
    entryCounts: {
      fitCases: fitCases.length,
      checkTravelCases: checkCases.length,
      builtinProfiles: builtinProfiles().length,
      defaultSettingsKeys: Object.keys(DEFAULT_SETTINGS).length
    },
    result: {
      fitCases: fitCases as unknown as JsonValue,
      checkTravelCases: checkCases as unknown as JsonValue,
      viaAppSettings: viaSettings as unknown as JsonValue,
      restoredDefaults: { settingsDefaults: settingsDefaults as unknown as JsonValue, fit: restored },
      builtinProfiles: builtinProfiles() as unknown as JsonValue,
      storageAfterRun: {
        savedProfiles: listSavedProfiles() as unknown as JsonValue,
        knownDeviceIds: listKnownDeviceIds() as unknown as JsonValue,
        deviceKnown: isDeviceKnown(1)
      }
    }
  }
}

const KEEPALIVE_NAMES = [
  '',
  '   ',
  'a',
  'x'.repeat(23),
  'y'.repeat(24),
  'z'.repeat(25),
  'w'.repeat(40),
  '汉'.repeat(23),
  '汉'.repeat(24),
  '汉'.repeat(25),
  'iGRBL 雕刻作业-2024.nc',
  '汉字😀',
  '  ' + 'q'.repeat(25) + '  ',
  '  trimmed.nc  ',
  'tab\tname.nc',
  'multi\nline.nc',
  '😀'.repeat(12),
  'é'.repeat(24),
  'a'.repeat(23) + '😀',
  'a'.repeat(22) + '😀',
  '\u00a0nbsp.nc',
  '\ufeffbom.nc',
  '\u3000ideo-space.nc',
  '\u00a0' + 'n'.repeat(25) + '\u00a0',
  'x'.repeat(24) + '\u00a0'
]

const KEEPALIVE_PERCENT_PAIRS: [number, number][] = [
  [0, 0],
  [0, 100],
  [1, 3],
  [33, 100],
  [100, 100],
  [150, 100],
  [-10, 100],
  [50, Number.NaN],
  [0, Number.NaN],
  [Number.NaN, Number.NaN],
  [Number.NaN, 100],
  [50, Number.POSITIVE_INFINITY],
  [Number.POSITIVE_INFINITY, 100],
  [Number.NEGATIVE_INFINITY, 100],
  [0.5, 1],
  [99.5, 100],
  [2, 3],
  [1e9, 3],
  [-1, 0],
  [100, -5]
]

const KEEPALIVE_FORMAT_PAIRS: [string, number][] = [
  ['job.nc', 0],
  ['job.nc', 33.3],
  ['job.nc', 100],
  ['job.nc', -5],
  ['job.nc', 150],
  ['job.nc', Number.NaN],
  ['job.nc', 99.5],
  ['job.nc', -0.4],
  ['', 42],
  ['   ', 42],
  ['x'.repeat(25), 7],
  ['汉'.repeat(25), 7],
  ['iGRBL 雕刻作业-2024.nc', 50]
]

function caseKeepAliveFormat(): CaseOutcome {
  const names = KEEPALIVE_NAMES.map((n) => ({
    input: n,
    codeUnits: n.length,
    trimmedCodeUnits: n.trim().length,
    output: truncateName(n)
  }))
  const percents = KEEPALIVE_PERCENT_PAIRS.map(([e, t]) => ({
    executed: numToken(e),
    total: numToken(t),
    output: percentOf(e, t)
  }))
  const formats = KEEPALIVE_FORMAT_PAIRS.map(([n, p]) => ({
    name: n,
    percent: numToken(p),
    output: formatText(n, p)
  }))
  return {
    name: 'keepalive-format',
    modules: ['src/core/native/KeepAlive.ts'],
    input:
      'truncateName over 20 names (length 0/1/23/24/25/40, CJK, emoji surrogate pairs, whitespace/control chars), percentOf over 20 pairs (0/33.3/100/negative/over-total/NaN/Infinity), formatText over 13 name+percent combos',
    entryCount: names.length + percents.length + formats.length,
    entryCounts: {
      names: names.length,
      percents: percents.length,
      formats: formats.length,
      notificationTitle: 1
    },
    result: {
      notificationTitle: NOTIFICATION_TITLE,
      names: names as unknown as JsonValue,
      percents: percents as unknown as JsonValue,
      formats: formats as unknown as JsonValue
    }
  }
}

// ===========================================================================
// 7. Main
// ===========================================================================

/**
 * 图像变换（`src/core/raster/ImageTransform.ts`）—— **只捕获不依赖 canvas 的那部分**。
 *
 * `resizeImage` 需要真实的 `document.createElement('canvas')`（重采样由浏览器实现），
 * Node 里没有等价物，所以本 case **不包含**它；那条路径的记录见
 * `docs/PHASE3-PLATFORM-MODULES.md`（Kotlin 侧做成可注入的 `ResizeSampler` 接缝）。
 *
 * 其余 7 个函数都是纯像素运算，逐像素可复现。
 */
function caseImageTransform(): CaseOutcome {
  const base = imageTransformSampleImage()
  const clone = (): ImageDataShim => new ImageDataShim(new Uint8ClampedArray(base.data), base.width, base.height)

  const grayVariants: { name: string; args: [number, number, number, number, number, Formula] }[] = [
    { name: 'simple-average-default', args: [100, 100, 100, 0, 1, Formula.SimpleAverage] },
    { name: 'weight-average-brightness', args: [100, 100, 100, 0.25, 1, Formula.WeightAverage] },
    { name: 'optical-correct-contrast', args: [100, 100, 100, 0, 1.5, Formula.OpticalCorrect] },
    { name: 'custom-rgb', args: [60, 25, 15, 0, 1, Formula.Custom] },
    { name: 'negative-brightness-and-low-contrast', args: [100, 100, 100, -0.5, 0.2, Formula.OpticalCorrect] },
    { name: 'custom-over-100-percent', args: [200, 150, 50, 0.1, 2.5, Formula.Custom] }
  ]
  const grays = grayVariants.map((v) => {
    const img = clone()
    grayScale(img, v.args[0], v.args[1], v.args[2], v.args[3], v.args[4], v.args[5])
    return { name: v.name, args: v.args as unknown as JsonValue, output: encodeImage(img) as unknown as JsonValue }
  })

  const whitenizeVariants = [0, 1, 5, 10, 20, 128].map((threshold) => {
    const img = clone()
    whitenize(img, threshold)
    return { threshold, output: encodeImage(img) as unknown as JsonValue }
  })

  const thresholdVariants: { name: string; threshold01: number; apply: boolean }[] = [
    { name: 'apply-0.5', threshold01: 0.5, apply: true },
    { name: 'apply-0.2', threshold01: 0.2, apply: true },
    { name: 'apply-0.8', threshold01: 0.8, apply: true },
    { name: 'composite-only-0.5', threshold01: 0.5, apply: false }
  ]
  const thresholds = thresholdVariants.map((v) => {
    const img = clone()
    threshold(img, v.threshold01, v.apply)
    return { name: v.name, threshold01: v.threshold01, apply: v.apply, output: encodeImage(img) as unknown as JsonValue }
  })

  const ditherVariants = ['FloydSteinberg', 'Atkinson', 'SierraLight'].map((mode) => {
    const img = clone()
    dither(img, mode as DitheringMode)
    return { mode, output: encodeImage(img) as unknown as JsonValue }
  })

  const flipped = flipVertical(base)

  const grayImage = clone()
  grayScale(grayImage, 100, 100, 100, 0, 1, Formula.OpticalCorrect)

  const grayOnly = blankImage(8, 4, WHITE)
  for (let y = 0; y < 4; y++) {
    for (let x = 0; x < 8; x++) {
      const v = (x * 31 + y * 17) % 256
      paint(grayOnly, x, y, [v, v, v, 255])
    }
  }

  return {
    name: 'image-transform',
    modules: ['src/core/raster/ImageTransform.ts', 'src/core/raster/dithering.ts'],
    input:
      'synthetic 24x16 RGBA bitmap cycling through 10 pixel forms (black / white / saturated RGB / near-white 250,251,249 / fully transparent with non-zero RGB / half transparent / mid gray / near-black); ' +
      'grayScale 6 option sets, whitenize 6 thresholds, threshold 4 sets, dither 3 modes, flipVertical, and testGrayScale on both this image and a synthetic gray-only image',
    entryCount: grays.length + whitenizeVariants.length + thresholds.length + ditherVariants.length + 3,
    entryCounts: {
      grayScaleVariants: grays.length,
      whitenizeThresholds: whitenizeVariants.length,
      thresholdVariants: thresholds.length,
      ditherModes: ditherVariants.length,
      formulas: Object.keys(Formula).filter((k) => Number.isNaN(Number(k))).length,
      formulaLabels: Object.keys(FORMULA_LABELS).length
    },
    result: {
      formulaEnum: Formula as unknown as JsonValue,
      formulaLabels: FORMULA_LABELS as unknown as JsonValue,
      input: encodeImage(base) as unknown as JsonValue,
      grayScale: grays as unknown as JsonValue,
      whitenize: whitenizeVariants as unknown as JsonValue,
      threshold: thresholds as unknown as JsonValue,
      dither: ditherVariants as unknown as JsonValue,
      flipVertical: encodeImage(flipped) as unknown as JsonValue,
      grayScaleThenFlip: encodeImage(flipVertical(grayImage)) as unknown as JsonValue,
      testGrayScale: {
        colorful: testGrayScale(base),
        grayOnly: testGrayScale(grayOnly),
        grayOnlyImage: encodeImage(grayOnly) as unknown as JsonValue
      }
    }
  }
}

function caseImageVector(): CaseOutcome {
  // 注意：`convertImageVector` 只读 data/width/height，所以 ImageDataShim 可直接复用
  const potraceImage: ImageDataShim = imageVectorSampleImage()

  interface Variant {
    name: string
    image: ImageDataShim
    options: Record<string, JsonValue>
  }

  const variants: Variant[] = [
    { name: 'outline-default', image: potraceImage, options: { ...DEFAULT_IMAGE_VECTOR_OPTIONS } as Record<string, JsonValue> },
    {
      name: 'outline-tuned',
      image: potraceImage,
      options: {
        ...DEFAULT_IMAGE_VECTOR_OPTIONS,
        threshold: 200,
        turdSize: 0,
        alphaMax: 0.8,
        optTolerance: 0.05,
        curveOptimizing: false,
        flattenTolerance: 0.5,
        widthMm: 30,
        heightMm: 20
      } as Record<string, JsonValue>
    },
    {
      name: 'outline-inverted',
      image: potraceImage,
      options: { ...DEFAULT_IMAGE_VECTOR_OPTIONS, invert: true, threshold: 100 } as Record<string, JsonValue>
    },
    {
      name: 'outline-no-optimize-travel-nopwm',
      image: potraceImage,
      options: {
        ...DEFAULT_IMAGE_VECTOR_OPTIONS,
        optimize: false,
        travelSpeed: 0,
        pwm: false,
        maxPower: 255,
        laserPower: 200,
        markSpeed: 500,
        header: 'G90\n; image vector',
        footer: 'M5\nG0 X0 Y0'
      } as Record<string, JsonValue>
    },
    {
      name: 'centerline-default',
      image: potraceImage,
      options: { ...DEFAULT_IMAGE_VECTOR_OPTIONS, tool: 'Centerline' } as Record<string, JsonValue>
    },
    {
      name: 'centerline-tuned',
      image: potraceImage,
      options: {
        ...DEFAULT_IMAGE_VECTOR_OPTIONS,
        tool: 'Centerline',
        minBranchPx: 2,
        simplifyTolerance: 0.5,
        threshold: 160,
        widthMm: 12
      } as Record<string, JsonValue>
    },
    {
      name: 'outline-threshold-90-of-bars',
      image: centerlineSampleImage(),
      options: { ...DEFAULT_IMAGE_VECTOR_OPTIONS, threshold: 90 } as Record<string, JsonValue>
    },
    {
      // 退化输入：宽或高为 0 时必须原样返回空结果（不抛异常）
      name: 'degenerate-empty-image',
      image: new ImageDataShim(0, 0),
      options: { ...DEFAULT_IMAGE_VECTOR_OPTIONS } as Record<string, JsonValue>
    }
  ]

  const results = variants.map((v) => {
    const r = convertImageVector(
      { data: v.image.data, width: v.image.width, height: v.image.height },
      v.options as unknown as Parameters<typeof convertImageVector>[1]
    )
    return {
      name: v.name,
      options: v.options,
      image: encodeImage(v.image) as unknown as JsonValue,
      lines: r.lines as unknown as JsonValue,
      lineCount: r.lines.length,
      pathCount: r.pathCount,
      lengthMm: r.lengthMm,
      preview: encodeImage(r.preview) as unknown as JsonValue
    }
  })

  const smartVariants = [
    { name: 'smart-outline-image-default', image: potraceImage, options: { ...DEFAULT_IMAGE_VECTOR_OPTIONS } as Record<string, JsonValue> },
    {
      name: 'smart-centerline-image-strict',
      image: potraceImage,
      options: {
        ...DEFAULT_IMAGE_VECTOR_OPTIONS,
        strokeWidthThresholdPct: 6,
        maxInkRatio: 0.2,
        maxIterations: 12,
        widthMm: 25
      } as Record<string, JsonValue>
    },
    {
      name: 'smart-thin-lines',
      image: centerlineSampleImage(),
      options: { ...DEFAULT_IMAGE_VECTOR_OPTIONS } as Record<string, JsonValue>
    }
  ]
  const smartResults = smartVariants.map((v) => {
    const r = convertImageVectorSmart(
      { data: v.image.data, width: v.image.width, height: v.image.height },
      v.options as unknown as Parameters<typeof convertImageVectorSmart>[1]
    )
    return {
      name: v.name,
      options: v.options as unknown as JsonValue,
      lines: r.lines as unknown as JsonValue,
      lineCount: r.lines.length,
      pathCount: r.pathCount,
      lengthMm: r.lengthMm,
      decision: r.decision as unknown as JsonValue,
      preview: encodeImage(r.preview) as unknown as JsonValue
    }
  })

  const nonAsciiInputs = ['iGRBL', 'A1 - 2.5mm', '', ' ', 'iGRBL 中文', '温度', 'café', 'line1\nline2', '\t', '~!@#$%^&*()']
  const textEngineInputs = ['iGRBL', 'A1', 'hello world', 'line1\nline2', 'line1\r\nline2', 'tab\there', '中文', 'iGRBL 中文', 'café', '', '   ', '温度 25°C']

  return {
    name: 'image-vector',
    modules: [
      'src/core/vector/ImageVector.ts',
      'src/core/vector/SmartVector.ts',
      'src/core/vector/Potrace.ts',
      'src/core/vector/Centerline.ts',
      'src/core/vector/Paths.ts'
    ],
    input:
      'synthetic 128x128 RGBA bitmap (black disc r=24 at (34,34) + half-transparent black rect + hollow black ring at (92,92) + fully transparent strip with non-zero RGB) plus the 40x40 centerline sample; ' +
      '8 convertImageVector option sets (6 outline / 2 centerline, incl. tuned thresholds and a degenerate 0x0 image), 3 convertImageVectorSmart sets, hasNonAscii sweep and decideTextEngine sweep',
    entryCount: results.length + smartResults.length + nonAsciiInputs.length + textEngineInputs.length,
    entryCounts: {
      imageVectorVariants: results.length,
      smartVariants: smartResults.length,
      emittedLines: results.reduce((a, v) => a + v.lineCount, 0),
      smartEmittedLines: smartResults.reduce((a, v) => a + v.lineCount, 0),
      hasNonAsciiCases: nonAsciiInputs.length,
      decideTextEngineCases: textEngineInputs.length,
      toolLabels: Object.keys(VECTOR_TOOL_LABELS).length
    },
    result: {
      toolLabels: VECTOR_TOOL_LABELS as unknown as JsonValue,
      defaultOptions: DEFAULT_IMAGE_VECTOR_OPTIONS as unknown as JsonValue,
      variants: results as unknown as JsonValue,
      smartVariants: smartResults as unknown as JsonValue,
      hasNonAscii: nonAsciiInputs.map((s) => ({ input: s, output: hasNonAscii(s) })) as unknown as JsonValue,
      hasNonHersheyChar: textEngineInputs.map((s) => ({ input: s, output: hasNonHersheyChar(s) })) as unknown as JsonValue,
      decideTextEngine: textEngineInputs.map((s) => ({ input: s, output: decideTextEngine(s) as unknown as JsonValue })) as unknown as JsonValue
    }
  }
}

// ===========================================================================
// 7b. raster-converter
//
// `RasterConverter.ts` 的唯一平台依赖是开头那一次 `resizeImage(...)`（浏览器 canvas 重采样）。
// 与 image-transform 不同，这个 case **让 v2 真实的 `convertImageToGcode` 原样跑完**：
// 只替换 `document.createElement('canvas')` 这一层（画布 shim，见下），
// 之后的 testGrayScale → grayScale → whitenize → dither/threshold → flipVertical →
// getSegments → segToGCodeNumber → optimizeLine2Line → 头尾拼装全部是 v2 的真实代码。
// 因此这份夹具是整条光栅管线的逐行判据，而不是"生成器自己实现一遍管线"。
//
// ⚠️ 画布 shim 的内核是**确定性最近邻**，不是浏览器实现：真实 `ctx.drawImage` 的平滑滤波
// 在 Node 里不存在（见 docs/PHASE3-PLATFORM-MODULES.md §3.1）。这一点在
// `result.resample` 里显式记录，Kotlin 侧对应的实现细节是 `ResizeSampler` 接缝。
// ===========================================================================

/** 画布 shim 能读的最小像素源（= v2 传给 `drawImage` 的 `CanvasImageSource`）。 */
interface RasterPixelSource {
  data: Uint8ClampedArray
  width: number
  height: number
}

/**
 * 确定性最近邻重采样 —— 画布 shim 的 `drawImage` 内核（**不是**浏览器实现）。
 *
 * 定义（Kotlin 侧测试用的最近邻重采样器必须与此逐条一致）：
 *
 *     目标像素 (dx, dy) 的中心映射回源坐标：
 *       sx = sx0 + floor((dx + 0.5) * sw / dw)
 *       sy = sy0 + floor((dy + 0.5) * sh / dh)
 *     两个坐标都夹到 [sx0, sx0 + sw - 1] / [sy0, sy0 + sh - 1]，
 *     取该源像素的 RGBA **原样**写入（不改 alpha、不与白底合成 —— 对应 killAlpha=false）。
 *
 * 目标尺寸按 `Math.max(0, Math.trunc())` 处理（v2 的 `resizeImage` 已经做过 `Math.max(1, ...)`）。
 * `imageSmoothingEnabled` / `imageSmoothingQuality` 只被记录、不被内核使用。
 */
function nearestNeighborDrawImage(
  source: RasterPixelSource,
  sx0: number,
  sy0: number,
  sw: number,
  sh: number,
  dw: number,
  dh: number
): { pixels: Uint8ClampedArray; width: number; height: number } {
  const w = Math.max(0, Math.trunc(dw))
  const h = Math.max(0, Math.trunc(dh))
  const out = new Uint8ClampedArray(w * h * 4)
  for (let y = 0; y < h; y++) {
    const sy = Math.min(sh - 1, Math.floor(((y + 0.5) * sh) / h))
    for (let x = 0; x < w; x++) {
      const sx = Math.min(sw - 1, Math.floor(((x + 0.5) * sw) / w))
      const si = ((sy0 + sy) * source.width + (sx0 + sx)) * 4
      const di = (y * w + x) * 4
      out[di] = source.data[si]
      out[di + 1] = source.data[si + 1]
      out[di + 2] = source.data[si + 2]
      out[di + 3] = source.data[si + 3]
    }
  }
  return { pixels: out, width: w, height: h }
}

/**
 * `canvas.getContext('2d')` 的 shim：只实现 `resizeImage` 用到的成员
 * （`fillStyle` / `fillRect` / `imageSmoothingEnabled` / `imageSmoothingQuality` /
 * `drawImage` / `getImageData`）。多实现的部分一律抛错，避免"未实现却被静默忽略"。
 */
class RasterContext2DShim {
  fillStyle = '#000000'
  imageSmoothingEnabled = true
  imageSmoothingQuality: 'low' | 'high' = 'high'

  constructor(private readonly canvas: RasterCanvasShim) {}

  /** 只在 `killAlpha = true` 时被调用；本 case 传 false，所以这条路径不会走到（仍然实现，不静默忽略）。 */
  fillRect(x: number, y: number, w: number, h: number): void {
    const m = /^#([0-9a-fA-F]{6})$/.exec(this.fillStyle)
    if (!m) throw new Error(`RasterContext2DShim.fillRect 只支持 #rrggbb，收到 ${this.fillStyle}`)
    const value = Number.parseInt(m[1], 16)
    const r = (value >> 16) & 0xff
    const g = (value >> 8) & 0xff
    const b = value & 0xff
    for (let yy = Math.trunc(y); yy < Math.trunc(y + h); yy++) {
      for (let xx = Math.trunc(x); xx < Math.trunc(x + w); xx++) {
        this.canvas.blitPixel(xx, yy, r, g, b, 255)
      }
    }
  }

  drawImage(
    source: RasterPixelSource,
    sx: number,
    sy: number,
    sw: number,
    sh: number,
    dx: number,
    dy: number,
    dw: number,
    dh: number
  ): void {
    this.canvas.drawCalls.push({
      sx,
      sy,
      sw,
      sh,
      dx,
      dy,
      dw,
      dh,
      imageSmoothingEnabled: this.imageSmoothingEnabled,
      imageSmoothingQuality: this.imageSmoothingQuality
    })
    const res = nearestNeighborDrawImage(source, sx, sy, sw, sh, dw, dh)
    this.canvas.blit(res.pixels, res.width, res.height, Math.trunc(dx), Math.trunc(dy))
  }

  getImageData(x: number, y: number, w: number, h: number): ImageDataShim {
    return this.canvas.readRegion(Math.trunc(x), Math.trunc(y), Math.trunc(w), Math.trunc(h))
  }
}

/**
 * 极简画布 shim。语义上与真实 canvas 对齐的三点：
 *  1. 设置 `width`/`height` 会**重新分配**后备存储（内容清空，初值全 0 = 透明黑）；
 *  2. `drawImage` 是覆盖式写入（本 case 里画布本来就是空的，且 dst 覆盖整块画布）；
 *  3. `getImageData` 返回**副本**（v2 之后的 `grayScale` 等都在改这份副本，
 *     所以跑完管线后 `canvas.pixels` 仍然是"重采样原图"—— 夹具里的 `resampled` 就是它）。
 */
class RasterCanvasShim {
  /** `drawImage` 的实参记录（含当时的平滑开关），便于复核重采样输入。 */
  readonly drawCalls: { [k: string]: number | boolean | string }[] = []

  private w = 0
  private h = 0
  private buf = new Uint8ClampedArray(0)
  private readonly ctx = new RasterContext2DShim(this)

  get width(): number {
    return this.w
  }

  set width(v: number) {
    this.resize(v, this.h)
  }

  get height(): number {
    return this.h
  }

  set height(v: number) {
    this.resize(this.w, v)
  }

  /** 画布后备存储（RGBA，长度 `width * height * 4`）。 */
  get pixels(): Uint8ClampedArray {
    return this.buf
  }

  getContext(type: string, _options?: unknown): RasterContext2DShim {
    if (type !== '2d') throw new Error(`RasterCanvasShim 只支持 getContext('2d')，收到 ${type}`)
    return this.ctx
  }

  /** 把一块 RGBA 覆盖写到 (dx, dy)（越界部分丢弃）。 */
  blit(src: Uint8ClampedArray, w: number, h: number, dx: number, dy: number): void {
    for (let y = 0; y < h; y++) {
      const ty = dy + y
      if (ty < 0 || ty >= this.h) continue
      for (let x = 0; x < w; x++) {
        const tx = dx + x
        if (tx < 0 || tx >= this.w) continue
        const si = (y * w + x) * 4
        const di = (ty * this.w + tx) * 4
        this.buf[di] = src[si]
        this.buf[di + 1] = src[si + 1]
        this.buf[di + 2] = src[si + 2]
        this.buf[di + 3] = src[si + 3]
      }
    }
  }

  /** `fillRect` 用：写单个像素（越界忽略）。 */
  blitPixel(x: number, y: number, r: number, g: number, b: number, a: number): void {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return
    const i = (y * this.w + x) * 4
    this.buf[i] = r
    this.buf[i + 1] = g
    this.buf[i + 2] = b
    this.buf[i + 3] = a
  }

  /** `getImageData` 用：复制一块区域（越界部分为 0，与真实 canvas 一致）。 */
  readRegion(x: number, y: number, w: number, h: number): ImageDataShim {
    const out = new Uint8ClampedArray(Math.max(0, w) * Math.max(0, h) * 4)
    for (let yy = 0; yy < h; yy++) {
      for (let xx = 0; xx < w; xx++) {
        const sx = x + xx
        const sy = y + yy
        if (sx < 0 || sy < 0 || sx >= this.w || sy >= this.h) continue
        const si = (sy * this.w + sx) * 4
        const di = (yy * w + xx) * 4
        out[di] = this.buf[si]
        out[di + 1] = this.buf[si + 1]
        out[di + 2] = this.buf[si + 2]
        out[di + 3] = this.buf[si + 3]
      }
    }
    return new ImageDataShim(out, Math.max(0, w), Math.max(0, h))
  }

  private resize(width: number, height: number): void {
    const w = Math.max(0, Math.trunc(width))
    const h = Math.max(0, Math.trunc(height))
    if (w === this.w && h === this.h) return
    this.w = w
    this.h = h
    this.buf = new Uint8ClampedArray(w * h * 4)
  }
}

/** 装上画布 shim（返回恢复函数与本次创建的画布列表）。 */
function installRasterCanvasShim(): { canvases: RasterCanvasShim[]; restore: () => void } {
  const canvases: RasterCanvasShim[] = []
  const previous = g.document
  g.document = {
    createElement: (tag: string): RasterCanvasShim => {
      if (tag !== 'canvas') throw new Error(`画布 shim 只支持 createElement('canvas')，收到 ${tag}`)
      const canvas = new RasterCanvasShim()
      canvases.push(canvas)
      return canvas
    }
  }
  return {
    canvases,
    restore: () => {
      g.document = previous
    }
  }
}

/**
 * 48×32 合成源图（**重采样之前**）：
 *  * 白底 `(255,255,255,255)`：灰化后 `rv = 0`，是"激光关"的天然背景；
 *  * 左上 16×16 黑色对角线渐变方块（0..255）：给亮度/对比度、阈值化提供连续输入；
 *  * y=24 上一条 1 px 纯黑细线（x ∈ [4,28]）：细结构在放大后仍然存在；
 *  * 右下 14×10 半透明块（RGB=(200,60,20)，alpha 32..224）：覆盖 `getColor` 的 alpha 因子；
 *  * 右上 8×6 全透明块（RGBA=(0,0,0,0)）：覆盖 alpha=0；
 *  * y=10 上 4 个饱和色点（红/绿/蓝/品红）：保证 `testGrayScale` 为 false，走 `options.formula` 分支。
 */
function rasterConverterSampleImage(): ImageDataShim {
  const w = 48
  const h = 32
  const img = blankImage(w, h, WHITE)
  for (let y = 2; y <= 17; y++) {
    for (let x = 2; x <= 17; x++) {
      const v = ((x - 2) * 16 + (y - 2) * 8) % 256
      paint(img, x, y, [v, v, v, 255])
    }
  }
  for (let y = 20; y <= 29; y++) {
    for (let x = 30; x <= 43; x++) {
      const a = Math.min(224, 32 + ((x - 30) + (y - 20)) * 16)
      paint(img, x, y, [200, 60, 20, a])
    }
  }
  for (let y = 2; y <= 7; y++) {
    for (let x = 36; x <= 43; x++) paint(img, x, y, [0, 0, 0, 0])
  }
  for (let x = 4; x <= 28; x++) paint(img, x, 24, [0, 0, 0, 255])
  const dots: [number, number, [number, number, number, number]][] = [
    [20, 10, [255, 0, 0, 255]],
    [22, 10, [0, 255, 0, 255]],
    [24, 10, [0, 0, 255, 255]],
    [26, 10, [255, 0, 255, 255]]
  ]
  for (const [x, y, rgba] of dots) paint(img, x, y, rgba)
  return img
}

/** `DEFAULT_RASTER_OPTIONS` + 覆盖项（25 个字段全部序列化进夹具，Kotlin 侧按字段比对）。 */
function rasterOptions(overrides: Record<string, JsonValue>): Record<string, JsonValue> {
  return { ...(DEFAULT_RASTER_OPTIONS as unknown as Record<string, JsonValue>), ...overrides }
}

interface RasterVariant {
  name: string
  targetMmW: number
  targetMmH: number
  options: Record<string, JsonValue>
}

/**
 * 跑 v2 真实的 `convertImageToGcode`（画布 shim 顶替 `resizeImage` 的 canvas），
 * 按组记录：重采样位图、预处理后的预览、行数、行 sha256、首尾 12 行与 `drawImage` 实参。
 *
 * ⚠️ 不记录 `lines` 全量（可能有几万行）：`linesSha256` 用 `lines.join('\n')` 的 sha256，
 * Kotlin 侧必须用同样的拼接方式（`joinToString("\n")`）计算。
 */
async function buildRasterConverterOutcome(): Promise<CaseOutcome> {
  const source = rasterConverterSampleImage()

  const variants: RasterVariant[] = [
    {
      name: 'line2line-horizontal-pwm-multidir',
      targetMmW: 40,
      targetMmH: 30,
      options: rasterOptions({
        tool: 'Line2Line',
        direction: 'Horizontal',
        quality: 3,
        pwm: true,
        useThreshold: true,
        threshold: 50,
        unidirectional: false,
        interpolation: 'high'
      })
    },
    {
      name: 'line2line-vertical-nopwm-uni-disablefastskip',
      targetMmW: 40,
      targetMmH: 30,
      options: rasterOptions({
        direction: 'Vertical',
        pwm: false,
        unidirectional: true,
        disableFastSkip: true,
        interpolation: 'low',
        brightness: 70,
        contrast: 150
      })
    },
    {
      name: 'dithering-diagonal-pwm',
      targetMmW: 40,
      targetMmH: 30,
      options: rasterOptions({
        tool: 'Dithering',
        direction: 'Diagonal',
        dithering: 'FloydSteinberg',
        pwm: true
      })
    },
    {
      name: 'line2line-diagonal-nopwm-minpower-nonzero-offset',
      targetMmW: 25.5,
      targetMmH: 18.25,
      options: rasterOptions({
        direction: 'Diagonal',
        pwm: false,
        minPower: 200,
        maxPower: 900,
        offsetX: 3.5,
        offsetY: -2.25,
        header: '\nG90\n; raster converter 头\n\n  G21  \n\n',
        footer: '\nM5\n; raster converter 尾\n\nG0 X0 Y0\n'
      })
    },
    {
      // 退化目标：heightMm = 0 → pixelH 走 Math.max(1, Math.round(...)) 夹取，
      // filesize = 0 → 走 Math.max(filesize, 0.0001) 兜底。
      name: 'degenerate-zero-height-target',
      targetMmW: 40,
      targetMmH: 0,
      options: rasterOptions({ quality: 3 })
    }
  ]

  const results: JsonValue[] = []
  for (const v of variants) {
    const shim = installRasterCanvasShim()
    let converted: Awaited<ReturnType<typeof convertImageToGcode>>
    try {
      converted = await convertImageToGcode(
        { data: source.data, width: source.width, height: source.height },
        source.width,
        source.height,
        v.targetMmW,
        v.targetMmH,
        v.options as unknown as Parameters<typeof convertImageToGcode>[5]
      )
    } finally {
      shim.restore()
    }

    if (shim.canvases.length !== 1) {
      throw new Error(`${v.name}: 期望 resizeImage 恰好创建 1 块画布，实际 ${shim.canvases.length} 块`)
    }
    const canvas = shim.canvases[0]
    if (canvas.width !== converted.pixelWidth || canvas.height !== converted.pixelHeight) {
      throw new Error(
        `${v.name}: 画布尺寸 ${canvas.width}x${canvas.height} != RasterResult ${converted.pixelWidth}x${converted.pixelHeight}`
      )
    }
    const resampled = encodeImage({ data: canvas.pixels, width: canvas.width, height: canvas.height })
    const preview = encodeImage(converted.preview)
    // 退化目标（某一维为 0 → 被 `Math.max(1, ...)` 夹成 1 像素）时，预处理确实可能是恒等的，
    // 那时"重采样 == 预览"是**正确结果**而不是夹具失效。用标志位记录，只有非退化用例才报错。
    const degenerateTarget = v.targetMmW <= 0 || v.targetMmH <= 0
    const identicalToPreview = resampled.sha256 === preview.sha256
    if (identicalToPreview && !degenerateTarget) {
      throw new Error(`${v.name}: 重采样位图与预处理后的预览相同，说明预处理没生效（夹具无意义）`)
    }

    results.push({
      name: v.name,
      targetMm: { widthMm: v.targetMmW, heightMm: v.targetMmH },
      degenerateTarget,
      identicalToPreview,
      options: v.options,
      pixelWidth: converted.pixelWidth,
      pixelHeight: converted.pixelHeight,
      res: converted.res,
      resampled: resampled as unknown as JsonValue,
      preview: preview as unknown as JsonValue,
      lineCount: converted.lines.length,
      linesSha256: sha256Hex(converted.lines.join('\n')),
      firstLines: converted.lines.slice(0, 12) as unknown as JsonValue,
      lastLines: converted.lines.slice(-12) as unknown as JsonValue,
      canvasDrawCalls: canvas.drawCalls as unknown as JsonValue
    })
  }

  const variantLines = results.map((r) => (r as { lineCount: number }).lineCount)

  return {
    name: 'raster-converter',
    modules: [
      'src/core/raster/RasterConverter.ts',
      'src/core/raster/ImageTransform.ts',
      'src/core/raster/dithering.ts'
    ],
    input:
      'synthetic 48x32 RGBA source bitmap (white background + 16x16 black diagonal gradient block + 1 px black line at y=24 + 14x10 half-transparent block with alpha 32..224 + 8x6 fully transparent block + 4 saturated colour dots); ' +
      '5 option sets covering Line2Line/Dithering x Horizontal/Vertical/Diagonal x pwm on/off x unidirectional on/off x threshold apply/composite, plus a degenerate 0-height target; ' +
      'resizeImage is replaced by a deterministic nearest-neighbour canvas shim (see result.resample)',
    entryCount: results.length,
    entryCounts: {
      variants: results.length,
      totalLines: variantLines.reduce((a, b) => a + b, 0),
      maxLines: Math.max(...variantLines),
      resampledBitmaps: results.length,
      optionFields: Object.keys(DEFAULT_RASTER_OPTIONS).length
    },
    result: {
      directionLabels: DIRECTION_LABELS as unknown as JsonValue,
      defaultOptions: DEFAULT_RASTER_OPTIONS as unknown as JsonValue,
      resample: {
        definition:
          'dx,dy 为目标像素；sx = sx0 + floor((dx + 0.5) * sw / dw)，sy = sy0 + floor((dy + 0.5) * sh / dh)，夹到源矩形内，取该源像素 RGBA 原样写入；目标尺寸按 Math.max(0, Math.trunc())',
        kernel: 'nearest-neighbour (deterministic, not a browser kernel)',
        browserKernelCaptured: false,
        smoothingIgnored: true,
        notes: [
          'v2 的 resizeImage 走 document.createElement("canvas") + ctx.drawImage，真实重采样内核由浏览器实现，Node 里不存在',
          '本 case 只替换画布这一层：grayScale 之后的整条管线（含 getSegments / optimizeLine2Line / 头尾拼装）是 v2 的真实代码',
          'imageSmoothingEnabled / imageSmoothingQuality 被记录但被最近邻内核忽略；插值质量在 Kotlin 侧是 ResizeSampler 接缝的实现细节',
          'getSegments 是 RasterConverter.ts 的模块私有函数（未 export），无法单独捕获；它的输出由最终 lines 的 sha256 间接钉住',
          'maxRes = sqrt(maxSize / max(filesize, 0.0001)) 生效的一侧需要 22000x22000 像素才可能触发，夹具不可承载；本例只钉住 res = min(maxRes, quality) = quality 的一侧'
        ]
      },
      source: encodeImage(source) as unknown as JsonValue,
      variants: results
    }
  }
}

/**
 * v2 的 `convertImageToGcode` 是 async 函数（函数体里没有任何 await）。
 * 现有 16 个 case 与 `main()` 全是同步的；为了**不改动 `main()`/`CASES` 的结构与顺序**，
 * 这里在模块顶层 await 一次，把结果折叠成同步的 `CaseOutcome`。
 */
const RASTER_CONVERTER_OUTCOME: CaseOutcome = await buildRasterConverterOutcome()

/** 同步包装：真正的构建已在模块顶层 await 完成。 */
function caseRasterConverter(): CaseOutcome {
  return RASTER_CONVERTER_OUTCOME
}

const CASES: (() => CaseOutcome)[] = [
  casePotrace,
  caseCenterline,
  caseStrokeAnalysis,
  caseDithering,
  caseImageTransform,
  caseImageVector,
  caseHershey,
  casePolylinesToGcode,
  caseSvgToGcode,
  caseGcodeAnalysis,
  caseGrblMessages,
  caseGrblVersion,
  caseSettingsPreset,
  caseFormatDecimal,
  caseFitToTravel,
  caseKeepAliveFormat,
  caseRasterConverter
]

function main(): void {
  const previousHash = process.env['GOLDEN_PREVIOUS_HASH'] // reserved for CI double-run checks
  void previousHash

  if (!existsSync(OUT_DIR)) mkdirSync(OUT_DIR, { recursive: true })

  const outcomes = CASES.map((fn) => fn())
  const names = new Set(outcomes.map((o) => o.name))
  if (names.size !== outcomes.length) throw new Error('duplicate case names')

  const manifestCases: Record<string, JsonValue>[] = []
  const negativeZeroPaths: string[] = []
  let totalBytes = 0
  let totalEntries = 0

  for (const o of outcomes) {
    const fixture = {
      case: o.name,
      generator: GENERATOR_REL,
      generatorCommand: GENERATOR_COMMAND,
      modules: o.modules,
      input: o.input,
      entryCount: o.entryCount,
      entryCounts: o.entryCounts,
      result: o.result
    }
    assertJsonSafe(fixture, o.name, negativeZeroPaths)
    const text = JSON.stringify(fixture, null, 2) + '\n'
    const file = `${o.name}.json`
    const path = join(OUT_DIR, file)
    writeFileSync(path, text, 'utf8')
    const bytes = Buffer.byteLength(text, 'utf8')
    const sha = sha256Hex(text)
    totalBytes += bytes
    totalEntries += o.entryCount

    manifestCases.push({
      case: o.name,
      outputFile: file,
      bytes,
      sha256: sha,
      entryCount: o.entryCount,
      entryCounts: o.entryCounts as unknown as JsonValue,
      modules: o.modules as unknown as JsonValue,
      input: o.input
    })
    console.log(`${sha.slice(0, 12)}  ${String(bytes).padStart(7)} B  ${file.padEnd(26)} entries=${o.entryCount}`)
  }

  const manifest = {
    schemaVersion: 1,
    oracle: 'src/core/** (v2 TypeScript core — read-only, must not be modified)',
    generator: GENERATOR_REL,
    command: GENERATOR_COMMAND,
    runtime: { node: process.version, platform: process.platform },
    shims: [
      'ImageData: { data: Uint8ClampedArray, width, height }',
      'localStorage: in-memory Map (empty at start of every run)',
      'DOMParser: minimal well-formed-XML DOM (documentElement/children/tagName/getAttribute/querySelector) used only by svg-to-gcode'
    ],
    nondeterminismHandling: [
      'dithering Random mode: Date.now() pinned to 1700000000000 for that single call (recorded per mode as dateNowOverride)',
      'no Math.random is reachable from the captured cases',
      'localStorage/AppSettings start from DEFAULT_SETTINGS; AppSettings mutations are restored inside the fit-to-travel case'
    ],
    floatFidelity: 'JSON.stringify default (shortest round-trip double) — no rounding, no fixed formatting',
    cases: manifestCases as unknown as JsonValue,
    totals: { cases: outcomes.length, bytes: totalBytes, entries: totalEntries }
  }
  assertJsonSafe(manifest, 'manifest', negativeZeroPaths)
  const manifestText = JSON.stringify(manifest, null, 2) + '\n'
  writeFileSync(join(OUT_DIR, 'manifest.json'), manifestText, 'utf8')

  const expected = new Set([...outcomes.map((o) => `${o.name}.json`), 'manifest.json'])
  const extra = readdirSync(OUT_DIR).filter((f) => f.endsWith('.json') && !expected.has(f))
  if (extra.length > 0) console.log(`note: pre-existing files not written by this run: ${extra.join(', ')}`)

  if (negativeZeroPaths.length > 0) {
    console.log(`note: ${negativeZeroPaths.length} negative-zero number(s) serialized as 0:`)
    for (const p of negativeZeroPaths.slice(0, 10)) console.log(`  ${p}`)
  }

  console.log(
    `\nmanifest.json written — ${outcomes.length} cases, ${totalBytes} fixture bytes, ${totalEntries} entries, ` +
      `sha256 of manifest ${sha256Hex(manifestText).slice(0, 12)}`
  )
}

main()
