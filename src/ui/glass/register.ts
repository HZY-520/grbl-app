/**
 * 注册 <liquid-glass> 自定义元素。
 *
 * simple-liquid-glass 是框架无关的 Web Component：
 * 内部用 `backdrop-filter: url(#feDisplacementMap)` 对「背景源」做真实光学折射
 * （位移贴图 + 边缘镜面高光 + 色散）。该渲染路径只在 Chromium 生效，
 * 而 Capacitor 的 Android WebView 正是 Chromium —— 因此折射在目标平台上有效，
 * 在不支持的环境会自动降级为普通毛玻璃。
 *
 * 这里只做副作用导入，元素注册一次即可（模块级幂等）。
 */
import 'simple-liquid-glass/web-component'

/** 背景源（极光画布）的元素 id，所有玻璃面都用它做折射源。 */
export const GLASS_SOURCE_ID = 'glass-aurora'

/** 供 backdrop-selector 使用的选择器。 */
export const GLASS_SOURCE_SELECTOR = `#${GLASS_SOURCE_ID}`
