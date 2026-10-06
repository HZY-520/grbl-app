<script setup lang="ts">
/**
 * G 代码路径预览画布。
 * 快速空移用暗色显示，实际雕刻路径用强调色显示，并标注坐标原点。
 *
 * 在原有能力上新增：
 *   1. 按雕刻进度逐步绘制：已雕刻部分用强调色实线，未雕刻部分用低透明度淡色，
 *      末端画一个发光激光头指示点；进度推进用 requestAnimationFrame 插值，不生硬跳变。
 *   2. 左上角玻璃风尺寸标签，显示作品实际尺寸（宽 × 高，单位 mm）。
 *
 * 兼容性：不传 progress 时行为与旧版完全一致（整条路径一次性绘制）。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { BoundingBox, PreviewMove } from '../../core/gcode/GrblFile'

const props = withDefaults(
  defineProps<{
    preview: PreviewMove[]
    bbox?: BoundingBox | null
    height?: number
    showBbox?: boolean
    /** 雕刻完成百分比（0–100）；不传表示暂无进度数据，整条路径一次性绘制 */
    progress?: number
    /** 是否按进度做平滑补间（默认 true）；关闭或系统「减弱动态效果」时直接静态绘制 */
    animated?: boolean
  }>(),
  { bbox: null, height: 240, showBbox: true, progress: undefined, animated: true }
)

const wrapRef = ref<HTMLElement | null>(null)
const canvasRef = ref<HTMLCanvasElement | null>(null)
let ro: ResizeObserver | null = null
let themeObserver: MutationObserver | null = null
let motionQuery: MediaQueryList | null = null
/** 重绘调度句柄（尺寸 / 数据变化） */
let raf = 0
/** 进度补间循环句柄 */
let tweenRaf = 0

/** 当前已渲染的进度（0–100），由补间推动向目标值逼近 */
let shown = 0
/** 上一帧的位图尺寸：不变时跳过画布重建，避免每帧重新分配显存 */
let lastW = 0
let lastH = 0
let lastDpr = 0
/** 调色板缓存：避免每帧调用 getComputedStyle */
let palette: { accent: string; dim: string; border: string } | null = null

interface Seg {
  x1: number
  y1: number
  x2: number
  y2: number
}

/** 线段索引表：仅在 preview / bbox 变化时重建，绘制时不再遍历原始数组 */
const geom = computed(() => {
  const rapids: Seg[] = []
  const cuts: Seg[] = []
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const m of props.preview) {
    const seg: Seg = { x1: m.x1, y1: m.y1, x2: m.x2, y2: m.y2 }
    if (m.rapid) rapids.push(seg)
    else cuts.push(seg)
    if (m.x1 < minX) minX = m.x1
    if (m.x2 < minX) minX = m.x2
    if (m.y1 < minY) minY = m.y1
    if (m.y2 < minY) minY = m.y2
    if (m.x1 > maxX) maxX = m.x1
    if (m.x2 > maxX) maxX = m.x2
    if (m.y1 > maxY) maxY = m.y1
    if (m.y2 > maxY) maxY = m.y2
  }

  const b = props.bbox
  let bounds: { minX: number; minY: number; maxX: number; maxY: number }
  if (b && b.valid) {
    bounds = { minX: b.minX, minY: b.minY, maxX: b.maxX, maxY: b.maxY }
  } else if (Number.isFinite(minX)) {
    bounds = { minX, minY, maxX, maxY }
  } else {
    bounds = { minX: 0, minY: 0, maxX: 1, maxY: 1 }
  }

  return { rapids, cuts, bounds }
})

/** 保留 1 位小数 */
function format(v: number) {
  return v.toFixed(1)
}

/** 尺寸文本：宽 × 高（mm）；bbox 无效或缺失时为 -- */
const sizeText = computed(() => {
  const b = props.bbox
  if (!b || !b.valid) return '--'
  const w = b.maxX - b.minX
  const h = b.maxY - b.minY
  if (!Number.isFinite(w) || !Number.isFinite(h)) return '--'
  return `宽 ${format(w)} × 高 ${format(h)} mm`
})

function cssVar(name: string, fallback: string) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

function getPalette() {
  if (!palette) {
    palette = {
      accent: cssVar('--lg-accent', '#ff7a18'),
      dim: cssVar('--lg-text-dim', '#9a9aa5'),
      border: cssVar('--lg-border', '#2e2e36')
    }
  }
  return palette
}

