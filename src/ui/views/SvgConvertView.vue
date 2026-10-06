<script setup lang="ts">
/**
 * SVG 转雕刻
 *  - 轮廓提取：直接解析 path 几何（原有实现，未改动）
 *  - 中心线描线：光栅化 → 骨架化 → 单线走线，适合签名 / 线稿 / 单线图形
 *  - 智能：按笔画宽度启发式自动在两者之间选择
 */
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import GcodePreview from '../components/GcodePreview.vue'
import { convertSvgToGcode, type SvgConvertResult } from '../../core/vector/SvgToGcode'
import {
  convertSvgVector,
  SVG_MODE_LABELS,
  type SvgVectorMode,
  type SvgVectorResult
} from '../../core/vector/SvgVector'
import { toDataURL } from '../../core/raster/ImageTransform'
import { fitSizeToTravel, checkGcodeWithinTravel } from '../../core/grbl/DeviceProfile'
import type { GcodeFileData } from '../../core/gcode/GrblFile'
import { AppSettings } from '../../core/grbl/GrblConfig'
import { loadGcodeLines } from '../store'
import { saveGcodeFile } from '../storage'
import { pickFile } from '../utils'
import { toast } from '../utils'

const router = useRouter()

const svgName = ref('')
const svgText = ref('')
const generated = ref<GcodeFileData | null>(null)
const info = ref<SvgConvertResult | null>(null)
const warn = ref('')
const busy = ref(false)

/** 走线方式（默认保持原有的轮廓提取） */
const mode = ref<SvgVectorMode>('Outline')
const MODE_OPTIONS: SvgVectorMode[] = ['Outline', 'Centerline', 'Auto']
const MODE_HINTS: Record<SvgVectorMode, string> = {
  Outline: '解析 SVG 几何，沿图形内外轮廓走线；适合实心图案、字母轮廓。已转成填充路径的线稿会描出每条线的外框（双边）。',
  Centerline: '先光栅化再取骨架，沿笔画中心单线走线；适合签名 / 线稿 / 单线图形，省时间省材料。',
  Auto: '先按「平均笔画宽度 ≈ 墨水面积 / 骨架长度」判断是否线稿：细线稿 → 中心线描线；实心 / 粗笔画 → 轮廓提取。'
}

/** 智能判定结果与光栅化信息（界面展示用） */
const decisionText = ref('')
const rasterInfo = ref('')
const rasterPreviewUrl = ref('')

/** 描线（中心线 / 智能）专用参数 */
const vec = reactive({
  /** 二值化阈值（百分数 1..99） */
  threshold: 50,
  invert: false,
  /** 光栅化长边像素 */
  rasterPixels: 1024,
  /** 中心线：去毛刺长度（像素） */
  minBranchPx: 6,
  /** 中心线：简化容差（像素） */
  simplifyTolerance: 1.2,
  /** 智能：笔画宽度阈值（占图像短边百分比） */
  strokeWidthPct: 2.5
})

const opts = reactive({
  widthMm: 60,
  heightMm: 0,
  autoHeight: true,
  tolerance: 0.1,
  markSpeed: AppSettings.get<number>('Mark Speed', 1000),
  travelSpeed: 0,
  maxPower: AppSettings.get<number>('Max Power', 1000),
  pwm: AppSettings.get<boolean>('Support Hardware PWM', true),
  laserOn: AppSettings.get<string>('Laser On Command', 'M4'),
  laserOff: AppSettings.get<string>('Laser Off Command', 'M5'),
  offsetX: 0,
  offsetY: 0
})

const canGenerate = computed(() => svgText.value.length > 0 && opts.widthMm > 0)

/** 从 data URL 还原 SVG 源码（base64 或百分号编码，UTF-8 安全） */
function decodeSvgDataUrl(dataUrl: string): string {
  if (!dataUrl.startsWith('data:')) return ''
  const comma = dataUrl.indexOf(',')
  if (comma < 0) return ''
  const head = dataUrl.slice(0, comma)
  const body = dataUrl.slice(comma + 1)
  try {
    if (/;base64/i.test(head)) {
      const bin = atob(body)
      const bytes = new Uint8Array(bin.length)
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
      return new TextDecoder('utf-8').decode(bytes)
    }
    return decodeURIComponent(body)
  } catch {
    return ''
  }
}

