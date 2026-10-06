<script setup lang="ts">
/** 玻璃提示条。兼容 var-alert：title / type。 */
withDefaults(
  defineProps<{
    title?: string
    /** info | success | warning | error */
    type?: string
  }>(),
  { title: '', type: 'info' }
)
</script>

<template>
  <div class="g-alert" :class="`g-alert--${type}`">
    <slot>{{ title }}</slot>
  </div>
</template>
