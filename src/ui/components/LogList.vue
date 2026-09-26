<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { state, clearLog, type LogItem } from '../store'
import { MessageType } from '../../core/grbl/GrblCommand'
import AppIcon from './AppIcon.vue'

const props = withDefaults(defineProps<{ height?: string; autoscroll?: boolean }>(), {
  height: '260px',
  autoscroll: true
})

const boxRef = ref<HTMLElement | null>(null)

function lineClass(item: LogItem) {
  switch (item.kind) {
    case MessageType.Alarm:
      return 'lg-log__line--err'
    case MessageType.Warning:
      return 'lg-log__line--warn'
    case MessageType.Startup:
    case MessageType.Feedback:
      return 'lg-log__line--ok'
    case MessageType.Command:
      return 'lg-log__line--cmd'
    case MessageType.Config:
      return 'lg-log__line--dim'
    default:
      return ''
  }
}

watch(
  () => state.log.length,
  async () => {
    if (!props.autoscroll) return
    await nextTick()
    const el = boxRef.value
    if (el) el.scrollTop = el.scrollHeight
  }
)
</script>

<template>
  <div class="log-wrap">
    <div ref="boxRef" class="lg-log" :style="{ height }">
      <div v-if="state.log.length === 0" class="lg-dim">暂无日志</div>
      <div v-for="item in state.log" :key="item.id" class="lg-log__line" :class="lineClass(item)">
        {{ item.text }}
      </div>
    </div>
    <div class="log-actions">
      <var-button size="small" text @click="clearLog">
        <AppIcon name="trash" :size="16" />
        <span class="log-actions__text">清空</span>
      </var-button>
    </div>
  </div>
</template>

<style scoped>
.log-wrap {
  position: relative;
}

.log-actions {
  position: absolute;
  top: -34px;
  right: 0;
}

.log-actions__text {
  margin-left: 4px;
  font-size: 12px;
}
</style>