<script setup lang="ts">
/**
 * 标签栏子项。兼容 var-bottom-navigation-item：
 * :name / :label，插槽 #icon 接收 { active } 作用域参数。
 *
 * 拖动选栏模式下，手指下的标签会放大一圈并亮起光晕（由父级 GlassTabBar 注入的
 * scrubName / scrubbing 控制）。
 */
import { computed, inject, type Ref } from 'vue'

const props = withDefaults(defineProps<{ name: string; label?: string }>(), { label: '' })

const bar = inject<{
  isActive: (n: string) => boolean
  select: (n: string) => void
  scrubName: Ref<string>
  scrubbing: Ref<boolean>
}>('glass-tabbar')

const active = computed(() => bar?.isActive(props.name) ?? false)
const scrubbed = computed(() => !!bar?.scrubbing.value && bar?.scrubName.value === props.name)
</script>

<template>
  <button
    class="g-tab"
    :class="{ 'g-tab--active': active, 'g-tab--scrub': scrubbed }"
    type="button"
    :data-tab="name"
    @click="bar?.select(name)"
  >
    <slot name="icon" :active="active" />
    <span>{{ label }}</span>
  </button>
</template>
