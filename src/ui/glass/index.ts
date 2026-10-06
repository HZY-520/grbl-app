/**
 * 玻璃组件库统一出口。
 *
 * 组件在 main.ts 中全局注册，因此页面模板里可以直接写 <GlassButton>…
 * 全局注册的好处：各页面不需要重复写 import，模板改动最小。
 */
import type { App } from 'vue'

import './register'

import GlassAurora from './GlassAurora.vue'
import GlassSurface from './GlassSurface.vue'
import GlassButton from './GlassButton.vue'
import GlassInput from './GlassInput.vue'
import GlassSelect from './GlassSelect.vue'
import GlassOption from './GlassOption.vue'
import GlassSwitch from './GlassSwitch.vue'
import GlassSlider from './GlassSlider.vue'
import GlassCell from './GlassCell.vue'
import GlassProgress from './GlassProgress.vue'
import GlassAlert from './GlassAlert.vue'
import GlassCollapse from './GlassCollapse.vue'
import GlassCollapseItem from './GlassCollapseItem.vue'
import GlassLoading from './GlassLoading.vue'
import GlassNavBar from './GlassNavBar.vue'
import GlassTabBar from './GlassTabBar.vue'
import GlassTabItem from './GlassTabItem.vue'
import GlassSegmented from './GlassSegmented.vue'
import GlassToaster from './GlassToaster.vue'

const components = {
  GlassAurora,
  GlassSurface,
  GlassButton,
  GlassInput,
  GlassSelect,
  GlassOption,
  GlassSwitch,
  GlassSlider,
  GlassCell,
  GlassProgress,
  GlassAlert,
  GlassCollapse,
  GlassCollapseItem,
  GlassLoading,
  GlassNavBar,
  GlassTabBar,
  GlassTabItem,
  GlassSegmented,
  GlassToaster
}

export function installGlass(app: App) {
  for (const [name, comp] of Object.entries(components)) {
    app.component(name, comp)
  }
}

export { showToast } from './toast'
export { toasts } from './toast'
export { GLASS_SOURCE_ID, GLASS_SOURCE_SELECTOR } from './register'