async function pick() {
  const file = await pickFile('.svg,image/svg+xml')
  if (!file) return
  // pickFile 对 image/* 走 readAsDataURL（此时 file.text 为空），而桌面浏览器会把 .svg
  // 识别成 image/svg+xml，导致导入后拿不到 SVG 源码、无法生成；这里从 data URL 还原。
  const text = file.text || decodeSvgDataUrl(file.dataUrl)
  if (!/svg/i.test(file.name) && !/svg/i.test(file.dataUrl.slice(0, 40)) && !text.includes('<svg')) {
    toast('请选择 SVG 文件', 'error')
    return
  }
  svgName.value = file.name
  svgText.value = text
  generated.value = null
  info.value = null
  decisionText.value = ''
  rasterInfo.value = ''
  rasterPreviewUrl.value = ''
  toast(`已导入 ${file.name}`, 'success')
}

/** 组装转换参数（尺寸可变，便于行程自适应后重算） */
function buildOptions(w: number, h?: number) {
  return {
    targetWidthMm: w,
    targetHeightMm: h,
    tolerance: opts.tolerance,
    markSpeed: opts.markSpeed,
    travelSpeed: opts.travelSpeed,
    laserOn: opts.laserOn,
    laserOff: opts.laserOff,
    pwm: opts.pwm,
    maxPower: opts.maxPower,
    offsetX: opts.offsetX,
    offsetY: opts.offsetY,
    header: AppSettings.get<string>('Header', 'G90'),
    footer: AppSettings.get<string>('Footer', 'M5')
  }
}

/** 描线（中心线 / 智能）参数 */
function buildVectorOptions(w: number, h?: number) {
  return {
    ...buildOptions(w, h),
    mode: mode.value,
    rasterPixels: vec.rasterPixels,
    threshold: Math.round((vec.threshold / 100) * 255),
    invert: vec.invert,
    minBranchPx: vec.minBranchPx,
    simplifyTolerance: vec.simplifyTolerance,
    strokeWidthThresholdPct: vec.strokeWidthPct,
    optimize: true as const
  }
}

/**
 * 统一入口：
 *  - 轮廓提取：原样调用 convertSvgToGcode（不做光栅化，结果与旧版逐字节一致）；
 *  - 中心线 / 智能：走 convertSvgVector。
 */
async function runConvert(w: number, h?: number): Promise<SvgVectorResult> {
  if (mode.value === 'Outline') {
    const r = convertSvgToGcode(svgText.value, buildOptions(w, h))
    return { ...r, mode: 'Outline', rasterized: false, rasterWidth: 0, rasterHeight: 0, preview: null }
  }
  return await convertSvgVector(svgText.value, buildVectorOptions(w, h))
}

/** 切换走线方式：清掉上一次的判定 / 光栅化展示，避免误导 */
function setMode(m: SvgVectorMode) {
  if (mode.value === m) return
  mode.value = m
  decisionText.value = ''
  rasterInfo.value = ''
  rasterPreviewUrl.value = ''
}

async function generate() {
  if (!canGenerate.value) {
    toast('请先导入 SVG 文件', 'error')
    return
  }
  busy.value = true
  warn.value = ''
  try {
    let res = await runConvert(opts.widthMm, opts.autoHeight ? undefined : opts.heightMm)

    // 按设备行程做尺寸自适应：超出时等比缩小后重算
    const fit = fitSizeToTravel(res.widthMm, res.heightMm, { mode: 'Fit' })
    if (fit.adjusted) {
      warn.value = fit.message ?? ''
      res = await runConvert(fit.widthMm, opts.autoHeight ? undefined : fit.heightMm)
    }

    const chk = checkGcodeWithinTravel(res.lines)
    if (!chk.ok && chk.message) warn.value = warn.value ? `${warn.value}；${chk.message}` : chk.message

    info.value = res
    decisionText.value = res.decision ? res.decision.summary : ''
    rasterInfo.value = res.rasterized ? `${res.rasterWidth} × ${res.rasterHeight} px，二值化 ${vec.threshold}%` : ''
    rasterPreviewUrl.value = res.preview ? toDataURL(res.preview) : ''
    const name = `${svgName.value.replace(/\.svg$/i, '') || 'vector'}.gcode`
    generated.value = loadGcodeLines(name, res.lines)
    toast(`已生成 ${res.pathCount} 条路径 / ${res.lines.length} 行`, 'success')
  } catch (e) {
    toast(`生成失败：${String(e)}`, 'error')
  } finally {
    busy.value = false
  }
}

function save() {
  if (!generated.value) return
  saveGcodeFile(generated.value.name, generated.value.commands.map((c) => c.command), {
    kind: 'svg',
    widthMm: info.value?.widthMm,
    heightMm: info.value?.heightMm
  })
  toast('已保存到雕刻文件', 'success')
}

