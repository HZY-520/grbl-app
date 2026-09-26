<script setup lang="ts">
/** 运动控制：点动、绝对移动、倍率调节与坐标操作 */
import { computed, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import {
  state,
  jog,
  moveTo,
  setJogParams,
  setTargetOverride,
  setNewZero,
  unlock,
  homing,
  laserTest,
  sendCommand,
  MacStatus,
  JogDirection
} from '../store'
import { AppSettings } from '../../core/grbl/GrblConfig'
import { fmt, toast } from '../utils'

const STEPS = [0.1, 1, 5, 10, 50]

const targetX = ref('0')
const targetY = ref('0')

/** 测试激光参数（记忆上次使用值） */
const testPower = ref(AppSettings.get<number>('Test Laser Power', 200))
const testDuration = ref(AppSettings.get<number>('Test Laser Duration', 300))

const step = computed(() => state.jog.step)
const speed = computed(() => state.jog.speed)

/** 9 宫格方向定义（左上到右下） */
const PAD: { dir?: JogDirection; label: string; center?: boolean }[] = [
  { dir: JogDirection.NW, label: '↖' },
  { dir: JogDirection.N, label: '↑' },
  { dir: JogDirection.NE, label: '↗' },
  { dir: JogDirection.W, label: '←' },
  { label: '⌂', center: true },
  { dir: JogDirection.E, label: '→' },
  { dir: JogDirection.SW, label: '↙' },
  { dir: JogDirection.S, label: '↓' },
  { dir: JogDirection.SE, label: '↘' }
]

const disabled = computed(() => !state.connected)

function onPad(item: { dir?: JogDirection; center?: boolean }) {
  if (disabled.value) {
    toast('未连接设备', 'warning')
    return
  }
  if (item.center) {
    homing()
    return
  }
  if (item.dir) jog(item.dir)
}

function onStep(s: number) {
  setJogParams(s, undefined)
}

function onSpeed(v: number | number[]) {
  setJogParams(undefined, Array.isArray(v) ? v[0] : v)
}

function onGoto() {
  if (disabled.value) {
    toast('未连接设备', 'warning')
    return
  }
  const x = parseFloat(targetX.value)
  const y = parseFloat(targetY.value)
  if (!Number.isFinite(x) || !Number.isFinite(y)) {
    toast('请输入有效的坐标', 'error')
    return
  }
  moveTo(x, y)
  toast(`移动到 X${x} Y${y}`, 'info')
}

function onZero() {
  if (disabled.value) return
  setNewZero()
  toast('已设置当前点为工作零点', 'success')
}

function onUnlock() {
  if (disabled.value) return
  unlock()
  toast('已发送解锁指令 $X', 'info')
}

/** 测试激光：短暂出光，用于对焦 / 功率检查 */
function onTestLaser() {
  const p = Number(testPower.value)
  const d = Number(testDuration.value)
  if (!Number.isFinite(p) || !Number.isFinite(d)) {
    toast('请输入有效的功率与时长', 'error')
    return
  }
  if (laserTest(p, d)) {
    AppSettings.set('Test Laser Power', p)
    AppSettings.set('Test Laser Duration', d)
    toast(`测试激光 S${Math.round(p)} · ${Math.round(d)}ms`, 'success')
  }
}

/** 立即关闭激光 */
function onLaserOff() {
  if (disabled.value) {
    toast('未连接设备', 'warning')
    return
  }
  sendCommand(AppSettings.get<string>('Laser Off Command', 'M5'))
  toast('已发送关闭激光指令', 'info')
}

function onTestNum(key: 'power' | 'duration', ev: Event) {
  const t = ev.target as HTMLInputElement
  const v = parseFloat(t.value)
  if (!Number.isFinite(v)) return
  if (key === 'power') testPower.value = v
  else testDuration.value = v
}

function feedOverride(v: number | number[]) {
  setTargetOverride('feed', Array.isArray(v) ? v[0] : v)
}
function powerOverride(v: number | number[]) {
  setTargetOverride('power', Array.isArray(v) ? v[0] : v)
}
function rapidOverride(v: number | number[]) {
  setTargetOverride('rapids', Array.isArray(v) ? v[0] : v)
}
</script>

<template>
  <div class="lg-page">
    <div class="lg-body lg-body--nav">
      <div v-if="!state.connected" class="lg-section lg-center">
        <span class="lg-badge lg-badge--idle"><span class="lg-dot" />未连接设备</span>
        <div class="lg-dim lg-mt">请先连接雕刻机后再进行运动控制。</div>
      </div>

      <!-- 点动 -->
      <section class="lg-section">
        <div class="lg-title">
          <span>点动控制</span>
          <span class="lg-dim">步长 {{ step }} mm · 速度 {{ speed }}</span>
        </div>
        <div class="lg-pad">
          <var-button
            v-for="(item, i) in PAD"
            :key="i"
            :type="item.center ? 'primary' : 'default'"
            :disabled="disabled"
            @click="onPad(item)"
          >
            {{ item.label }}
          </var-button>
        </div>
      </section>

      <!-- 步长与速度 -->
      <section class="lg-section">
        <div class="lg-title"><span>步长 (mm)</span></div>
        <div class="steps">
          <var-button
            v-for="s in STEPS"
            :key="s"
            size="small"
            :type="step === s ? 'primary' : 'default'"
            :plain="step !== s"
            @click="onStep(s)"
          >
            {{ s }}
          </var-button>
        </div>

        <div class="lg-title lg-mt"><span>点动速度 (mm/min)</span></div>
        <div class="lg-slider-row">
          <var-slider
            :model-value="speed"
            :min="100"
            :max="5000"
            :step="100"
            @change="onSpeed"
          />
          <span class="lg-slider-row__val lg-mono">{{ speed }}</span>
        </div>
      </section>

      <!-- 绝对移动 -->
      <section class="lg-section">
        <div class="lg-title"><span>移动到坐标</span></div>
        <div class="coord-row">
          <var-input v-model="targetX" type="number" placeholder="X" variant="outlined" />
          <var-input v-model="targetY" type="number" placeholder="Y" variant="outlined" />
          <var-button type="primary" :disabled="disabled" @click="onGoto">
            <AppIcon name="move" :size="17" />
          </var-button>
        </div>
      </section>

      <!-- 倍率 -->
      <section class="lg-section">
        <div class="lg-title"><span>实时倍率</span></div>

        <div class="ov-label">
          <span>进给倍率</span>
          <span class="lg-mono">{{ state.targetOverrides.feed }}%</span>
        </div>
        <var-slider
          :model-value="state.targetOverrides.feed"
          :min="10"
          :max="200"
          :step="10"
          @change="feedOverride"
        />

        <div class="ov-label lg-mt">
          <span>功率倍率</span>
          <span class="lg-mono">{{ state.targetOverrides.power }}%</span>
        </div>
        <var-slider
          :model-value="state.targetOverrides.power"
          :min="10"
          :max="200"
          :step="10"
          @change="powerOverride"
        />

        <div class="ov-label lg-mt">
          <span>快速移动倍率</span>
          <span class="lg-mono">{{ state.targetOverrides.rapids }}%</span>
        </div>
        <div class="steps lg-mt">
          <var-button
            v-for="r in [100, 50, 25]"
            :key="r"
            size="small"
            :type="state.targetOverrides.rapids === r ? 'primary' : 'default'"
            :plain="state.targetOverrides.rapids !== r"
            @click="rapidOverride(r)"
          >
            {{ r }}%
          </var-button>
        </div>
      </section>

      <!-- 测试激光 -->
      <section class="lg-section">
        <div class="lg-title"><span>测试激光</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">功率 S</span>
            <var-input :model-value="String(testPower)" type="number" variant="outlined" @blur="onTestNum('power', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">持续时间 (ms)</span>
            <var-input :model-value="String(testDuration)" type="number" variant="outlined" @blur="onTestNum('duration', $event)" />
          </div>
        </div>
        <div class="lg-grid-2 lg-mt">
          <var-button block type="warning" :disabled="disabled" @click="onTestLaser">
            <AppIcon name="flame" :size="17" />
            <span class="btn-text">点亮测试</span>
          </var-button>
          <var-button block plain :disabled="disabled" @click="onLaserOff">
            <AppIcon name="power" :size="17" />
            <span class="btn-text">关闭激光</span>
          </var-button>
        </div>
        <div class="lg-dim lg-mt">测试激光会短暂出光，请佩戴护目镜并确认光路安全。</div>
      </section>

      <!-- 坐标操作 -->
      <section class="lg-section">
        <div class="lg-title"><span>坐标操作</span></div>
        <div class="lg-grid-2">
          <var-button block plain :disabled="disabled" @click="onZero">
            <AppIcon name="crosshair" :size="17" />
            <span class="btn-text">设为零点</span>
          </var-button>
          <var-button block plain :disabled="disabled" @click="onUnlock">
            <AppIcon name="power" :size="17" />
            <span class="btn-text">解锁 $X</span>
          </var-button>
        </div>
        <div class="lg-kv lg-mt">
          <span class="lg-kv__k">工作坐标</span>
          <span class="lg-kv__v lg-mono">
            X {{ fmt(state.wpos.x) }} · Y {{ fmt(state.wpos.y) }}
          </span>
        </div>
        <div class="lg-kv">
          <span class="lg-kv__k">机器状态</span>
          <span class="lg-kv__v">{{ state.status === MacStatus.Jog ? '点动中' : state.status }}</span>
        </div>
      </section>
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

.coord-row {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 8px;
  align-items: center;
}

.ov-label {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--lg-text-dim);
  margin-bottom: 2px;
}

.btn-text {
  margin-left: 5px;
}
</style>