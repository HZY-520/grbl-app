<script setup lang="ts">
/**
 * 文字转雕刻
 *  - Hershey 单线矢量：笔画单线，仅支持 ASCII（英文 / 数字 / 符号）
 *  - 轮廓描线 / 中心线走线：先将文字栅格化（支持中文等任意字符），再矢量化走线
 *  - 智能：纯 ASCII 用 Hershey 单线，含中文等非 ASCII 用中心线（Hershey 无中文图形）
 */
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import GcodePreview from '../components/GcodePreview.vue'
import { textToGcode } from '../../core/text/Hershey'
import {
  convertTextVector,
  renderTextToImage,
  hasNonAscii,
  DEFAULT_TEXT_FONT,
  VECTOR_TOOL_LABELS,
  type VectorTool
} from '../../core/vector/ImageVector'
import { decideTextEngine } from '../../core/vector/SmartVector'
import { toDataURL } from '../../core/raster/ImageTransform'
import { fitSizeToTravel, checkGcodeWithinTravel } from '../../core/grbl/DeviceProfile'
import type { GcodeFileData } from '../../core/gcode/GrblFile'
import { AppSettings } from '../../core/grbl/GrblConfig'
import { loadGcodeLines } from '../store'
import { saveGcodeFile } from '../storage'
import { toast } from '../utils'

const router = useRouter()

/** 渲染引擎（Auto = 智能：按字符集自动选择） */
type Engine = 'Hershey' | VectorTool | 'Auto'
const ENGINE_LABELS: Record<Engine, string> = {
  Hershey: 'Hershey 单线',
  Outline: VECTOR_TOOL_LABELS.Outline,
  Centerline: VECTOR_TOOL_LABELS.Centerline,
  Auto: '智能'
}
const ENGINES: Engine[] = ['Hershey', 'Outline', 'Centerline', 'Auto']

const text = ref('激光雕刻')
const engine = ref<Engine>('Outline')
const generated = ref<GcodeFileData | null>(null)
const previewUrl = ref('')
const extraInfo = ref('')
const warn = ref('')
const busy = ref(false)

const opts = reactive({
  sizeMm: 20,
  orientation: 'horizontal' as 'horizontal' | 'vertical',
  bold: false,
  lineSpacing: 1.5,
  offsetX: 0,
  offsetY: 0,
  markSpeed: AppSettings.get<number>('Mark Speed', 1000),
  maxPower: AppSettings.get<number>('Max Power', 1000),
  minPower: AppSettings.get<number>('Min Power', 0),
  pwm: AppSettings.get<boolean>('Support Hardware PWM', true),
  laserOn: AppSettings.get<string>('Laser On Command', 'M4'),
  laserOff: AppSettings.get<string>('Laser Off Command', 'M5'),
  header: AppSettings.get<string>('Header', 'G90'),
  footer: AppSettings.get<string>('Footer', 'M5\nG0 X0 Y0'),
  // 矢量（位图）专用
  fontFamily: DEFAULT_TEXT_FONT,
  threshold: 50,
  invert: false,
  optimize: true,
  turdSize: 2,
  minBranchPx: 6,
  simplifyTolerance: 1.2
})

const FONT_PRESETS = [
  { value: DEFAULT_TEXT_FONT, label: '系统默认（支持中文）' },
  { value: '"Noto Sans CJK SC", "Source Han Sans SC", sans-serif', label: '思源黑体 / Noto Sans CJK' },
  { value: '"Noto Serif CJK SC", "Source Han Serif SC", serif', label: '思源宋体 / Noto Serif CJK' },
  { value: '"Microsoft YaHei", "PingFang SC", sans-serif', label: '微软雅黑 / 苹方' }
]

/** 智能模式的实时判定（纯 ASCII → Hershey；含中文等非 ASCII → 中心线） */
const autoDecision = computed(() => decideTextEngine(text.value))

/** 实际使用的引擎：智能模式下取自动判定结果 */
const effectiveEngine = computed<Engine>(() =>
  engine.value === 'Auto' ? autoDecision.value.engine : engine.value
)

const isVector = computed(() => effectiveEngine.value !== 'Hershey')

/** Hershey 单线字体无法渲染非 ASCII（中文）字符 */
const cjkBlocked = computed(() => engine.value === 'Hershey' && hasNonAscii(text.value))

const canGenerate = computed(() => text.value.trim().length > 0 && opts.sizeMm > 0 && !cjkBlocked.value)

