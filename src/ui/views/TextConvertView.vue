<script setup lang="ts">
/** 文字转雕刻：Hershey 矢量字体 */
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import GcodePreview from '../components/GcodePreview.vue'
import { textToGcode } from '../../core/text/Hershey'
import type { GcodeFileData } from '../../core/gcode/GrblFile'
import { AppSettings } from '../../core/grbl/GrblConfig'
import { loadGcodeLines } from '../store'
import { saveGcodeFile } from '../storage'
import { toast } from '../utils'

const router = useRouter()

const text = ref('LASER')
const generated = ref<GcodeFileData | null>(null)
const busy = ref(false)

const opts = reactive({
  sizeMm: 20,
  orientation: 'horizontal' as 'horizontal' | 'vertical',
  bold: false,
  lineSpacing: 1.5,
  offsetX: 0,
  offsetY: 0,
  markSpeed: AppSettings.get<number>('Mark Speed', 1000),
  maxPower: AppSettings.get<number>('Max Power', 1000),
  pwm: AppSettings.get<boolean>('Support Hardware PWM', true),
  laserOn: AppSettings.get<string>('Laser On Command', 'M4'),
  laserOff: AppSettings.get<string>('Laser Off Command', 'M5')
})

const canGenerate = computed(() => text.value.trim().length > 0 && opts.sizeMm > 0)

function generate() {
  if (!canGenerate.value) {
    toast('请输入文字内容', 'error')
    return
  }
  busy.value = true
  try {
    const res = textToGcode({
      text: text.value,
      orientation: opts.orientation,
      sizeMm: opts.sizeMm,
      bold: opts.bold,
      lineSpacing: opts.lineSpacing,
      offsetX: opts.offsetX,
      offsetY: opts.offsetY,
      markSpeed: opts.markSpeed,
      laserOn: opts.laserOn,
      laserOff: opts.laserOff,
      pwm: opts.pwm,
      maxPower: opts.maxPower
    })
    const name = `文字-${text.value.trim().split('\n')[0].slice(0, 12) || 'text'}.gcode`
    generated.value = loadGcodeLines(name, res.lines)
    toast(`已生成 ${res.lines.length} 行 G 代码`, 'success')
  } catch (e) {
    toast(`生成失败：${String(e)}`, 'error')
  } finally {
    busy.value = false
  }
}

function save() {
  if (!generated.value) return
  saveGcodeFile(generated.value.name, generated.value.commands.map((c) => c.command), {
    kind: 'text'
  })
  toast('已保存到雕刻文件', 'success')
}

function goHome() {
  router.push('/home')
}

function onNum(key: 'sizeMm' | 'lineSpacing' | 'offsetX' | 'offsetY' | 'markSpeed' | 'maxPower', ev: Event) {
  const t = ev.target as HTMLInputElement
  const v = parseFloat(t.value)
  if (Number.isFinite(v)) opts[key] = v
}
</script>

<template>
  <div class="lg-page">
    <div class="lg-body">
      <!-- 文字内容 -->
      <section class="lg-section">
        <div class="lg-title"><span>文字内容</span></div>
        <var-input v-model="text" textarea :rows="3" placeholder="输入要雕刻的文字，支持换行" variant="outlined" />
      </section>

      <!-- 排版 -->
      <section class="lg-section">
        <div class="lg-title"><span>排版</span></div>

        <div class="lg-dim">字体朝向</div>
        <div class="steps lg-mt">
          <var-button
            size="small"
            :type="opts.orientation === 'horizontal' ? 'primary' : 'default'"
            :plain="opts.orientation !== 'horizontal'"
            @click="opts.orientation = 'horizontal'"
          >
            横向排版
          </var-button>
          <var-button
            size="small"
            :type="opts.orientation === 'vertical' ? 'primary' : 'default'"
            :plain="opts.orientation !== 'vertical'"
            @click="opts.orientation = 'vertical'"
          >
            纵向排版
          </var-button>
        </div>

        <div class="num-grid lg-mt">
          <div class="num-item">
            <span class="lg-dim">字号 / 字高 (mm)</span>
            <var-input :model-value="String(opts.sizeMm)" type="number" variant="outlined" @blur="onNum('sizeMm', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">行距倍率</span>
            <var-input :model-value="String(opts.lineSpacing)" type="number" variant="outlined" @blur="onNum('lineSpacing', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">起点 X (mm)</span>
            <var-input :model-value="String(opts.offsetX)" type="number" variant="outlined" @blur="onNum('offsetX', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">起点 Y (mm)</span>
            <var-input :model-value="String(opts.offsetY)" type="number" variant="outlined" @blur="onNum('offsetY', $event)" />
          </div>
        </div>

        <var-cell title="加粗" description="重复雕刻并微偏移，笔画更粗">
          <template #extra>
            <var-switch v-model="opts.bold" />
          </template>
        </var-cell>
      </section>

      <!-- 参数 -->
      <section class="lg-section">
        <div class="lg-title"><span>雕刻参数</span></div>
        <div class="num-grid">
          <div class="num-item">
            <span class="lg-dim">雕刻速度 (mm/min)</span>
            <var-input :model-value="String(opts.markSpeed)" type="number" variant="outlined" @blur="onNum('markSpeed', $event)" />
          </div>
          <div class="num-item">
            <span class="lg-dim">激光功率 S</span>
            <var-input :model-value="String(opts.maxPower)" type="number" variant="outlined" @blur="onNum('maxPower', $event)" />
          </div>
        </div>
        <var-cell title="硬件 PWM" description="使用 S 值控制激光功率">
          <template #extra>
            <var-switch v-model="opts.pwm" />
          </template>
        </var-cell>
      </section>

      <var-button block type="primary" :loading="busy" :disabled="!canGenerate" @click="generate">
        <AppIcon name="layers" :size="17" />
        <span class="btn-text">生成雕刻路径</span>
      </var-button>

      <!-- 预览 -->
      <section v-if="generated" class="lg-section lg-mt">
        <div class="lg-title">
          <span>路径预览</span>
          <span class="lg-dim">{{ generated.stats.totalLines }} 行</span>
        </div>
        <GcodePreview :preview="generated.preview" :bbox="generated.stats.bbox" :height="220" />
        <div class="lg-grid-2 lg-mt">
          <var-button block plain @click="save">
            <AppIcon name="save" :size="17" />
            <span class="btn-text">保存文件</span>
          </var-button>
          <var-button block type="primary" @click="goHome">
            <AppIcon name="play" :size="17" />
            <span class="btn-text">去雕刻</span>
          </var-button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.steps {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.num-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.num-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.btn-text {
  margin-left: 5px;
}
</style>