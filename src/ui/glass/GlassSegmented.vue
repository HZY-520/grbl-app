<script setup lang="ts">
/**
 * iOS 分段控件。传入选项数组即可，用于替换页面里
 * 「一排 var-button 靠 plain/type 切换选中态」的写法，视觉更贴近系统原生控件。
 */
export interface SegmentOption {
  label: string
  value: string | number
  disabled?: boolean
}

withDefaults(
  defineProps<{
    modelValue?: string | number
    options: SegmentOption[]
    /** 撑满整行（等分） */
    block?: boolean
  }>(),
  { modelValue: '', block: true }
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: string | number): void
  (e: 'change', v: string | number): void
}>()

function pick(v: string | number, disabled?: boolean) {
  if (disabled) return
  emit('update:modelValue', v)
  emit('change', v)
}
</script>

<template>
  <div class="g-seg" :style="block ? undefined : { display: 'inline-flex' }">
    <button
      v-for="o in options"
      :key="String(o.value)"
      class="g-seg__item"
      :class="{ 'g-seg__item--active': o.value === modelValue }"
      :disabled="o.disabled"
      type="button"
      @click="pick(o.value, o.disabled)"
    >
      {{ o.label }}
    </button>
  </div>
</template>
