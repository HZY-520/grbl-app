<script setup lang="ts">
/**
 * 图片转雕刻
 *  - 光栅模式：Line2Line（线条）/ 抖动（黑白点阵），逐行扫描出光
 *  - 线性模式：轮廓描线（Potrace）/ 中心线走线（骨架），沿图形走线，非水平轮询
 */
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import GcodePreview from '../components/GcodePreview.vue'
import {
  convertImageToGcode,
  DIRECTION_LABELS,
  DEFAULT_RASTER_OPTIONS,
  type RasterDirection,
  type RasterTool
} from '../../core/raster/RasterConverter'
import { Formula, FORMULA_LABELS, resizeImage, toDataURL } from '../../core/raster/ImageTransform'
import { DITHERING_MODES, DITHERING_LABELS, type DitheringMode } from '../../core/raster/dithering'
import {
  convertImageVector,
  DEFAULT_IMAGE_VECTOR_OPTIONS,
  VECTOR_TOOL_LABELS,
  type VectorTool
} from '../../core/vector/ImageVector'
import { fitSizeToTravel, checkGcodeWithinTravel } from '../../core/grbl/DeviceProfile'
import type { GcodeFileData } from '../../core/gcode/GrblFile'
import { AppSettings } from '../../core/grbl/GrblConfig'
import { loadGcodeLines } from '../store'
import { saveGcodeFile } from '../storage'
import { pickFile, loadImage, toast } from '../utils'

const router = useRouter()

/** 扩展后的转换方式：光栅两种 + 线性两种 */
type ImageTool = RasterTool | VectorTool
const TOOL_LABELS: Record<ImageTool, string> = {
  Line2Line: '线条扫描',
  Dithering: '抖动点阵',
  Outline: VECTOR_TOOL_LABELS.Outline,
  Centerline: VECTOR_TOOL_LABELS.Centerline
}

const imgUrl = ref('')
const imgName = ref('')
const imgEl = ref<HTMLImageElement | null>(null)
const imgW = ref(0)
const imgH = ref(0)

const previewUrl = ref('')
const generated = ref<GcodeFileData | null>(null)
const pixelInfo = ref('')
const extraInfo = ref('')
const warn = ref('')
const busy = ref(false)
const tool = ref<ImageTool>('Line2Line')

const opts = reactive({
  ...DEFAULT_RASTER_OPTIONS,
  markSpeed: AppSettings.get<number>('Mark Speed', 1000),
  minPower: AppSettings.get<number>('Min Power', 0),
  maxPower: AppSettings.get<number>('Max Power', 1000),
  laserOn: AppSettings.get<string>('Laser On Command', 'M4'),
  laserOff: AppSettings.get<string>('Laser Off Command', 'M5'),
  pwm: AppSettings.get<boolean>('Support Hardware PWM', true),
  unidirectional: AppSettings.get<boolean>('Unidirectional Engraving', false),
  disableFastSkip: AppSettings.get<boolean>('Disable G0 fast skip', false),
  header: AppSettings.get<string>('Header', 'G90'),
  footer: AppSettings.get<string>('Footer', 'M5\nG0 X0 Y0')
})

/** 线性（描线/中心线）专用参数 */
const vec = reactive({
  /** 二值化阈值（百分数 1..99） */
  threshold: 50,
  invert: false,
  /** 轮廓：去斑面积（像素） */
  turdSize: DEFAULT_IMAGE_VECTOR_OPTIONS.turdSize,
  /** 轮廓：圆角阈值 */
  alphaMax: DEFAULT_IMAGE_VECTOR_OPTIONS.alphaMax,
  /** 轮廓：曲线优化 */
  curveOptimizing: DEFAULT_IMAGE_VECTOR_OPTIONS.curveOptimizing,
  /** 中心线：去毛刺长度（像素） */
  minBranchPx: DEFAULT_IMAGE_VECTOR_OPTIONS.minBranchPx,
  /** 中心线：简化容差（像素） */
  simplifyTolerance: DEFAULT_IMAGE_VECTOR_OPTIONS.simplifyTolerance,
  /** 最近邻排序，缩短空移 */
  optimize: true
})

const widthMm = ref(50)
const heightMm = ref(50)
const autoHeight = ref(true)

const isVector = computed(() => tool.value === 'Outline' || tool.value === 'Centerline')

const aspect = computed(() => (imgW.value > 0 ? imgH.value / imgW.value : 1))

const effectiveHeight = computed(() => {
  if (autoHeight.value) return Math.max(1, Math.round(widthMm.value * aspect.value * 100) / 100)
  return heightMm.value
})

