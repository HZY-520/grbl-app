<script setup lang="ts">
/**
 * 设备初始化向导
 *
 * 首次连接新设备（或从设置页手动打开）时，引导用户设置并保存设备参数：
 * 行程距离、激光功率上下限、雕刻速度等。保存后写入设备档案并同步到应用设置，
 * 之后所有转换流程生成 G 代码时都会按该行程做尺寸自适应。
 */
import { computed, reactive, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import { AppSettings } from '../../core/grbl/GrblConfig'
import { Firmware } from '../../core/grbl/types'
import {
  applyProfileToSettings,
  builtinProfiles,
  findProfileByDevice,
  markDeviceKnown,
  newProfile,
  saveProfile,
  setActiveProfile,
  setSetupDone,
  type DeviceProfile
} from '../../core/grbl/DeviceProfile'
import { clearNeedsSetup, laserTest, readMachineLimits, state } from '../store'
import { toast } from '../utils'

const props = withDefaults(defineProps<{ deviceId?: number }>(), { deviceId: -1 })
const emit = defineEmits<{ done: [] }>()

const STEPS = ['基本信息', '行程范围', '激光与测试', '雕刻参数'] as const

const step = ref(0)
const busy = ref(false)

/** 预填：优先已绑定档案，其次应用当前设置 */
function initial(): DeviceProfile {
  const bound = props.deviceId >= 0 ? findProfileByDevice(props.deviceId) : null
  if (bound) return bound
  return newProfile({
    name: props.deviceId >= 0 ? `雕刻机 #${props.deviceId}` : '我的雕刻机',
    firmware: AppSettings.get<string>('Firmware Type', 'Grbl'),
    baud: AppSettings.get<number>('Last Baud', 115200),
    travelX: AppSettings.get<number>('Travel X', 300),
    travelY: AppSettings.get<number>('Travel Y', 200),
    maxPower: AppSettings.get<number>('Max Power', 1000),
    minPower: AppSettings.get<number>('Min Power', 0),
    markSpeed: AppSettings.get<number>('Mark Speed', 1000),
    travelSpeed: AppSettings.get<number>('Jog Speed', 3000),
    quality: 3
  })
}

const base = initial()

const form = reactive({
  name: base.name,
  firmware: base.firmware,
  baud: base.baud,
  travelX: base.travelX,
  travelY: base.travelY,
  maxPower: base.maxPower,
  minPower: base.minPower,
  markSpeed: base.markSpeed,
  travelSpeed: base.travelSpeed,
  quality: base.quality
})

const testPower = ref(AppSettings.get<number>('Test Laser Power', Math.min(200, form.maxPower)))
const testDuration = ref(AppSettings.get<number>('Test Laser Duration', 300))

function onTestNum(key: 'power' | 'duration', ev: Event) {
  const t = ev.target as HTMLInputElement
  const v = parseFloat(t.value)
  if (!Number.isFinite(v)) return
  if (key === 'power') testPower.value = v
  else testDuration.value = v
}

const FIRMWARES = [
  { value: Firmware.Grbl, label: 'GRBL' },
  { value: Firmware.Smoothie, label: 'Smoothieware' },
  { value: Firmware.Marlin, label: 'Marlin' },
  { value: Firmware.Vigo, label: 'Vigo' }
]

const BAUD_RATES = [9600, 19200, 38400, 57600, 115200, 230400, 250000]

const presets = computed(() => builtinProfiles())

const stepTitle = computed(() => STEPS[step.value])
const isLast = computed(() => step.value === STEPS.length - 1)

const summary = computed(() => [
  { k: '设备名称', v: form.name },
  { k: '固件 / 波特率', v: `${form.firmware} · ${form.baud}` },
  { k: '行程 X × Y', v: `${form.travelX} × ${form.travelY} mm` },
  { k: '功率范围 S', v: `${form.minPower} ~ ${form.maxPower}` },
  { k: '雕刻速度', v: `${form.markSpeed} mm/min` },
  { k: '空移速度', v: `${form.travelSpeed} mm/min` }
])

function onNum(
  key: 'travelX' | 'travelY' | 'maxPower' | 'minPower' | 'markSpeed' | 'travelSpeed' | 'quality' | 'baud',
  ev: Event
) {
  const t = ev.target as HTMLInputElement
  const v = parseFloat(t.value)
  if (Number.isFinite(v)) form[key] = v
}

function applyPreset(p: DeviceProfile) {
  form.name = p.name
  form.firmware = p.firmware
  form.travelX = p.travelX
  form.travelY = p.travelY
  form.maxPower = p.maxPower
  form.minPower = p.minPower
  form.markSpeed = p.markSpeed
  form.travelSpeed = p.travelSpeed
  form.quality = p.quality
  form.baud = p.baud
  toast(`已套用预设「${p.name}」`, 'success')
}

/** 从已连接设备读取 $130/$131/$30/$31 预填 */
function pullFromMachine() {
  if (!state.connected) {
    toast('请先连接设备', 'warning')
    return
  }
  const m = readMachineLimits()
  let hit = 0
  if (m.travelX !== undefined) {
    form.travelX = Math.round(m.travelX)
    hit++
  }
  if (m.travelY !== undefined) {
    form.travelY = Math.round(m.travelY)
    hit++
  }
  if (m.maxPower !== undefined) {
    form.maxPower = Math.round(m.maxPower)
    hit++
  }
  if (m.minPower !== undefined) {
    form.minPower = Math.round(m.minPower)
    hit++
  }
  toast(hit > 0 ? `已从设备读取 ${hit} 项参数` : '未读取到机器参数，请先在“机器参数”页读取 $$', hit > 0 ? 'success' : 'warning')
}

function onTestLaser() {
  const ok = laserTest(testPower.value, testDuration.value)
  if (ok) {
    AppSettings.set('Test Laser Power', testPower.value)
    AppSettings.set('Test Laser Duration', testDuration.value)
  }
}

function next() {
  if (step.value < STEPS.length - 1) step.value++
}

function prev() {
  if (step.value > 0) step.value--
}

function skip() {
  // 标记完成，避免每次连接都弹出；但不再重复写入参数
  if (props.deviceId >= 0) markDeviceKnown(props.deviceId)
  setSetupDone(true)
  clearNeedsSetup()
  toast('已跳过初始化，可稍后在设置中重新打开', 'info')
  emit('done')
}

function finish() {
  if (!Number.isFinite(form.travelX) || form.travelX <= 0 || !Number.isFinite(form.travelY) || form.travelY <= 0) {
    toast('行程必须为大于 0 的数值', 'error')
    return
  }
  busy.value = true
  try {
    const p = newProfile({
      id: base.id,
      name: form.name || '我的雕刻机',
      firmware: form.firmware,
      baud: form.baud,
      travelX: form.travelX,
      travelY: form.travelY,
      maxPower: form.maxPower,
      minPower: form.minPower,
      markSpeed: form.markSpeed,
      travelSpeed: form.travelSpeed,
      quality: form.quality,
      active: true,
      deviceId: props.deviceId >= 0 ? props.deviceId : base.deviceId
    })
    saveProfile(p)
    setActiveProfile(p.id)
    applyProfileToSettings(p)
    AppSettings.set('Test Laser Power', testPower.value)
    AppSettings.set('Test Laser Duration', testDuration.value)
    if (p.deviceId !== undefined) markDeviceKnown(p.deviceId)
    setSetupDone(true)
    clearNeedsSetup()
    toast(`设备「${p.name}」参数已保存`, 'success')
    emit('done')
  } catch (e) {
    toast(`保存失败：${String(e)}`, 'error')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="wiz">
    <!-- 顶部 -->
    <header class="wiz__head">
      <div class="wiz__head-row">
        <AppIcon name="settings" :size="20" />
        <span class="wiz__title">设备初始化向导</span>
        <GlassButton size="small" text @click="skip">跳过</GlassButton>
      </div>
      <div class="wiz__steps">
        <span v-for="(s, i) in STEPS" :key="s" class="wiz__step" :class="{ 'is-active': i === step, 'is-done': i < step }">
          {{ i + 1 }}
        </span>
      </div>
      <div class="wiz__meta">
        <span>{{ step + 1 }} / {{ STEPS.length }} · {{ stepTitle }}</span>
        <span v-if="props.deviceId >= 0" class="lg-dim">设备 #{{ props.deviceId }}</span>
      </div>
    </header>

    <!-- 内容 -->
    <div class="wiz__body">
      <!-- 1. 基本信息 -->
      <GlassSurface v-if="step === 0" class="lg-section">
        <div class="lg-title"><span>设备名称</span></div>
        <GlassInput v-model="form.name" variant="outlined" placeholder="给设备起个名字" />

        <div class="lg-deco lg-mt">
          <div class="lg-dim">固件类型</div>
          <GlassSelect class="lg-mt" v-model="form.firmware" variant="outlined">
            <GlassOption v-for="f in FIRMWARES" :key="f.value" :label="f.label" :value="f.value" />
          </GlassSelect>
        </div>

        <div class="lg-deco lg-mt">
          <div class="lg-dim">波特率</div>
          <GlassSelect class="lg-mt" v-model="form.baud" variant="outlined">
            <GlassOption v-for="b in BAUD_RATES" :key="b" :label="`${b} bps`" :value="b" />
          </GlassSelect>
        </div>
      </GlassSurface>

      <!-- 2. 行程 -->
      <GlassSurface v-else-if="step === 1" class="lg-section">
        <div class="lg-title"><span>行程范围（工作区域）</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">X 行程 (mm)</span>
            <GlassInput :model-value="String(form.travelX)" type="number" variant="outlined" @blur="onNum('travelX', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">Y 行程 (mm)</span>
            <GlassInput :model-value="String(form.travelY)" type="number" variant="outlined" @blur="onNum('travelY', $event)" />
          </div>
        </div>
        <GlassButton class="lg-mt" block plain :disabled="!state.connected" @click="pullFromMachine">
          <AppIcon name="download" :size="16" />
          <span class="btn-text">从设备读取 ($130/$131)</span>
        </GlassButton>
        <div class="lg-dim lg-mt">
          生成 G 代码时会以此行程做尺寸自适应：超出时等比缩放，避免雕刻跑出机器范围。
        </div>

        <div class="lg-title lg-mt"><span>常用机型预设</span></div>
        <div class="preset-list">
          <GlassButton
            v-for="p in presets"
            :key="p.id"
            class="preset"
            size="small"
            plain
            block
            @click="applyPreset(p)"
          >
            {{ p.name }}
          </GlassButton>
        </div>
      </GlassSurface>

      <!-- 3. 激光与测试 -->
      <GlassSurface v-else-if="step === 2" class="lg-section">
        <div class="lg-title"><span>激光功率范围（S 值）</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">最小功率 S-MIN</span>
            <GlassInput :model-value="String(form.minPower)" type="number" variant="outlined" @blur="onNum('minPower', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">最大功率 S-MAX</span>
            <GlassInput :model-value="String(form.maxPower)" type="number" variant="outlined" @blur="onNum('maxPower', $event)" />
          </div>
        </div>
        <GlassButton class="lg-mt" block plain :disabled="!state.connected" @click="pullFromMachine">
          <AppIcon name="download" :size="16" />
          <span class="btn-text">从设备读取 ($30/$31)</span>
        </GlassButton>

        <div class="lg-title lg-mt"><span>测试激光</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">测试功率 S</span>
            <GlassInput :model-value="String(testPower)" type="number" variant="outlined" @blur="onTestNum('power', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">持续时间 (ms)</span>
            <GlassInput :model-value="String(testDuration)" type="number" variant="outlined" @blur="onTestNum('duration', $event)" />
          </div>
        </div>
        <GlassButton class="lg-mt" block type="warning" :disabled="!state.connected" @click="onTestLaser">
          <AppIcon name="flame" :size="17" />
          <span class="btn-text">点亮激光测试</span>
        </GlassButton>
        <GlassAlert class="lg-mt" type="warning" title="测试激光会短暂出光，请佩戴护目镜并确认光路安全。" />
      </GlassSurface>

      <!-- 4. 雕刻参数 -->
      <GlassSurface v-else class="lg-section">
        <div class="lg-title"><span>默认雕刻参数</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">雕刻速度 (mm/min)</span>
            <GlassInput :model-value="String(form.markSpeed)" type="number" variant="outlined" @blur="onNum('markSpeed', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">空移速度 (mm/min)</span>
            <GlassInput :model-value="String(form.travelSpeed)" type="number" variant="outlined" @blur="onNum('travelSpeed', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">默认线数 (线/mm)</span>
            <GlassInput :model-value="String(form.quality)" type="number" variant="outlined" @blur="onNum('quality', $event)" />
          </div>
        </div>

        <div class="lg-title lg-mt"><span>确认信息</span></div>
        <div v-for="row in summary" :key="row.k" class="lg-kv">
          <span class="lg-kv__k">{{ row.k }}</span>
          <span class="lg-kv__v">{{ row.v }}</span>
        </div>
      </GlassSurface>
    </div>

    <!-- 底部操作 -->
    <footer class="wiz__foot">
      <GlassButton v-if="step > 0" class="wiz__foot-btn" plain @click="prev">
        <AppIcon name="back" :size="16" />
        <span class="btn-text">上一步</span>
      </GlassButton>
      <GlassButton v-else class="wiz__foot-btn" plain @click="skip">稍后设置</GlassButton>

      <GlassButton v-if="!isLast" class="wiz__foot-btn" type="primary" @click="next">
        <span class="btn-text">下一步</span>
        <AppIcon name="forward" :size="16" />
      </GlassButton>
      <GlassButton v-else class="wiz__foot-btn" type="primary" :loading="busy" @click="finish">
        <AppIcon name="check" :size="16" />
        <span class="btn-text">完成并保存</span>
      </GlassButton>
    </footer>
  </div>
</template>

<style scoped>
.wiz {
  position: fixed;
  inset: 0;
  z-index: 400;
  display: flex;
  flex-direction: column;
  /* 半透明遮罩 + 背景模糊：让底层极光透出来，
     向导内部的玻璃卡片才有内容可折射（否则玻璃会退化成纯色块）。 */
  background: var(--g-scrim);
  backdrop-filter: blur(22px) saturate(160%);
  -webkit-backdrop-filter: blur(22px) saturate(160%);
  padding-top: env(safe-area-inset-top);
}

.wiz__head {
  flex: 0 0 auto;
  padding: 12px 12px 8px;
  border-bottom: 1px solid var(--lg-border);
}

.wiz__head-row {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--lg-accent);
}

.wiz__title {
  flex: 1;
  min-width: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--lg-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wiz__steps {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.wiz__step {
  flex: 1;
  height: 4px;
  border-radius: 999px;
  background: var(--lg-border);
  color: transparent;
  font-size: 0;
}

.wiz__step.is-active {
  background: var(--lg-accent);
}

.wiz__step.is-done {
  background: rgba(255, 122, 24, 0.5);
}

.wiz__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--lg-text-dim);
}

.wiz__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  padding: 10px;
  padding-bottom: calc(76px + env(safe-area-inset-bottom));
}

.wiz__body .lg-section {
  margin-bottom: 0;
}

.wiz__foot {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  gap: 10px;
  padding: 10px 12px calc(10px + env(safe-area-inset-bottom));
  background: var(--g-chrome);
  backdrop-filter: blur(24px) saturate(160%);
  -webkit-backdrop-filter: blur(24px) saturate(160%);
  border-top: 1px solid var(--lg-border);
}

.wiz__foot-btn {
  flex: 1;
  min-width: 0;
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

.preset-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.btn-text {
  margin: 0 4px;
}
</style>