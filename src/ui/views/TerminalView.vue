<script setup lang="ts">
/** 串口终端：查看通讯日志并发送原始命令 */
import { ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import LogList from '../components/LogList.vue'
import { state, sendCommand, readMachineConfig, softReset, homing, unlock, clearLog } from '../store'
import { toast } from '../utils'

const input = ref('')

const QUICK = [
  { label: '读取设置 $$', cmd: '$$' },
  { label: '版本 $I', cmd: '$I' },
  { label: '坐标系 $#', cmd: '$#' },
  { label: '回原点 $H', cmd: '$H' },
  { label: '解锁 $X', cmd: '$X' },
  { label: '状态报告 ?', cmd: '?' }
]

function send(cmd?: string) {
  const line = (cmd ?? input.value).trim()
  if (!line) return
  sendCommand(line)
  if (!cmd) input.value = ''
}

function quick(cmd: string) {
  if (cmd === '$$') {
    readMachineConfig()
    return
  }
  if (cmd === '$H') {
    homing()
    return
  }
  if (cmd === '$X') {
    unlock()
    return
  }
  if (cmd === '?') {
    sendCommand('?')
    return
  }
  send(cmd)
}

function onSoftReset() {
  softReset()
  toast('已软复位', 'warning')
}

function onClear() {
  clearLog()
  toast('日志已清空', 'info')
}
</script>

<template>
  <div class="lg-page">
    <div class="lg-body">
      <GlassSurface class="lg-section">
        <div class="lg-title">
          <span>串口终端</span>
          <span class="lg-badge" :class="state.connected ? 'lg-badge--ok' : 'lg-badge--idle'">
            <span class="lg-dot" />{{ state.connected ? '已连接' : '未连接' }}
          </span>
        </div>
        <LogList :height="'52vh'" />
        <div class="term-actions">
          <GlassButton size="small" text @click="onClear">
            <AppIcon name="trash" :size="16" />
            <span class="btn-text">清空</span>
          </GlassButton>
          <GlassButton size="small" text type="warning" :disabled="!state.connected" @click="onSoftReset">
            <AppIcon name="power" :size="16" />
            <span class="btn-text">软复位</span>
          </GlassButton>
        </div>
      </GlassSurface>

      <GlassSurface class="lg-section">
        <div class="lg-title"><span>发送命令</span></div>
        <div class="send-row">
          <GlassInput
            v-model="input"
            placeholder="输入 G 代码或 $ 命令"
            variant="outlined"
            @keyup.enter="send()"
          />
          <GlassButton type="primary" :disabled="!state.connected" @click="send()">
            发送
          </GlassButton>
        </div>
      </GlassSurface>

      <GlassSurface class="lg-section">
        <div class="lg-title"><span>快捷命令</span></div>
        <div class="quick">
          <GlassButton
            v-for="q in QUICK"
            :key="q.cmd"
            size="small"
            plain
            :disabled="!state.connected"
            @click="quick(q.cmd)"
          >
            {{ q.label }}
          </GlassButton>
        </div>
      </GlassSurface>
    </div>
  </div>
</template>

<style scoped>
.term-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  margin-top: 6px;
}

.send-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
  align-items: center;
}

.quick {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>