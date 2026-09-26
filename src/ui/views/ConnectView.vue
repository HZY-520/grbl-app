<script setup lang="ts">
/** 连接设备：USB 设备枚举、波特率选择、连接/断开 */
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import {
  state,
  refreshDevices,
  connect,
  disconnect,
  STATUS_LABELS,
  FIRMWARE_LABELS,
  MacStatus
} from '../store'
import type { UsbDeviceInfo } from '../../core/serial/types'
import { toast } from '../utils'

const router = useRouter()

const BAUD_RATES = [9600, 19200, 38400, 57600, 115200, 230400, 250000]

const baudModel = computed({
  get: () => state.baud,
  set: (v: number | string) => {
    state.baud = Number(v)
  }
})

const statusText = computed(() => STATUS_LABELS[state.status] ?? String(state.status))
const firmwareText = computed(() => FIRMWARE_LABELS[state.firmware] ?? String(state.firmware))

function deviceTitle(d: UsbDeviceInfo) {
  return d.product || d.name || `设备 #${d.deviceId}`
}

function deviceSub(d: UsbDeviceInfo) {
  const vid = d.vendorId !== undefined ? `0x${d.vendorId.toString(16).toUpperCase().padStart(4, '0')}` : ''
  const pid = d.productId !== undefined ? `0x${d.productId.toString(16).toUpperCase().padStart(4, '0')}` : ''
  const ids = vid && pid ? ` · ${vid}:${pid}` : ''
  return `${d.vendor || d.name}${ids}`
}

async function onConnect(d: UsbDeviceInfo) {
  if (state.connected || state.connecting) return
  await connect(d.deviceId)
}

async function onDisconnect() {
  await disconnect()
  toast('已断开连接', 'info')
}

function back() {
  router.back()
}

onMounted(() => {
  if (!state.connected) refreshDevices()
})
</script>

<template>
  <div class="lg-page">
    <div class="lg-body">
      <!-- 已连接 -->
      <section v-if="state.connected" class="lg-section">
        <div class="lg-title"><span>已连接设备</span></div>
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
        <var-button class="lg-mt" block type="danger" @click="onDisconnect">
          <AppIcon name="close" :size="17" />
          <span class="btn-text">断开连接</span>
        </var-button>
      </section>

      <template v-else>
        <!-- 波特率 -->
        <section class="lg-section">
          <div class="lg-title"><span>串口参数</span></div>
          <var-select v-model="baudModel" placeholder="选择波特率" variant="outlined">
            <var-option v-for="b in BAUD_RATES" :key="b" :label="`${b} bps`" :value="b" />
          </var-select>
          <div class="lg-dim lg-mt">
            大多数 GRBL 设备使用 115200；部分老设备使用 9600 或 57600。
          </div>
        </section>

        <!-- 设备列表 -->
        <section class="lg-section">
          <div class="lg-title">
            <span>USB 设备</span>
            <var-button size="small" text type="primary" :loading="state.scanning" @click="refreshDevices">
              <AppIcon name="refresh" :size="16" />
              <span class="btn-text">刷新</span>
            </var-button>
          </div>

          <div v-if="state.scanning && state.devices.length === 0" class="lg-empty">
            <var-loading type="circle" />
            <div class="lg-mt">正在扫描 USB 设备…</div>
          </div>

          <div v-else-if="state.devices.length === 0" class="lg-empty">
            未发现 USB 串口设备
            <div class="lg-mt lg-dim">
              请用 OTG 数据线连接雕刻机，并在系统弹窗中允许访问 USB 设备。
            </div>
          </div>

          <div v-else class="dev-list">
            <div v-for="d in state.devices" :key="d.deviceId" class="dev-item">
              <div class="dev-item__icon"><AppIcon name="usb" :size="22" /></div>
              <div class="dev-item__text">
                <div class="dev-item__title">{{ deviceTitle(d) }}</div>
                <div class="dev-item__sub lg-dim">{{ deviceSub(d) }}</div>
              </div>
              <var-button
                size="small"
                type="primary"
                :loading="state.connecting"
                :disabled="state.connecting"
                @click="onConnect(d)"
              >
                连接
              </var-button>
            </div>
          </div>
        </section>

        <section class="lg-section">
          <div class="lg-title"><span>连接提示</span></div>
          <ul class="tips">
            <li>连接后应用会自动发送软复位并读取机器版本与设置。</li>
            <li>若长时间无响应，请检查波特率或更换数据线（需支持数据传输）。</li>
            <li>Android 首次连接会弹出 USB 权限请求，请选择“允许”。</li>
          </ul>
        </section>
      </template>

      <var-button class="lg-mt" block text @click="back">
        <AppIcon name="back" :size="17" />
        <span class="btn-text">返回</span>
      </var-button>
    </div>
  </div>
</template>

<style scoped>
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

.btn-text {
  margin-left: 5px;
}
</style>