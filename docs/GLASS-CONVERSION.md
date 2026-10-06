# Liquid Glass 视图改造规范

本文件是**唯一契约**。所有视图改造必须严格按此执行，目的是：
把 Varlet UI 全部换成项目自带的玻璃组件，**功能与交互行为完全不变**。

参照实现：`src/ui/views/HomeView.vue`（已完成，请先阅读它）。

---

## 0. 铁律

1. **只改 `<template>` 与 `<style>`。绝对不要修改 `<script>` 里的任何逻辑** ——
   变量名、函数体、事件处理、计算属性、导入，一律保持原样。
2. **保留所有中文文案**，一个字都不要改。
3. **保留所有 `lg-*` 工具类名**（`lg-section` / `lg-body` / `lg-title` / `lg-grid-2` /
   `lg-stat` / `lg-kv` / `lg-dim` / `lg-mono` / `lg-mt` / `lg-mb` / `lg-empty` /
   `lg-canvas-wrap` / `lg-log` / `lg-pad` / `lg-row` / `lg-badge` / `lg-dot` 等）——
   它们已在 `src/styles/glass.css` 里重新定义为玻璃风格。
4. 不要新增依赖，不要引入新组件（除非本规范指定）。
5. 不要动 `<script setup>` 的 import 列表。

---

## 1. 组件映射表

| Varlet（旧） | Glass（新） | 属性 / 事件是否一致 |
| --- | --- | --- |
| `<var-button>` | `<GlassButton>` | ✅ 完全一致：`type` `size` `block` `plain` `text` `round` `disabled` `loading` `@click` |
| `<var-input>` | `<GlassInput>` | ✅ `v-model` / `:model-value` / `type` / `textarea` / `:rows` / `placeholder` / `:disabled` / `@blur` / `@keyup.enter`；`variant` 可保留（会被忽略） |
| `<var-select>` | `<GlassSelect>` | ✅ `v-model` / `:model-value` / `placeholder` / `@change`（参数为值）；`variant` 可保留（会被忽略） |
| `<var-option>` | `<GlassOption>` | ✅ `:label` `:value` |
| `<var-switch>` | `<GlassSwitch>` | ✅ `v-model` / `:model-value` / `@update:model-value` / `:disabled` |
| `<var-slider>` | `<GlassSlider>` | ✅ `v-model` / `:model-value` / `:min` `:max` `:step` / `@change`（参数为数值） |
| `<var-cell>` | `<GlassCell>` | ✅ `title` `description` `is-link` `@click`，插槽 `#icon` / `#extra` |
| `<var-progress>` | `<GlassProgress>` | ✅ `:value` `:show-label` `color` `track-color` |
| `<var-alert>` | `<GlassAlert>` | ✅ `title` `type` |
| `<var-collapse>` | `<GlassCollapse>` | ✅（不传 v-model 时默认全部收起） |
| `<var-collapse-item>` | `<GlassCollapseItem>` | ✅ `name` `title` |
| `<var-loading>` | `<GlassLoading>` | ✅（`type` 会被忽略） |
| `<section class="lg-section">` | `<GlassSurface class="lg-section">` | 折射玻璃卡片，见下 |

改法就是**把标签名换掉**，属性与事件照抄。例如：

```html
<!-- 旧 -->
<var-button block type="primary" :loading="busy" :disabled="!canGenerate" @click="generate">
  <AppIcon name="play" :size="17" /><span class="btn-text">生成</span>
</var-button>

<!-- 新 -->
<GlassButton block type="primary" :loading="busy" :disabled="!canGenerate" @click="generate">
  <AppIcon name="play" :size="17" /><span class="btn-text">生成</span>
</GlassButton>
```

---

## 2. 区块卡片：`<section class="lg-section">` → `<GlassSurface class="lg-section">`

`.lg-section` 现在只负责圆角；内边距由 `GlassSurface` 的 `pad`（默认 `true`）提供，
避免和视图里的 `.lg-section` 叠加成两层内边距。

```html
<!-- 旧 -->
<section class="lg-section">
  <div class="lg-title"><span>尺寸</span></div>
  …
</section>

<!-- 新 -->
<GlassSurface class="lg-section">
  <div class="lg-title"><span>尺寸</span></div>
  …
</GlassSurface>
```

注意开闭标签都要换。**页面里每一个 `<section class="lg-section">` 都要换掉**，
否则它会变成一块没有背景的透明区域（截图验收会立刻暴露）。

若某个区块**不需要折射**，用 `<GlassSurface class="lg-section" :flat="true">`，
或保留 `<section class="lg-section lg-flat">`（`.lg-flat` 提供 CSS 毛玻璃外观）。

