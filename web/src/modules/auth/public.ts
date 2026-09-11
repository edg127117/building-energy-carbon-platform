// 认证通过模块公开入口供应用路由与外壳使用，不暴露内部 API 装配。
export { default as messages } from './locales/zh-CN'
export { useSession } from './stores/session'
export type { GrantedMenu } from './models/session'
export { default as LoginPage } from './pages/LoginPage.vue'
export { expireBrowserSession, installPlatformAuthentication } from './router'
export { default as PasswordSetupPage } from './pages/PasswordSetupPage.vue'