function goHome() {
  router.push('/home')
}

function onNum(
  key: 'widthMm' | 'heightMm' | 'tolerance' | 'markSpeed' | 'travelSpeed' | 'maxPower' | 'offsetX' | 'offsetY',
  ev: Event
) {
  const t = ev.target as HTMLInputElement
  const v = parseFloat(t.value)
  if (Number.isFinite(v)) opts[key] = v
}

/** 描线参数（原生 FocusEvent → ev.target.value，契约与 GlassInput 一致） */
function onVecNum(key: 'threshold' | 'minBranchPx' | 'simplifyTolerance', ev: Event) {
  const t = ev.target as HTMLInputElement
  const v = parseFloat(t.value)
  if (Number.isFinite(v)) vec[key] = v
}
</script>

<template>
  <div class="lg-page">
    <div class="lg-body">
      <!-- 导入 -->
      <GlassSurface class="lg-section">
        <div class="lg-title"><span>SVG 文件</span></div>
        <div v-if="!svgName" class="upload-box" @click="pick">
          <AppIcon name="vector" :size="30" />
          <div class="lg-mt">点击选择 SVG 文件</div>
          <div class="lg-dim">支持 path、rect、circle、ellipse、line、polygon 等图形</div>
        </div>
        <div v-else class="file-row">
          <AppIcon name="file" :size="22" />
          <span class="file-row__name">{{ svgName }}</span>
          <GlassButton size="small" text type="primary" @click="pick">更换</GlassButton>
        </div>
      </GlassSurface>

      <!-- 尺寸 -->
      <GlassSurface class="lg-section">
        <div class="lg-title"><span>尺寸与精度</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">目标宽度 (mm)</span>
            <GlassInput :model-value="String(opts.widthMm)" type="number" variant="outlined" @blur="onNum('widthMm', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">目标高度 (mm)</span>
            <GlassInput
              :model-value="opts.autoHeight ? '' : String(opts.heightMm)"
              type="number"
              variant="outlined"
              :disabled="opts.autoHeight"
              placeholder="自动"
              @blur="onNum('heightMm', $event)"
            />
          </div>
        </div>
        <GlassCell title="高度按比例自动" description="保持原始宽高比">
          <template #extra>
            <GlassSwitch v-model="opts.autoHeight" />
          </template>
        </GlassCell>

        <div class="num-item lg-mt">
          <span class="lg-dim">曲线精度 (mm，越小越平滑)</span>
          <GlassInput :model-value="String(opts.tolerance)" type="number" variant="outlined" @blur="onNum('tolerance', $event)" />
        </div>
      </GlassSurface>

      <!-- 走线方式 -->
      <GlassSurface class="lg-section">
        <div class="lg-title"><span>走线方式</span></div>
        <div class="steps">
          <GlassButton
            v-for="m in MODE_OPTIONS"
            :key="m"
            size="small"
            :type="mode === m ? 'primary' : 'default'"
            :plain="mode !== m"
            @click="setMode(m)"
          >
            {{ SVG_MODE_LABELS[m] }}
          </GlassButton>
        </div>
        <div class="lg-dim lg-mt">{{ MODE_HINTS[mode] }}</div>
      </GlassSurface>

      <!-- 描线参数（中心线 / 智能） -->
      <GlassSurface v-if="mode !== 'Outline'" class="lg-section">
        <div class="lg-title"><span>{{ mode === 'Auto' ? '描线参数（智能判定为线稿时生效）' : '中心线参数' }}</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">二值化阈值 (%)</span>
            <GlassInput :model-value="String(vec.threshold)" type="number" variant="outlined" @blur="onVecNum('threshold', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">去毛刺长度 (像素)</span>
            <GlassInput :model-value="String(vec.minBranchPx)" type="number" variant="outlined" @blur="onVecNum('minBranchPx', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">简化容差 (像素)</span>
            <GlassInput :model-value="String(vec.simplifyTolerance)" type="number" variant="outlined" @blur="onVecNum('simplifyTolerance', $event)" />
          </div>
        </div>
        <div class="lg-dim lg-mt">阈值：光栅化后按灰度二值化的分界（50% ≈ 128/255），线条偏灰时可调高。</div>
        <div class="lg-dim">去毛刺：丢弃短于该像素长度的骨架分支，消除边缘毛刺，越大越干净。</div>
        <div class="lg-dim">简化容差：Douglas-Peucker 容差（像素），越大节点越少、线条越硬。</div>

        <div class="slider-row lg-mt">
          <span class="lg-dim">光栅化分辨率（长边像素）</span>
          <span class="lg-mono">{{ vec.rasterPixels }}</span>
        </div>
        <GlassSlider v-model="vec.rasterPixels" :min="256" :max="2048" :step="64" />
        <div class="lg-dim">先把 SVG 画成位图再取骨架：太小细线容易断裂，太大生成较慢（默认 1024）。</div>

        <GlassCell title="反相" description="深底浅色线条时启用">
          <template #extra>
            <GlassSwitch v-model="vec.invert" />
          </template>
        </GlassCell>
      </GlassSurface>

      <!-- 智能判定 -->
      <GlassSurface v-if="mode === 'Auto'" class="lg-section">
        <div class="lg-title"><span>智能判定</span></div>
        <div class="slider-row">
          <span class="lg-dim">笔画宽度阈值（占图像短边 %）</span>
          <span class="lg-mono">{{ vec.strokeWidthPct.toFixed(1) }}%</span>
        </div>
        <GlassSlider v-model="vec.strokeWidthPct" :min="0.5" :max="10" :step="0.1" />
        <div class="lg-dim lg-mt">
          平均笔画宽度 ≈ 墨水面积 / 骨架长度，再除以图像短边得到百分比：小于等于该阈值判为线稿 →
          中心线描线；大于则判为实心 / 粗笔画图案 → 轮廓提取。
        </div>
        <div class="lg-dim">兜底：骨架为空或退化、墨水覆盖率超过 50% 时，一律回退轮廓提取。</div>
      </GlassSurface>

      <!-- 参数 -->
      <GlassSurface class="lg-section">
        <div class="lg-title"><span>雕刻参数</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">雕刻速度 (mm/min)</span>
            <GlassInput :model-value="String(opts.markSpeed)" type="number" variant="outlined" @blur="onNum('markSpeed', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">空移速度 (0=快速)</span>
            <GlassInput :model-value="String(opts.travelSpeed)" type="number" variant="outlined" @blur="onNum('travelSpeed', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">激光功率 S</span>
            <GlassInput :model-value="String(opts.maxPower)" type="number" variant="outlined" @blur="onNum('maxPower', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">起点 X (mm)</span>
            <GlassInput :model-value="String(opts.offsetX)" type="number" variant="outlined" @blur="onNum('offsetX', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">起点 Y (mm)</span>
            <GlassInput :model-value="String(opts.offsetY)" type="number" variant="outlined" @blur="onNum('offsetY', $event)" />
          </div>
        </div>
        <GlassCell title="硬件 PWM" description="使用 S 值控制激光功率">
          <template #extra>
            <GlassSwitch v-model="opts.pwm" />
          </template>
        </GlassCell>
      </GlassSurface>

      <GlassAlert v-if="warn" type="warning" :title="warn" class="lg-mb" />

      <GlassButton block type="primary" :loading="busy" :disabled="!canGenerate" @click="generate">
        <AppIcon name="layers" :size="17" />
        <span class="btn-text">生成雕刻路径</span>
      </GlassButton>

      <!-- 预览 -->
      <GlassSurface v-if="generated" class="lg-section lg-mt">
        <div class="lg-title">
          <span>路径预览</span>
          <span class="lg-dim">
            {{ info?.pathCount }} 条 · {{ (info?.pathLengthMm ?? 0).toFixed(1) }} mm
          </span>
        </div>

        <!-- 智能识别结果：让用户看到自动选了什么、为什么 -->
        <GlassAlert v-if="decisionText" class="lg-mb" type="info" :title="decisionText" />

        <template v-if="rasterPreviewUrl">
          <div class="lg-title"><span>光栅化预览</span><span class="lg-dim">{{ rasterInfo }}</span></div>
          <div class="processed lg-mb">
            <img :src="rasterPreviewUrl" alt="光栅化预览" />
          </div>
        </template>

        <GcodePreview :preview="generated.preview" :bbox="generated.stats.bbox" :height="220" />
        <div class="lg-grid-2 lg-mt">
          <GlassButton block plain @click="save">
            <AppIcon name="save" :size="17" />
            <span class="btn-text">保存文件</span>
          </GlassButton>
          <GlassButton block type="primary" @click="goHome">
            <AppIcon name="play" :size="17" />
            <span class="btn-text">去雕刻</span>
          </GlassButton>
        </div>
      </GlassSurface>
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

.file-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  background: var(--lg-panel-2);
  border-radius: 10px;
  color: var(--lg-accent);
}

.file-row__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--lg-text);
  font-size: 13.5px;
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
}

.slider-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
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
</style>