/** 目标进度；无进度数据时返回 null（表示整条路径一次性绘制） */
function targetProgress(): number | null {
  const p = props.progress
  if (typeof p !== 'number' || !Number.isFinite(p)) return null
  return Math.min(100, Math.max(0, p))
}

/** 系统是否开启「减弱动态效果」 */
function reducedMotion() {
  if (motionQuery) return motionQuery.matches
  return (
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}

/** 绘制激光头指示点（外发光 + 白色高光核心） */
function drawHead(ctx: CanvasRenderingContext2D, x: number, y: number, accent: string) {
  ctx.save()
  ctx.shadowColor = accent
  ctx.shadowBlur = 12
  ctx.fillStyle = accent
  ctx.beginPath()
  ctx.arc(x, y, 3.4, 0, Math.PI * 2)
  ctx.fill()
  ctx.shadowBlur = 0
  ctx.fillStyle = '#fff'
  ctx.beginPath()
  ctx.arc(x, y, 1.5, 0, Math.PI * 2)
  ctx.fill()
  ctx.restore()
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
  const bw = Math.round(cssW * dpr)
  const bh = Math.round(cssH * dpr)
  // 尺寸未变时不重置画布，省掉每帧的显存重分配
  if (bw !== lastW || bh !== lastH || dpr !== lastDpr) {
    canvas.width = bw
    canvas.height = bh
    lastW = bw
    lastH = bh
    lastDpr = dpr
  }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, cssW, cssH)

  const { accent, dim, border } = getPalette()
  const { rapids, cuts, bounds: b } = geom.value

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
  for (const s of rapids) {
    ctx.moveTo(tx(s.x1), ty(s.y1))
    ctx.lineTo(tx(s.x2), ty(s.y2))
  }
  ctx.stroke()

  const total = cuts.length
  const target = targetProgress()
  let head: { x: number; y: number } | null = null

  if (target === null) {
    // 无进度数据：与旧版一致，整条路径一次性画完
    ctx.globalAlpha = 1
    ctx.strokeStyle = accent
    ctx.beginPath()
    for (const s of cuts) {
      ctx.moveTo(tx(s.x1), ty(s.y1))
      ctx.lineTo(tx(s.x2), ty(s.y2))
    }
    ctx.stroke()
  } else {
    // 按线段索引分配进度：前 full 条已雕刻，第 full 条按 frac 部分绘制
    const rendered = Math.min(100, Math.max(0, shown))
    const exact = (total * rendered) / 100
    const full = Math.min(total, Math.max(0, Math.floor(exact)))
    const frac = full < total ? exact - full : 0

    // 未雕刻部分：低透明度淡色（含正在雕刻的那一段，随后被实线覆盖，避免出现缝隙）
    if (full < total) {
      ctx.globalAlpha = 0.18
      ctx.strokeStyle = accent
      ctx.beginPath()
      for (let i = full; i < total; i++) {
        const s = cuts[i]
        ctx.moveTo(tx(s.x1), ty(s.y1))
        ctx.lineTo(tx(s.x2), ty(s.y2))
      }
      ctx.stroke()
    }

    // 已雕刻部分：强调色实线
    if (full > 0) {
      ctx.globalAlpha = 1
      ctx.strokeStyle = accent
      ctx.beginPath()
      for (let i = 0; i < full; i++) {
        const s = cuts[i]
        ctx.moveTo(tx(s.x1), ty(s.y1))
        ctx.lineTo(tx(s.x2), ty(s.y2))
      }
      ctx.stroke()
    }

    // 边界平滑：最后一段按比例绘制到当前进度点
    if (frac > 0 && full < total) {
      const s = cuts[full]
      const ex = s.x1 + (s.x2 - s.x1) * frac
      const ey = s.y1 + (s.y2 - s.y1) * frac
      ctx.globalAlpha = 1
      ctx.strokeStyle = accent
      ctx.beginPath()
      ctx.moveTo(tx(s.x1), ty(s.y1))
      ctx.lineTo(tx(ex), ty(ey))
      ctx.stroke()
      head = { x: tx(ex), y: ty(ey) }
    } else if (full > 0) {
      const s = cuts[full - 1]
      head = { x: tx(s.x2), y: ty(s.y2) }
    } else if (total > 0) {
      // 进度 0：指示点停在首段起点
      const s = cuts[0]
      head = { x: tx(s.x1), y: ty(s.y1) }
    }

    ctx.globalAlpha = 1
  }

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

  // 激光头指示点画在最上层，避免被原点十字压住
  ctx.globalAlpha = 1
  if (head) drawHead(ctx, head.x, head.y, accent)

  ctx.globalAlpha = 1
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

function stopTween() {
  if (tweenRaf) cancelAnimationFrame(tweenRaf)
  tweenRaf = 0
}

let lastTs = 0

/** 补间循环：指数逼近目标进度，与帧率无关且不生硬跳变 */
function step(ts: number) {
  tweenRaf = 0
  // 本帧已经有排队的重绘就取消，避免同一帧画两次
  if (raf) {
    cancelAnimationFrame(raf)
    raf = 0
  }
  const target = targetProgress()
  if (target === null) {
    draw()
    return
  }
  const dt = lastTs ? Math.min(ts - lastTs, 100) : 16
  lastTs = ts
  const diff = target - shown
  if (Math.abs(diff) < 0.05) {
    shown = target
    lastTs = 0
    draw()
    return
  }
  shown += diff * (1 - Math.exp(-dt / 120))
  draw()
  tweenRaf = requestAnimationFrame(step)
}

/** 让已渲染进度对齐目标进度；instant 为 true 时不做补间 */
function syncProgress(instant = false) {
  stopTween()
  lastTs = 0
  const target = targetProgress()
  if (target === null) {
    // 无进度数据：恢复一次性绘制
    shown = 0
    requestDraw()
    return
  }
  if (instant || !props.animated || reducedMotion()) {
    shown = target
    requestDraw()
    return
  }
  tweenRaf = requestAnimationFrame(step)
}

/** 系统「减弱动态效果」开关变化时立即按静态状态重绘 */
function onMotionChange() {
  syncProgress(true)
}

onMounted(() => {
  syncProgress(true)
  if (typeof ResizeObserver !== 'undefined' && wrapRef.value) {
    ro = new ResizeObserver(() => {
      lastW = 0
      lastH = 0
      requestDraw()
    })
    ro.observe(wrapRef.value)
  }
  if (typeof MutationObserver !== 'undefined') {
    // 主题切换后令牌变化，清空调色板缓存并重绘
    themeObserver = new MutationObserver(() => {
      palette = null
      requestDraw()
    })
    themeObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme', 'class']
    })
  }
  if (typeof window.matchMedia === 'function') {
    motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
    motionQuery.addEventListener?.('change', onMotionChange)
  }
})

