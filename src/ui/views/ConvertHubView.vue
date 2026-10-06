<script setup lang="ts">
/** 图案生成入口 */
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'

const router = useRouter()

const ITEMS = [
  {
    path: '/convert/image',
    icon: 'image',
    title: '图片转雕刻',
    desc: '将照片、位图转换为激光雕刻 G 代码，支持抖动与多档预处理'
  },
  {
    path: '/convert/text',
    icon: 'text',
    title: '文字转雕刻',
    desc: '使用 Hershey 矢量字体生成文字路径，支持横向 / 纵向排版'
  },
  {
    path: '/convert/svg',
    icon: 'vector',
    title: 'SVG 转雕刻',
    desc: '导入矢量图，按轮廓生成路径，曲线自动离散为平滑折线'
  }
]

function goto(path: string) {
  router.push(path)
}
</script>

<template>
  <div class="lg-page">
    <div class="lg-body lg-body--nav">
      <GlassSurface v-for="item in ITEMS" :key="item.path" class="lg-section hub-card" @click="goto(item.path)">
        <div class="hub-card__icon"><AppIcon :name="item.icon" :size="26" /></div>
        <div class="hub-card__text">
          <div class="hub-card__title">{{ item.title }}</div>
          <div class="hub-card__desc lg-dim">{{ item.desc }}</div>
        </div>
        <AppIcon name="forward" :size="18" />
      </GlassSurface>

      <GlassSurface class="lg-section">
        <div class="lg-title"><span>使用流程</span></div>
        <ol class="flow">
          <li>选择图案类型并导入素材</li>
          <li>设置尺寸与雕刻参数（速度、功率）</li>
          <li>生成 G 代码并预览路径</li>
          <li>回到首页开始雕刻</li>
        </ol>
      </GlassSurface>

      <GlassSurface class="lg-section">
        <div class="lg-row lg-row--between">
          <span class="lg-dim">已有 G 代码文件？</span>
          <GlassButton size="small" plain @click="goto('/file')">
            <AppIcon name="folder" :size="16" />
            <span class="btn-text">打开文件</span>
          </GlassButton>
        </div>
      </GlassSurface>
    </div>
  </div>
</template>

<style scoped>
.hub-card {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}

.hub-card:active {
  opacity: 0.75;
}

.hub-card__icon {
  width: 46px;
  height: 46px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  color: var(--lg-accent);
  background: rgba(255, 122, 24, 0.12);
}

.hub-card__text {
  flex: 1;
  min-width: 0;
}

.hub-card__title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 3px;
}

.hub-card__desc {
  font-size: 12px;
  line-height: 1.5;
}

.flow {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  color: var(--lg-text-dim);
  line-height: 2;
}
</style>