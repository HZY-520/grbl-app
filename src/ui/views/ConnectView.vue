<script setup lang="ts">
/** 连接设备：USB / 蓝牙设备枚举、波特率选择、连接/断开 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import {
  state,
  refreshDevices,
  refreshBluetoothDevices,
  connect,
  disconnect,
  openBluetoothSettings,
  STATUS_LABELS,
  FIRMWARE_LABELS
} from '../store'
import type { SerialDeviceInfo, TransportKind } from '../../core/serial/types'
import { toast } from '../utils'

const router = useRouter()

const BAUD_RATES = [9600, 19200, 38400, 57600, 115200, 230400, 250000]

/** 当前选择的连接方式 */
const kind = ref<TransportKind>('usb')

const baudModel = computed({
  get: () => state.baud,
  set: (v: number | string) => {
    state.baud = Number(v)
  }
})

const statusText = computed(() => STATUS_LABELS[state.status] ?? String(state.status))
const firmwareText = computed(() => FIRMWARE_LABELS[state.firmware] ?? String(state.firmware))

/** 当前连接方式对应的设备列表 */
const devices = computed(() => (kind.value === 'bluetooth' ? state.bluetoothDevices : state.devices))

const listTitle = computed(() => (kind.value === 'bluetooth' ? '配对蓝牙设备' : 'USB 设备'))

function deviceTitle(d: SerialDeviceInfo) {
  return d.product || d.name || d.address || `设备 #${d.deviceId}`
}

function deviceSub(d: SerialDeviceInfo) {
  if (kind.value === 'bluetooth') return d.address || ''
  const vid = d.vendorId !== undefined ? `0x${d.vendorId.toString(16).toUpperCase().padStart(4, '0')}` : ''
  const pid = d.productId !== undefined ? `0x${d.productId.toString(16).toUpperCase().padStart(4, '0')}` : ''
  const ids = vid && pid ? ` · ${vid}:${pid}` : ''
  return `${d.vendor || d.name}${ids}`
}

function refresh() {
  if (kind.value === 'bluetooth') return refreshBluetoothDevices()
  return refreshDevices()
}

function selectKind(k: TransportKind) {
  if (kind.value === k) return
  kind.value = k
  refresh()
}

async function onConnect(d: SerialDeviceInfo) {
  if (state.connected || state.connecting) return
  await connect(d)
}

async function onDisconnect() {
  await disconnect()
  toast('已断开连接', 'info')
}

function back() {
  router.back()
}

onMounted(() => {
  if (!state.connected) refresh()
})
</script>

