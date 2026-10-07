package com.lasergrbl.android.app

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.LiquidTextField
import com.lasergrbl.glasskit.theme.LiquidTheme

/**
 * 图案生成入口 —— 对应 v2 `src/ui/views/ConvertHubView.vue`（116 行）。
 *
 * ### 逐条对照
 * | v2 | 这里 |
 * | --- | --- |
 * | `ITEMS` 三个入口卡（图片 / 文字 / SVG） | [GlassNavCard]（壳层提供，与首页复用同一卡片） |
 * | 卡点击 → `router.push(path)` | `container.navigator.push(Screen.ConvertImage())` / `ConvertText()` / `ConvertSvg()` |
 * | `GlassSurface class="lg-section"` 使用流程 | [GlassCard] + `<ol>` 的中文列表 |
 * | 第四张卡：`已有 G 代码文件？` + `打开文件` 按钮 → `/file` | 同一行 + `navigator.selectTab(Tab.File)`（`/file` 是 tab 页） |
 *
 * ### 与 v2 的差异
 * v2 的 `router.push('/file')` 会保留 `/convert` 的返回栈；3.0 的 `Tab.File` 是**标签页**
 * （`selectTab` 清空返回栈，语义是"切标签"）。终点一致，差别只在"在 `/file` 按返回键
 * 回到生成中心还是回到首页"；3.0 选择与标签栏一致的行为（v2 的底部标签同样是切标签）。
 *
 * ### 本文件同时承载三个转换屏幕共用的排版助手
 * [ConvertNumField]、[ConvertChipRow]、[ConvertInlineSelect]、[convertFmt] 都放在这里
 * （同 package 的 `internal`），避免在三个屏幕文件里各抄一份导致文案漂移。
 */
@Composable
fun ConvertHubScreen(container: AppContainer, backdrop: Backdrop) {
    val colors = LiquidTheme.colors
    val navigator = container.navigator

    GlassScreenBody {
        GlassNavCard(
            backdrop = backdrop,
            icon = "image",
            title = "图片转雕刻",
            description = "将照片、位图转换为激光雕刻 G 代码，支持抖动与多档预处理",
            onClick = { navigator.push(Screen.ConvertImage()) }
        )
        GlassNavCard(
            backdrop = backdrop,
            icon = "text",
            title = "文字转雕刻",
            description = "使用 Hershey 矢量字体生成文字路径，支持横向 / 纵向排版",
            onClick = { navigator.push(Screen.ConvertText()) }
        )
        GlassNavCard(
            backdrop = backdrop,
            icon = "vector",
            title = "SVG 转雕刻",
            description = "导入矢量图，按轮廓生成路径，曲线自动离散为平滑折线",
            onClick = { navigator.push(Screen.ConvertSvg()) }
        )

        // 使用流程（v2 的 `<ol class="flow">`，四条中文逐字保留）
        GlassCard(backdrop) {
            Column(Modifier.fillMaxWidth()) {
                GlassSectionHeader("使用流程")
                Spacer(Modifier.height(6.dp))
                FLOW_STEPS.forEachIndexed { index, step ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        GlassText(
                            text = "${index + 1}.",
                            color = colors.textDim,
                            fontSize = 13.sp,
                            modifier = Modifier.width(18.dp)
                        )
                        GlassText(text = step, color = colors.textDim, fontSize = 13.sp)
                    }
                }
            }
        }

        GlassCard(backdrop) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                GlassText("已有 G 代码文件？", color = colors.textDim, fontSize = 13.sp)
                GlassIconButton(
                    backdrop = backdrop,
                    icon = "folder",
                    label = "打开文件",
                    size = GlassButtonSize.Small,
                    plain = true,
                    onClick = { navigator.selectTab(Tab.File) }
                )
            }
        }
    }
}

/** v2 `ConvertHubView.vue:49-52` 的流程说明，逐字保留。 */
private val FLOW_STEPS = listOf(
    "选择图案类型并导入素材",
    "设置尺寸与雕刻参数（速度、功率）",
    "生成 G 代码并预览路径",
    "回到首页开始雕刻"
)

// ---------------------------------------------------------------------------
// 转换屏幕共用的助手
// ---------------------------------------------------------------------------

/**
 * 数值输入的解析规则 —— 对应 v2 `ConvertHubView` 之外三个屏幕里的 `onNum(key, ev)`：
 * `parseFloat(value)` 之后 `if (!Number.isFinite(v)) return`，也就是**非法值回退原值**。
 *
 * [IntField] 对应 v2 的整数语义输入（行数、像素数、速度、功率等）；
 * [RealField] 对应允许小数的输入（尺寸、阈值、容差、倍率）。
 */
sealed class ConvertNumField {
    /** 纯整数（`parseFloat` 通过后取整，与 v2 的 `opts[k] = v` 一致 —— JS 侧没有额外取整，这里保留小数语义的差别） */
    data object IntField : ConvertNumField()

