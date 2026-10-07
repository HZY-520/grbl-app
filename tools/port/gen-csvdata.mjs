#!/usr/bin/env node
// 从 src/core/grbl/csvData.ts 生成 core/src/commonMain/kotlin/com/lasergrbl/core/grbl/CsvData.kt
//
// 设计要点：
//  * 不做正则/手工复制，而是用 TypeScript 官方编译器 API 解析源文件 AST，
//    按源码字面量顺序逐个读取对象字面量的键与字符串字面量的 *真实值*
//    （StringLiteral.text 已经是解码后的值，源码里的 \" 会还原成 "，
//     所以生成侧不需要再猜源码的转义规则）。
//  * 顺序 = 源码字面量顺序（不使用 localeCompare / 排序）。
//  * 完全确定性：输出只依赖源文件字节内容 + 源文件 sha256。
//  * 产物里嵌入了源文件 sha256 与内容摘要，便于在 Kotlin 运行时自检。
//
// 用法：
//   node tools/port/gen-csvdata.mjs            # 生成 Kotlin 文件（默认输出路径）
//   node tools/port/gen-csvdata.mjs --out <p>  # 生成到指定路径
//   node tools/port/gen-csvdata.mjs --stdout   # 打印到 stdout，不写文件
//   node tools/port/gen-csvdata.mjs --json     # 打印规范化数据 JSON（校验用）
//   node tools/port/gen-csvdata.mjs --check    # 只打印统计与 sha256，不写文件

