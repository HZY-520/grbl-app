#!/usr/bin/env node
// 从 src/core/text/hersheyData.ts 生成 core/src/commonMain/kotlin/com/lasergrbl/core/text/HersheyData.kt
//
// 设计要点：
//  * 不做正则/手工复制，而是用 TypeScript 官方编译器 API 解析源文件 AST，
//    按源码字面量顺序逐个读取数组字面量里的字符串字面量的 *真实值*
//    （StringLiteral.text 已经是解码后的值，源码里的 \" 会还原成 "，
//     所以生成侧不需要再猜源码的转义规则）。
//  * 顺序 = 源码字面量顺序；下标 i 对应字符码 32 + i（ASCII 32..126），不做任何排序。
//  * 表内容 sha256 按黄金样本的定义计算：sha256(JSON.stringify(字符串二维数组))，
//    与 tools/golden/generate.ts 的 caseHershey() 完全一致，便于双侧交叉自检。
//  * 完全确定性：输出只依赖源文件字节内容 + 源文件 sha256。
//
// 用法：
//   node tools/port/gen-hershey.mjs            # 生成 Kotlin 文件（默认输出路径）
//   node tools/port/gen-hershey.mjs --out <p>  # 生成到指定路径
//   node tools/port/gen-hershey.mjs --stdout   # 打印到 stdout，不写文件
//   node tools/port/gen-hershey.mjs --json     # 打印规范化摘要 JSON（校验用）
//   node tools/port/gen-hershey.mjs --check    # 只打印统计与 sha256，不写文件

