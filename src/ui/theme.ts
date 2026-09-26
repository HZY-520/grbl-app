/** Varlet 主题应用：深色为默认，可切换浅色 */
import { StyleProvider } from '@varlet/ui'
import darkTheme from '@varlet/ui/es/themes/dark'

/** 应用强调色：激光橙 */
const ACCENT: Record<string, string> = {
  '--hsl-primary': '24, 100%, 55%',
  '--color-primary': '#ff7a18',
  '--hsl-primary-container': '24, 100%, 22%',
  '--color-primary-container': '#4a2609',
  '--hsl-on-primary': '0, 0%, 100%',
  '--color-on-primary': '#ffffff',
  '--hsl-on-primary-container': '24, 100%, 88%',
  '--color-on-primary-container': '#ffe0c7',
  '--hsl-success': '150, 70%, 42%',
  '--color-success': '#23c55e'
}

export function applyTheme(theme: 'dark' | 'light') {
  if (theme === 'light') {
    StyleProvider({ ...ACCENT, '--color-body': '#f4f4f6', '--hsl-body': '240, 12%, 96%' })
    document.documentElement.dataset.theme = 'light'
  } else {
    StyleProvider({ ...(darkTheme as Record<string, string>), ...ACCENT })
    document.documentElement.dataset.theme = 'dark'
  }
  document.documentElement.style.colorScheme = theme
}