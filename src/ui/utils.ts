/** 界面通用工具：文件选择、格式化、错误提示 */
import { Snackbar } from '@varlet/ui'
import { DetectedIssue } from '../core/grbl/types'

export interface PickedFile {
  name: string
  text: string
  dataUrl: string
  size: number
}

/** 打开系统文件选择框（Android WebView 支持 <input type=file>） */
export function pickFile(accept: string): Promise<PickedFile | null> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = accept
    input.style.display = 'none'
    document.body.appendChild(input)

    const cleanup = () => {
      if (input.parentNode) input.parentNode.removeChild(input)
    }

    input.onchange = () => {
      const file = input.files && input.files[0]
      if (!file) {
        cleanup()
        resolve(null)
        return
      }
      const reader = new FileReader()
      reader.onload = () => {
        const result = String(reader.result ?? '')
        if (/^data:/.test(result)) {
          const comma = result.indexOf(',')
          const isBase64 = result.slice(0, comma).includes('base64')
          resolve({
            name: file.name,
            dataUrl: result,
            text: isBase64 ? '' : decodeURIComponent(result.slice(comma + 1)),
            size: file.size
          })
        } else {
          resolve({ name: file.name, dataUrl: '', text: result, size: file.size })
        }
        cleanup()
      }
      reader.onerror = () => {
        Snackbar.error('读取文件失败')
        cleanup()
        resolve(null)
      }
      // 图片读为 dataURL，其余读为文本
      if (/^image\//.test(file.type)) reader.readAsDataURL(file)
      else reader.readAsText(file)
    }

    input.oncancel = () => {
      cleanup()
      resolve(null)
    }

    input.click()
  })
}

/** 把 dataURL 载入为 HTMLImageElement */
export function loadImage(dataUrl: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('图片解码失败'))
    img.src = dataUrl
  })
}

/** 数字格式化：最多 3 位小数 */
export function fmt(v: number, digits = 3): string {
  if (!Number.isFinite(v)) return '0'
  const r = Number(v.toFixed(digits))
  return String(r)
}

/** 时长格式化 */
export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return '--'
  const s = Math.round(seconds)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (h > 0) return `${h} 时 ${m} 分 ${sec} 秒`
  if (m > 0) return `${m} 分 ${sec} 秒`
  return `${sec} 秒`
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

/** 检测到的问题 → 中文提示 */
export const ISSUE_LABELS: Record<number, string> = {
  [DetectedIssue.Unknown]: '',
  [DetectedIssue.ManualReset]: '手动复位',
  [DetectedIssue.ManualDisconnect]: '手动断开',
  [DetectedIssue.ManualAbort]: '手动中止',
  [DetectedIssue.StopResponding]: '设备无响应',
  [DetectedIssue.UnexpectedReset]: '意外复位',
  [DetectedIssue.UnexpectedDisconnect]: '意外断开',
  [DetectedIssue.MachineAlarm]: '机器报警'
}

export function issueLabel(issue: number): string {
  return ISSUE_LABELS[issue] ?? '未知异常'
}

export function toast(message: string, type: 'success' | 'error' | 'warning' | 'info' = 'info') {
  if (type === 'success') Snackbar.success(message)
  else if (type === 'error') Snackbar.error(message)
  else if (type === 'warning') Snackbar.warning(message)
  else Snackbar(message)
}