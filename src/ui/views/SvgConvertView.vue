<script setup lang="ts">
/** SVG 转雕刻：矢量轮廓 */
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import GcodePreview from '../components/GcodePreview.vue'
import { convertSvgToGcode, type SvgConvertResult } from '../../core/vector/SvgToGcode'
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

async function pick() {
  const file = await pickFile('.svg,image/svg+xml')
  if (!file) return
  if (!/svg/i.test(file.name) && !/svg/i.test(file.dataUrl.slice(0, 40)) && !file.text.includes('<svg')) {
    toast('请选择 SVG 文件', 'error')
    return
  }
  svgName.value = file.name
  svgText.value = file.text
  generated.value = null
  info.value = null
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

function generate() {
  if (!canGenerate.value) {
    toast('请先导入 SVG 文件', 'error')
    return
  }
  busy.value = true
  warn.value = ''
  try {
    let res = convertSvgToGcode(svgText.value, buildOptions(opts.widthMm, opts.autoHeight ? undefined : opts.heightMm))

    // 按设备行程做尺寸自适应：超出时等比缩小后重算
    const fit = fitSizeToTravel(res.widthMm, res.heightMm, { mode: 'Fit' })
    if (fit.adjusted) {
      warn.value = fit.message ?? ''
      res = convertSvgToGcode(svgText.value, buildOptions(fit.widthMm, opts.autoHeight ? undefined : fit.heightMm))
    }

    const chk = checkGcodeWithinTravel(res.lines)
    if (!chk.ok && chk.message) warn.value = warn.value ? `${warn.value}；${chk.message}` : chk.message

    info.value = res
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
</script>

<template>
  <div class="lg-page">
    <div class="lg-body">
      <!-- 导入 -->
      <section class="lg-section">
        <div class="lg-title"><span>SVG 文件</span></div>
        <div v-if="!svgName" class="upload-box" @click="pick">
          <AppIcon name="vector" :size="30" />
          <div class="lg-mt">点击选择 SVG 文件</div>
          <div class="lg-dim">支持 path、rect、circle、ellipse、line、polygon 等图形</div>
        </div>
        <div v-else class="file-row">
          <AppIcon name="file" :size="22" />
          <span class="file-row__name">{{ svgName }}</span>
          <var-button size="small" text type="primary" @click="pick">更换</var-button>
        </div>
      </section>

      <!-- 尺寸 -->
      <section class="lg-section">
        <div class="lg-title"><span>尺寸与精度</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">目标宽度 (mm)</span>
            <var-input :model-value="String(opts.widthMm)" type="number" variant="outlined" @blur="onNum('widthMm', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">目标高度 (mm)</span>
            <var-input
              :model-value="opts.autoHeight ? '' : String(opts.heightMm)"
              type="number"
              variant="outlined"
              :disabled="opts.autoHeight"
              placeholder="自动"
              @blur="onNum('heightMm', $event)"
            />
          </div>
        </div>
        <var-cell title="高度按比例自动" description="保持原始宽高比">
          <template #extra>
            <var-switch v-model="opts.autoHeight" />
          </template>
        </var-cell>

        <div class="num-item lg-mt">
          <span class="lg-dim">曲线精度 (mm，越小越平滑)</span>
          <var-input :model-value="String(opts.tolerance)" type="number" variant="outlined" @blur="onNum('tolerance', $event)" />
        </div>
      </section>

      <!-- 参数 -->
      <section class="lg-section">
        <div class="lg-title"><span>雕刻参数</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">雕刻速度 (mm/min)</span>
            <var-input :model-value="String(opts.markSpeed)" type="number" variant="outlined" @blur="onNum('markSpeed', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">空移速度 (0=快速)</span>
            <var-input :model-value="String(opts.travelSpeed)" type="number" variant="outlined" @blur="onNum('travelSpeed', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">激光功率 S</span>
            <var-input :model-value="String(opts.maxPower)" type="number" variant="outlined" @blur="onNum('maxPower', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">起点 X (mm)</span>
            <var-input :model-value="String(opts.offsetX)" type="number" variant="outlined" @blur="onNum('offsetX', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">起点 Y (mm)</span>
            <var-input :model-value="String(opts.offsetY)" type="number" variant="outlined" @blur="onNum('offsetY', $event)" />
          </div>
        </div>
        <var-cell title="硬件 PWM" description="使用 S 值控制激光功率">
          <template #extra>
            <var-switch v-model="opts.pwm" />
          </template>
        </var-cell>
      </section>

      <var-alert v-if="warn" type="warning" :title="warn" class="lg-mb" />

      <var-button block type="primary" :loading="busy" :disabled="!canGenerate" @click="generate">
        <AppIcon name="layers" :size="17" />
        <span class="btn-text">生成雕刻路径</span>
      </var-button>

      <!-- 预览 -->
      <section v-if="generated" class="lg-section lg-mt">
        <div class="lg-title">
          <span>路径预览</span>
          <span class="lg-dim">
            {{ info?.pathCount }} 条 · {{ (info?.pathLengthMm ?? 0).toFixed(1) }} mm
          </span>
        </div>
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

.btn-text {
  margin-left: 5px;
}
</style>