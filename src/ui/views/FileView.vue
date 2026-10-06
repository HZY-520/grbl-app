<script setup lang="ts">
/** 雕刻文件：载入本地 G 代码、管理已保存文件与开始雕刻 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import GcodePreview from '../components/GcodePreview.vue'
import { state, loadGcodeText, loadGcodeLines, clearFile, runFile } from '../store'
import { listSavedFiles, saveGcodeFile, deleteSavedFile, type SavedFile } from '../storage'
import { GCODE_EXTENSIONS } from '../../core/grbl/types'
import { pickFile, formatDuration, toast } from '../utils'

const router = useRouter()

const files = ref<SavedFile[]>([])
const busy = ref(false)

const canRun = computed(() => state.connected && !!state.file && !state.running)

function reload() {
  files.value = listSavedFiles()
}

async function openLocal() {
  const accept = [...GCODE_EXTENSIONS, 'text/plain'].join(',')
  busy.value = true
  try {
    const file = await pickFile(accept)
    if (!file) return
    if (!file.text) {
      toast('无法读取该文件内容', 'error')
      return
    }
    loadGcodeText(file.name, file.text)
    toast(`已载入 ${file.name}`, 'success')
  } finally {
    busy.value = false
  }
}

function loadSaved(f: SavedFile) {
  loadGcodeLines(f.name, f.lines)
  toast(`已载入 ${f.name}`, 'success')
  router.push('/preview')
}

function remove(f: SavedFile) {
  deleteSavedFile(f.id)
  reload()
  toast('已删除', 'info')
}

function saveCurrent() {
  if (!state.file) return
  saveGcodeFile(state.file.name, state.file.commands.map((c) => c.command), {
    kind: 'gcode',
    note: `${state.file.stats.totalLines} 行`
  })
  reload()
  toast('已保存当前文件', 'success')
}

function onRun() {
  if (runFile()) {
    toast('已开始雕刻', 'success')
    router.push('/home')
  }
}

function onClear() {
  clearFile()
  toast('已清除当前文件', 'info')
}

onMounted(reload)
</script>

<template>
  <div class="lg-page">
    <div class="lg-body lg-body--nav">
      <!-- 当前文件 -->
      <GlassSurface class="lg-section">
        <div class="lg-title">
          <span>当前文件</span>
          <GlassButton v-if="state.file" size="small" text @click="onClear">
            <AppIcon name="close" :size="16" />
            <span class="btn-text">清除</span>
          </GlassButton>
        </div>

        <div v-if="!state.file" class="lg-empty">
          尚未载入 G 代码文件
        </div>

        <template v-else>
          <div class="file-name">
            <AppIcon name="file" :size="20" />
            <span>{{ state.file.name }}</span>
          </div>
          <div class="lg-grid-3 lg-mt">
            <div class="stat">
              <div class="stat__k">行数</div>
              <div class="stat__v lg-mono">{{ state.file.stats.totalLines }}</div>
            </div>
            <div class="stat">
              <div class="stat__k">路径长度</div>
              <div class="stat__v lg-mono">{{ state.file.stats.pathLengthMm.toFixed(1) }} mm</div>
            </div>
            <div class="stat">
              <div class="stat__k">预计时间</div>
              <div class="stat__v">{{ formatDuration(state.file.stats.estimatedSeconds) }}</div>
            </div>
          </div>
          <GcodePreview
            class="lg-mt"
            :preview="state.file.preview"
            :bbox="state.file.stats.bbox"
            :height="200"
          />
          <div class="lg-grid-3 lg-mt">
            <GlassButton type="primary" :disabled="!canRun" @click="onRun">
              <AppIcon name="play" :size="17" />
            </GlassButton>
            <GlassButton plain @click="router.push('/preview')">
              <AppIcon name="eye" :size="17" />
            </GlassButton>
            <GlassButton plain @click="saveCurrent">
              <AppIcon name="save" :size="17" />
            </GlassButton>
          </div>
        </template>
      </GlassSurface>

      <!-- 载入 -->
      <GlassSurface class="lg-section">
        <div class="lg-title"><span>载入文件</span></div>
        <div class="lg-grid-2">
          <GlassButton block type="primary" :loading="busy" @click="openLocal">
            <AppIcon name="folder" :size="17" />
            <span class="btn-text">打开本地文件</span>
          </GlassButton>
          <GlassButton block plain @click="router.push('/convert')">
            <AppIcon name="layers" :size="17" />
            <span class="btn-text">生成图案</span>
          </GlassButton>
        </div>
        <div class="lg-dim lg-mt">支持 {{ GCODE_EXTENSIONS.join(' / ') }} 格式</div>
      </GlassSurface>

      <!-- 已保存 -->
      <GlassSurface class="lg-section">
        <div class="lg-title">
          <span>已保存文件</span>
          <GlassButton size="small" text type="primary" @click="reload">
            <AppIcon name="refresh" :size="16" />
          </GlassButton>
        </div>

        <div v-if="files.length === 0" class="lg-empty">暂无已保存文件</div>

        <div v-else class="saved-list">
          <div v-for="f in files" :key="f.id" class="saved-item">
            <div class="saved-item__main" @click="loadSaved(f)">
              <div class="saved-item__name">{{ f.name }}</div>
              <div class="saved-item__sub lg-dim">
                {{ f.lines.length }} 行
                <template v-if="f.meta?.widthMm"> · {{ f.meta.widthMm }}×{{ f.meta.heightMm }} mm</template>
                <template v-if="f.meta?.note"> · {{ f.meta.note }}</template>
              </div>
            </div>
            <GlassButton size="small" text type="danger" @click="remove(f)">
              <AppIcon name="trash" :size="16" />
            </GlassButton>
          </div>
        </div>
      </GlassSurface>
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

.stat {
  padding: 8px 10px;
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
  font-size: 13px;
  font-weight: 600;
}

.saved-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.saved-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px;
  background: var(--lg-panel-2);
  border-radius: 10px;
}

.saved-item__main {
  flex: 1;
  min-width: 0;
  cursor: pointer;
}

.saved-item__name {
  font-size: 13.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.saved-item__sub {
  font-size: 11.5px;
  margin-top: 2px;
}
</style>