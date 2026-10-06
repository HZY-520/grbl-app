/**
 * 主题：深色为默认，浅色可切换。
 * 只切换 <html data-theme>，所有颜色由 styles/glass.css 的 CSS 变量驱动。
 */
export type ThemeName = 'dark' | 'light'

export function applyTheme(theme: ThemeName) {
  document.documentElement.dataset.theme = theme
  document.documentElement.style.colorScheme = theme
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', theme === 'light' ? '#eef0f6' : '#05060a')
}