const canGenerate = computed(() => !!imgEl.value && widthMm.value > 0 && effectiveHeight.value > 0)

const FORMULA_OPTIONS = Object.entries(FORMULA_LABELS).map(([value, label]) => ({
  value: Number(value),
  label
}))

const DIRECTION_OPTIONS = (Object.keys(DIRECTION_LABELS) as RasterDirection[]).map((value) => ({
  value,
  label: DIRECTION_LABELS[value]
}))

const DITHER_OPTIONS = DITHERING_MODES.map((value) => ({ value, label: DITHERING_LABELS[value] }))

const TOOL_OPTIONS: ImageTool[] = ['Line2Line', 'Dithering', 'Outline', 'Centerline']

/** 依据设备行程对尺寸做自适应（Fit：等比缩放到行程内） */
function adaptSize(): { w: number; h: number; message?: string } {
  const r = fitSizeToTravel(widthMm.value, effectiveHeight.value, { mode: 'Fit' })
  return { w: r.widthMm, h: r.heightMm, message: r.message }
}

async function pick() {
  const file = await pickFile('image/*')
  if (!file || !file.dataUrl) {
    if (file) toast('请选择图片文件', 'error')
    return
  }
  try {
    const img = await loadImage(file.dataUrl)
    imgEl.value = img
    imgW.value = img.naturalWidth
    imgH.value = img.naturalHeight
    imgUrl.value = file.dataUrl
    imgName.value = file.name
    if (autoHeight.value) {
      heightMm.value = Math.max(1, Math.round(widthMm.value * (imgH.value / imgW.value) * 100) / 100)
    }
    previewUrl.value = ''
    generated.value = null
    warn.value = ''
    toast(`已导入 ${file.name}（${imgW.value}×${imgH.value}）`, 'success')
  } catch (e) {
    toast(`图片加载失败：${String(e)}`, 'error')
  }
}

/** 线性模式：位图 → 沿图形走线（轮廓 / 中心线） */
function generateVector(wMm: number, hMm: number) {
  const el = imgEl.value as HTMLImageElement
  const srcW = imgW.value
  const srcH = imgH.value
  // 控制处理分辨率，兼顾精度与性能
  const maxDim = 1600
  const s = Math.min(1, maxDim / Math.max(srcW, srcH))
  const pw = Math.max(1, Math.round(srcW * s))
  const ph = Math.max(1, Math.round(srcH * s))
  const id = resizeImage(el, srcW, srcH, pw, ph, true, 'high')

  const res = convertImageVector(
    { data: id.data, width: pw, height: ph },
    {
      ...DEFAULT_IMAGE_VECTOR_OPTIONS,
      tool: tool.value as VectorTool,
      threshold: Math.round((vec.threshold / 100) * 255),
      invert: vec.invert,
      widthMm: wMm,
      heightMm: hMm,
      offsetX: opts.offsetX,
      offsetY: opts.offsetY,
      markSpeed: opts.markSpeed,
      travelSpeed: 3000,
      minPower: opts.minPower,
      maxPower: opts.maxPower,
      laserPower: opts.maxPower,
      laserOn: opts.laserOn,
      laserOff: opts.laserOff,
      pwm: opts.pwm,
      header: opts.header,
      footer: opts.footer,
      optimize: vec.optimize,
      turdSize: vec.turdSize,
      alphaMax: vec.alphaMax,
      curveOptimizing: vec.curveOptimizing,
      minBranchPx: vec.minBranchPx,
      simplifyTolerance: vec.simplifyTolerance
    }
  )

  previewUrl.value = toDataURL(res.preview)
  pixelInfo.value = `${pw} × ${ph} px · 二值化 ${vec.threshold}%`
  extraInfo.value = `走线 ${res.pathCount} 段 · 路径长度 ${res.lengthMm.toFixed(1)} mm`
  const base = imgName.value.replace(/\.[^.]+$/, '') || 'image'
  generated.value = loadGcodeLines(`${base}-${tool.value}.gcode`, res.lines)
  return res.lines
}

/** 光栅模式：逐行扫描出光 */
async function generateRaster(wMm: number, hMm: number) {
  opts.tool = tool.value as RasterTool
  const res = await convertImageToGcode(imgEl.value as HTMLImageElement, imgW.value, imgH.value, wMm, hMm, opts)
  previewUrl.value = toDataURL(res.preview)
  pixelInfo.value = `${res.pixelWidth} × ${res.pixelHeight} px · ${res.res.toFixed(2)} 线/mm`
  extraInfo.value = ''
  const base = imgName.value.replace(/\.[^.]+$/, '') || 'image'
  generated.value = loadGcodeLines(`${base}.gcode`, res.lines)
  return res.lines
}

