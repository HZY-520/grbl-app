<script setup lang="ts">
/** <GlassSelect> 的选项声明组件：自身不渲染，只把 label/value 注册给父级。 */
import { inject, onMounted, onBeforeUnmount } from 'vue'
import type { GlassOptionItem } from './GlassSelect.vue'

const props = defineProps<{ label: string; value: string | number }>()

const registry = inject<{
  register: (i: GlassOptionItem) => void
  unregister: (i: GlassOptionItem) => void
}>('glass-select')

const item: GlassOptionItem = { label: props.label, value: props.value }

onMounted(() => registry?.register(item))
onBeforeUnmount(() => registry?.unregister(item))
</script>

<template>
  <span hidden />
</template>
