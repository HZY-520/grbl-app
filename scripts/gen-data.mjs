// 从 LaserGRBL 原始资源生成 TypeScript 数据模块
// 用法: node scripts/gen-data.mjs <LaserGRBL源码目录>
import fs from 'node:fs'
import path from 'node:path'

const SRC = process.argv[2] || '/root/src/LaserGRBL-master/LaserGRBL'
const OUT_CSV = path.resolve('src/core/grbl/csvData.ts')
const OUT_HERSHEY = path.resolve('src/core/text/hersheyData.ts')

// ---------- CSV ----------
function parseCsv(text, len) {
  const out = {}
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim()
    if (!line) continue
    // 处理逗号分隔（LaserGRBL 使用简单的逗号分割）
    const parts = line.split(',')
    const key = (parts.shift() || '').trim()
    const data = []
    for (let i = 0; i < len; i++) data.push((parts[i] ?? '').trim())
    // description 可能自带逗号，重新拼接
    if (parts.length > len) {
      data[len - 1] = parts.slice(len - 1).join(',').trim()
    }
    if (!(key in out)) out[key] = data
  }
  return out
}

const csvDir = path.join(SRC, 'CSV')
const settingsFiles = {
  'v0.8': ['setting_codes.v0.8.csv', 3],
  'v0.9': ['setting_codes.v0.9.csv', 3],
  'v1.1': ['setting_codes.v1.1.csv', 3],
  'ortur.v1.4.x': ['setting_codes.ortur.v1.4.x.csv', 3],
  'ortur.v1.5.x': ['setting_codes.ortur.v1.5.x.csv', 3],
  'ortur.v1.7.x': ['setting_codes.ortur.v1.7.x.csv', 3],
  'ortur.GrblHal': ['setting_codes.ortur.GrblHal.csv', 3],
  'longer.nanoduo': ['setting_codes.longer.nanoduo.csv', 3]
}
const alarmsFiles = {
  standard: ['alarm_codes.csv', 2],
  'ortur.GrblHal': ['alarm_codes.ortur.GrblHal.csv', 2],
  'longer.nanoduo': ['alarm_codes.longer.nanoduo.csv', 2]
}
const errorsFiles = {
  standard: ['error_codes.csv', 2],
  'ortur.GrblHal': ['error_codes.ortur.GrblHal.csv', 2],
  'longer.nanoduo': ['error_codes.longer.nanoduo.csv', 2]
}

function buildGroup(files) {
  const groups = {}
  for (const [name, [file, len]] of Object.entries(files)) {
    const p = path.join(csvDir, file)
    if (!fs.existsSync(p)) {
      console.warn('缺少文件', p)
      continue
    }
    groups[name] = parseCsv(fs.readFileSync(p, 'utf8'), len)
  }
  return groups
}

const settings = buildGroup(settingsFiles)
const alarms = buildGroup(alarmsFiles)
const errors = buildGroup(errorsFiles)

let ts = `// 本文件由 scripts/gen-data.mjs 从 LaserGRBL 原始 CSV 资源自动生成，请勿手工编辑。
/* eslint-disable */
export type CodeEntry = string[]

/** GRBL 设置项说明: 键为参数编号, 值为 [名称, 单位, 说明] */
export const SETTING_CODES: Record<string, Record<string, CodeEntry>> = ${JSON.stringify(settings, null, 2)}

/** GRBL 报警码: 键为编号, 值为 [简述, 详细说明] */
export const ALARM_CODES: Record<string, Record<string, CodeEntry>> = ${JSON.stringify(alarms, null, 2)}

/** GRBL 错误码: 键为编号, 值为 [简述, 详细说明] */
export const ERROR_CODES: Record<string, Record<string, CodeEntry>> = ${JSON.stringify(errors, null, 2)}
`
fs.mkdirSync(path.dirname(OUT_CSV), { recursive: true })
fs.writeFileSync(OUT_CSV, ts)
console.log('已生成', OUT_CSV)

// ---------- Hershey ----------
const hersheySrc = fs.readFileSync(path.join(SRC, 'Hershey', 'Hershey.cs'), 'utf8')

function extractArray(name) {
  const startRe = new RegExp(`string\\[\\]\\[\\]\\s+${name}\\s*=`)
  const m = startRe.exec(hersheySrc)
  if (!m) throw new Error('未找到数组 ' + name)
  let i = hersheySrc.indexOf('{', m.index)
  let depth = 0
  const start = i
  for (; i < hersheySrc.length; i++) {
    const c = hersheySrc[i]
    if (c === '{') depth++
    else if (c === '}') {
      depth--
      if (depth === 0) {
        i++
        break
      }
    }
  }
  const body = hersheySrc.slice(start, i)
  // 提取每个字符的字符串数组
  const chars = []
  const charRe = /new\s+string\[\]\s*\{([^}]*)\}/g
  let cm
  while ((cm = charRe.exec(body)) !== null) {
    const parts = []
    const sRe = /"((?:[^"\\]|\\.)*)"/g
    let sm
    while ((sm = sRe.exec(cm[1])) !== null) parts.push(sm[1])
    chars.push(parts)
  }
  return chars
}

const hor = extractArray('hor')
const ver = extractArray('ver')
console.log('Hershey 字符数: hor=%d ver=%d', hor.length, ver.length)

const hOut = `// 本文件由 scripts/gen-data.mjs 从 LaserGRBL Hershey.cs 自动生成，请勿手工编辑。
/* eslint-disable */
/** Hershey 矢量字体(横向) 字符 32..126 对应的 G 代码片段 */
export const HERSHEY_HORIZONTAL: string[][] = ${JSON.stringify(hor)}

/** Hershey 矢量字体(纵向) 字符 32..126 对应的 G 代码片段 */
export const HERSHEY_VERTICAL: string[][] = ${JSON.stringify(ver)}

/** 字符间距 */
export const HERSHEY_SPACE_BETWEEN = 0.3
`
fs.mkdirSync(path.dirname(OUT_HERSHEY), { recursive: true })
fs.writeFileSync(OUT_HERSHEY, hOut)
console.log('已生成', OUT_HERSHEY)