async function generate() {
  if (!canGenerate.value || !imgEl.value) {
    toast('请先导入图片', 'error')
    return
  }
  busy.value = true
  warn.value = ''
  try {
    const fit = adaptSize()
    if (fit.message) warn.value = fit.message
    const lines = isVector.value ? generateVector(fit.w, fit.h) : await generateRaster(fit.w, fit.h)
    const chk = checkGcodeWithinTravel(lines)
    if (!chk.ok && chk.message) warn.value = warn.value ? `${warn.value}；${chk.message}` : chk.message
    toast(`已生成 ${lines.length} 行 G 代码`, 'success')
  } catch (e) {
    toast(`生成失败：${String(e)}`, 'error')
  } finally {
    busy.value = false
  }
}

function save() {
  if (!generated.value) return
  saveGcodeFile(generated.value.name, generated.value.commands.map((c) => c.command), {
    kind: tool.value,
    widthMm: widthMm.value,
    heightMm: effectiveHeight.value
  })
  toast('已保存到雕刻文件', 'success')
}

function goHome() {
  router.push('/home')
}

function onNum(
  key: 'widthMm' | 'heightMm' | 'quality' | 'markSpeed' | 'minPower' | 'maxPower' | 'offsetX' | 'offsetY' | 'whiteClip' | 'threshold',
  ev: Event
) {
  const t = ev.target as HTMLInputElement
  const v = parseFloat(t.value)
  if (!Number.isFinite(v)) return
  if (key === 'widthMm') widthMm.value = v
  else if (key === 'heightMm') heightMm.value = v
  else opts[key] = v
}

function onVecNum(
  key: 'threshold' | 'turdSize' | 'alphaMax' | 'minBranchPx' | 'simplifyTolerance',
  ev: Event
) {
  const t = ev.target as HTMLInputElement
  const v = parseFloat(t.value)
  if (Number.isFinite(v)) vec[key] = v
}

function setTool(t: ImageTool) {
  tool.value = t
  if (t === 'Line2Line' || t === 'Dithering') opts.tool = t
}
</script>

