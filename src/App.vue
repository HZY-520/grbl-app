<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from './ui/components/AppIcon.vue'
import SetupWizard from './ui/components/SetupWizard.vue'
import { applyTheme } from './ui/theme'
import { state, STATUS_LABELS, MacStatus } from './ui/store'

const route = useRoute()
const router = useRouter()

const TABS = [
  { name: 'home', label: '首页', icon: 'home', path: '/home' },
  { name: 'convert', label: '图案', icon: 'layers', path: '/convert' },
  { name: 'file', label: '文件', icon: 'file', path: '/file' },
  { name: 'jog', label: '控制', icon: 'move', path: '/jog' },
  { name: 'settings', label: '设置', icon: 'settings', path: '/settings' }
] as const

const title = computed(() => (route.meta.title as string) ?? 'iGRBL')
const activeTab = computed(() => route.meta.tab as string | undefined)
const showNav = computed(() => !!route.meta.tab)
const canGoBack = computed(() => !showNav.value && route.path !== '/home')

const statusKind = computed(() => {
  if (state.connecting) return 'warn'
  if (!state.connected) return 'idle'
  if (state.status === MacStatus.Alarm) return 'err'
  if (state.status === MacStatus.Run || state.status === MacStatus.Jog) return 'ok'
  if (state.status === MacStatus.Hold || state.status === MacStatus.Door) return 'warn'
  return 'ok'
})

const statusText = computed(() => STATUS_LABELS[state.status] ?? String(state.status))

function onTabChange(name: string | number) {
  const tab = TABS.find((t) => t.name === name)
  if (tab && route.path !== tab.path) router.push(tab.path)
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/home')
}

function openConnect() {
  router.push('/connect')
}

onMounted(() => applyTheme(state.theme))
watch(
  () => state.theme,
  (t) => applyTheme(t)
)
</script>

<template>
  <div class="lg-app">
    <!-- 极光背景：玻璃的折射内容来源 -->
    <GlassAurora />

    <GlassNavBar :title="title">
      <template #left>
        <GlassButton v-if="canGoBack" text round size="small" @click="goBack">
          <AppIcon name="back" :size="22" />
        </GlassButton>
        <span v-else class="g-brand">
          <AppIcon name="flame" :size="20" />
          <span>iGRBL</span>
        </span>
      </template>
      <template #right>
        <button class="lg-status" :class="`lg-status--${statusKind}`" @click="openConnect">
          <span class="lg-dot" />
          <span>{{ statusText }}</span>
        </button>
      </template>
    </GlassNavBar>

    <main class="lg-app__main">
      <router-view v-slot="{ Component }">
        <keep-alive :include="['HomeView']">
          <component :is="Component" />
        </keep-alive>
      </router-view>
    </main>

    <GlassTabBar v-if="showNav" :active="activeTab as string" @change="onTabChange">
      <GlassTabItem v-for="t in TABS" :key="t.name" :name="t.name" :label="t.label">
        <template #icon="{ active }">
          <AppIcon :name="t.icon" :size="22" :stroke-width="active ? 2.2 : 1.7" />
        </template>
      </GlassTabItem>
    </GlassTabBar>

    <!-- 首次连接新设备的初始化向导 -->
    <SetupWizard v-if="state.needsSetup" :device-id="state.setupDeviceId" />

    <!-- 全局 Toast -->
    <GlassToaster />
  </div>
</template>
