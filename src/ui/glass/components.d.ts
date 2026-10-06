/**
 * 全局组件类型声明：让 vue-tsc 认识在 main.ts 里全局注册的玻璃组件，
 * 以及 simple-liquid-glass 提供的 <liquid-glass> 自定义元素。
 */
import type GlassAurora from './GlassAurora.vue'
import type GlassSurface from './GlassSurface.vue'
import type GlassButton from './GlassButton.vue'
import type GlassInput from './GlassInput.vue'
import type GlassSelect from './GlassSelect.vue'
import type GlassOption from './GlassOption.vue'
import type GlassSwitch from './GlassSwitch.vue'
import type GlassSlider from './GlassSlider.vue'
import type GlassCell from './GlassCell.vue'
import type GlassProgress from './GlassProgress.vue'
import type GlassAlert from './GlassAlert.vue'
import type GlassCollapse from './GlassCollapse.vue'
import type GlassCollapseItem from './GlassCollapseItem.vue'
import type GlassLoading from './GlassLoading.vue'
import type GlassNavBar from './GlassNavBar.vue'
import type GlassTabBar from './GlassTabBar.vue'
import type GlassTabItem from './GlassTabItem.vue'
import type GlassSegmented from './GlassSegmented.vue'
import type GlassToaster from './GlassToaster.vue'

declare module 'vue' {
  export interface GlobalComponents {
    GlassAurora: typeof GlassAurora
    GlassSurface: typeof GlassSurface
    GlassButton: typeof GlassButton
    GlassInput: typeof GlassInput
    GlassSelect: typeof GlassSelect
    GlassOption: typeof GlassOption
    GlassSwitch: typeof GlassSwitch
    GlassSlider: typeof GlassSlider
    GlassCell: typeof GlassCell
    GlassProgress: typeof GlassProgress
    GlassAlert: typeof GlassAlert
    GlassCollapse: typeof GlassCollapse
    GlassCollapseItem: typeof GlassCollapseItem
    GlassLoading: typeof GlassLoading
    GlassNavBar: typeof GlassNavBar
    GlassTabBar: typeof GlassTabBar
    GlassTabItem: typeof GlassTabItem
    GlassSegmented: typeof GlassSegmented
    GlassToaster: typeof GlassToaster
    /** simple-liquid-glass 的 Web Component（仅在 GlassSurface 内部使用） */
    'liquid-glass': Record<string, unknown>
  }
}

export {}
