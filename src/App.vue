<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from './ui/components/AppIcon.vue'
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

const title = computed(() => (route.meta.title as string) ?? 'LaserGRBL')
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
    <var-app-bar
      class="lg-appbar"
      :title="title"
      title-position="center"
      :elevation="false"
      fixed
      placeholder
      safe-area-top
    >
      <template #left>
        <var-button v-if="canGoBack" text round @click="goBack">
          <AppIcon name="back" :size="22" />
        </var-button>
        <span v-else class="lg-brand">
          <AppIcon name="flame" :size="20" />
          <span>LaserGRBL</span>
        </span>
      </template>
      <template #right>
        <button class="lg-status" :class="`lg-status--${statusKind}`" @click="openConnect">
          <span class="lg-dot" />
          <span>{{ statusText }}</span>
        </button>
      </template>
    </var-app-bar>

    <main class="lg-app__main">
      <router-view v-slot="{ Component }">
        <keep-alive :include="['HomeView']">
          <component :is="Component" />
        </keep-alive>
      </router-view>
    </main>

    <var-bottom-navigation
      v-if="showNav"
      :active="activeTab"
      fixed
      safe-area
      active-color="#ff7a18"
      @change="onTabChange"
    >
      <var-bottom-navigation-item v-for="t in TABS" :key="t.name" :name="t.name" :label="t.label">
        <template #icon="{ active }">
          <AppIcon :name="t.icon" :size="22" :stroke-width="active ? 2.2 : 1.7" />
        </template>
      </var-bottom-navigation-item>
    </var-bottom-navigation>
  </div>
</template>

<style scoped>
.lg-app {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--lg-bg);
}

.lg-app__main {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.lg-brand {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 700;
  font-size: 14px;
  color: var(--lg-accent);
  padding-left: 4px;
}

.lg-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid transparent;
  background: transparent;
  color: inherit;
  font-size: 12px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 999px;
  margin-right: 4px;
}

.lg-status--ok {
  color: #23c55e;
  border-color: rgba(35, 197, 94, 0.35);
  background: rgba(35, 197, 94, 0.12);
}

.lg-status--warn {
  color: #f59e0b;
  border-color: rgba(245, 158, 11, 0.35);
  background: rgba(245, 158, 11, 0.12);
}

.lg-status--err {
  color: #ef4444;
  border-color: rgba(239, 68, 68, 0.35);
  background: rgba(239, 68, 68, 0.12);
}

.lg-status--idle {
  color: var(--lg-text-dim);
  border-color: var(--lg-border);
  background: var(--lg-panel-2);
}
</style>