import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { dirname, resolve, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = resolve(HERE, '..', '..')

const TS_SOURCE = resolve(REPO_ROOT, 'src/core/text/hersheyData.ts')
const DEFAULT_OUT = resolve(
  REPO_ROOT,
  'core/src/commonMain/kotlin/com/lasergrbl/core/text/HersheyData.kt',
)

/** 需要移植的两张字体表，顺序即 Kotlin 中属性的声明顺序。 */
const TABLES = ['HERSHEY_HORIZONTAL', 'HERSHEY_VERTICAL']

/** 字体表覆盖的字符码区间（源数据是数组，下标 0 对应 32）。 */
const FIRST_CODE = 32
const LAST_CODE = 126

/** 仓库内统一的相对路径写法（始终使用 /，与平台无关，保证可复现）。 */
function repoRel(absPath) {
  return relative(REPO_ROOT, absPath).split(sep).join('/')
}

function sha256(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex')
}

/**
 * 载入 TypeScript 编译器（优先仓库本地依赖，其次脚本自身解析路径）。
 * 只用于 `ts.createSourceFile`，不依赖任何 tsconfig。
 */
async function loadTypeScript() {
  const require = createRequire(import.meta.url)
  const candidates = []
  try {
    candidates.push(require.resolve('typescript', { paths: [REPO_ROOT] }))
  } catch {
    /* 继续尝试下一个 */
  }
  try {
    candidates.push(require.resolve('typescript'))
  } catch {
    /* 忽略 */
  }
  for (const id of candidates) {
    const mod = await import(id.startsWith('file:') ? id : `file://${id.replace(/\\/g, '/')}`)
    const ts = mod.default ?? mod
    if (ts && typeof ts.createSourceFile === 'function') return ts
  }
  throw new Error(
    '找不到 typescript。请在仓库根目录执行 `npm install`（devDependencies 已含 typescript）。',
  )
}

/**
 * 解析 hersheyData.ts，返回两张表的原始（顺序敏感）数据。
 * @returns {{tables: Record<string, string[][]>, spaceBetween: number, spaceBetweenText: string}}
 */
function parseFont(ts, sourceText) {
  const sf = ts.createSourceFile(
    'hersheyData.ts',
    sourceText,
    ts.ScriptTarget.ESNext,
    /* setParentNodes */ false,
    ts.ScriptKind.TS,
  )

  /** @type {Map<string, import('typescript').Expression>} */
  const found = new Map()

  for (const stmt of sf.statements) {
    if (!ts.isVariableStatement(stmt)) continue
    // 只接受 `export const NAME = ...`（跳过 `export type` 等声明）
    const isExported = (stmt.modifiers ?? []).some((m) => m.kind === ts.SyntaxKind.ExportKeyword)
    if (!isExported) continue
    for (const decl of stmt.declarationList.declarations) {
      if (!ts.isIdentifier(decl.name)) continue
      const name = decl.name.text
      if (name !== 'HERSHEY_SPACE_BETWEEN' && !TABLES.includes(name)) continue
      if (!decl.initializer) throw new Error(`${name} 没有初始化器，无法移植`)
      if (found.has(name)) throw new Error(`${name} 重复声明，无法移植`)
      found.set(name, decl.initializer)
    }
  }

  for (const name of [...TABLES, 'HERSHEY_SPACE_BETWEEN']) {
    if (!found.has(name)) throw new Error(`源文件中未找到 export const ${name}`)
  }

  /** 读取二维字符串数组：保持源码顺序，元素必须是字符串字面量。 */
  const readTable = (name) => {
    const node = found.get(name)
    if (!ts.isArrayLiteralExpression(node)) {
      throw new Error(`${name} 的初始化器不是数组字面量，无法移植`)
    }
    return node.elements.map((glyph, gi) => {
      if (!ts.isArrayLiteralExpression(glyph)) {
        throw new Error(`${name}[${gi}] 不是数组字面量，无法移植`)
      }
      return glyph.elements.map((el, ei) => {
        if (!ts.isStringLiteralLike(el)) {
          throw new Error(`${name}[${gi}][${ei}] 不是字符串字面量，无法移植`)
        }
        return el.text // 已解码的真实字符串内容
      })
    })
  }

  const tables = {}
  for (const name of TABLES) {
    const table = readTable(name)
    if (table.length !== LAST_CODE - FIRST_CODE + 1) {
      throw new Error(
        `${name} 有 ${table.length} 个字形，期望 ${LAST_CODE - FIRST_CODE + 1} 个（字符码 ${FIRST_CODE}..${LAST_CODE}）`,
      )
    }
    table.forEach((glyph, i) => {
      if (glyph.length === 0) {
        process.stderr.write(`gen-hershey: 提示: ${name}[${i}]（字符码 ${FIRST_CODE + i}）是空字形\n`)
      }
    })
    tables[name] = table
  }

  const spaceNode = found.get('HERSHEY_SPACE_BETWEEN')
  if (!ts.isNumericLiteral(spaceNode)) {
    throw new Error('HERSHEY_SPACE_BETWEEN 不是数字字面量，无法移植')
  }
  const spaceBetweenText = spaceNode.getText(sf)
  const spaceBetween = Number(spaceBetweenText)
  if (!Number.isFinite(spaceBetween)) {
    throw new Error(`HERSHEY_SPACE_BETWEEN 不是有限数: ${spaceBetweenText}`)
  }

  return { tables, spaceBetween, spaceBetweenText }
}

/** Kotlin 字符串转义：\, ", $, 以及控制字符。 */
function kotlinString(value) {
  let out = '"'
  for (const ch of value) {
    const cp = ch.codePointAt(0)
    switch (ch) {
      case '\\':
        out += '\\\\'
        continue
      case '"':
        out += '\\"'
        continue
      case '$':
        out += '\\$'
        continue
      case '\n':
        out += '\\n'
        continue
      case '\r':
        out += '\\r'
        continue
      case '\t':
        out += '\\t'
        continue
      case '\b':
        out += '\\b'
        continue
      default:
        break
    }
    if (cp < 0x20 || cp === 0x7f) {
      out += '\\u' + cp.toString(16).padStart(4, '0')
    } else {
      out += ch
    }
  }
  return out + '"'
}

/**
 * 注释里展示该下标对应的字符。刻意避开 `*` / `/` / `\` 的字面量写法：
 * Kotlin 的块注释可以嵌套，注释里出现 `/*` 会直接编译失败。
 */
function glyphCharLabel(code) {
  if (code === 32) return 'SP'
  if (code === 42 || code === 47 || code === 92) {
    return 'U+' + code.toString(16).toUpperCase().padStart(4, '0')
  }
  return `'${String.fromCharCode(code)}'`
}

/** 统计 escape 使用情况，用于报告中说明（并确认 Kotlin 侧不需要转义）。 */
function escapeStats(tables) {
  const stats = {
    totalStrings: 0,
    totalChars: 0,
    maxStringLength: 0,
    maxGlyphStrings: 0,
    stringsWithBackslash: 0,
    stringsWithDoubleQuote: 0,
    stringsWithDollar: 0,
    stringsWithControlChar: 0,
    stringsWithNonAscii: 0,
    emptyGlyphs: [],
    samples: {},
  }
  const note = (kind, ctx, value) => {
    stats.samples[kind] ??= []
    if (stats.samples[kind].length < 6) stats.samples[kind].push({ ctx, value })
  }
  for (const table of TABLES) {
    tables[table].forEach((glyph, gi) => {
      const code = FIRST_CODE + gi
      if (glyph.length === 0) stats.emptyGlyphs.push(`${table}[${gi}] (${code})`)
      if (glyph.length > stats.maxGlyphStrings) stats.maxGlyphStrings = glyph.length
      glyph.forEach((s, si) => {
        stats.totalStrings++
        stats.totalChars += s.length
        if (s.length > stats.maxStringLength) stats.maxStringLength = s.length
        const ctx = `${table}[${gi}][${si}]`
        if (s.includes('\\')) {
          stats.stringsWithBackslash++
          note('backslash', ctx, s)
        }
        if (s.includes('"')) {
          stats.stringsWithDoubleQuote++
          note('doubleQuote', ctx, s)
        }
        if (s.includes('$')) {
          stats.stringsWithDollar++
          note('dollar', ctx, s)
        }
        // eslint-disable-next-line no-control-regex
        if (/[\u0000-\u001f\u007f]/.test(s)) {
          stats.stringsWithControlChar++
          note('control', ctx, JSON.stringify(s))
        }
        if (/[^\u0000-\u007f]/.test(s)) {
          stats.stringsWithNonAscii++
          note('nonAscii', ctx, s)
        }
      })
    })
  }
  return stats
}

/** 表内容的 sha256 —— 定义与黄金样本一致：sha256(JSON.stringify(二维数组))。 */
function tableSha256(table) {
  return sha256(JSON.stringify(table))
}

/** 规范化摘要（用于 --json 与自检）；顺序敏感、逐字符。 */
function canonicalData(tables, sourceSha) {
  return {
    source: repoRel(TS_SOURCE),
    sourceSha256: sourceSha,
    firstCode: FIRST_CODE,
    lastCode: LAST_CODE,
    tables: TABLES.map((name) => ({
      name,
      count: tables[name].length,
      glyphStrings: tables[name].reduce((n, g) => n + g.length, 0),
      sha256: tableSha256(tables[name]),
    })),
  }
}

function buildKotlin(tables, spaceBetweenText, sourceSha, sourceText) {
  const stats = escapeStats(tables)
  const payloadSha = sha256(JSON.stringify(canonicalData(tables, sourceSha)))

  const lines = []
  lines.push('// 由 tools/port/gen-hershey.mjs 从 src/core/text/hersheyData.ts 生成，请勿手改')
  lines.push(`// source: ${repoRel(TS_SOURCE)}`)
  lines.push(`// source-sha256: ${sourceSha}`)
  lines.push(`// content-sha256: ${payloadSha}`)
  lines.push(
    `// table-sha256-horizontal: ${tableSha256(tables.HERSHEY_HORIZONTAL)}  (sha256 of JSON.stringify(HERSHEY_HORIZONTAL)，与黄金样本同定义)`,
  )
  lines.push(
    `// table-sha256-vertical: ${tableSha256(tables.HERSHEY_VERTICAL)}  (sha256 of JSON.stringify(HERSHEY_VERTICAL)，与黄金样本同定义)`,
  )
  lines.push(
    `// source-bytes: ${Buffer.byteLength(sourceText, 'utf8')}  source-lines: ${sourceText.split('\n').length - 1}`,
  )
  lines.push(
    `// 重新生成: node tools/port/gen-hershey.mjs    （输出为确定性结果，重复执行 sha256 不变）`,
  )
  lines.push('')
  lines.push('package com.lasergrbl.core.text')
  lines.push('')
  lines.push('/**')
  lines.push(' * Hershey 矢量字体表，由 TypeScript 版本 (`src/core/text/hersheyData.ts`) 机械移植。')
  lines.push(' *')
  lines.push(' * 结构: 表 -> 字形（字符串列表，元素是 G0/G1/M3/M5 以及裸 X/Y 续接移动的片段）。')
  lines.push(
    ` * 下标 i 对应字符码 ${FIRST_CODE} + i（即 ASCII ${FIRST_CODE}..${LAST_CODE}，共 ${LAST_CODE - FIRST_CODE + 1} 个字形）；`,
  )
  lines.push(' * 顺序与源文件数组字面量顺序完全一致，不做任何排序。')
  lines.push(' * 每个字符串都与源文件逐字节相同（源数据是纯 ASCII，Kotlin 侧无需转义）。')
  lines.push(' */')
  lines.push('')
  lines.push(`/** 源文件 \`src/core/text/hersheyData.ts\` 的 sha256。 */`)
  lines.push(`const val HERSHEY_SOURCE_SHA256: String = "${sourceSha}"`)
  lines.push('')
  lines.push(`/** 两张表内容的规范化摘要 (sha256)，用于运行时/CI 自检。 */`)
  lines.push(`const val HERSHEY_CONTENT_SHA256: String = "${payloadSha}"`)
  lines.push('')
  lines.push('/** 横向字体表 `JSON.stringify` 的 sha256（定义见黄金样本 `golden/hershey.json`）。 */')
  lines.push(
    `const val HERSHEY_HORIZONTAL_SHA256: String = "${tableSha256(tables.HERSHEY_HORIZONTAL)}"`,
  )
  lines.push('')
  lines.push('/** 纵向字体表 `JSON.stringify` 的 sha256（定义见黄金样本 `golden/hershey.json`）。 */')
  lines.push(`const val HERSHEY_VERTICAL_SHA256: String = "${tableSha256(tables.HERSHEY_VERTICAL)}"`)
  lines.push('')
  lines.push('/** 字符间距（字体单位）；乘上缩放系数后即为毫米间距。 */')
  lines.push(`const val HERSHEY_SPACE_BETWEEN: Double = ${spaceBetweenText}`)
  lines.push('')

  for (const name of TABLES) {
    const table = tables[name]
    const strings = table.reduce((n, g) => n + g.length, 0)
    lines.push(
      `/** ${name === 'HERSHEY_HORIZONTAL' ? '横向' : '纵向'}字体表: ${table.length} 个字形, ${strings} 个片段。 */`,
    )
    lines.push(`val ${name}: List<List<String>> = listOf(`)
    table.forEach((glyph, gi) => {
      const code = FIRST_CODE + gi
      const isLast = gi === table.length - 1
      const sep = isLast ? '' : ','
      const body = glyph.map(kotlinString).join(', ')
      const label = `// #${code} ${glyphCharLabel(code)} (${glyph.length} 段)`
      if (body.length <= 96) {
        lines.push(`    ${label}`)
        lines.push(`    listOf(${body})${sep}`)
      } else {
        lines.push(`    ${label}`)
        lines.push(`    listOf(`)
        glyph.forEach((s, si) => {
          lines.push(`        ${kotlinString(s)}${si === glyph.length - 1 ? '' : ','}`)
        })
        lines.push(`    )${sep}`)
      }
    })
    lines.push(')')
    lines.push('')
  }

  return { text: lines.join('\n'), stats, payloadSha }
}

function analyzeSource(sourceText, tables) {
  return {
    source: repoRel(TS_SOURCE),
    sourceBytes: Buffer.byteLength(sourceText, 'utf8'),
    sourceLines: sourceText.split('\n').length - 1,
    sourceSha256: sha256(sourceText),
    firstCode: FIRST_CODE,
    lastCode: LAST_CODE,
    perTable: Object.fromEntries(
      TABLES.map((name) => [
        name,
        {
          glyphs: tables[name].length,
          strings: tables[name].reduce((n, g) => n + g.length, 0),
          chars: tables[name].reduce((n, g) => n + g.reduce((m, s) => m + s.length, 0), 0),
          emptyGlyphs: tables[name].filter((g) => g.length === 0).length,
          sha256: tableSha256(tables[name]),
        },
      ]),
    ),
  }
}

async function main() {
  const argv = process.argv.slice(2)
  const getFlagValue = (flag) => {
    const i = argv.indexOf(flag)
    return i >= 0 && i + 1 < argv.length ? argv[i + 1] : undefined
  }
  const mode = {
    stdout: argv.includes('--stdout'),
    json: argv.includes('--json'),
    check: argv.includes('--check'),
    stats: argv.includes('--stats'),
    out: getFlagValue('--out') ?? DEFAULT_OUT,
  }

  const sourceText = readFileSync(TS_SOURCE, 'utf8')
  const ts = await loadTypeScript()
  const { tables, spaceBetween, spaceBetweenText } = parseFont(ts, sourceText)
  const sourceSha = sha256(sourceText)

  if (mode.json) {
    process.stdout.write(
      JSON.stringify(
        { ...canonicalData(tables, sourceSha), spaceBetween, spaceBetweenText },
        null,
        2,
      ) + '\n',
    )
    return
  }

  const info = analyzeSource(sourceText, tables)
  const { text, stats, payloadSha } = buildKotlin(tables, spaceBetweenText, sourceSha, sourceText)

  if (mode.check || mode.stats) {
    const report = {
      ...info,
      spaceBetween,
      spaceBetweenText,
      contentSha256: payloadSha,
      kotlinBytes: Buffer.byteLength(text, 'utf8'),
      kotlinLines: text.split('\n').length - 1,
      escapes: {
        totalStrings: stats.totalStrings,
        totalChars: stats.totalChars,
        maxStringLength: stats.maxStringLength,
        maxGlyphStrings: stats.maxGlyphStrings,
        stringsWithBackslash: stats.stringsWithBackslash,
        stringsWithDoubleQuote: stats.stringsWithDoubleQuote,
        stringsWithDollar: stats.stringsWithDollar,
        stringsWithControlChar: stats.stringsWithControlChar,
        stringsWithNonAscii: stats.stringsWithNonAscii,
        emptyGlyphs: stats.emptyGlyphs,
        samples: stats.samples,
      },
    }
    process.stdout.write(JSON.stringify(report, null, 2) + '\n')
    return
  }

  if (mode.stdout) {
    process.stdout.write(text)
    return
  }

  const outPath = resolve(process.cwd(), mode.out)
  if (!existsSync(dirname(outPath))) mkdirSync(dirname(outPath), { recursive: true })
  writeFileSync(outPath, text, 'utf8')
  process.stdout.write(
    [
      `生成 ${repoRel(outPath)}`,
      `  源文件 sha256 : ${sourceSha}`,
      `  内容 sha256   : ${payloadSha}`,
      `  输出 sha256   : ${sha256(text)}`,
      `  横向表 sha256 : ${tableSha256(tables.HERSHEY_HORIZONTAL)}`,
      `  纵向表 sha256 : ${tableSha256(tables.HERSHEY_VERTICAL)}`,
      `  ${tables.HERSHEY_HORIZONTAL.length} + ${tables.HERSHEY_VERTICAL.length} 个字形 / ${stats.totalStrings} 个片段 / 间距 ${spaceBetweenText}`,
      `  ${Buffer.byteLength(text, 'utf8')} 字节, ${text.split('\n').length - 1} 行`,
    ].join('\n') + '\n',
  )
}

main().catch((err) => {
  process.stderr.write(`gen-hershey: ${err?.stack ?? err}\n`)
  process.exitCode = 1
})
