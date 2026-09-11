import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import App from './App.vue'
import { createPlatformRouter } from './router'
import { applyTheme } from './providers/theme'
import { t } from '@/locales'
import { installPlatformAuthentication } from '@/modules/auth/public'

applyTheme('office-light')
document.title = t('terminology.systemName')
const app = createApp(App)
const pinia = createPinia()
const router = createPlatformRouter()
app.use(pinia)
installPlatformAuthentication(router)
app.use(router)
app.mount('#app')
