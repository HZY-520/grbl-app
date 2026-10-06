<script setup lang="ts">
/**
 * 玻璃输入框（单行 / 多行）。
 *
 * ⚠️ 事件契约必须与 var-input 一致，否则会静默破坏功能：
 *   现有页面用 `@blur="onNum('x', $event)"`，而 onNum 内部是
 *   `(ev.target as HTMLInputElement).value` —— 也就是说必须抛出
 *   **原生 FocusEvent**，且 target 是真实的 input 元素。
 *   同理 `@keyup.enter` 需要原生 KeyboardEvent。
 *   因此这里显式声明并转发这两个事件，而不是让它们走 attrs 透传。
 */
import { ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: string | number | null
    type?: string
    placeholder?: string
    disabled?: boolean
    /** 多行文本域 */
    textarea?: boolean
    /** 文本域行数 */
    rows?: number
    /** 兼容旧写法（var-input 的 variant），此处不参与渲染 */
    variant?: string
  }>(),
  {
    modelValue: '',
    type: 'text',
    placeholder: '',
    disabled: false,
    textarea: false,
    rows: 3,
    variant: 'outlined'
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
  (e: 'blur', ev: FocusEvent): void
  (e: 'focus', ev: FocusEvent): void
  (e: 'keyup', ev: KeyboardEvent): void
  (e: 'input', ev: Event): void
}>()

const el = ref<HTMLInputElement | HTMLTextAreaElement | null>(null)
const focused = ref(false)
const draft = ref(props.modelValue == null ? '' : String(props.modelValue))

// 外部赋值时同步显示值；但用户正在输入时不打断
watch(
  () => props.modelValue,
  (v) => {
    if (!focused.value) draft.value = v == null ? '' : String(v)
  }
)

function onInput(e: Event) {
  const t = e.target as HTMLInputElement
  draft.value = t.value
  emit('update:modelValue', t.value)
  emit('input', e)
}

function onFocus(e: FocusEvent) {
  focused.value = true
  emit('focus', e)
}

function onBlur(e: FocusEvent) {
  focused.value = false
  // 原样抛出原生事件：父组件依赖 ev.target.value
  emit('blur', e)
  // 父组件若接受了该值，modelValue 会更新；若拒绝了（解析失败），这里回退到旧值
  draft.value = props.modelValue == null ? '' : String(props.modelValue)
}

defineExpose({ el })
</script>

<template>
  <div class="g-field" :class="{ 'g-field--focus': focused, 'g-field--disabled': disabled, 'g-field--textarea': textarea }">
    <textarea
      v-if="textarea"
      ref="el"
      class="g-field__input"
      :value="draft"
      :rows="rows"
      :placeholder="placeholder"
      :disabled="disabled"
      @input="onInput"
      @focus="onFocus"
      @blur="onBlur"
      @keyup="emit('keyup', $event as KeyboardEvent)"
    />
    <input
      v-else
      ref="el"
      class="g-field__input"
      :type="type"
      :value="draft"
      :placeholder="placeholder"
      :disabled="disabled"
      @input="onInput"
      @focus="onFocus"
      @blur="onBlur"
      @keyup="emit('keyup', $event as KeyboardEvent)"
    />
  </div>
</template>
