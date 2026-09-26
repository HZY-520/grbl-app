import { createApp } from 'vue'
import Varlet from '@varlet/ui'
import '@varlet/ui/es/style'
import './styles/global.css'
import App from './App.vue'
import { router } from './router'
import { applyTheme } from './ui/theme'
import { state } from './ui/store'

// 应用主题（默认深色）
applyTheme(state.theme)

const app = createApp(App)
app.use(Varlet)
app.use(router)
app.mount('#app')