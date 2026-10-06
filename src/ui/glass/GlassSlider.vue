<script setup lang="ts">
/**
 * 玻璃滑块。
 * 底层用原生 <input type="range">（透明覆盖）以获得系统级手势与无障碍支持，
 * 视觉层自行绘制轨道 / 填充 / 圆钮，保持 iOS 观感。
 *
 * 事件：v-model 更新用 update:modelValue；@change 抛数值（兼容 var-slider）。
 */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: number
    min?: number
    max?: number
    step?: number
    disabled?: boolean
    /** 右侧显示数值 */
    showValue?: boolean
    /** 数值后缀 */
    suffix?: string
  }>(),
  { modelValue: 0, min: 0, max: 100, step: 1, disabled: false, showValue: false, suffix: '' }
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: number): void
  (e: 'change', v: number): void
}>()

const percent = computed(() => {
  const span = props.max - props.min
  if (span <= 0) return 0
  return Math.min(100, Math.max(0, ((props.modelValue - props.min) / span) * 100))
})

const display = computed(() =>
  props.step >= 1 ? String(Math.round(props.modelValue)) : props.modelValue.toFixed(2)
)

function onInput(e: Event) {
  const v = Number((e.target as HTMLInputElement).value)
  emit('update:modelValue', v)
  emit('change', v)
}
</script>

<template>
  <div class="g-slider" :class="{ 'g-btn--disabled': disabled }">
    <div class="g-slider__track-wrap">
      <div class="g-slider__track">
        <div class="g-slider__fill" :style="{ width: `${percent}%` }" />
      </div>
      <span class="g-slider__knob" :style="{ left: `${percent}%` }" />
      <input
        class="g-slider__native"
        type="range"
        :min="min"
        :max="max"
        :step="step"
        :value="modelValue"
        :disabled="disabled"
        @input="onInput"
      />
    </div>
    <span v-if="showValue" class="g-slider__val">{{ display }}{{ suffix }}</span>
  </div>
</template>
