<script setup lang="ts">
/** 玻璃折叠面板容器。v-model 为展开项 name 数组；不传则全部收起（与 var-collapse 一致）。 */
import { provide, ref, watch } from 'vue'

const props = withDefaults(defineProps<{ modelValue?: string[] }>(), { modelValue: () => [] })

const emit = defineEmits<{ (e: 'update:modelValue', v: string[]): void }>()

const active = ref<string[]>([...props.modelValue])

watch(
  () => props.modelValue,
  (v) => {
    active.value = [...v]
  }
)

provide('glass-collapse', {
  isOpen: (name: string) => active.value.includes(name),
  toggle: (name: string) => {
    const next = active.value.includes(name)
      ? active.value.filter((n) => n !== name)
      : [...active.value, name]
    active.value = next
    emit('update:modelValue', next)
  }
})
</script>

<template>
  <div class="g-collapse"><slot /></div>
</template>
