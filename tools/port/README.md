# tools/port

TypeScript → Kotlin 机械移植工具。原则：**不手抄大表**，一切由可复现的脚本生成。

## gen-csvdata.mjs

把 `src/core/grbl/csvData.ts` 里的 `SETTING_CODES` / `ALARM_CODES` / `ERROR_CODES`
移植为 `core/src/commonMain/kotlin/com/lasergrbl/core/grbl/CsvData.kt`。

`src/**` 是冻结的 v2 事实源，本工具**只读**它，永不修改。

### 重新生成

在仓库根目录执行（Node v24，ESM，无需构建）：

```powershell
node tools/port/gen-csvdata.mjs
```

输出固定为 `core/src/commonMain/kotlin/com/lasergrbl/core/grbl/CsvData.kt`，脚本会自动建目录。
其他模式：

```powershell
node tools/port/gen-csvdata.mjs --check          # 只打印统计/摘要，不写文件（JSON）
node tools/port/gen-csvdata.mjs --stats          # 同 --check
node tools/port/gen-csvdata.mjs --stdout         # 打到 stdout，不写文件
node tools/port/gen-csvdata.mjs --json           # 规范化数据 JSON（供外部逐字符比对）
node tools/port/gen-csvdata.mjs --out <路径>     # 写到指定路径
```

依赖仓库本地 `typescript`（`devDependencies`，脚本通过 `require.resolve('typescript', { paths: [repoRoot] })`
定位）。缺依赖时先 `npm install`。

### 实现要点（为什么不会漂移）

- 用 **TypeScript 官方编译器 API**（`ts.createSourceFile`）解析 AST，而不是正则。
  按 `ObjectLiteralExpression` 的 `properties` 数组顺序读取，因此**顺序天然等于源码字面量顺序**，
  不做任何排序、不使用 `localeCompare`。
- 直接取 `StringLiteral.text`（已是解码后的真实值），所以源码里的 `\"` 会被正确还原成 `"`，
  生成侧不需要猜源码的转义规则，也就不会双重转义。
- 结构上遇到非字符串字面量的键、非对象字面量的值、重复键都会**直接报错退出**，
  不会静默产出错误数据。
- 只依赖源文件字节内容，产物完全确定：**同一份源文件重复执行，输出 sha256 不变**。

### 生成文件里嵌了什么

Kotlin 头部注释与常量携带可校验的指纹，便于在 CI 或运行时自检：

| 位置 | 含义 |
| --- | --- |
| `// source-sha256` | `src/core/grbl/csvData.ts` 的 sha256（按 UTF-8 字节） |
| `// content-sha256` | 三张表**规范化数据**（表→固件族→编号→字符串，保序）JSON 的 sha256 |
| `CsvData.SOURCE_SHA256` | 同上 source-sha256，供 Kotlin 侧读取 |
| `CsvData.CONTENT_SHA256` | 同上 content-sha256 |

`CONTENT_SHA256` 的取值方式与 `--json` 输出一致，所以可以跨语言互相校验。

### 一致性语义

- 三张表都是 `Map<String, Map<String, List<String>>>`，构造用 `linkedMapOf(...)`（外层与内层）
  和 `listOf(...)`，全部 insertion-ordered：**迭代顺序与 TypeScript 对象字面量书写顺序逐一相同**。
- 字符串逐字节保留：中文、`$`、`"`、末尾空格、全角标点、源码里的原有笔误一律照抄，不"顺手修"。
- 转义规则：`\` → `\\`，`"` → `\"`，`$` → `\$`，控制字符 → `\n` `\r` `\t` `\b` `\uXXXX`。
  实测本表只用到 `\$` 与 `\"`（见下）。

## 如何验证

### 1. 确定性（同一源文件，两次生成字节相同）

```powershell
node tools/port/gen-csvdata.mjs; (Get-FileHash core/src/commonMain/kotlin/com/lasergrbl/core/grbl/CsvData.kt -Algorithm SHA256).Hash
node tools/port/gen-csvdata.mjs; (Get-FileHash core/src/commonMain/kotlin/com/lasergrbl/core/grbl/CsvData.kt -Algorithm SHA256).Hash
# 两行必须一致
```

### 2. 数据与源文件同源（推荐，一条命令）

`--json` 打印的规范化数据的 sha256，就是写进 Kotlin 的 `content-sha256`
（注意：该值按 **去掉 `--json` 结尾换行后的 JSON 文本** 计算，下面的命令已按此处理）：

```powershell
node -e "const{execFileSync}=require('child_process');const{createHash}=require('crypto');const f=require('fs');const j=execFileSync(process.execPath,['tools/port/gen-csvdata.mjs','--json'],{encoding:'utf8'}).replace(/\n$/,'');const h=createHash('sha256').update(j,'utf8').digest('hex');const kt=f.readFileSync('core/src/commonMain/kotlin/com/lasergrbl/core/grbl/CsvData.kt','utf8');console.log(kt.includes(h)?'OK '+h:'MISMATCH '+h)"
```

