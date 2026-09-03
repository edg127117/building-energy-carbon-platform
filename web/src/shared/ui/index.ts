// 新界面统一使用 Element Plus；继承页面不经由此入口，避免两套组件库互相污染。
export {
  ElAlert, ElButton, ElCard, ElConfigProvider, ElDialog, ElEmpty, ElMenu, ElMenuItem,
  ElSkeleton, ElSubMenu, ElTag, ElTooltip, ElResult,
} from 'element-plus'
export { default as zhCn } from 'element-plus/es/locale/lang/zh-cn'
export { CircleAlert, CircleCheck, Info, Monitor, Maximize, Minimize, ArrowLeft, Clock, WifiOff } from 'lucide-vue-next'