function generate() {
  if (!canGenerate.value) {
    if (cjkBlocked.value) toast('Hershey 单线字体不支持中文，请切换到轮廓描线或中心线走线', 'error')
    else toast('请输入文字内容', 'error')
    return
  }
  busy.value = true
  warn.value = ''
  try {
    const lines = isVector.value ? generateVector() : generateHershey()
    const chk = checkGcodeWithinTravel(lines)
    if (!chk.ok && chk.message) warn.value = chk.message
    const name = `文字-${text.value.trim().split('\n')[0].slice(0, 12) || 'text'}.gcode`
    generated.value = loadGcodeLines(name, lines)
    toast(`已生成 ${lines.length} 行 G 代码`, 'success')
  } catch (e) {
    toast(`生成失败：${String(e)}`, 'error')
  } finally {
    busy.value = false
  }
}

/** Hershey 单线矢量（ASCII） */
function generateHershey(): string[] {
  previewUrl.value = ''
  extraInfo.value = ''
  const res = textToGcode({
    text: text.value,
    orientation: opts.orientation,
    sizeMm: opts.sizeMm,
    bold: opts.bold,
    lineSpacing: opts.lineSpacing,
    offsetX: opts.offsetX,
    offsetY: opts.offsetY,
    markSpeed: opts.markSpeed,
    laserOn: opts.laserOn,
    laserOff: opts.laserOff,
    pwm: opts.pwm,
    maxPower: opts.maxPower
  })
  return res.lines
}

/** 位图矢量化（支持中文） */
function generateVector(): string[] {
  // 先测量文字尺寸，再按设备行程等比适配
  const measure = renderTextToImage({
    text: text.value,
    sizeMm: opts.sizeMm,
    bold: opts.bold,
    orientation: opts.orientation,
    lineSpacing: opts.lineSpacing,
    fontFamily: opts.fontFamily
  })
  if (measure.widthMm <= 0 || measure.heightMm <= 0) {
    toast('文字为空，无法生成', 'error')
    return []
  }
  const fit = fitSizeToTravel(measure.widthMm, measure.heightMm, { mode: 'Fit' })
  if (fit.message) warn.value = fit.message
  const scale = measure.widthMm > 0 ? fit.widthMm / measure.widthMm : 1
  const effectiveSizeMm = Math.max(0.5, Math.round(opts.sizeMm * scale * 100) / 100)

  const res = convertTextVector({
    text: text.value,
    sizeMm: effectiveSizeMm,
    bold: opts.bold,
    orientation: opts.orientation,
    lineSpacing: opts.lineSpacing,
    fontFamily: opts.fontFamily,
    threshold: Math.round((opts.threshold / 100) * 255),
    invert: opts.invert,
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
    optimize: opts.optimize,
    tool: effectiveEngine.value as VectorTool,
    turdSize: opts.turdSize,
    alphaMax: 1,
    optTolerance: 0.2,
    curveOptimizing: true,
    flattenTolerance: 0.2,
    minBranchPx: opts.minBranchPx,
    simplifyTolerance: opts.simplifyTolerance
  })

  previewUrl.value = toDataURL(res.preview)
  extraInfo.value = `尺寸 ${res.widthMm.toFixed(1)} × ${res.heightMm.toFixed(1)} mm · 走线 ${res.pathCount} 段 · 路径长度 ${res.lengthMm.toFixed(1)} mm`
  return res.lines
}

function save() {
  if (!generated.value) return
  saveGcodeFile(generated.value.name, generated.value.commands.map((c) => c.command), {
    kind: 'text',
    widthMm: undefined,
    heightMm: undefined
  })
  toast('已保存到雕刻文件', 'success')
}

function goHome() {
  router.push('/home')
}

function onNum(key: 'sizeMm' | 'lineSpacing' | 'offsetX' | 'offsetY' | 'markSpeed' | 'maxPower' | 'minPower' | 'threshold' | 'turdSize' | 'minBranchPx' | 'simplifyTolerance', ev: Event) {
  const t = ev.target as HTMLInputElement
  const v = parseFloat(t.value)
  if (Number.isFinite(v)) opts[key] = v
}
</script>

