<script setup lang="ts">
/** 玻璃折叠面板子项。兼容 var-collapse-item：name / title。 */
import { inject, computed } from 'vue'

const props = withDefaults(defineProps<{ name?: string; title?: string }>(), {
  name: '',
  title: ''
})

const collapse = inject<{ isOpen: (n: string) => boolean; toggle: (n: string) => void }>(
  'glass-collapse'
)

const open = computed(() => collapse?.isOpen(props.name) ?? false)
</script>

<template>
  <div>
    <button class="g-collapse__head" type="button" @click="collapse?.toggle(name)">
      <span>{{ title }}</span>
      <svg
        class="g-collapse__chevron"
        :class="{ 'g-collapse__chevron--open': open }"
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2.2"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M9 5 L16 12 L9 19" />
      </svg>
    </button>
    <div v-if="open" class="g-collapse__body"><slot /></div>
  </div>
</template>
