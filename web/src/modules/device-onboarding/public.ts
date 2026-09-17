export { default as messages } from './locales/zh-CN'
export { routes } from './routes'
export { getDeviceProduct, listDeviceProducts } from './api/onboarding'
export type { DeviceProductDetail, DeviceProductListItem, ProductPointTemplate } from './models/onboarding'

export { default as ProductWorkspace } from './components/ProductWorkspace.vue'
export { listEquipmentTypes } from './api/onboarding'
export type { EquipmentTypeOption } from './models/onboarding'
