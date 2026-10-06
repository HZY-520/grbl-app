<script setup lang="ts">
/** 关于页面 */
import AppIcon from '../components/AppIcon.vue'
import { APP_VERSION } from '../version'

const FEATURES = [
  'iOS 26 液态玻璃界面（真实光学折射，支持浅色 / 深色）',
  'GRBL 固件通讯与实时状态监控',
  'USB OTG 直连激光雕刻机',
  '图片转雕刻（线条 / 抖动 / 轮廓描线 / 中心线走线 / 智能识别）',
  '中英文文字雕刻（Hershey 单线 / 轮廓描线 / 中心线走线 / 智能识别）',
  'SVG 矢量雕刻（轮廓提取 / 中心线描线 / 智能识别）',
  '路径预览动画随雕刻进度实时推进并标注尺寸',
  '雕刻时前台服务保活后台并显示进度通知',
  '首次使用安装向导与行程自适应',
  '测试激光与点动控制',
  '机器参数 ($) 读写、.nc 预设导入与串口终端'
]

const AUTHOR = '离愁Lico'
const EMAIL = '2197958514@qq.com'
const REPO = 'https://github.com/HZY-520/grbl-app'
const UPSTREAM = 'https://github.com/arkypita/LaserGRBL'

function openLink(url: string) {
  window.open(url, '_blank')
}
</script>

<template>
  <div class="lg-page">
    <div class="lg-body">
      <GlassSurface class="lg-section lg-center">
        <div class="about-logo">
          <AppIcon name="flame" :size="44" />
        </div>
        <div class="about-name">iGRBL</div>
        <div class="lg-dim">Android 激光雕刻控制 · v{{ APP_VERSION }}</div>
      </GlassSurface>

      <!-- 作者署名 -->
      <GlassSurface class="lg-section">
        <div class="lg-title"><span>作者</span></div>
        <div class="lg-kv">
          <span class="lg-kv__k">作者</span>
          <span class="lg-kv__v">{{ AUTHOR }}</span>
        </div>
        <div class="lg-kv">
          <span class="lg-kv__k">邮箱</span>
          <span class="lg-kv__v lg-mono">{{ EMAIL }}</span>
        </div>
        <GlassButton class="lg-mt" block plain type="primary" @click="openLink(`mailto:${EMAIL}`)">
          <AppIcon name="link" :size="16" />
          <span class="btn-text">发送邮件</span>
        </GlassButton>
      </GlassSurface>

      <GlassSurface class="lg-section">
        <div class="lg-title"><span>功能</span></div>
        <div v-for="(f, i) in FEATURES" :key="i" class="feat">
          <AppIcon name="check" :size="16" />
          <span>{{ f }}</span>
        </div>
      </GlassSurface>

      <!-- 开源地址 -->
      <GlassSurface class="lg-section">
        <div class="lg-title"><span>开源地址</span></div>
        <GlassButton class="lg-mb" block plain type="primary" @click="openLink(REPO)">
          <AppIcon name="link" :size="16" />
          <span class="btn-text">本项目源码</span>
        </GlassButton>
        <div class="lg-dim repo-url">{{ REPO }}</div>

        <div class="lg-title lg-mt"><span>项目来源</span></div>
        <div class="lg-dim">
          本应用基于开源项目 LaserGRBL 移植，聚焦激光雕刻相关完整功能。
        </div>
        <GlassButton class="lg-mt" block plain @click="openLink(UPSTREAM)">
          <AppIcon name="link" :size="16" />
          <span class="btn-text">LaserGRBL 原始项目</span>
        </GlassButton>
        <GlassButton class="lg-mt" block plain @click="openLink('https://github.com/lucaperullo/simple-liquid-glass')">
          <AppIcon name="link" :size="16" />
          <span class="btn-text">simple-liquid-glass 折射引擎</span>
        </GlassButton>
      </GlassSurface>

      <GlassSurface class="lg-section">
        <div class="lg-title"><span>技术栈</span></div>
        <div class="lg-kv">
          <span class="lg-kv__k">应用框架</span>
          <span class="lg-kv__v">Capacitor + Vue 3</span>
        </div>
        <div class="lg-kv">
          <span class="lg-kv__k">界面组件</span>
          <span class="lg-kv__v">Liquid Glass（自研玻璃组件库）</span>
        </div>
        <div class="lg-kv">
          <span class="lg-kv__k">折射引擎</span>
          <span class="lg-kv__v">simple-liquid-glass</span>
        </div>
        <div class="lg-kv">
          <span class="lg-kv__k">设备通讯</span>
          <span class="lg-kv__v">usb-serial-for-android</span>
        </div>
      </GlassSurface>

      <GlassSurface class="lg-section lg-center lg-dim">
        遵循 LaserGRBL 的开源许可协议发布（GPL-3.0）
      </GlassSurface>
    </div>
  </div>
</template>

<style scoped>
.about-logo {
  display: inline-flex;
  padding: 16px;
  border-radius: 20px;
  background: rgba(255, 122, 24, 0.12);
  color: var(--lg-accent);
  margin-bottom: 10px;
}

.about-name {
  font-size: 20px;
  font-weight: 700;
  color: var(--lg-accent);
}

.feat {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 13.5px;
  color: var(--lg-text-dim);
}

.feat span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.repo-url {
  text-align: center;
  overflow-wrap: anywhere;
}
</style>