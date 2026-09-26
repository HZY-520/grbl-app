<script setup lang="ts">
/** 首页：设备状态、坐标、任务执行、快捷操作与日志 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import LogList from '../components/LogList.vue'
import GcodePreview from '../components/GcodePreview.vue'
import {
  state,
  runFile,
  abortFile,
  feedHold,
  cycleStart,
  softReset,
  homing,
  unlock,
  setNewZero,
  resetWCO,
  STATUS_LABELS,
  FIRMWARE_LABELS,
  MacStatus
} from '../store'
import { fmt, formatDuration, toast } from '../utils'

const router = useRouter()

const statusText = computed(() => STATUS_LABELS[state.status] ?? String(state.status))
const firmwareText = computed(() => FIRMWARE_LABELS[state.firmware] ?? String(state.firmware))

const statusKind = computed(() => {
  if (state.connecting) return 'warn'
  if (!state.connected) return 'idle'
  if (state.status === MacStatus.Alarm) return 'err'
  if (state.status === MacStatus.Run || state.status === MacStatus.Jog) return 'ok'
  if (state.status === MacStatus.Hold || state.status === MacStatus.Door) return 'warn'
  return 'ok'
})

const percent = computed(() => {
  const { executed, total } = state.progress
  if (!total) return 0
  return Math.min(100, Math.max(0, Math.round((executed / total) * 100)))
})

const progressText = computed(() => {
  const { executed, total } = state.progress
  return total ? `${executed} / ${total} 行` : '—'
})

const remainText = computed(() => {
  if (!state.file) return '--'
  const base = state.file.stats.estimatedSeconds
  return formatDuration(base * (1 - percent.value / 100))
})

const totalText = computed(() => (state.file ? formatDuration(state.file.stats.estimatedSeconds) : '--'))

const canRun = computed(() => state.connected && !!state.file && !state.running)

function onRun() {
  if (runFile()) toast('已开始雕刻', 'success')
}
function onAbort() {
  abortFile()
  toast('已中止任务', 'warning')
}
function onHold() {
  feedHold()
  toast('已暂停', 'warning')
}
function onResume() {
  cycleStart()
  toast('继续执行', 'success')
}
function onHoming() {
  homing()
  toast('发送回零指令', 'info')
}
function onUnlock() {
  unlock()
  toast('发送解锁指令', 'info')
}
function onSoftReset() {
  softReset()
  toast('已软复位', 'warning')
}
function goto(path: string) {
  router.push(path)
}
</script>

<template>
  <div class="lg-page">
    <div class="lg-body lg-body--nav">
      <!-- 设备状态 -->
      <section class="lg-section">
        <div class="lg-row lg-row--between">
          <div class="lg-row">
            <span class="lg-badge" :class="`lg-badge--${statusKind}`">
              <span class="lg-dot" />
              {{ state.connecting ? '连接中' : statusText }}
            </span>
            <span v-if="state.connected" class="lg-dim">{{ firmwareText }} {{ state.version }}</span>
          </div>
          <var-button
            v-if="state.connected"
            size="small"
            type="danger"
            plain
            @click="goto('/connect')"
          >
            <AppIcon name="usb" :size="16" />
            <span class="btn-text">设备</span>
          </var-button>
          <var-button v-else size="small" type="primary" @click="goto('/connect')">
            <AppIcon name="usb" :size="16" />
            <span class="btn-text">连接设备</span>
          </var-button>
        </div>
      </section>

      <!-- 坐标 -->
      <section class="lg-section">
        <div class="lg-title">
          <span>当前坐标</span>
          <span class="lg-dim">进给 {{ fmt(state.feed, 0) }} / 功率 {{ fmt(state.spindle, 0) }}</span>
        </div>
        <div class="lg-grid-3">
          <div class="lg-stat">
            <div class="lg-stat__k">工作坐标 X</div>
            <div class="lg-stat__v lg-mono">{{ fmt(state.wpos.x) }}</div>
          </div>
          <div class="lg-stat">
            <div class="lg-stat__k">工作坐标 Y</div>
            <div class="lg-stat__v lg-mono">{{ fmt(state.wpos.y) }}</div>
          </div>
          <div class="lg-stat">
            <div class="lg-stat__k">工作坐标 Z</div>
            <div class="lg-stat__v lg-mono">{{ fmt(state.wpos.z) }}</div>
          </div>
        </div>
        <div class="lg-kv lg-mt">
          <span class="lg-kv__k">机械坐标</span>
          <span class="lg-kv__v lg-mono">
            X {{ fmt(state.pos.x) }} · Y {{ fmt(state.pos.y) }} · Z {{ fmt(state.pos.z) }}
          </span>
        </div>
        <div class="lg-kv">
          <span class="lg-kv__k">坐标系偏移 (WCO)</span>
          <span class="lg-kv__v lg-mono">
            X {{ fmt(state.wco.x) }} · Y {{ fmt(state.wco.y) }}
          </span>
        </div>
      </section>

      <!-- 当前任务 -->
      <section class="lg-section">
        <div class="lg-title">
          <span>当前任务</span>
          <span v-if="state.file" class="lg-dim">{{ state.file.name }}</span>
        </div>

        <div v-if="!state.file" class="lg-empty">
          尚未载入雕刻文件
          <div class="lg-mt">
            <var-button size="small" text type="primary" @click="goto('/convert')">
              <AppIcon name="layers" :size="16" />
              <span class="btn-text">生成图案</span>
            </var-button>
            <var-button size="small" text type="primary" @click="goto('/file')">
              <AppIcon name="folder" :size="16" />
              <span class="btn-text">打开文件</span>
            </var-button>
          </div>
        </div>

        <template v-else>
          <div class="lg-row lg-row--between">
            <span class="lg-dim">进度 {{ progressText }}</span>
            <span class="lg-dim">{{ percent }}%</span>
          </div>
          <var-progress
            class="lg-mt"
            :value="percent"
            :show-label="false"
            color="#ff7a18"
            track-color="var(--lg-panel-2)"
          />
          <div class="lg-grid-3 lg-mt">
            <div class="lg-stat">
              <div class="lg-stat__k">行数</div>
              <div class="lg-stat__v lg-mono">{{ state.file.stats.totalLines }}</div>
            </div>
            <div class="lg-stat">
              <div class="lg-stat__k">路径长度</div>
              <div class="lg-stat__v lg-mono">{{ fmt(state.file.stats.pathLengthMm, 1) }} mm</div>
            </div>
            <div class="lg-stat">
              <div class="lg-stat__k">{{ state.running ? '剩余' : '预计' }}</div>
              <div class="lg-stat__v lg-mono">{{ state.running ? remainText : totalText }}</div>
            </div>
          </div>

          <div class="lg-grid-4 lg-mt">
            <var-button type="primary" :disabled="!canRun" @click="onRun">
              <AppIcon name="play" :size="18" />
            </var-button>
            <var-button
              v-if="state.status !== MacStatus.Hold"
              type="warning"
              :disabled="!state.running"
              @click="onHold"
            >
              <AppIcon name="pause" :size="18" />
            </var-button>
            <var-button v-else type="success" @click="onResume">
              <AppIcon name="play" :size="18" />
            </var-button>
            <var-button type="danger" :disabled="!state.running" @click="onAbort">
              <AppIcon name="stop" :size="18" />
            </var-button>
            <var-button plain @click="goto('/preview')">
              <AppIcon name="eye" :size="18" />
            </var-button>
          </div>

          <GcodePreview
            class="lg-mt"
            :preview="state.file.preview"
            :bbox="state.file.stats.bbox"
            :height="200"
          />
        </template>
      </section>

      <!-- 快捷操作 -->
      <section class="lg-section">
        <div class="lg-title"><span>快捷操作</span></div>
        <div class="lg-grid-2">
          <var-button block plain :disabled="!state.connected" @click="onHoming">
            <AppIcon name="homeZero" :size="17" />
            <span class="btn-text">回原点 $H</span>
          </var-button>
          <var-button block plain :disabled="!state.connected" @click="onUnlock">
            <AppIcon name="power" :size="17" />
            <span class="btn-text">解锁 $X</span>
          </var-button>
          <var-button block plain :disabled="!state.connected" @click="setNewZero(); toast('已设置工作零点', 'success')">
            <AppIcon name="crosshair" :size="17" />
            <span class="btn-text">设为零点</span>
          </var-button>
          <var-button block plain :disabled="!state.connected" @click="resetWCO(); toast('已清除坐标偏移', 'info')">
            <AppIcon name="refresh" :size="17" />
            <span class="btn-text">清除偏移</span>
          </var-button>
          <var-button block plain type="warning" :disabled="!state.connected" @click="onSoftReset">
            <AppIcon name="power" :size="17" />
            <span class="btn-text">软复位</span>
          </var-button>
          <var-button block plain @click="goto('/jog')">
            <AppIcon name="move" :size="17" />
            <span class="btn-text">运动控制</span>
          </var-button>
        </div>
      </section>

      <!-- 日志 -->
      <section class="lg-section">
        <div class="lg-title">
          <span>运行日志</span>
          <var-button size="small" text @click="goto('/terminal')">
            <AppIcon name="terminal" :size="16" />
            <span class="btn-text">终端</span>
          </var-button>
        </div>
        <LogList :height="'220px'" />
      </section>
    </div>
  </div>
</template>

<style scoped>
.lg-stat {
  padding: 8px 10px;
  background: var(--lg-panel-2);
  border-radius: 9px;
  text-align: center;
}

.lg-stat__k {
  font-size: 11px;
  color: var(--lg-text-dim);
  margin-bottom: 4px;
}

.lg-stat__v {
  font-size: 14px;
  font-weight: 600;
}

.btn-text {
  margin-left: 5px;
}
</style>