<script setup lang="ts">
/** 关于页面 */
import AppIcon from '../components/AppIcon.vue'

const FEATURES = [
  'GRBL 固件通讯与实时状态监控',
  'USB OTG 直连激光雕刻机',
  '图片转雕刻（线条 / 抖动 / 轮廓描线 / 中心线走线）',
  '中英文文字雕刻（Hershey 单线 / 轮廓描线 / 中心线走线）',
  'SVG 矢量雕刻（轮廓走线 / 填充）',
  '首次使用安装向导与行程自适应',
  '测试激光与点动控制',
  '机器参数 ($) 读写与串口终端'
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
      <section class="lg-section lg-center">
        <div class="about-logo">
          <AppIcon name="flame" :size="44" />
        </div>
        <div class="about-name">iGRBL</div>
        <div class="lg-dim">Android 激光雕刻控制 · v1.0.0</div>
      </section>

      <!-- 作者署名 -->
      <section class="lg-section">
        <div class="lg-title"><span>作者</span></div>
        <div class="lg-kv">
          <span class="lg-kv__k">作者</span>
          <span class="lg-kv__v">{{ AUTHOR }}</span>
        </div>
        <div class="lg-kv">
          <span class="lg-kv__k">邮箱</span>
          <span class="lg-kv__v lg-mono">{{ EMAIL }}</span>
        </div>
        <var-button class="lg-mt" block plain type="primary" @click="openLink(`mailto:${EMAIL}`)">
          <AppIcon name="link" :size="16" />
          <span class="btn-text">发送邮件</span>
        </var-button>
      </section>

      <section class="lg-section">
        <div class="lg-title"><span>功能</span></div>
        <div v-for="(f, i) in FEATURES" :key="i" class="feat">
          <AppIcon name="check" :size="16" />
          <span>{{ f }}</span>
        </div>
      </section>

      <!-- 开源地址 -->
      <section class="lg-section">
        <div class="lg-title"><span>开源地址</span></div>
        <var-button class="lg-mb" block plain type="primary" @click="openLink(REPO)">
          <AppIcon name="link" :size="16" />
          <span class="btn-text">本项目源码</span>
        </var-button>
        <div class="lg-dim repo-url">{{ REPO }}</div>

        <div class="lg-title lg-mt"><span>项目来源</span></div>
        <div class="lg-dim">
          本应用基于开源项目 LaserGRBL 移植，聚焦激光雕刻相关完整功能。
        </div>
        <var-button class="lg-mt" block plain @click="openLink(UPSTREAM)">
          <AppIcon name="link" :size="16" />
          <span class="btn-text">LaserGRBL 原始项目</span>
        </var-button>
        <var-button class="lg-mt" block plain @click="openLink('https://github.com/varletjs/varlet')">
          <AppIcon name="link" :size="16" />
          <span class="btn-text">Varlet UI 组件库</span>
        </var-button>
      </section>

      <section class="lg-section">
        <div class="lg-title"><span>技术栈</span></div>
        <div class="lg-kv">
          <span class="lg-kv__k">应用框架</span>
          <span class="lg-kv__v">Capacitor + Vue 3</span>
        </div>
        <div class="lg-kv">
          <span class="lg-kv__k">界面组件</span>
          <span class="lg-kv__v">Varlet UI</span>
        </div>
        <div class="lg-kv">
          <span class="lg-kv__k">设备通讯</span>
          <span class="lg-kv__v">usb-serial-for-android</span>
        </div>
      </section>

      <section class="lg-section lg-center lg-dim">
        遵循 LaserGRBL 的开源许可协议发布（GPL-3.0）
      </section>
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

.btn-text {
  margin-left: 5px;
}
</style>