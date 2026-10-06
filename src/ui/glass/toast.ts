/**
 * 玻璃 Toast —— 替换 Varlet 的 Snackbar。
 * 保持与 utils.toast(message, type) 相同的调用方式，页面代码无需改动。
 */
import { reactive } from 'vue'

export interface ToastItem {
  id: number
  message: string
  type: 'success' | 'error' | 'warning' | 'info'
}

export const toasts = reactive<ToastItem[]>([])

let seq = 0

export function showToast(
  message: string,
  type: ToastItem['type'] = 'info',
  duration = 2200
) {
  const id = ++seq
  toasts.push({ id, message, type })
  window.setTimeout(() => {
    const i = toasts.findIndex((t) => t.id === id)
    if (i >= 0) toasts.splice(i, 1)
  }, duration)
}
