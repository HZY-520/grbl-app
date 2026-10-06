import { createApp } from 'vue'
import './styles/glass.css'
import App from './App.vue'
import { router } from './router'
import { installGlass } from './ui/glass'
import { applyTheme } from './ui/theme'
import { state } from './ui/store'

// 应用主题（默认深色）
applyTheme(state.theme)

const app = createApp(App)
installGlass(app)
app.use(router)
app.mount('#app')
