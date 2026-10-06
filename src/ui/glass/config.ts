/**
 * 玻璃渲染配置。
 *
 * refraction = false 时所有玻璃面降级为 CSS 毛玻璃（backdrop-filter），
 * 不再做 SVG 位移折射 —— 供低端设备 / 电量紧张时使用。
 * 由设置页的「高性能玻璃」开关驱动。
 */
import { reactive } from 'vue'

export const glassConfig = reactive({
  /** 是否启用真实光学折射 */
  refraction: true
})
