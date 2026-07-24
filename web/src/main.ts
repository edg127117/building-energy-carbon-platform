import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'

// 创建Vue应用实例
const app = createApp(App)

app.use(createPinia())

// 使用路由
app.use(router)

app.use(Antd)

// 挂载应用
app.mount('#app')
