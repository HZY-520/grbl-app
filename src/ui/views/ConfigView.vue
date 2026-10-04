<script setup lang="ts">
/** 机器参数：读取并修改 GRBL 的 $ 设置项 */
import { onMounted, ref, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import {
  state,
  configEntries,
  settingInfo,
  readMachineConfig,
  writeMachineSetting,
  refreshConfigEntries
} from '../store'
import { toast, pickFile } from '../utils'
import { parseSettingsPreset } from '../../core/grbl/SettingsPreset'

/** 常见 GRBL 设置项的中文名称 */
const SETTING_NAMES: Record<number, string> = {
  0: 'X 轴步进分辨率',
  1: 'Y 轴步进分辨率',
  2: 'Z 轴步进分辨率',
  3: '步进脉冲宽度',
  4: '最大空移速度 (G0)',
  5: '最大进给速度 (G1)',
  6: '步进信号反转',
  7: '步进空闲延时',
  8: '加速度',
  9: '转角偏差',
  10: '圆弧分段精度',
  11: '最大圆弧半径偏差',
  12: '回零时 Z 轴清理距离',
  13: '回零时 Z 轴清理方向',
  20: '启用软限位',
  21: '启用硬限位',
  22: '回零方向反转',
  23: '回零进给速度',
  24: '回零快速速度',
  25: '回零脱开距离',
  26: '主轴/LASER 使能',
  27: '回零开关反转',
  28: '回零开关上拉',
  30: '最大主轴转速 / 激光功率',
  31: '最小主轴转速 / 激光功率',
  32: '激光模式',
  100: 'X 轴最大行程',
  101: 'Y 轴最大行程',
  102: 'Z 轴最大行程',
  110: 'X 轴最大速度',
  111: 'Y 轴最大速度',
  112: 'Z 轴最大速度',
  120: 'X 轴加速度',
  121: 'Y 轴加速度',
  122: 'Z 轴加速度',
  130: 'X 轴最大行程',
  131: 'Y 轴最大行程',
  132: 'Z 轴最大行程'
}

interface Entry {
  id: number
  value: number
  name: string
  unit: string
}

const entries = ref<Entry[]>([])
const drafts = ref<Record<number, string>>({})

function reload() {
  const list = configEntries().map(([id, value]) => {
    const info = settingInfo(id)
    return { id, value, name: SETTING_NAMES[id] ?? info[0] ?? '未知参数', unit: info[1] ?? '' }
  })
  entries.value = list
  const nextDrafts: Record<number, string> = {}
  for (const e of list) nextDrafts[e.id] = String(e.value)
  drafts.value = nextDrafts
}

function onRead() {
  readMachineConfig()
  toast('正在读取机器设置…', 'info')
}

function write(e: Entry) {
  const raw = drafts.value[e.id]
  const v = parseFloat(raw)
  if (!Number.isFinite(v)) {
    toast('请输入有效数值', 'error')
    return
  }
  writeMachineSetting(e.id, v)
  e.value = v
  toast(`已写入 $${e.id}=${v}`, 'success')
}

function resetDraft(e: Entry) {
  drafts.value[e.id] = String(e.value)
}

onMounted(reload)
watch(() => state.configRev, reload)

// 进入本页时若已连接且尚无数据，自动读取一次
onMounted(() => {
  if (state.connected && refreshConfigEntries().length === 0) readMachineConfig()
})

/** 从 .nc 预设文件导入的参数行 */
interface PresetRow {
  id: number
  name: string
  unit: string
  draft: string
}

const presetName = ref('')
const presetRows = ref<PresetRow[]>([])
const importing = ref(false)

/** 导入 .nc 预设：解析 $编号=值 行并生成预览 */
async function onImportPreset() {
  importing.value = true
  try {
    const file = await pickFile('.nc,.txt,text/plain')
    if (!file) return
    if (!file.text) {
      toast('无法读取该文件内容', 'error')
      return
    }
    const { entries, skipped } = parseSettingsPreset(file.text)
    if (entries.length === 0) {
      toast('未找到 GRBL 参数行（应为 $编号=值）', 'error')
      return
    }
    presetName.value = file.name
    presetRows.value = entries.map((e) => {
      const info = settingInfo(e.id)
      return {
        id: e.id,
        name: SETTING_NAMES[e.id] ?? info[0] ?? '未知参数',
        unit: info[1] ?? '',
        draft: String(e.value)
      }
    })
    toast(`已载入 ${entries.length} 项参数${skipped ? `，忽略 ${skipped} 行` : ''}`, 'success')
  } finally {
    importing.value = false
  }
}

function clearPreset() {
  presetName.value = ''
  presetRows.value = []
}

/** 逐条下发导入的参数到设备 */
function applyPreset() {
  if (!state.connected) {
    toast('未连接设备，无法下发参数', 'warning')
    return
  }
  if (presetRows.value.length === 0) return
  let count = 0
  for (const row of presetRows.value) {
    const v = parseFloat(row.draft)
    if (!Number.isFinite(v)) continue
    writeMachineSetting(row.id, v)
    count++
  }
  toast(`已下发 ${count} 项参数到设备`, 'success')
}
</script>

<template>
  <div class="lg-page">
    <div class="lg-body">
      <section class="lg-section">
        <div class="lg-title">
          <span>参数预设 (.nc)</span>
          <var-button size="small" type="primary" :loading="importing" @click="onImportPreset">
            <AppIcon name="folder" :size="16" />
            <span class="btn-text">导入</span>
          </var-button>
        </div>

        <div v-if="presetRows.length === 0" class="lg-dim">
          导入 GRBL 参数预设文件（.nc），格式为每行一条 <span class="lg-mono">$编号=值</span>。
          导入后可预览、编辑并一键下发到设备。
        </div>
        <template v-else>
          <div class="preset-head">
            <span class="lg-mono">{{ presetName }}</span>
            <span class="lg-dim">共 {{ presetRows.length }} 项</span>
          </div>
          <div class="cfg-list">
            <div v-for="row in presetRows" :key="row.id" class="cfg-item">
              <div class="cfg-item__head">
                <span class="cfg-item__name">{{ row.name }}</span>
                <span class="lg-dim lg-mono">${{ row.id }}<template v-if="row.unit"> · {{ row.unit }}</template></span>
              </div>
              <var-input v-model="row.draft" type="number" variant="outlined" />
            </div>
          </div>
          <div class="preset-actions">
            <var-button size="small" type="primary" :disabled="!state.connected" @click="applyPreset">
              下发到设备
            </var-button>
            <var-button size="small" @click="clearPreset">清除</var-button>
          </div>
        </template>
      </section>

      <section class="lg-section">
        <div class="lg-title">
          <span>机器参数</span>
          <var-button size="small" type="primary" :disabled="!state.connected" @click="onRead">
            <AppIcon name="refresh" :size="16" />
            <span class="btn-text">读取 $$</span>
          </var-button>
        </div>

        <div v-if="!state.connected" class="lg-empty">
          未连接设备，无法读取机器参数。
        </div>
        <div v-else-if="entries.length === 0" class="lg-empty">
          暂无参数数据，点击右上角"读取 $$"从设备获取。
        </div>
        <div v-else class="cfg-list">
          <div v-for="e in entries" :key="e.id" class="cfg-item">
            <div class="cfg-item__head">
              <span class="cfg-item__name">{{ e.name }}</span>
              <span class="lg-dim lg-mono">${{ e.id }}<template v-if="e.unit"> · {{ e.unit }}</template></span>
            </div>
            <div class="cfg-item__body">
              <var-input
                v-model="drafts[e.id]"
                type="number"
                variant="outlined"
                @blur="resetDraft(e)"
              />
              <var-button size="small" type="primary" @click="write(e)">写入</var-button>
            </div>
          </div>
        </div>
      </section>

      <section class="lg-section">
        <div class="lg-title"><span>说明</span></div>
        <div class="lg-dim">
          修改参数会立即写入设备 EEPROM。错误的步进分辨率或行程可能导致设备运动异常，请谨慎操作。
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.cfg-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.cfg-item {
  padding: 10px;
  background: var(--lg-panel-2);
  border-radius: 10px;
}

.cfg-item__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.cfg-item__name {
  font-size: 13.5px;
  font-weight: 600;
}

.cfg-item__body {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
  align-items: center;
}

.btn-text {
  margin-left: 5px;
}

.preset-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.preset-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
</style>