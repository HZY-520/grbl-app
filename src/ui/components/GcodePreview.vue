<script setup lang="ts">
/**
 * G 代码路径预览画布。
 * 快速空移用暗色显示，实际雕刻路径用强调色显示，并标注坐标原点。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { BoundingBox, PreviewMove } from '../../core/gcode/GrblFile'

const props = withDefaults(
  defineProps<{
    preview: PreviewMove[]
    bbox?: BoundingBox | null
    height?: number
    showBbox?: boolean
  }>(),
  { bbox: null, height: 240, showBbox: true }
)

const wrapRef = ref<HTMLElement | null>(null)
const canvasRef = ref<HTMLCanvasElement | null>(null)
let ro: ResizeObserver | null = null
let raf = 0

const bounds = computed(() => {
  const b = props.bbox
  if (b && b.valid) {
    return { minX: b.minX, minY: b.minY, maxX: b.maxX, maxY: b.maxY }
  }
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const m of props.preview) {
    minX = Math.min(minX, m.x1, m.x2)
    maxX = Math.max(maxX, m.x1, m.x2)
    minY = Math.min(minY, m.y1, m.y2)
    maxY = Math.max(maxY, m.y1, m.y2)
  }
  if (!Number.isFinite(minX)) return { minX: 0, minY: 0, maxX: 1, maxY: 1 }
  return { minX, minY, maxX, maxY }
})

function cssVar(name: string, fallback: string) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

function draw() {
  const canvas = canvasRef.value
  const wrap = wrapRef.value
  if (!canvas || !wrap) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  const cssW = Math.max(wrap.clientWidth, 1)
  const cssH = props.height
  canvas.width = Math.round(cssW * dpr)
  canvas.height = Math.round(cssH * dpr)
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, cssW, cssH)

  const accent = cssVar('--lg-accent', '#ff7a18')
  const dim = cssVar('--lg-text-dim', '#9a9aa5')
  const border = cssVar('--lg-border', '#2e2e36')

  const b = bounds.value
  const w = Math.max(b.maxX - b.minX, 0.0001)
  const h = Math.max(b.maxY - b.minY, 0.0001)
  const pad = 14
  const scale = Math.min((cssW - pad * 2) / w, (cssH - pad * 2) / h)
  const offX = (cssW - w * scale) / 2
  const offY = (cssH - h * scale) / 2
  const tx = (x: number) => offX + (x - b.minX) * scale
  const ty = (y: number) => cssH - offY - (y - b.minY) * scale

  if (props.showBbox && props.bbox?.valid) {
    ctx.save()
    ctx.strokeStyle = border
    ctx.setLineDash([4, 4])
    ctx.lineWidth = 1
    ctx.strokeRect(tx(b.minX), ty(b.maxY), w * scale, h * scale)
    ctx.restore()
  }

  ctx.lineWidth = 1
  ctx.lineCap = 'round'
  ctx.strokeStyle = dim
  ctx.globalAlpha = 0.3
  ctx.beginPath()
  for (const m of props.preview) {
    if (!m.rapid) continue
    ctx.moveTo(tx(m.x1), ty(m.y1))
    ctx.lineTo(tx(m.x2), ty(m.y2))
  }
  ctx.stroke()

  ctx.globalAlpha = 1
  ctx.strokeStyle = accent
  ctx.beginPath()
  for (const m of props.preview) {
    if (m.rapid) continue
    ctx.moveTo(tx(m.x1), ty(m.y1))
    ctx.lineTo(tx(m.x2), ty(m.y2))
  }
  ctx.stroke()

  // 原点十字
  const ox = tx(0)
  const oy = ty(0)
  if (ox >= -20 && ox <= cssW + 20 && oy >= -20 && oy <= cssH + 20) {
    ctx.save()
    ctx.strokeStyle = accent
    ctx.lineWidth = 1.4
    ctx.beginPath()
    ctx.moveTo(ox - 6, oy)
    ctx.lineTo(ox + 6, oy)
    ctx.moveTo(ox, oy - 6)
    ctx.lineTo(ox, oy + 6)
    ctx.stroke()
    ctx.restore()
  }

  if (props.preview.length === 0) {
    ctx.fillStyle = dim
    ctx.font = '13px -apple-system, "PingFang SC", sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText('暂无路径', cssW / 2, cssH / 2)
  }
}

function requestDraw() {
  if (raf) cancelAnimationFrame(raf)
  raf = requestAnimationFrame(() => {
    raf = 0
    draw()
  })
}

onMounted(() => {
  requestDraw()
  if (typeof ResizeObserver !== 'undefined' && wrapRef.value) {
    ro = new ResizeObserver(() => requestDraw())
    ro.observe(wrapRef.value)
  }
})

onBeforeUnmount(() => {
  if (raf) cancelAnimationFrame(raf)
  ro?.disconnect()
  ro = null
})

watch(
  () => [props.preview, props.bbox, props.height],
  () => requestDraw()
)
</script>

<template>
  <div ref="wrapRef" class="lg-canvas-wrap" :style="{ height: `${height}px` }">
    <canvas ref="canvasRef" :style="{ height: `${height}px` }" />
  </div>
</template>