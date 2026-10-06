<script setup lang="ts">
/** 玻璃加载指示器。兼容 var-loading：type（忽略，统一用环形）。 */
defineProps<{ type?: string }>()
</script>

<template>
  <div class="g-loading"><span class="g-loading__ring" /></div>
</template>