<template>
  <div class="lg-page">
    <div class="lg-body">
      <!-- 已连接 -->
      <GlassSurface v-if="state.connected" class="lg-section">
        <div class="lg-title"><span>已连接设备</span></div>
        <div class="lg-kv">
          <span class="lg-kv__k">连接方式</span>
          <span class="lg-kv__v">{{ state.deviceKind === 'bluetooth' ? '蓝牙串口' : 'USB 串口' }}</span>
        </div>
        <div class="lg-kv">
          <span class="lg-kv__k">状态</span>
          <span class="lg-kv__v">{{ statusText }}</span>
        </div>
        <div class="lg-kv">
          <span class="lg-kv__k">固件</span>
          <span class="lg-kv__v">{{ firmwareText }} {{ state.version }}</span>
        </div>
        <div class="lg-kv">
          <span class="lg-kv__k">波特率</span>
          <span class="lg-kv__v lg-mono">{{ state.baud }}</span>
        </div>
        <GlassButton class="lg-mt" block type="danger" @click="onDisconnect">
          <AppIcon name="close" :size="17" />
          <span class="btn-text">断开连接</span>
        </GlassButton>
      </GlassSurface>

      <template v-else>
        <!-- 连接方式 -->
        <GlassSurface class="lg-section">
          <div class="lg-title"><span>连接方式</span></div>
          <div class="seg">
            <button
              class="seg__item"
              :class="{ 'seg__item--active': kind === 'usb' }"
              @click="selectKind('usb')"
            >
              <AppIcon name="usb" :size="18" />
              <span>USB</span>
            </button>
            <button
              class="seg__item"
              :class="{ 'seg__item--active': kind === 'bluetooth' }"
              @click="selectKind('bluetooth')"
            >
              <AppIcon name="bluetooth" :size="18" />
              <span>蓝牙</span>
            </button>
          </div>
        </GlassSurface>

        <!-- 波特率 -->
        <GlassSurface class="lg-section">
          <div class="lg-title"><span>串口参数</span></div>
          <GlassSelect v-model="baudModel" placeholder="选择波特率" variant="outlined">
            <GlassOption v-for="b in BAUD_RATES" :key="b" :label="`${b} bps`" :value="b" />
          </GlassSelect>
          <div class="lg-dim lg-mt">
            大多数 GRBL 设备使用 115200；部分老设备使用 9600 或 57600。
          </div>
        </GlassSurface>

        <!-- 设备列表 -->
        <GlassSurface class="lg-section">
          <div class="lg-title">
            <span>{{ listTitle }}</span>
            <GlassButton size="small" text type="primary" :loading="state.scanning" @click="refresh">
              <AppIcon name="refresh" :size="16" />
              <span class="btn-text">刷新</span>
            </GlassButton>
          </div>

          <div v-if="state.scanning && devices.length === 0" class="lg-empty">
            <GlassLoading type="circle" />
            <div class="lg-mt">正在扫描{{ kind === 'bluetooth' ? '蓝牙' : ' USB ' }}设备…</div>
          </div>

          <div v-else-if="devices.length === 0" class="lg-empty">
            未发现{{ kind === 'bluetooth' ? '已配对的蓝牙' : ' USB 串口' }}设备
            <div v-if="kind === 'bluetooth'" class="lg-mt lg-dim">
              请先在系统蓝牙设置中与雕刻机的蓝牙模块（如 HC-05 / HC-06）配对，再返回刷新。
            </div>
            <div v-else class="lg-mt lg-dim">
              请用 OTG 数据线连接雕刻机，并在系统弹窗中允许访问 USB 设备。
            </div>
          </div>

          <div v-else class="dev-list">
            <div v-for="d in devices" :key="d.id" class="dev-item">
              <div class="dev-item__icon">
                <AppIcon :name="kind === 'bluetooth' ? 'bluetooth' : 'usb'" :size="22" />
              </div>
              <div class="dev-item__text">
                <div class="dev-item__title">{{ deviceTitle(d) }}</div>
                <div class="dev-item__sub lg-dim">{{ deviceSub(d) }}</div>
              </div>
              <GlassButton
                size="small"
                type="primary"
                :loading="state.connecting"
                :disabled="state.connecting"
                @click="onConnect(d)"
              >
                连接
              </GlassButton>
            </div>
          </div>

          <GlassButton
            v-if="kind === 'bluetooth'"
            class="lg-mt"
            block
            text
            type="primary"
            @click="openBluetoothSettings"
          >
            <AppIcon name="settings" :size="16" />
            <span class="btn-text">打开系统蓝牙设置</span>
          </GlassButton>
        </GlassSurface>

        <GlassSurface class="lg-section">
          <div class="lg-title"><span>连接提示</span></div>
          <ul class="tips">
            <li>连接后应用会自动发送软复位并读取机器版本与设置。</li>
            <li>若长时间无响应，请检查波特率或更换数据线（需支持数据传输）。</li>
            <li>Android 首次连接会弹出 USB 权限请求，请选择“允许”。</li>
            <li>蓝牙连接使用经典蓝牙 SPP，需先在系统设置中完成配对。</li>
          </ul>
        </GlassSurface>
      </template>

      <GlassButton class="lg-mt" block text @click="back">
        <AppIcon name="back" :size="17" />
        <span class="btn-text">返回</span>
      </GlassButton>
    </div>
  </div>
</template>

<style scoped>
.seg {
  display: flex;
  gap: 8px;
  padding: 4px;
  background: var(--lg-panel-2);
  border-radius: 12px;
}

.seg__item {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 9px 0;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--lg-text-dim);
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.seg__item--active {
  background: var(--lg-accent);
  color: #fff;
}

.dev-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.dev-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  background: var(--lg-panel-2);
  border-radius: 10px;
}

.dev-item__icon {
  color: var(--lg-accent);
  display: flex;
}

.dev-item__text {
  flex: 1;
  min-width: 0;
}

.dev-item__title {
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dev-item__sub {
  font-size: 11.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tips {
  margin: 0;
  padding-left: 18px;
  font-size: 12.5px;
  color: var(--lg-text-dim);
  line-height: 1.9;
}
</style>
