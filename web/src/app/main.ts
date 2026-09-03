import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import App from './App.vue'
import { createPlatformRouter } from './router'
import { applyTheme } from './providers/theme'
import { t } from '@/locales'

// 不导入继承入口的 Ant Design、Tailwind 或 HVAC 全局样式。
applyTheme('office-light')
document.title = t('terminology.systemName')
const app = createApp(App)
app.use(createPinia())
app.use(createPlatformRouter())
app.mount('#app')
