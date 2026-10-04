<script setup lang="ts">
/** 应用设置：外观、连接、雕刻默认参数与入口 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import {
  state,
  updateSetting,
  setTheme,
  setThreadingMode,
  reloadSettings,
  openSetupWizard,
  STATUS_LABELS
} from '../store'
import { Firmware } from '../../core/grbl/types'
import type { MacStatus } from '../../core/grbl/types'
import {
  applyProfileToSettings,
  deleteProfile,
  listSavedProfiles,
  setActiveProfile,
  type DeviceProfile
} from '../../core/grbl/DeviceProfile'
import { toast } from '../utils'

const router = useRouter()

const settings = computed(() => state.settings)

/** 已保存的设备档案（本地读取，操作后手动刷新） */
const profiles = ref<DeviceProfile[]>(listSavedProfiles())
const activeProfile = computed(() => profiles.value.find((p) => p.active) ?? null)

function refreshProfiles() {
  profiles.value = listSavedProfiles()
}

// 初始化向导保存 / 取消后，向导会清除 needsSetup；此时刷新档案列表
watch(
  () => state.needsSetup,
  (v) => {
    if (!v) refreshProfiles()
  }
)

function startWizard() {
  openSetupWizard(state.connected ? state.deviceId : -1)
}

function onActivate(p: DeviceProfile) {
  setActiveProfile(p.id)
  applyProfileToSettings(p)
  reloadSettings()
  refreshProfiles()
  toast(`已启用设备「${p.name}」`, 'success')
}

function onDelete(p: DeviceProfile) {
  deleteProfile(p.id)
  refreshProfiles()
  toast(`已删除设备「${p.name}」`, 'info')
}

function bool(key: string, def = false): boolean {
  const v = settings.value[key]
  return v === undefined || v === null ? def : Boolean(v)
}
function num(key: string, def = 0): number {
  const v = settings.value[key]
  return typeof v === 'number' ? v : def
}
function str(key: string, def = ''): string {
  const v = settings.value[key]
  return typeof v === 'string' ? v : def
}

const themeModel = computed({
  get: () => state.theme === 'dark',
  set: (v: boolean) => setTheme(v ? 'dark' : 'light')
})

const FIRMWARES: { value: Firmware; label: string }[] = [
  { value: Firmware.Grbl, label: 'GRBL' },
  { value: Firmware.Smoothie, label: 'Smoothieware' },
  { value: Firmware.Marlin, label: 'Marlin' },
  { value: Firmware.Vigo, label: 'Vigo' }
]

const THREADING: { value: string; label: string }[] = [
  { value: 'Slow', label: '慢速（最省资源）' },
  { value: 'Quiet', label: '安静' },
  { value: 'Fast', label: '快速（推荐）' },
  { value: 'UltraFast', label: '极速' },
  { value: 'Insane', label: '疯狂（高负载）' }
]

function onBool(key: string, v: boolean | string | number) {
  updateSetting(key, Boolean(v))
}

function onNum(key: string, ev: Event) {
  const t = ev.target as HTMLInputElement
  const v = parseFloat(t.value)
  if (Number.isFinite(v)) updateSetting(key, v)
}

function onStr(key: string, ev: Event) {
  const t = ev.target as HTMLInputElement | HTMLTextAreaElement
  updateSetting(key, t.value)
}

function onFirmware(v: string | number) {
  updateSetting('Firmware Type', v)
  toast(`固件类型已切换为 ${v}`, 'success')
}

function onThreading(v: string | number) {
  setThreadingMode(String(v))
  toast('通讯模式已更新', 'success')
}

function goto(path: string) {
  router.push(path)
}

const issueText = computed(() => {
  if (!state.issue) return '正常'
  return STATUS_LABELS[state.status as MacStatus] ?? String(state.status)
})
</script>