onBeforeUnmount(() => {
  if (raf) cancelAnimationFrame(raf)
  raf = 0
  stopTween()
  ro?.disconnect()
  ro = null
  themeObserver?.disconnect()
  themeObserver = null
  motionQuery?.removeEventListener?.('change', onMotionChange)
  motionQuery = null
})

watch(
  () => [props.preview, props.bbox, props.height],
  () => requestDraw()
)

watch(
  () => [props.progress, props.animated],
  () => syncProgress()
)
</script>

<template>
  <div ref="wrapRef" class="lg-canvas-wrap" :style="{ height: `${height}px` }">
    <canvas ref="canvasRef" :style="{ height: `${height}px` }" />
    <!-- 尺寸标签：玻璃质感小标签，叠在深色画布左上角 -->
    <div class="size-tag" :class="{ 'size-tag--empty': sizeText === '--' }">{{ sizeText }}</div>
  </div>
</template>

<style scoped>
.size-tag {
  position: absolute;
  left: 8px;
  top: 8px;
  max-width: calc(100% - 16px);
  padding: 4px 10px;
  border-radius: var(--g-r-pill, 999px);
  background: var(--g-fill);
  border: 1px solid var(--g-hairline);
  backdrop-filter: blur(12px) saturate(160%);
  -webkit-backdrop-filter: blur(12px) saturate(160%);
  color: var(--g-text);
  font-size: 11.5px;
  font-weight: 620;
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  pointer-events: none;
  box-shadow: var(--g-shadow-sm);
}

.size-tag--empty {
  color: var(--g-text-dim);
}
</style>
