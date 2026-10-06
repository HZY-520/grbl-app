<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { state, clearLog, type LogItem } from '../store'
import { MessageType } from '../../core/grbl/GrblCommand'
import AppIcon from './AppIcon.vue'

const props = withDefaults(
  defineProps<{ height?: string; autoscroll?: boolean; showClear?: boolean }>(),
  {
    height: '260px',
    autoscroll: true,
    showClear: true
  }
)

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
    <div v-if="showClear" class="log-actions">
      <GlassButton size="small" text @click="clearLog">
        <AppIcon name="trash" :size="15" />
        <span class="log-actions__text">清空</span>
      </GlassButton>
    </div>
  </div>
</template>

<style scoped>
.log-wrap {
  position: relative;
}

/* 清空按钮浮在日志框右上角内部：既不与区块标题行里的按钮打架，
 * 也不会因为 title 行内容变化而错位。 */
.log-actions {
  position: absolute;
  top: 5px;
  right: 5px;
  z-index: 1;
  border-radius: 10px;
  background: color-mix(in srgb, var(--g-bg) 62%, transparent);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}

.log-actions__text {
  margin-left: 4px;
  font-size: 12px;
}
</style>