### GlassSurface 的 DOM 结构与布局规则（重要）

```
<div class="g-host …调用方的 class…">     ← 普通盒子：布局 / 事件 / class 都在这
  <liquid-glass class="g-host__bg" />      ← 绝对定位的折射背景层（z-index:-1）
  <div class="g-host__content">            ← display: contents
     …插槽内容…
  </div>
</div>
```

- **布局类写在 `GlassSurface` 上即可**：`display:flex` / `grid` / `gap` / `align-items`
  都能正常作用于插槽内容。
  （背景层是用 `display:contents` 穿透的，所以插槽内容直接参与 `.g-host` 的布局。）
- ⚠️ **不要把布局类写进 `<liquid-glass>` 本身**：`<liquid-glass>` 的 shadow root 里
  插槽内容位于 `.lg-content`（`display:block`），写在它宿主上的 flex/grid 只会作用于
  库内部的几个图层，对内容无效。这正是 `GlassSurface` 要包一层的原因。
- `.g-host` 使用 `isolation: isolate`，配合背景层的 `z-index:-1`，
  保证玻璃压在文字之下、且不会跑到其它卡片背后。

---

## 3. 常见片段的写法

### 表单行（标签 + 输入框）
```html
<div class="num-item">
  <span class="lg-dim">宽度 (mm)</span>
  <GlassInput :model-value="String(widthMm)" type="number" @blur="onNum('widthMm', $event)" />
</div>
```
> ⚠️ `@blur="onNum('x', $event)"` 必须**原样保留**。`GlassInput` 抛的是**原生 FocusEvent**，
> 处理函数内部用 `ev.target.value` 取值，契约与原 `var-input` 一致。不要改成 `@blur="onNum('x', $event.target.value)"`。

### 开关行
```html
<GlassCell title="高度按比例自动" description="保持图片宽高比">
  <template #extra>
    <GlassSwitch v-model="autoHeight" />
  </template>
</GlassCell>
```

### 滑块行
```html
<div class="lg-slider-row">
  <GlassSlider v-model="opts.brightness" :min="0" :max="200" :step="5" />
</div>
```
> 若原来滑块旁边已经手写了数值显示，**保持原样**，不要给 `GlassSlider` 加 `show-value`。

### 选择器
```html
<GlassSelect class="lg-mt" :model-value="opts.direction" @change="opts.direction = $event as RasterDirection">
  <GlassOption v-for="d in DIRECTION_OPTIONS" :key="d.value" :label="d.label" :value="d.value" />
</GlassSelect>
```

### 一排切换按钮（保持原表达式，只换标签）
```html
<GlassButton
  v-for="t in TOOL_OPTIONS"
  :key="t"
  size="small"
  :type="tool === t ? 'primary' : 'default'"
  :plain="tool !== t"
  @click="setTool(t)"
>{{ TOOL_LABELS[t] }}</GlassButton>
```
> **不要**改写成 `GlassSegmented`。保留原有 `:type` / `:plain` / `@click` 表达式，
> 行为才 100% 不变。

---

## 4. `<style scoped>` 处理

- 删除与 `glass.css` 重复的规则：`.lg-stat` / `.lg-stat__k` / `.lg-stat__v` /
  `.btn-text` / `.lg-title` / `.lg-row` / `.lg-dim` / `.lg-mono` / `.lg-empty` /
  `.lg-canvas-wrap` / `.lg-pad` / `.lg-slider-row`。
- **保留**视图特有的布局类（例如 `.upload-box`、`.steps`、`.hub-card`、`.num-grid`、
  `.saved-item`、`.cfg-row`、`.dev-item`、`.wiz__foot-btn`、`.preset` 等）——
  这些也已在 `glass.css` 里给了玻璃外观，scoped 里若重复可删，若有额外定位需求则保留。
- 不确定就**保留**，多余样式不会导致功能问题。

---

## 5. 验收

改完后：
1. 通读 diff，确认 `<script>` 部分**零改动**（`git diff <你的文件>` 里不应出现 script 变更）。
2. 确认文件里**不再出现** `var-` 开头的标签：搜索 `<var-`。
3. 可选：运行 `npx vue-tsc --noEmit`，**只修你自己文件**报出的错误（其他文件可能正被他人改，忽略即可）。

## 6. 不要做的事

- ❌ 不要修改 `src/ui/glass/**`、`src/styles/glass.css`、`src/App.vue`、`src/main.ts`。
- ❌ 不要新建组件（如果确实缺一个组件，在报告里说明，不要自己造）。
- ❌ 不要顺手"优化"或"修复"原有逻辑。
- ❌ 不要改路由、store、core 逻辑。
