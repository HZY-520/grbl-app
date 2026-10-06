<script setup lang="ts">
/**
 * 玻璃下拉选择器：字段外观 + iOS 底部弹层。
 *
 * 选项通过子组件 <GlassOption> 声明（与 var-select/var-option 写法一致），
 * 由 provide/inject 注册表收集，因此不需要改页面里的 v-for 结构。
 */
import { provide, ref, computed } from 'vue'

export interface GlassOptionItem {
  label: string
  value: string | number
}

const props = withDefaults(
  defineProps<{
    modelValue?: string | number
    placeholder?: string
    disabled?: boolean
    /** 兼容旧写法（var-select 的 variant），不参与渲染 */
    variant?: string
  }>(),
  { modelValue: '', placeholder: '请选择', disabled: false, variant: 'outlined' }
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: string | number): void
  (e: 'change', v: string | number): void
}>()

// 本组件有多个根节点（按钮 + 选项注册区 + Teleport），
// Vue 无法自动透传 class/style，必须手动绑定到按钮上，
// 否则页面里写的 class="lg-mt" 会丢失。
defineOptions({ inheritAttrs: false })

const options = ref<GlassOptionItem[]>([])

provide('glass-select', {
  register(item: GlassOptionItem) {
    if (!options.value.some((o) => o.value === item.value)) options.value.push(item)
  },
  unregister(item: GlassOptionItem) {
    const i = options.value.findIndex((o) => o.value === item.value)
    if (i >= 0) options.value.splice(i, 1)
  }
})

const open = ref(false)

const current = computed(() => options.value.find((o) => o.value === props.modelValue))
const label = computed(() => (current.value ? current.value.label : ''))

function pick(item: GlassOptionItem) {
  emit('update:modelValue', item.value)
  emit('change', item.value)
  open.value = false
}
</script>

<template>
  <button v-bind="$attrs" class="g-select" type="button" :disabled="disabled" @click="open = true">
    <span class="g-select__value" :class="{ 'g-select__value--placeholder': !label }">
      {{ label || placeholder }}
    </span>
    <svg class="g-select__chevron" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
      <path d="M6 9 L12 15 L18 9" />
    </svg>
  </button>

  <!-- 选项声明区（不渲染，仅供注册） -->
  <div style="display: none"><slot /></div>

  <Teleport to="body">
    <div v-if="open" class="g-sheet-mask" @click.self="open = false">
      <div class="g-sheet">
        <div class="g-sheet__grabber" />
        <div class="g-sheet__title">{{ placeholder }}</div>
        <div class="g-sheet__list">
          <button
            v-for="o in options"
            :key="String(o.value)"
            class="g-sheet__opt"
            :class="{ 'g-sheet__opt--active': o.value === modelValue }"
            type="button"
            @click="pick(o)"
          >
            <span>{{ o.label }}</span>
            <svg v-if="o.value === modelValue" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
              <path d="M5 13 L10 18 L19 6" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