<template>
  <div class="lg-page">
    <div class="lg-body">
      <!-- 导入 -->
      <section class="lg-section">
        <div class="lg-title"><span>图片</span></div>
        <div v-if="!imgUrl" class="upload-box" @click="pick">
          <AppIcon name="image" :size="30" />
          <div class="lg-mt">点击选择图片</div>
          <div class="lg-dim">支持 JPG / PNG / BMP / GIF / WEBP</div>
        </div>
        <div v-else class="img-row">
          <img :src="imgUrl" class="thumb" alt="原图" />
          <div class="img-row__text">
            <div class="img-row__name">{{ imgName }}</div>
            <div class="lg-dim">{{ imgW }} × {{ imgH }} px</div>
          </div>
          <var-button size="small" text type="primary" @click="pick">更换</var-button>
        </div>
      </section>

      <!-- 尺寸 -->
      <section class="lg-section">
        <div class="lg-title"><span>尺寸</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">宽度 (mm)</span>
            <var-input :model-value="String(widthMm)" type="number" variant="outlined" @blur="onNum('widthMm', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">高度 (mm)</span>
            <var-input
              :model-value="autoHeight ? String(effectiveHeight) : String(heightMm)"
              type="number"
              variant="outlined"
              :disabled="autoHeight"
              @blur="onNum('heightMm', $event)"
            />
          </div>
        </div>
        <var-cell title="高度按比例自动" description="保持图片宽高比">
          <template #extra>
            <var-switch v-model="autoHeight" />
          </template>
        </var-cell>
        <div class="lg-dim lg-mt">生成时会自动按设备行程（可在设置 / 初始化向导中修改）等比适配。</div>
      </section>

      <!-- 转换方式 -->
      <section class="lg-section">
        <div class="lg-title"><span>转换方式</span></div>
        <div class="steps">
          <var-button
            v-for="t in TOOL_OPTIONS"
            :key="t"
            size="small"
            :type="tool === t ? 'primary' : 'default'"
            :plain="tool !== t"
            @click="setTool(t)"
          >
            {{ TOOL_LABELS[t] }}
          </var-button>
        </div>
        <div class="lg-dim lg-mt">
          {{ isVector ? '沿图形轮廓 / 笔画走线，不是水平轮询。' : '逐行扫描出光，适合照片 / 渐变色块。' }}
        </div>

        <!-- 光栅：扫描方向 -->
        <template v-if="!isVector">
          <div class="lg-deco lg-mt">
            <div class="lg-dim">扫描方向</div>
            <var-select class="lg-mt" :model-value="opts.direction" variant="outlined" @change="opts.direction = $event as RasterDirection">
              <var-option v-for="d in DIRECTION_OPTIONS" :key="d.value" :label="d.label" :value="d.value" />
            </var-select>
          </div>
        </template>

        <div class="num-grid lg-mt">
          <div v-if="!isVector" class="num-item">
            <span class="lg-dim">分辨率 (线/mm)</span>
            <var-input :model-value="String(opts.quality)" type="number" variant="outlined" @blur="onNum('quality', $event)" />
          </div>
          <div v-else class="num-item">
            <span class="lg-dim">二值化阈值 (%)</span>
            <var-input :model-value="String(vec.threshold)" type="number" variant="outlined" @blur="onVecNum('threshold', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">起始偏移 X / Y (mm)</span>
            <div class="lg-grid-2">
              <var-input :model-value="String(opts.offsetX)" type="number" variant="outlined" @blur="onNum('offsetX', $event)" />
              <var-input :model-value="String(opts.offsetY)" type="number" variant="outlined" @blur="onNum('offsetY', $event)" />
            </div>
          </div>
        </div>

        <var-cell v-if="isVector" title="反相" description="深底浅图时启用">
          <template #extra>
            <var-switch v-model="vec.invert" />
          </template>
        </var-cell>
      </section>

      <!-- 抖动模式 -->
      <section v-if="tool === 'Dithering'" class="lg-section">
        <div class="lg-title"><span>抖动算法</span></div>
        <var-select :model-value="opts.dithering" variant="outlined" @change="opts.dithering = $event as DitheringMode">
          <var-option v-for="d in DITHER_OPTIONS" :key="d.value" :label="d.label" :value="d.value" />
        </var-select>
      </section>

      <!-- 线性参数 -->
      <section v-if="isVector" class="lg-section">
        <div class="lg-title"><span>{{ tool === 'Centerline' ? '中心线参数' : '轮廓描线参数' }}</span></div>
        <div v-if="tool === 'Outline'" class="num-grid">
          <div class="num-item">
            <span class="lg-dim">去斑面积 (像素)</span>
            <var-input :model-value="String(vec.turdSize)" type="number" variant="outlined" @blur="onVecNum('turdSize', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">圆角阈值</span>
            <var-input :model-value="String(vec.alphaMax)" type="number" variant="outlined" @blur="onVecNum('alphaMax', $event)" />
          </div>
        </div>
        <div v-else class="num-grid">
          <div class="num-item">
            <span class="lg-dim">去毛刺长度 (像素)</span>
            <var-input :model-value="String(vec.minBranchPx)" type="number" variant="outlined" @blur="onVecNum('minBranchPx', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">简化容差 (像素)</span>
            <var-input :model-value="String(vec.simplifyTolerance)" type="number" variant="outlined" @blur="onVecNum('simplifyTolerance', $event)" />
          </div>
        </div>
        <var-cell title="轮廓曲线优化" description="把相邻曲线合并，减少节点数（仅轮廓描线）">
          <template #extra>
            <var-switch v-model="vec.curveOptimizing" :disabled="tool !== 'Outline'" />
          </template>
        </var-cell>
        <var-cell title="路径排序优化" description="最近邻排序，缩短空移距离">
          <template #extra>
            <var-switch v-model="vec.optimize" />
          </template>
        </var-cell>
      </section>

      <!-- 雕刻参数 -->
      <section class="lg-section">
        <div class="lg-title"><span>雕刻参数</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">雕刻速度 (mm/min)</span>
            <var-input :model-value="String(opts.markSpeed)" type="number" variant="outlined" @blur="onNum('markSpeed', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">最大功率 S</span>
            <var-input :model-value="String(opts.maxPower)" type="number" variant="outlined" @blur="onNum('maxPower', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">最小功率 S</span>
            <var-input :model-value="String(opts.minPower)" type="number" variant="outlined" @blur="onNum('minPower', $event)" />
          </div>
        </div>
        <var-cell title="硬件 PWM" description="使用 S 值渐变控制激光功率">
          <template #extra>
            <var-switch v-model="opts.pwm" />
          </template>
        </var-cell>
        <var-cell v-if="!isVector" title="单向雕刻" description="仅单向出光，质量更高">
          <template #extra>
            <var-switch v-model="opts.unidirectional" />
          </template>
        </var-cell>
        <var-cell v-if="!isVector" title="禁用 G0 快速空移" description="空移使用 G1 进给">
          <template #extra>
            <var-switch v-model="opts.disableFastSkip" />
          </template>
        </var-cell>
      </section>

      <!-- 进阶预处理（仅光栅） -->
      <section v-if="!isVector" class="lg-section">
        <var-collapse>
          <var-collapse-item title="进阶：图像预处理" name="adv">
            <div class="lg-deco">
              <div class="lg-dim">灰度公式</div>
              <var-select class="lg-mt" :model-value="opts.formula" variant="outlined" @change="opts.formula = $event as Formula">
                <var-option v-for="f in FORMULA_OPTIONS" :key="f.value" :label="f.label" :value="f.value" />
              </var-select>
            </div>

            <div class="slider-row lg-mt">
              <span class="lg-dim">亮度</span>
              <span class="lg-mono">{{ opts.brightness }}</span>
            </div>
            <var-slider v-model="opts.brightness" :min="0" :max="200" :step="5" />

            <div class="slider-row lg-mt">
              <span class="lg-dim">对比度</span>
              <span class="lg-mono">{{ opts.contrast }}</span>
            </div>
            <var-slider v-model="opts.contrast" :min="0" :max="200" :step="5" />

            <div class="slider-row lg-mt">
              <span class="lg-dim">白色裁剪</span>
              <span class="lg-mono">{{ opts.whiteClip }}</span>
            </div>
            <var-slider v-model="opts.whiteClip" :min="0" :max="100" :step="1" />

            <template v-if="tool === 'Line2Line'">
              <var-cell title="启用阈值化" description="按阈值二值化，适合线稿">
                <template #extra>
                  <var-switch v-model="opts.useThreshold" />
                </template>
              </var-cell>
              <div class="slider-row">
                <span class="lg-dim">阈值</span>
                <span class="lg-mono">{{ opts.threshold }}</span>
              </div>
              <var-slider v-model="opts.threshold" :min="1" :max="99" :step="1" />
            </template>

            <var-cell title="高质量插值" description="缩放时使用平滑插值">
              <template #extra>
                <var-switch :model-value="opts.interpolation === 'high'" @update:model-value="opts.interpolation = $event ? 'high' : 'low'" />
              </template>
            </var-cell>
          </var-collapse-item>
        </var-collapse>
      </section>

      <var-alert v-if="warn" type="warning" :title="warn" class="lg-mb" />

      <var-button block type="primary" :loading="busy" :disabled="!canGenerate" @click="generate">
        <AppIcon name="layers" :size="17" />
        <span class="btn-text">生成雕刻路径</span>
      </var-button>

      <!-- 结果 -->
      <section v-if="generated" class="lg-section lg-mt">
        <div class="lg-title">
          <span>二值化预览</span>
          <span class="lg-dim">{{ pixelInfo }}</span>
        </div>
        <div class="processed">
          <img :src="previewUrl" alt="预处理预览" />
        </div>
        <div v-if="extraInfo" class="lg-dim lg-mt">{{ extraInfo }}</div>

        <div class="lg-title lg-mt"><span>路径预览</span></div>
        <GcodePreview :preview="generated.preview" :bbox="generated.stats.bbox" :height="220" />

        <div class="lg-grid-2 lg-mt">
          <var-button block plain @click="save">
            <AppIcon name="save" :size="17" />
            <span class="btn-text">保存文件</span>
          </var-button>
          <var-button block type="primary" @click="goHome">
            <AppIcon name="play" :size="17" />
            <span class="btn-text">去雕刻</span>
          </var-button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.upload-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 26px 12px;
  border: 1px dashed var(--lg-border);
  border-radius: 12px;
  color: var(--lg-accent);
  text-align: center;
  cursor: pointer;
}

.upload-box:active {
  opacity: 0.75;
}

.img-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.thumb {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--lg-border);
}

.img-row__text {
  flex: 1;
  min-width: 0;
}

.img-row__name {
  font-size: 13.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.steps {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.num-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.num-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.slider-row {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
}

.processed {
  display: flex;
  justify-content: center;
  padding: 10px;
  background: var(--lg-panel-2);
  border-radius: 10px;
  overflow: hidden;
}

.processed img {
  max-width: 100%;
  max-height: 260px;
  image-rendering: pixelated;
  border-radius: 6px;
}

.btn-text {
  margin-left: 5px;
}
</style>