    /** 允许小数。 */
    data object RealField : ConvertNumField()
}

/**
 * 一行数值输入：标签在上、输入框在下，失焦时按 [field] 规则解析。
 *
 * 契约（与 v2 的 `@blur` + `GlassInput` 完全一致）：
 *  * 解析成功 → 调用 [onValue]；
 *  * 解析失败（空串 / 非数字）→ **回退原值**（[LiquidTextField] 在父层未接受草稿时会自动回弹）；
 *  * [IntField] 走 `KeyboardType.Number`，[RealField] 走 `KeyboardType.Decimal`（v2 是 `type="number"`）。
 */
@Composable
internal fun ConvertNumField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    field: ConvertNumField = ConvertNumField.RealField,
    enabled: Boolean = true,
    placeholder: String? = null,
    onReject: (() -> Unit)? = null
) {
    val colors = LiquidTheme.colors
    Column(modifier) {
        GlassText(label, color = colors.textDim, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        LiquidTextField(
            value = value,
            onValueChange = { },
            backdrop = null,
            placeholder = placeholder,
            enabled = enabled,
            keyboardType = when (field) {
                ConvertNumField.IntField -> KeyboardType.Number
                ConvertNumField.RealField -> KeyboardType.Decimal
            },
            onCommit = { draft ->
                val parsed = draft.trim().toDoubleOrNull()
                if (parsed == null || !parsed.isFinite()) {
                    // 非法值回退原值（v2：`if (!Number.isFinite(v)) return`）
                    onReject?.invoke()
                } else {
                    onValue(convertFmt(parsed))
                }
            }
        )
    }
}

/**
 * 把 `Double` 渲染成输入框里的字符串（与 v2 `String(opts[k])` 的观感一致）：
 * 整数不显示小数点，小数最多 3 位且去掉尾零。
 *
 * 注意：这里**只用于输入框回显**；G 代码里的数字一律由 `:core` 的
 * `jsNumberToString` / `fmt` 负责，不要用本函数参与数值输出。
 */
internal fun convertFmt(v: Double): String {
    if (!v.isFinite()) return "0"
    val scaled = Math.round(v * 1000.0) / 1000.0
    if (scaled == Math.floor(scaled)) return scaled.toLong().toString()
    return scaled.toString()
}

/** 把数值渲染到滑块 / 统计标签上（小数位可控，`decimals <= 0` 时取整）。 */
internal fun convertFmt(v: Double, decimals: Int): String =
    if (decimals <= 0) v.toLong().toString() else String.format("%.${decimals}f", v)

/**
 * 选项按钮行 —— 对应 v2 反复出现的 `<div class="steps">` + 若干 `GlassButton`。
 *
 * v2 的选中态是 `type="primary"` + `!plain`，未选是 `type="default"` + `plain`；
 * 这里用 [accent] 承载"主操作"语义，视觉与 v2 一致（强调色药丸）。
 */
@Composable
internal fun ConvertChipRow(
    backdrop: Backdrop,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            GlassButton(
                backdrop = backdrop,
                onClick = { onSelect(index) },
                size = GlassButtonSize.Small,
                plain = !selected,
                accent = selected,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) {
                GlassText(
                    text = label,
                    color = if (enabled) LiquidTheme.colors.text else LiquidTheme.colors.textFaint,
                    fontSize = 12.5.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 行内下拉选择器 —— 对应 v2 的 `GlassSelect` + `GlassOption`（底部弹层）。
 *
 * ⚠️ **与 v2 的差异（必须记录）**：v2 的 `GlassSelect` 点开后是一个**底部弹层**。
 * 3.0 的弹层只能由 `AppShell` 放进 `LiquidScaffold` 的 `overlay` 槽（Phase 1 架构结论：
 * 用 `Popup`/`Dialog` 会另开窗口、玻璃取不到背景），而**屏幕拿不到那个槽**
 * （`LiquidScaffold` 的 `overlay` 参数是 `AppShell` 私有的）。
 * 所以这里退化成"点击展开、列表就地铺开"的行内选择器：选项全部可见、点击即选、
 * 选中项打勾。功能等价（可选、可选中、有中文标签），少了底部弹层的动效。
 * 若 Lead 愿意在 `AppShell` 里为屏幕开一个 overlay 注册口，这一处可以换回 `LiquidBottomSheet`。
 */
@Composable
internal fun ConvertInlineSelect(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    enabled: Boolean = true
) {
    val colors = LiquidTheme.colors
    var expanded by remember { mutableStateOf(false) }

    // label 只用于无障碍语义（视觉标题由 title 承担）
    Column(modifier.fillMaxWidth().semantics { contentDescription = label }) {
        if (title != null) {
            GlassText(title, color = colors.textDim, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassText(
                text = value,
                color = if (enabled) colors.text else colors.textFaint,
                fontSize = 13.5.sp,
                modifier = Modifier.weight(1f)
            )
            GlassIcon(
                name = "forward",
                size = 16.dp,
                tint = colors.textFaint,
                modifier = Modifier.rotate(if (expanded) 90f else 0f)
            )
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
                options.forEachIndexed { index, option ->
                    val selected = option == value
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) {
                                onSelect(index)
                                expanded = false
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlassText(
                            text = option,
                            color = if (selected) colors.accent else colors.text,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) GlassIcon(name = "check", size = 16.dp, tint = colors.accent)
                    }
                }
            }
        }
    }
}

/** 只读的键值提示行（用于"尺寸超出行程"等由 `:core` 产出的一句话提示）。 */
@Composable
internal fun ConvertHint(text: String, modifier: Modifier = Modifier) {
    GlassText(text, color = LiquidTheme.colors.textDim, fontSize = 12.sp, modifier = modifier)
}

/** 供屏幕拼接 `warn` 文案（v2 用 `warn ? `${warn}；${msg}` : msg`）。 */
internal fun appendWarn(current: String, message: String?): String {
    if (message.isNullOrEmpty()) return current
    return if (current.isEmpty()) message else "$current；$message"
}

// ---------------------------------------------------------------------------
// 数值输入网格（三个屏幕共用）
// ---------------------------------------------------------------------------

/**
 * 一个数值输入项：标签 + 当前值 + 提交回调。
 *
 * [field] 决定键盘类型与整数 / 小数语义；[enabled] 为 false 时输入框只读
 * （对应 v2 `:disabled="autoHeight"` 的高度框）。
 */
internal class ConvertNumSpec(
    val label: String,
    val value: String,
    val field: ConvertNumField = ConvertNumField.RealField,
    val enabled: Boolean = true,
    val placeholder: String? = null,
    val onValue: (Double) -> Unit
)

/**
 * 两列网格（v2 `.num-grid`）。奇数个项时最后一项**占满整行**
 * —— v2 的 CSS grid 也是这个行为（最后一行只有一列时不会拉伸，但这里占满更好读，
 * 视觉差异已记录；不影响任何功能与参数）。
 */
@Composable
internal fun ConvertNumGrid(
    specs: List<ConvertNumSpec>,
    modifier: Modifier = Modifier,
    columns: Int = 2
) {
    /** 把文本交给 spec：解析失败时**不调用** onValue（`LiquidTextField` 会自动回弹原值）。 */
    fun commit(spec: ConvertNumSpec, text: String) {
        val v = text.trim().toDoubleOrNull() ?: return
        if (v.isFinite()) spec.onValue(v)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        specs.chunked(columns).forEach { row ->
            if (row.size == 1) {
                val spec = row[0]
                ConvertNumField(
                    label = spec.label,
                    value = spec.value,
                    onValue = { text -> commit(spec, text) },
                    field = spec.field,
                    enabled = spec.enabled,
                    placeholder = spec.placeholder
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { spec ->
                        ConvertNumField(
                            label = spec.label,
                            value = spec.value,
                            onValue = { text -> commit(spec, text) },
                            field = spec.field,
                            enabled = spec.enabled,
                            placeholder = spec.placeholder,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 设置读取（转换屏幕的默认值来源，与 v2 的 `AppSettings.get(key, def)` 对齐）
// ---------------------------------------------------------------------------

/** 读一个数值设置；类型不符或非有限值时回退 [def]。 */
internal fun convertSettingNum(settings: Map<String, Any?>, key: String, def: Double): Double {
    val v = settings[key]
    val d = when (v) {
        is Double -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Float -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
    return if (d != null && d.isFinite()) d else def
}

/** 读一个字符串设置；空串回退 [def]。 */
internal fun convertSettingString(settings: Map<String, Any?>, key: String, def: String): String {
    val v = settings[key] as? String
    return if (v.isNullOrEmpty()) def else v
}

/** 读一个布尔设置；类型不符时回退 [def]。 */
internal fun convertSettingBool(settings: Map<String, Any?>, key: String, def: Boolean): Boolean =
    settings[key] as? Boolean ?: def

/**
 * 生成缩略图（对应 v2 的 `.thumb`，56×56 显示）。
 *
 * 为什么单独做一张：原图（最长 4096 px）直接交给 Compose 的 `Image` 会占用大量
 * 纹理内存，而列表里只需要 56 dp。用 `Bitmap.createScaledBitmap` 一次缩放即可。
 */
internal fun makeThumbnail(source: Bitmap, edge: Int = 160): Bitmap {
    val longEdge = maxOf(source.width, source.height)
    if (longEdge <= edge) return source
    val scale = edge.toDouble() / longEdge
    val w = maxOf(1, Math.round(source.width * scale).toInt())
    val h = maxOf(1, Math.round(source.height * scale).toInt())
    return runCatching { Bitmap.createScaledBitmap(source, w, h, true) }.getOrDefault(source)
}
