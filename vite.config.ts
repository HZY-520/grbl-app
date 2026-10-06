import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          // simple-liquid-glass 提供的 Web Component，交给浏览器自定义元素机制处理，
          // 不要让 Vue 当成未注册组件去解析。
          isCustomElement: (tag) => tag === 'liquid-glass'
        }
      }
    })
  ],
  base: './',
  build: {
    outDir: 'dist',
    target: 'es2020',
    chunkSizeWarningLimit: 4096
  },
  server: {
    host: '0.0.0.0',
    port: 5173
  }
})
