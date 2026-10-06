<script setup lang="ts">
/** iOS 风格开关。兼容 var-switch：v-model 与 :model-value / @update:model-value 均可。 */
const props = withDefaults(
  defineProps<{
    modelValue?: boolean | string | number
    disabled?: boolean
  }>(),
  { modelValue: false, disabled: false }
)

const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

function toggle() {
  if (props.disabled) return
  emit('update:modelValue', !props.modelValue)
}
</script>

<template>
  <button
    class="g-switch"
    :class="{ 'g-switch--on': !!modelValue }"
    :disabled="disabled"
    type="button"
    role="switch"
    :aria-checked="!!modelValue"
    @click="toggle"
  >
    <span class="g-switch__knob" />
  </button>
</template>
