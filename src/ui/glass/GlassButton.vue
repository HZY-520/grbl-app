<script setup lang="ts">
/**
 * iOS 风格按钮。兼容原有 <var-button> 的用法：
 * type / size / block / plain / text / round / disabled / loading。
 * 事件为原生 click，沿用原有 @click 写法。
 */
withDefaults(
  defineProps<{
    /** primary | danger | warning | success | info | default */
    type?: string
    size?: 'normal' | 'small' | 'mini'
    /** 占满整行 */
    block?: boolean
    /** 半透明填充（原 var-button 的 plain） */
    plain?: boolean
    /** 纯文字按钮 */
    text?: boolean
    /** 圆形按钮 */
    round?: boolean
    disabled?: boolean
    loading?: boolean
  }>(),
  { type: 'default', size: 'normal', block: false, plain: false, text: false, round: false, disabled: false, loading: false }
)
</script>

<template>
  <button
    class="g-btn"
    :class="[
      `g-btn--${type}`,
      size !== 'normal' && `g-btn--${size}`,
      block && 'g-btn--block',
      plain && 'g-btn--plain',
      text && 'g-btn--text',
      round && 'g-btn--round',
      (disabled || loading) && 'g-btn--disabled'
    ]"
    :disabled="disabled || loading"
    type="button"
  >
    <span v-if="loading" class="g-btn__spinner" />
    <slot />
  </button>
</template>