<template>
  <div class="lg-page">
    <div class="lg-body lg-body--nav">
      <!-- 外观 -->
      <section class="lg-section">
        <div class="lg-title"><span>外观</span></div>
        <var-cell title="深色模式" description="切换深色 / 浅色主题">
          <template #extra>
            <var-switch v-model="themeModel" />
          </template>
        </var-cell>
      </section>

      <!-- 连接 -->
      <section class="lg-section">
        <div class="lg-title"><span>连接与通讯</span></div>

        <div class="lg-deco">
          <div class="lg-dim">固件类型</div>
          <var-select
            class="lg-mt"
            :model-value="str('Firmware Type', 'Grbl')"
            variant="outlined"
            @change="onFirmware"
          >
            <var-option v-for="f in FIRMWARES" :key="f.value" :label="f.label" :value="f.value" />
          </var-select>
        </div>

        <div class="lg-deco lg-mt">
          <div class="lg-dim">通讯模式</div>
          <var-select
            class="lg-mt"
            :model-value="str('Threading Mode', 'Fast')"
            variant="outlined"
            @change="onThreading"
          >
            <var-option v-for="t in THREADING" :key="t.value" :label="t.label" :value="t.value" />
          </var-select>
        </div>

        <var-cell title="连接时软复位" description="连接后发送 0x18 复位设备">
          <template #extra>
            <var-switch :model-value="bool('Reset Grbl On Connect', true)" @update:model-value="onBool('Reset Grbl On Connect', $event)" />
          </template>
        </var-cell>

        <var-cell title="支持硬件 PWM" description="使用 S 值精细控制激光功率">
          <template #extra>
            <var-switch :model-value="bool('Support Hardware PWM', true)" @update:model-value="onBool('Support Hardware PWM', $event)" />
          </template>
        </var-cell>
      </section>

      <!-- 设备初始化与行程 -->
      <section class="lg-section">
        <div class="lg-title"><span>设备与行程</span></div>
        <var-button class="lg-mb" block type="primary" @click="startWizard">
          <AppIcon name="settings" :size="17" />
          <span class="btn-text">打开设备初始化向导</span>
        </var-button>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">X 行程 (mm)</span>
            <var-input :model-value="String(num('Travel X', 300))" type="number" variant="outlined" @blur="onNum('Travel X', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">Y 行程 (mm)</span>
            <var-input :model-value="String(num('Travel Y', 200))" type="number" variant="outlined" @blur="onNum('Travel Y', $event)" />
          </div>
        </div>
        <div class="lg-dim lg-mt">生成 G 代码时会按此行程做尺寸自适应，避免雕刻超出机器范围。</div>
      </section>

      <!-- 设备档案 -->
      <section v-if="profiles.length" class="lg-section">
        <div class="lg-title">
          <span>设备档案</span>
          <span class="lg-dim">{{ profiles.length }} 台</span>
        </div>
        <div v-for="p in profiles" :key="p.id" class="dev-row">
          <div class="dev-row__text">
            <div class="dev-row__name">
              {{ p.name }}
              <span v-if="activeProfile && activeProfile.id === p.id" class="dev-tag">当前</span>
            </div>
            <div class="lg-dim dev-row__sub">
              {{ p.travelX }}×{{ p.travelY }}mm · S{{ p.minPower }}~{{ p.maxPower }} · {{ p.travelSpeed }}mm/min
            </div>
          </div>
          <var-button size="small" plain :disabled="!!activeProfile && activeProfile.id === p.id" @click="onActivate(p)">
            启用
          </var-button>
          <var-button size="small" text type="danger" @click="onDelete(p)">
            <AppIcon name="trash" :size="16" />
          </var-button>
        </div>
      </section>

      <!-- 雕刻默认参数 -->
      <section class="lg-section">
        <div class="lg-title"><span>雕刻默认参数</span></div>

        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">雕刻速度 (mm/min)</span>
            <var-input :model-value="String(num('Mark Speed', 1000))" type="number" variant="outlined" @blur="onNum('Mark Speed', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">轮廓速度 (mm/min)</span>
            <var-input :model-value="String(num('Border Speed', 1000))" type="number" variant="outlined" @blur="onNum('Border Speed', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">最小功率 S</span>
            <var-input :model-value="String(num('Min Power', 0))" type="number" variant="outlined" @blur="onNum('Min Power', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">最大功率 S</span>
            <var-input :model-value="String(num('Max Power', 1000))" type="number" variant="outlined" @blur="onNum('Max Power', $event)" />
          </div>
        </div>

        <div class="num-item lg-mt">
          <span class="lg-dim">激光开启指令</span>
          <var-input :model-value="str('Laser On Command', 'M4')" variant="outlined" @blur="onStr('Laser On Command', $event)" />
        </div>
        <div class="num-item lg-mt">
          <span class="lg-dim">激光关闭指令</span>
          <var-input :model-value="str('Laser Off Command', 'M5')" variant="outlined" @blur="onStr('Laser Off Command', $event)" />
        </div>

        <div class="num-grid lg-mt">
          <div class="num-item">
            <span class="lg-dim">测试激光功率 S</span>
            <var-input :model-value="String(num('Test Laser Power', 200))" type="number" variant="outlined" @blur="onNum('Test Laser Power', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">测试激光时长 (ms)</span>
            <var-input :model-value="String(num('Test Laser Duration', 300))" type="number" variant="outlined" @blur="onNum('Test Laser Duration', $event)" />
          </div>
        </div>

        <var-cell title="单向雕刻" description="仅单向出光，反向空移（质量更高）">
          <template #extra>
            <var-switch :model-value="bool('Unidirectional Engraving', false)" @update:model-value="onBool('Unidirectional Engraving', $event)" />
          </template>
        </var-cell>
        <var-cell title="禁用 G0 快速空移" description="空移也使用 G1 进给，速度更稳">
          <template #extra>
            <var-switch :model-value="bool('Disable G0 fast skip', false)" @update:model-value="onBool('Disable G0 fast skip', $event)" />
          </template>
        </var-cell>
      </section>

      <!-- 文件头尾 -->
      <section class="lg-section">
        <div class="lg-title"><span>程序头 / 尾</span></div>
        <div class="num-item">
          <span class="lg-dim">文件头 (Header)</span>
          <var-input
            :model-value="str('Header', 'G90\nG0 X0 Y0')"
            textarea
            :rows="3"
            variant="outlined"
            @blur="onStr('Header', $event)"
          />
        </div>
        <div class="num-item lg-mt">
          <span class="lg-dim">文件尾 (Footer)</span>
          <var-input
            :model-value="str('Footer', 'M5\nG0 X0 Y0')"
            textarea
            :rows="3"
            variant="outlined"
            @blur="onStr('Footer', $event)"
          />
        </div>
      </section>

      <!-- 点动默认 -->
      <section class="lg-section">
        <div class="lg-title"><span>点动默认参数</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">点动速度 (mm/min)</span>
            <var-input :model-value="String(num('Jog Speed', 1000))" type="number" variant="outlined" @blur="onNum('Jog Speed', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">点动步长 (mm)</span>
            <var-input :model-value="String(num('Jog Step', 1))" type="number" variant="outlined" @blur="onNum('Jog Step', $event)" />
          </div>
        </div>
      </section>

      <!-- 入口 -->
      <section class="lg-section">
        <div class="lg-title"><span>更多</span></div>
        <var-cell is-link title="连接设备" @click="goto('/connect')">
          <template #icon><AppIcon name="usb" :size="18" /></template>
        </var-cell>
        <var-cell is-link title="机器参数 ($$)" @click="goto('/config')">
          <template #icon><AppIcon name="config" :size="18" /></template>
        </var-cell>
        <var-cell is-link title="串口终端" @click="goto('/terminal')">
          <template #icon><AppIcon name="terminal" :size="18" /></template>
        </var-cell>
        <var-cell is-link title="关于" @click="goto('/about')">
          <template #icon><AppIcon name="info" :size="18" /></template>
        </var-cell>
      </section>

      <section class="lg-section lg-center">
        <div class="lg-dim">当前状态：{{ issueText }}</div>
        <div class="lg-dim lg-mt">iGRBL · v1.0.0</div>
      </section>
    </div>
  </div>
</template>

<style scoped>
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

.dev-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-top: 1px solid var(--lg-border);
}

.dev-row:first-of-type {
  border-top: none;
}

.dev-row__text {
  flex: 1;
  min-width: 0;
}

.dev-row__name {
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dev-row__sub {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dev-tag {
  flex: 0 0 auto;
  font-size: 11px;
  font-weight: 600;
  color: var(--lg-accent);
  border: 1px solid rgba(255, 122, 24, 0.4);
  background: rgba(255, 122, 24, 0.12);
  border-radius: 999px;
  padding: 1px 7px;
}

.btn-text {
  margin-left: 5px;
}
</style>