输出 `OK <sha256>` 即为一致（`<sha256>` 应与 `CsvData.CONTENT_SHA256` 相同）。
任何时候改了 `csvData.ts` 或手改了 `CsvData.kt`，这条命令都会变成 `MISMATCH`。

### 3. 条目数 / 顺序 / 逐字符内容（本次移植的实测结果）

`--check` 输出的计数来自 TS AST，和 `CsvData.kt` 里的实测结构对比结果如下（本轮全部通过）：

| 表 | 固件族 TS/Kotlin | 记录条数 TS/Kotlin | 字符串数 | 内容不一致条目 |
| --- | --- | --- | --- | --- |
| `SETTING_CODES` | 8 / 8 | 426 / 426 | 1278 | 0 |
| `ALARM_CODES` | 3 / 3 | 39 / 39 | 78 | 0 |
| `ERROR_CODES` | 3 / 3 | 158 / 158 | 316 | 0 |
| **合计** | **14 / 14** | **623 / 623** | **1672** | **0** |

同轮实测的产物信息（LF 换行、UTF-8、无 BOM）：

| 指标 | 值 |
| --- | --- |
| 产物 sha256 | `e456b2c177d0da796404df099bf72948e261c984defe0cca5ece68a4fce52d76` |
| 产物大小 | 95039 字节（源文件 82541 字节） |
| 产物行数 | 1599 行 |
| `source-sha256` / `CONTENT_SHA256` | `260c18d5…f2376` / `45f2d207…c926` |

产物 sha256 会随生成器模板变化，以实际执行结果为准；`source-sha256` 与 `CONTENT_SHA256`
只取决于 `csvData.ts`，是真正需要长期稳定的指标。

- 外层键顺序：`SETTING_CODES` = `v0.8, v0.9, v1.1, ortur.v1.4.x, ortur.v1.5.x, ortur.v1.7.x, ortur.GrblHal, longer.nanoduo`；
  `ALARM_CODES` / `ERROR_CODES` = `standard, ortur.GrblHal, longer.nanoduo`。与 TS 字面量顺序完全一致。
- 逐字符比对 1672 个字符串（含每个 entry 的数组长度），mismatch = 0。

### 4. 重新做一次"结构级"比对（可选，独立于生成器）

如果需要再次证明 Kotlin 侧结构与 TS 完全一致（而不是只看摘要），可写一个一次性脚本：

1. `node tools/port/gen-csvdata.mjs --json > data.json` 拿到期望数据；
2. 用严格切片解析器把 `CsvData.kt` 里三张表的 `linkedMapOf(...)` 还原成结构
   （解析 `"..."` 字面量时按 Kotlin 规则反转义 `\$` `\"` `\\` `\n` `\r` `\t` `\b` `\uXXXX`；
   注意切片必须以 `\n    )\n\n` 定位外层表的结束括号，否则会少切一个字符）；
3. 逐表比较：外层键顺序、内层键顺序、每个 entry 的数组长度与每个字符串内容。

### 5. 转义情况（源码里需要转义的字符）

源数据里只出现两种需要转义的字符，生成器处理如下：

| 情况 | 出现次数 | 源码写法 | Kotlin 输出 |
| --- | --- | --- | --- |
| 键 `"$-Code"`（8 个固件族的设置表末尾各 1 个，共 9 个键） | 9 处 `$` | `"$-Code"` | `"\$-Code"` |
| 正文含 `$` 的字符串（`$setting` / `$H` / `$TPW` / `'$'` / `$TPW` 等） | 20 处 `$` | `... $H ...` | `... \$H ...` |
| 正文含 `"` 的字符串（`Enables "normal" processing ...`，出现在 `ortur.v1.5.x.39[2]`、`ortur.v1.7.x.39[2]`） | 4 处 `"` | `\"normal\"` | `\"normal\"` |

统计口径：生成文件中转义反斜杠共 33 个 = 29 个 `\$` + 4 个 `\"`，另有 0 个 `\\`；
文件里没有未转义的 `$`（`[^\\]\$` 匹配数 = 0）。

其余字符**不需要**转义，均原样输出：中文（全角标点、顿号、括号）、反引号 `` ` ``、
单引号 `'`、`~` `?` `!` `!` `<` `>` `^`、非 ASCII 字符 102 种。
未出现控制字符（`\n` `\r` `\t` `\b` 计数均为 0），也**没有** `$` 后紧跟标识符导致
Kotlin 字符串模板误插值的情况（已全量扫描，裸 `$` = 0）。

### 6. 不变量：不要手改生成物

`CsvData.kt` 首行即 `// 由 tools/port/gen-csvdata.mjs 从 src/core/grbl/csvData.ts 生成，请勿手改`。
任何内容变更都必须在 `src/core/grbl/csvData.ts` 侧做，然后重新生成——
否则 `content-sha256` 会对不上，上面第 2 步的校验会失败。
