<script setup lang="ts">
/** Toast 渲染容器，挂在 App 根部。 */
import { toasts } from './toast'
</script>

<template>
  <Teleport to="body">
    <div class="g-toast-wrap">
      <div v-for="t in toasts" :key="t.id" class="g-toast" :class="`g-toast--${t.type}`">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
          <path v-if="t.type === 'success'" d="M5 13 L10 18 L19 6" />
          <path v-else-if="t.type === 'error'" d="M6 6 L18 18 M18 6 L6 18" />
          <path v-else-if="t.type === 'warning'" d="M12 4 L21 20 H3 Z M12 10 V14 M12 17 V17.5" />
          <path v-else d="M12 3 A9 9 0 1 0 12 21 A9 9 0 1 0 12 3 Z M12 11 V17 M12 7.4 V8.4" />
        </svg>
        <span>{{ t.message }}</span>
      </div>
    </div>
  </Teleport>
</template>