<template>
  <div class="lg-page">
    <div class="lg-body">
      <!-- 文字内容 -->
      <GlassSurface class="lg-section">
        <div class="lg-title"><span>文字内容</span></div>
        <GlassInput v-model="text" textarea :rows="3" placeholder="输入要雕刻的文字，支持中文与换行" variant="outlined" />
        <GlassAlert
          v-if="cjkBlocked"
          class="lg-mt"
          type="warning"
          title="当前为 Hershey 单线字体，无法渲染中文，请切换到「轮廓描线」或「中心线走线」。"
        />
      </GlassSurface>

      <!-- 生成方式 -->
      <GlassSurface class="lg-section">
        <div class="lg-title"><span>生成方式</span></div>
        <div class="steps">
          <GlassButton
            v-for="e in ENGINES"
            :key="e"
            size="small"
            :type="engine === e ? 'primary' : 'default'"
            :plain="engine !== e"
            @click="engine = e"
          >
            {{ ENGINE_LABELS[e] }}
          </GlassButton>
        </div>
        <div class="lg-dim lg-mt">
          {{ isVector ? '栅格化后沿轮廓 / 笔画走线，支持中文等任意字符。' : '内置矢量单线字体，仅支持英文、数字与常见符号。' }}
        </div>
        <div v-if="engine === 'Auto'" class="lg-dim lg-mt">{{ autoDecision.reason }}</div>
      </GlassSurface>

      <!-- 排版 -->
      <GlassSurface class="lg-section">
        <div class="lg-title"><span>排版</span></div>

        <div class="lg-dim">字体朝向</div>
        <div class="steps lg-mt">
          <GlassButton
            size="small"
            :type="opts.orientation === 'horizontal' ? 'primary' : 'default'"
            :plain="opts.orientation !== 'horizontal'"
            @click="opts.orientation = 'horizontal'"
          >
            横向排版
          </GlassButton>
          <GlassButton
            size="small"
            :type="opts.orientation === 'vertical' ? 'primary' : 'default'"
            :plain="opts.orientation !== 'vertical'"
            @click="opts.orientation = 'vertical'"
          >
            纵向排版
          </GlassButton>
        </div>

        <div v-if="isVector" class="lg-deco lg-mt">
          <div class="lg-dim">字体</div>
          <GlassSelect class="lg-mt" :model-value="opts.fontFamily" variant="outlined" @change="opts.fontFamily = String($event)">
            <GlassOption v-for="f in FONT_PRESETS" :key="f.value" :label="f.label" :value="f.value" />
          </GlassSelect>
        </div>

        <div class="num-grid lg-mt">
          <div class="num-item">
            <span class="lg-dim">字号 / 字高 (mm)</span>
            <GlassInput :model-value="String(opts.sizeMm)" type="number" variant="outlined" @blur="onNum('sizeMm', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">行距倍率</span>
            <GlassInput :model-value="String(opts.lineSpacing)" type="number" variant="outlined" @blur="onNum('lineSpacing', $event)" />
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

        <GlassCell title="加粗" description="笔画更粗">
          <template #extra>
            <GlassSwitch v-model="opts.bold" />
          </template>
        </GlassCell>
      </GlassSurface>

      <!-- 矢量参数 -->
      <GlassSurface v-if="isVector" class="lg-section">
        <div class="lg-title"><span>{{ effectiveEngine === 'Centerline' ? '中心线参数' : '轮廓描线参数' }}</span></div>
        <div v-if="engine === 'Auto' && effectiveEngine === 'Centerline'" class="lg-dim">
          智能模式判定为含中文等非 ASCII 字符：Hershey 无该字符字形，改用中心线（骨架化）走线。
        </div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">二值化阈值 (%)</span>
            <GlassInput :model-value="String(opts.threshold)" type="number" variant="outlined" @blur="onNum('threshold', $event)" />
          </div>
          <div v-if="effectiveEngine === 'Outline'" class="num-item">
            <span class="lg-dim">去斑面积 (像素)</span>
            <GlassInput :model-value="String(opts.turdSize)" type="number" variant="outlined" @blur="onNum('turdSize', $event)" />
          </div>
          <template v-else>
            <div class="num-item">
              <span class="lg-dim">去毛刺长度 (像素)</span>
              <GlassInput :model-value="String(opts.minBranchPx)" type="number" variant="outlined" @blur="onNum('minBranchPx', $event)" />
            </div>
            <div class="num-item">
              <span class="lg-dim">简化容差 (像素)</span>
              <GlassInput :model-value="String(opts.simplifyTolerance)" type="number" variant="outlined" @blur="onNum('simplifyTolerance', $event)" />
            </div>
          </template>
        </div>
        <GlassCell title="反相" description="深底浅字时启用">
          <template #extra>
            <GlassSwitch v-model="opts.invert" />
          </template>
        </GlassCell>
        <GlassCell title="路径排序优化" description="最近邻排序，缩短空移距离">
          <template #extra>
            <GlassSwitch v-model="opts.optimize" />
          </template>
        </GlassCell>
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
            <span class="lg-dim">激光功率 S</span>
            <GlassInput :model-value="String(opts.maxPower)" type="number" variant="outlined" @blur="onNum('maxPower', $event)" />
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
        <!-- 智能识别结果：让用户看到自动选了什么、为什么 -->
        <GlassAlert v-if="engine === 'Auto'" class="lg-mb" type="info" :title="autoDecision.reason" />

        <div v-if="previewUrl" class="lg-title"><span>栅格化预览</span></div>
        <div v-if="previewUrl" class="processed">
          <img :src="previewUrl" alt="栅格化预览" />
        </div>
        <div v-if="extraInfo" class="lg-dim">{{ extraInfo }}</div>

        <div class="lg-title lg-mt">
          <span>路径预览</span>
          <span class="lg-dim">{{ generated.stats.totalLines }} 行</span>
        </div>
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
  max-height: 240px;
  image-rendering: pixelated;
  border-radius: 6px;
}
</style>