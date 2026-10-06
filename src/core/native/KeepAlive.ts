/**
 * 后台保活封装：雕刻运行期间通过 Android 前台服务 + 常驻进度通知保持 App 存活。
 *
 * Web / 非 Android 环境下所有方法都是安全的 no-op，便于浏览器调试。
 */
import { Capacitor, registerPlugin } from '@capacitor/core'

/** 通知标题（需求固定） */
export const NOTIFICATION_TITLE = 'iGRBL 正在雕刻'

/** 文件名最大长度：超长时截断，避免通知正文被系统整段折叠 */
const MAX_NAME_LENGTH = 24

/** 进度变化阈值（百分比）：小于该值不触发原生通知刷新，避免刷爆通知 */
const PROGRESS_STEP = 1

/** 原生插件返回的通知状态 */
export type KeepAliveNotificationState = 'shown' | 'denied' | 'unsupported'

/** 原生 KeepAlive 插件接口（对应 KeepAlivePlugin.java） */
interface KeepAlivePlugin {
  start(options: { title?: string; text?: string; progress?: number }): Promise<KeepAliveResult>
  update(options: { text?: string; progress?: number }): Promise<KeepAliveResult>
  stop(): Promise<KeepAliveResult>
}

export interface KeepAliveResult {
  /** 前台服务是否已启动 */
  running: boolean
  /** 通知是否已在通知栏显示（denied 表示缺少 POST_NOTIFICATIONS 权限，服务仍在保活） */
  notification: KeepAliveNotificationState
  /** 降级原因（仅在通知不可见时返回） */
  reason?: string
}

/** Web 环境下的兜底结果 */
const NOOP_RESULT: KeepAliveResult = { running: false, notification: 'unsupported' }

const plugin = registerPlugin<KeepAlivePlugin>('KeepAlive')

/** 当前是否处于 Android 原生环境 */
function isAndroidNative(): boolean {
  try {
    return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android'
  } catch {
    return false
  }
}

/** 文件名截断：过长时保留前 N 个字符并追加省略号 */
export function truncateName(name: string): string {
  const trimmed = (name ?? '').trim()
  if (trimmed.length <= MAX_NAME_LENGTH) return trimmed
  return `${trimmed.slice(0, MAX_NAME_LENGTH)}…`
}

/** 百分比换算：total 为 0（或非法）时返回 0，避免除零产生 NaN */
export function percentOf(executed: number, total: number): number {
  if (!Number.isFinite(total) || total <= 0) return 0
  const value = (executed / total) * 100
  if (!Number.isFinite(value)) return 0
  return Math.max(0, Math.min(100, Math.round(value)))
}

/** 规整外部传入的百分比（0~100 整数） */
function clampPercent(percent: number): number {
  if (!Number.isFinite(percent)) return 0
  return Math.max(0, Math.min(100, Math.round(percent)))
}

/** 通知正文：`<文件名> · <百分比>%` */
export function formatText(name: string, percent: number): string {
  const label = truncateName(name)
  return label ? `${label} · ${clampPercent(percent)}%` : `${clampPercent(percent)}%`
}

// ---- 节流状态：仅在百分比变化时才真正通知原生层 ----
let active = false
let jobName = ''
let lastPercent = -1

/** 重新开始一个任务：重置节流状态（无论当前是否在运行） */
function resetThrottle(name: string): void {
  jobName = name ?? ''
  lastPercent = -1
}

/**
 * 开始保活：启动前台服务并显示常驻进度通知。
 * 可重复调用（幂等）：重复 start 不会产生多条通知。
 */
export async function startKeepAlive(name: string, percent = 0): Promise<KeepAliveResult> {
  resetThrottle(name)
  active = false
  if (!isAndroidNative()) return NOOP_RESULT
  const safePercent = clampPercent(percent)
  try {
    const result = await plugin.start({
      title: NOTIFICATION_TITLE,
      text: formatText(jobName, safePercent),
      progress: safePercent
    })
    active = true
    lastPercent = safePercent
    return result
  } catch (e) {
    // 原生调用失败（例如极端定制系统禁用前台服务）不影响雕刻流程
    active = false
    lastPercent = -1
    throw e
  }
}

/**
 * 更新进度：百分比变化未达到阈值时直接跳过，避免刷爆通知。
 * 注意：最后一个进度值仍会被真正发出（例如 99% → 100%），不会丢尾。
 */
export async function updateKeepAliveProgress(executed: number, total: number): Promise<void> {
  if (!isAndroidNative() || !active) return
  const percent = percentOf(executed, total)
  if (percent === lastPercent) return
  if (Math.abs(percent - lastPercent) < PROGRESS_STEP) return
  lastPercent = percent
  try {
    await plugin.update({ text: formatText(jobName, percent), progress: percent })
  } catch {
    // 通知刷新失败可忽略：保活本身不依赖通知
  }
}

/**
 * 结束保活：停止前台服务并移除通知（幂等）。
 * 任务结束 / 中止 / 断开 / 出错时都必须调用，避免残留僵尸通知。
 */
export async function stopKeepAlive(): Promise<void> {
  // Web / 非 Android 环境：不做任何原生调用（纯 no-op），避免浏览器调试报错
  if (!isAndroidNative()) {
    active = false
    resetThrottle('')
    return
  }
  active = false
  resetThrottle('')
  try {
    // 无论是否处于激活状态都调用一次：可清理上一次页面刷新 / 崩溃遗留的僵尸通知
    await plugin.stop()
  } catch {
    // 服务可能已被系统回收，忽略
  }
}

