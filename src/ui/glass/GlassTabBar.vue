<script setup lang="ts">
/**
 * 底部悬浮玻璃标签栏（iOS 26 的浮动玻璃 Tab Bar）。
 *
 * 除普通点按外，还支持「长按后拖动滑动选栏」：
 *   1. 按住不放约 360ms → 进入拖动选栏模式：整条玻璃栏轻微放大浮起光晕，
 *      手指下的那个标签放大一圈、亮起强调色光晕（附带一次震动反馈）；
 *   2. 保持按住左右滑动 → 高亮实时跟随手指，松手即切到该栏；
 *   3. 松手后一切恢复常态。
 *   在长按判定前就移动超过阈值（想滚动页面）则取消长按，不影响原有手势。
 *
 * ⚠️ 这里**不能**用 setPointerCapture：一旦捕获，pointerup 的 target 会变成
 * 捕获元素本身，浏览器就不再在按钮上派发 click —— 普通点按会整个失效。
 * 因此改为在 window 上监听 move/up，既保证手指移出底栏仍能跟随，
 * 又保留原生 click 给「短按」使用。
 *
 * 兼容 var-bottom-navigation 的用法：:active + @change(name)，子项用 GlassTabItem。
 */
import { computed, onBeforeUnmount, provide, ref } from 'vue'
import GlassSurface from './GlassSurface.vue'

const props = withDefaults(defineProps<{ active?: string }>(), { active: '' })

const emit = defineEmits<{ (e: 'change', name: string): void }>()

/** 手指当前悬停（拖动选栏中）的标签名 */
const scrubName = ref('')
/** 是否已进入拖动选栏模式 */
const scrubbing = ref(false)

provide('glass-tabbar', {
  isActive: (name: string) => props.active === name,
  select: (name: string) => emit('change', name),
  scrubName,
  scrubbing
})

const innerRef = ref<HTMLElement | null>(null)

/** 长按判定阈值；超过它才算「长按」 */
const HOLD_MS = 360
/** 长按判定前允许的手指抖动（像素），超出即视为滚动手势 */
const MOVE_TOLERANCE = 10

let holdTimer = 0
let startX = 0
let startY = 0
let activePointer: number | null = null
/** 拖动选栏结束后要吞掉紧随其后的 click，避免重复切换 */
let swallowClick = false
let swallowTimer = 0

const rootClass = computed(() => ({ 'g-tabbar--scrubbing': scrubbing.value }))

/** 根据横坐标找到手指下的标签名 */
function nameAtX(x: number): string {
  const inner = innerRef.value
  if (!inner) return ''
  const tabs = inner.querySelectorAll<HTMLElement>('.g-tab')
  for (const el of tabs) {
    const r = el.getBoundingClientRect()
    if (x >= r.left && x <= r.right) return el.dataset.tab ?? ''
  }
  // 划过两端之外时，夹到最近的一栏，手感更跟手
  const list = [...tabs]
  if (!list.length) return ''
  const first = list[0].getBoundingClientRect()
  const last = list[list.length - 1].getBoundingClientRect()
  if (x < first.left) return list[0].dataset.tab ?? ''
  if (x > last.right) return list[list.length - 1].dataset.tab ?? ''
  return ''
}

function clearHoldTimer() {
  if (holdTimer) {
    window.clearTimeout(holdTimer)
    holdTimer = 0
  }
}

function detach() {
  window.removeEventListener('pointermove', onWindowMove)
  window.removeEventListener('pointerup', onWindowUp)
  window.removeEventListener('pointercancel', onWindowCancel)
}

function reset() {
  clearHoldTimer()
  detach()
  scrubbing.value = false
  scrubName.value = ''
  activePointer = null
}

function buzz() {
  // Android WebView 需要 VIBRATE 权限；没有权限时静默失败，不影响功能
  try {
    navigator.vibrate?.(8)
  } catch {
    /* ignore */
  }
}

function onWindowMove(e: PointerEvent) {
  if (activePointer === null || e.pointerId !== activePointer) return

  if (!scrubbing.value) {
    // 长按判定前就明显移动 → 用户是想滚页面，取消长按
    if (
      Math.abs(e.clientX - startX) > MOVE_TOLERANCE ||
      Math.abs(e.clientY - startY) > MOVE_TOLERANCE
    ) {
      reset()
    }
    return
  }

  // 拖动选栏：高亮跟随手指
  scrubName.value = nameAtX(e.clientX)
}

function onWindowUp(e: PointerEvent) {
  if (activePointer === null || e.pointerId !== activePointer) return
  const wasScrubbing = scrubbing.value
  const target = scrubName.value
  reset()
  if (wasScrubbing) {
    // 吞掉这次手势后续的 click，防止与普通点按重复触发
    swallowClick = true
    window.clearTimeout(swallowTimer)
    swallowTimer = window.setTimeout(() => {
      swallowClick = false
    }, 320)
    if (target && target !== props.active) emit('change', target)
  }
}

function onWindowCancel(e: PointerEvent) {
  if (activePointer === null || e.pointerId !== activePointer) return
  reset()
}

function onPointerDown(e: PointerEvent) {
  if (e.button !== undefined && e.button !== 0) return
  if (activePointer !== null) return // 已有手指在操作
  activePointer = e.pointerId
  startX = e.clientX
  startY = e.clientY
  detach()
  window.addEventListener('pointermove', onWindowMove, { passive: true })
  window.addEventListener('pointerup', onWindowUp)
  window.addEventListener('pointercancel', onWindowCancel)
  clearHoldTimer()
  holdTimer = window.setTimeout(() => {
    holdTimer = 0
    scrubbing.value = true
    scrubName.value = nameAtX(startX)
    buzz()
  }, HOLD_MS)
}

function onClickCapture(e: MouseEvent) {
  if (swallowClick) {
    e.stopPropagation()
    e.preventDefault()
  }
}

onBeforeUnmount(() => {
  clearHoldTimer()
  detach()
  window.clearTimeout(swallowTimer)
})
</script>

<template>
  <nav
    class="g-tabbar"
    :class="rootClass"
    @pointerdown="onPointerDown"
    @click.capture="onClickCapture"
  >
    <GlassSurface
      :radius="26"
      :lens="'shift'"
      :scale="110"
      :frost="0.24"
      :blur="9"
      :lens-strength="0.9"
      :pad="false"
    >
      <div ref="innerRef" class="g-tabbar__inner"><slot /></div>
    </GlassSurface>
  </nav>
</template>
