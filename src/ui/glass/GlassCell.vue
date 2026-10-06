<script setup lang="ts">
/** 玻璃列表行。兼容 var-cell：title / description / is-link，插槽 #icon / #extra。 */
withDefaults(
  defineProps<{
    title?: string
    description?: string
    /** 右侧显示箭头 */
    isLink?: boolean
  }>(),
  { title: '', description: '', isLink: false }
)
</script>

<template>
  <div class="g-cell" :class="{ 'g-cell--tap': isLink }">
    <span v-if="$slots.icon" class="g-cell__icon"><slot name="icon" /></span>
    <div class="g-cell__main">
      <div class="g-cell__title">{{ title }}</div>
      <div v-if="description" class="g-cell__desc">{{ description }}</div>
      <slot />
    </div>
    <div class="g-cell__extra">
      <slot name="extra" />
      <svg
        v-if="isLink"
        class="g-cell__chevron"
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2.2"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M9 5 L16 12 L9 19" />
      </svg>
    </div>
  </div>
</template>
