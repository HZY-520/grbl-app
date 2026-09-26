/** Varlet 主题变量的环境声明（包内未提供该深层路径的类型声明） */
declare module '@varlet/ui/es/themes/dark' {
  const vars: Record<string, string>
  export default vars
}

declare module '@varlet/ui/es/themes/index' {
  const themes: {
    dark: Record<string, string>
    md3Light: Record<string, string>
    md3Dark: Record<string, string>
    toViewport: (size: number) => string
    toRem: (size: number) => string
  }
  export default themes
}