import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { dirname, resolve, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = resolve(HERE, '..', '..')

const TS_SOURCE = resolve(REPO_ROOT, 'src/core/grbl/csvData.ts')
const DEFAULT_OUT = resolve(
  REPO_ROOT,
  'core/src/commonMain/kotlin/com/lasergrbl/core/grbl/CsvData.kt',
)

/** 需要移植的三个常量，顺序即 Kotlin 中属性的声明顺序。 */
const TABLES = ['SETTING_CODES', 'ALARM_CODES', 'ERROR_CODES']

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
 * 解析 csvData.ts，返回三个表的原始（顺序敏感）数据结构。
 * @returns {Record<string, Array<[string, Array<[string, string[]]>]>>}
 */
function parseTables(ts, sourceText) {
  const sf = ts.createSourceFile(
    'csvData.ts',
    sourceText,
    ts.ScriptTarget.ESNext,
    /* setParentNodes */ false,
    ts.ScriptKind.TS,
  )

  /** @type {Map<string, import('typescript').ObjectLiteralExpression>} */
  const found = new Map()

  for (const stmt of sf.statements) {
    if (!ts.isVariableStatement(stmt)) continue
    // 只接受 `export const NAME = {...}`（跳过 `export type` 等声明）
    const isExported = (stmt.modifiers ?? []).some(
      (m) => m.kind === ts.SyntaxKind.ExportKeyword,
    )
    if (!isExported) continue
    for (const decl of stmt.declarationList.declarations) {
      if (!ts.isIdentifier(decl.name)) continue
      const name = decl.name.text
      if (!TABLES.includes(name)) continue
      if (!decl.initializer || !ts.isObjectLiteralExpression(decl.initializer)) {
        throw new Error(`${name} 的初始化器不是对象字面量，无法移植`)
      }
      found.set(name, decl.initializer)
    }
  }

  for (const name of TABLES) {
    if (!found.has(name)) throw new Error(`源文件中未找到 export const ${name}`)
  }

  /** 读取对象字面量：保持源码顺序，键必须是字符串字面量。 */
  const readObject = (node, where) => {
    const out = []
    const seen = new Set()
    for (const prop of node.properties) {
      if (!ts.isPropertyAssignment(prop)) {
        throw new Error(`${where}: 含非 PropertyAssignment 成员，无法移植`)
      }
      if (!ts.isStringLiteralLike(prop.name)) {
        throw new Error(`${where}: 键不是字符串字面量，无法移植`)
      }
      const key = prop.name.text // 已解码的真实键值
      if (seen.has(key)) throw new Error(`${where}: 重复键 ${JSON.stringify(key)}`)
      seen.add(key)
      out.push([key, prop.initializer])
    }
    return out
  }

  const result = {}
  for (const name of TABLES) {
    const outer = []
    for (const [group, innerNode] of readObject(found.get(name), name)) {
      if (!ts.isObjectLiteralExpression(innerNode)) {
        throw new Error(`${name}.${group}: 值不是对象字面量`)
      }
      const inner = []
      for (const [code, arrNode] of readObject(innerNode, `${name}.${group}`)) {
        if (!ts.isArrayLiteralExpression(arrNode)) {
          throw new Error(`${name}.${group}.${code}: 值不是数组字面量`)
        }
        const strings = arrNode.elements.map((el, i) => {
          if (!ts.isStringLiteralLike(el)) {
            throw new Error(`${name}.${group}.${code}[${i}]: 元素不是字符串字面量`)
          }
          return el.text // 已解码的真实字符串内容
        })
        inner.push([code, strings])
      }
      outer.push([group, inner])
    }
    result[name] = outer
  }
  return result
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

/** 统计 escape 使用情况，用于报告中说明。 */
function escapeStats(tables) {
  const stats = {
    totalStrings: 0,
    stringsWithBackslash: 0,
    stringsWithDoubleQuote: 0,
    stringsWithDollar: 0,
    stringsWithControlChar: 0,
    stringsWithNonAscii: 0,
    backslashCount: 0,
    doubleQuoteCount: 0,
    dollarCount: 0,
    nonAsciiCount: 0,
    controlSamples: [],
    samples: {},
  }
  const note = (kind, ctx, value) => {
    stats.samples[kind] ??= []
    if (stats.samples[kind].length < 6) stats.samples[kind].push({ ctx, value })
  }
  for (const table of TABLES) {
    for (const [group, inner] of tables[table]) {
      for (const [code, strings] of inner) {
        strings.forEach((s, i) => {
          stats.totalStrings++
          const ctx = `${table}.${group}.${code}[${i}]`
          if (s.includes('\\')) {
            stats.stringsWithBackslash++
            stats.backslashCount += (s.match(/\\/g) ?? []).length
            note('backslash', ctx, s)
          }
          if (s.includes('"')) {
            stats.stringsWithDoubleQuote++
            stats.doubleQuoteCount += (s.match(/"/g) ?? []).length
            note('doubleQuote', ctx, s)
          }
          if (s.includes('$')) {
            stats.stringsWithDollar++
            stats.dollarCount += (s.match(/\$/g) ?? []).length
            note('dollar', ctx, s)
          }
          // eslint-disable-next-line no-control-regex
          const ctrl = s.match(/[\u0000-\u001f\u007f]/g)
          if (ctrl) {
            stats.stringsWithControlChar++
            stats.controlSamples.push({ ctx, value: JSON.stringify(s) })
            note('control', ctx, JSON.stringify(s))
          }
          if (/[^\u0000-\u007f]/.test(s)) {
            stats.stringsWithNonAscii++
            stats.nonAsciiCount += (s.match(/[^\u0000-\u007f]/g) ?? []).length
            note('nonAscii', ctx, s)
          }
        })
      }
    }
  }
  return stats
}

/** 规范化数据（用于 --json 与内容摘要）；顺序敏感、逐字符。 */
function canonicalData(tables, sourceSha) {
  return {
    source: repoRel(TS_SOURCE),
    sourceSha256: sourceSha,
    tables: TABLES.map((name) => ({
      name,
      groups: tables[name].map(([group, inner]) => ({
        group,
        entries: inner.map(([code, strings]) => ({ code, strings })),
      })),
    })),
  }
}

function buildKotlin(tables, sourceSha, sourceText) {
  const stats = escapeStats(tables)
  const payloadSha = sha256(JSON.stringify(canonicalData(tables, sourceSha)))

  const lines = []
  lines.push('// 由 tools/port/gen-csvdata.mjs 从 src/core/grbl/csvData.ts 生成，请勿手改')
  lines.push(`// source: ${repoRel(TS_SOURCE)}`)
  lines.push(`// source-sha256: ${sourceSha}`)
  lines.push(`// content-sha256: ${payloadSha}`)
  lines.push(
    `// source-bytes: ${Buffer.byteLength(sourceText, 'utf8')}  source-lines: ${sourceText.split('\n').length - 1}`,
  )
  lines.push(
    `// 重新生成: node tools/port/gen-csvdata.mjs    （输出为确定性结果，重复执行 sha256 不变）`,
  )
  lines.push('')
  lines.push('package com.lasergrbl.core.grbl')
  lines.push('')
  lines.push('/**')
  lines.push(' * GRBL 参数 / 报警码 / 错误码对照表，由 TypeScript 版本 (`src/core/grbl/csvData.ts`) 机械移植。')
  lines.push(' *')
  lines.push(' * 结构: 表 -> 固件族 (如 "v1.1" / "v0.9" / "ortur.*") -> 编号字符串 -> 字符串列表。')
  lines.push(' * 所有 Map/List 均为 insertion-ordered (`linkedMapOf` / `listOf`)，')
  lines.push(' * 迭代顺序与 TypeScript 对象字面量书写顺序完全一致。')
  lines.push(' */')
  lines.push('object CsvData {')
  lines.push(`    /** 源文件 \`src/core/grbl/csvData.ts\` 的 sha256。 */`)
  lines.push(`    const val SOURCE_SHA256: String = "${sourceSha}"`)
  lines.push('')
  lines.push(`    /** 三张表内容的规范化摘要 (sha256)，用于运行时/CI 自检。 */`)
  lines.push(`    const val CONTENT_SHA256: String = "${payloadSha}"`)
  lines.push('')

  for (const name of TABLES) {
    const groups = tables[name]
    const innerTotal = groups.reduce((n, [, inner]) => n + inner.length, 0)
    lines.push(`    // ${name}: ${groups.length} 个固件族, ${innerTotal} 条记录`)
    lines.push(
      `    val ${name}: Map<String, Map<String, List<String>>> = linkedMapOf<String, Map<String, List<String>>>(`,
    )
    groups.forEach(([group, inner], gi) => {
      const isLastGroup = gi === groups.length - 1
      lines.push(`        // ${group} (${inner.length})`)
      lines.push(`        ${kotlinString(group)} to linkedMapOf<String, List<String>>(`)
      inner.forEach(([code, strings], ei) => {
        const isLastEntry = ei === inner.length - 1
        const sep = isLastEntry ? '' : ','
        const body = strings.map(kotlinString).join(', ')
        if (body.length <= 96 && !strings.some((s) => s.includes('\n'))) {
          lines.push(`            ${kotlinString(code)} to listOf(${body})${sep}`)
        } else {
          const items = strings.map(kotlinString)
          lines.push(`            ${kotlinString(code)} to listOf(`)
          items.forEach((it, si) => {
            lines.push(`                ${it}${si === items.length - 1 ? '' : ','}`)
          })
          lines.push(`            )${sep}`)
        }
      })
      lines.push(`        )${isLastGroup ? '' : ','}`)
    })
    lines.push('    )')
    lines.push('')
  }

  lines.push('    /** 便于快速自检: 三张表的条目数汇总（固件族下的记录条数之和）。 */')
  lines.push('    val TOTAL_ENTRIES: Int =')
  lines.push(
    TABLES.map((n) => `        ${n}.values.sumOf { it.size }`).join(' +\n'),
  )
  lines.push('}')
  lines.push('')

  return { text: lines.join('\n'), stats, payloadSha }
}

function analyzeSource(sourceText, tables) {
  const perTable = {}
  for (const name of TABLES) {
    const groups = tables[name]
    perTable[name] = {
      groups: groups.length,
      entries: groups.reduce((n, [, inner]) => n + inner.length, 0),
      groupOrder: groups.map(([g]) => g),
      strings: groups.reduce(
        (n, [, inner]) => n + inner.reduce((m, [, ss]) => m + ss.length, 0),
        0,
      ),
    }
  }
  return {
    source: repoRel(TS_SOURCE),
    sourceBytes: Buffer.byteLength(sourceText, 'utf8'),
    sourceLines: sourceText.split('\n').length - 1,
    sourceSha256: sha256(sourceText),
    totals: {
      groups: TABLES.reduce((n, t) => n + perTable[t].groups, 0),
      entries: TABLES.reduce((n, t) => n + perTable[t].entries, 0),
      strings: TABLES.reduce((n, t) => n + perTable[t].strings, 0),
    },
    perTable,
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
  const tables = parseTables(ts, sourceText)
  const sourceSha = sha256(sourceText)

  if (mode.json) {
    process.stdout.write(JSON.stringify(canonicalData(tables, sourceSha)) + '\n')
    return
  }

  const info = analyzeSource(sourceText, tables)
  const { text, stats, payloadSha } = buildKotlin(tables, sourceSha, sourceText)

  if (mode.check || mode.stats) {
    const report = {
      ...info,
      contentSha256: payloadSha,
      kotlinBytes: Buffer.byteLength(text, 'utf8'),
      kotlinLines: text.split('\n').length - 1,
      escapes: {
        stringsWithBackslash: stats.stringsWithBackslash,
        backslashChars: stats.backslashCount,
        stringsWithDoubleQuote: stats.stringsWithDoubleQuote,
        doubleQuoteChars: stats.doubleQuoteCount,
        stringsWithDollar: stats.stringsWithDollar,
        dollarChars: stats.dollarCount,
        stringsWithControlChar: stats.stringsWithControlChar,
        stringsWithNonAscii: stats.stringsWithNonAscii,
        nonAsciiChars: stats.nonAsciiCount,
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
      `  ${info.totals.groups} 个固件族 / ${info.totals.entries} 条记录 / ${info.totals.strings} 个字符串`,
      `  ${Buffer.byteLength(text, 'utf8')} 字节, ${text.split('\n').length - 1} 行`,
    ].join('\n') + '\n',
  )
}

main().catch((err) => {
  process.stderr.write(`gen-csvdata: ${err?.stack ?? err}\n`)
  process.exitCode = 1
})
