<script setup lang="ts">
/**
 * 顶部导航栏：整条玻璃悬浮在极光之上（iOS 26 观感）。
 * 左右插槽对应原 var-app-bar 的 #left / #right；
 * 采用弹性三段布局，标题过长时省略而不会与两侧内容重叠。
 */
import GlassSurface from './GlassSurface.vue'

withDefaults(defineProps<{ title?: string }>(), { title: '' })
</script>

<template>
  <header class="g-nav">
    <GlassSurface :radius="22" :lens="'shift'" :scale="120" :frost="0.22" :blur="8" :lens-strength="1">
      <div class="g-nav__inner">
        <div class="g-nav__side">
          <slot name="left" />
        </div>
        <div class="g-nav__title">{{ title }}</div>
        <div class="g-nav__side g-nav__side--right">
          <slot name="right" />
        </div>
      </div>
    </GlassSurface>
  </header>
</template>
