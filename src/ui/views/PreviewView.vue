<script setup lang="ts">
/** 路径预览：放大查看雕刻路径、查看统计并直接开始 / 控制任务 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import GcodePreview from '../components/GcodePreview.vue'
import { state, runFile, abortFile, feedHold, cycleStart, MacStatus } from '../store'
import { formatDuration, toast } from '../utils'

const router = useRouter()

const height = ref(340)
const MIN_H = 200
const MAX_H = 640

const canRun = computed(() => state.connected && !!state.file && !state.running)
const canPause = computed(
  () => state.running && (state.status === MacStatus.Run || state.status === MacStatus.Jog)
)
const canResume = computed(() => state.running && state.status === MacStatus.Hold)

const sizeText = computed(() => {
  const b = state.file?.stats.bbox
  if (!b || !b.valid) return '--'
  return `${(b.maxX - b.minX).toFixed(1)} × ${(b.maxY - b.minY).toFixed(1)} mm`
})

const percent = computed(() => {
  const { executed, total } = state.progress
  if (!total) return 0
  return Math.min(100, Math.max(0, Math.round((executed / total) * 100)))
})

/** 预览动画进度：仅任务执行中传入；空闲 / 已结束 / 刚载入新文件时保持整条路径绘制 */
const previewProgress = computed<number | undefined>(() =>
  state.running ? percent.value : undefined
)

function zoom(delta: number) {
  height.value = Math.min(MAX_H, Math.max(MIN_H, height.value + delta))
}

function onRun() {
  if (runFile()) {
    toast('已开始雕刻', 'success')
    router.push('/home')
  }
}

function onPause() {
  feedHold()
  toast('已暂停', 'warning')
}

function onResume() {
  cycleStart()
  toast('继续执行', 'success')
}

function onAbort() {
  abortFile()
  toast('已中止任务', 'warning')
}
</script>

<template>
  <div class="lg-page">
    <div class="lg-body lg-body--nav">
      <div v-if="!state.file" class="lg-empty lg-mt">
        尚未载入 G 代码文件
        <div class="lg-mt">
          <GlassButton type="primary" @click="router.push('/file')">
            <AppIcon name="folder" :size="17" />
            <span class="btn-text">前往文件页载入</span>
          </GlassButton>
        </div>
      </div>

      <template v-else>
        <!-- 预览 -->
        <GlassSurface class="lg-section">
          <div class="lg-title">
            <span>路径预览</span>
            <span class="zoom">
              <GlassButton size="small" text @click="zoom(-80)">
                <AppIcon name="zoomOut" :size="16" />
              </GlassButton>
              <GlassButton size="small" text @click="zoom(80)">
                <AppIcon name="zoomIn" :size="16" />
              </GlassButton>
            </span>
          </div>
          <div class="file-name">
            <AppIcon name="file" :size="18" />
            <span>{{ state.file.name }}</span>
          </div>
          <GcodePreview
            class="lg-mt"
            :preview="state.file.preview"
            :bbox="state.file.stats.bbox"
            :height="height"
            :progress="previewProgress"
          />
          <div class="lg-dim lg-mt">
            橙色为雕刻路径，灰色为快速空移路径，十字为坐标原点
          </div>
        </GlassSurface>

        <!-- 统计 -->
        <GlassSurface class="lg-section">
          <div class="lg-title"><span>文件统计</span></div>
          <div class="lg-grid-2">
            <div class="stat">
              <div class="stat__k">总行数</div>
              <div class="stat__v lg-mono">{{ state.file.stats.totalLines }}</div>
            </div>
            <div class="stat">
              <div class="stat__k">运动指令</div>
              <div class="stat__v lg-mono">{{ state.file.stats.motionCommands }}</div>
            </div>
            <div class="stat">
              <div class="stat__k">路径长度</div>
              <div class="stat__v lg-mono">{{ state.file.stats.pathLengthMm.toFixed(1) }} mm</div>
            </div>
            <div class="stat">
              <div class="stat__k">雕刻尺寸</div>
              <div class="stat__v lg-mono">{{ sizeText }}</div>
            </div>
            <div class="stat">
              <div class="stat__k">预计时间</div>
              <div class="stat__v">{{ formatDuration(state.file.stats.estimatedSeconds) }}</div>
            </div>
            <div class="stat">
              <div class="stat__k">预计剩余</div>
              <div class="stat__v">
                {{ formatDuration(state.file.stats.estimatedSeconds * (1 - percent / 100)) }}
              </div>
            </div>
          </div>
        </GlassSurface>

        <!-- 任务控制 -->
        <GlassSurface class="lg-section">
          <div class="lg-title"><span>任务控制</span></div>

          <div v-if="!state.connected" class="lg-empty">未连接设备，请先在首页连接</div>

          <div class="lg-grid-2">
            <GlassButton v-if="!state.running" block type="primary" :disabled="!canRun" @click="onRun">
              <AppIcon name="play" :size="17" />
              <span class="btn-text">开始雕刻</span>
            </GlassButton>
            <GlassButton v-if="canPause" block type="warning" @click="onPause">
              <AppIcon name="pause" :size="17" />
              <span class="btn-text">暂停</span>
            </GlassButton>
            <GlassButton v-if="canResume" block type="success" @click="onResume">
              <AppIcon name="play" :size="17" />
              <span class="btn-text">继续</span>
            </GlassButton>
            <GlassButton v-if="state.running" block type="danger" @click="onAbort">
              <AppIcon name="stop" :size="17" />
              <span class="btn-text">中止</span>
            </GlassButton>
          </div>

          <div v-if="state.running" class="lg-mt">
            <GlassProgress :value="percent" />
            <div class="lg-dim lg-mt">{{ percent }}% · 已执行 {{ state.progress.executed }} / {{ state.progress.total }} 行</div>
          </div>
        </GlassSurface>
      </template>
    </div>
  </div>
</template>

<style scoped>
.file-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--lg-accent);
  overflow: hidden;
}

.file-name span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--lg-text);
}

.zoom {
  display: inline-flex;
  align-items: center;
}

.stat {
  padding: 10px;
  background: var(--lg-panel-2);
  border-radius: 9px;
  text-align: center;
}

.stat__k {
  font-size: 11px;
  color: var(--lg-text-dim);
  margin-bottom: 4px;
}

.stat__v {
  font-size: 13.5px;
  font-weight: 600;
}
</style>