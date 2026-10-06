<script setup lang="ts">
/** 玻璃进度条。兼容 var-progress：value / :show-label / color / track-color。 */
withDefaults(
  defineProps<{
    value?: number
    showLabel?: boolean
    color?: string
    trackColor?: string
  }>(),
  { value: 0, showLabel: false, color: '', trackColor: '' }
)
</script>

<template>
  <div
    class="g-progress"
    :style="trackColor ? { background: trackColor } : undefined"
    role="progressbar"
    :aria-valuenow="Math.round(value)"
    aria-valuemin="0"
    aria-valuemax="100"
  >
    <div
      class="g-progress__bar"
      :style="{
        width: `${Math.min(100, Math.max(0, value))}%`,
        ...(color ? { background: color } : {})
      }"
    />
  </div>
</template>
