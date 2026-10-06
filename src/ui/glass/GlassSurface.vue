<script setup lang="ts">
/**
 * 折射玻璃面 —— 整个 UI 的视觉基元。
 *
 * 结构（关键设计）：
 *   <div class="g-host">            ← 普通盒子：承载调用方的 class / 布局 / 点击
 *     <liquid-glass class="g-host__bg" />   ← 绝对定位，只当「背景折射层」
 *     <div class="g-host__content"><slot /></div>
 *   </div>
 *
 * 为什么玻璃不做容器本身？
 * <liquid-glass> 的 shadow root 结构是 .lg-glass / .lg-border / .lg-content，插槽内容
 * 落在 .lg-content（display:block）里。也就是说：**写在宿主上的 display:flex/grid
 * 只作用于库内部的那几个图层，根本作用不到插槽内容**（实测 .hub-card 在宿主上是
 * flex、子元素却仍竖排）。
 *
 * 因此这里把折射层降级为绝对定位的背景层，并让内容层 `display: contents` ——
 * 插槽内容于是直接参与 .g-host 的布局，调用方写在 .g-host 上的
 * display:flex / grid / gap 都能按直觉生效。
 * 背景层与高光层用 `z-index: -1` + `.g-host { isolation: isolate }`，
 * 保证它们压在文字下面、且不会跑到别的卡片背后。
 *
 * flat = true 时降级为普通 CSS 毛玻璃（低端设备性能模式）。
 *
 * ⚠️ 玻璃特性必须用 setAttribute 写入，不能用模板绑定（见下方 watchEffect 注释）。
 */
import { ref, watchEffect } from 'vue'
import { glassConfig } from './config'

const props = withDefaults(
  defineProps<{
    /** 圆角（px） */
    radius?: number
    /** 透镜形状：经典径向 / 凸透镜放大 / 整体偏移 / 边缘清晰中心 */
    lens?: 'classic' | 'convex' | 'shift' | 'rim'
    /** 透镜强度，0 = 关闭 */
    lensStrength?: number
    /** 位移强度（负值反向） */
    scale?: number
    /** 磨砂强度 0–1，越大越「奶白」 */
    frost?: number
    /** 色散（边缘彩虹） */
    dispersion?: number
    /** 背景饱和度 % */
    saturation?: number
    /** 玻璃透明度 0–1 */
    alpha?: number
    /** 玻璃明度 0–100 */
    lightness?: number
    /** 折射贴图分辨率 / 通道数 */
    quality?: 'low' | 'standard' | 'high'
    /** 折射后叠加的模糊（px）：悬浮栏需要它来柔化背后的文字 */
    blur?: number
    /** 强制关闭折射，改用 CSS 毛玻璃 */
    flat?: boolean
    /** 内容内边距 */
    pad?: boolean
    /** 折射方向（度） */
    angle?: number
  }>(),
  {
    radius: 26,
    lens: 'convex',
    lensStrength: 1.15,
    scale: 150,
    frost: 0.2,
    dispersion: 52,
    saturation: 155,
    alpha: 0.5,
    lightness: 56,
    quality: 'low',
    blur: 0,
    flat: false,
    pad: true,
    angle: 0
  }
)

const el = ref<HTMLElement | null>(null)

/**
 * 用 setAttribute 显式写入所有玻璃特性。
 *
 * 不能用 `:blur="blur"` 这类模板绑定：<liquid-glass> 是自定义元素，
 * 而 `blur` 恰好是 HTMLElement 上的方法名，Vue 会把它当 property 赋值
 * （el.blur = 9）而不是 setAttribute，库的 observedAttributes 永远收不到，
 * 表现为「设了没反应」。统一走 setAttribute 后行为可预测。
 */
watchEffect(() => {
  const node = el.value
  if (!node) return
  const attrs: Record<string, string | number> = {
    radius: props.radius,
    lens: props.lens,
    'lens-strength': props.lensStrength,
    scale: props.scale,
    frost: props.frost,
    dispersion: props.dispersion,
    saturation: props.saturation,
    alpha: props.alpha,
    lightness: props.lightness,
    quality: props.quality,
    blur: props.blur,
    angle: props.angle
  }
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v))
})
</script>

<template>
  <div class="g-host" :class="{ 'g-host--pad': pad }">
    <div v-if="flat || !glassConfig.refraction" class="g-host__bg g-flat" :style="{ borderRadius: `${radius}px` }" />
    <liquid-glass v-else ref="el" class="g-host__bg" />

    <div class="g-host__content">
      <slot />
    </div>
  </div>
</template>
