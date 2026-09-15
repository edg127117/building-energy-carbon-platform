export { default as messages } from './locales/zh-CN'
export { routes } from './routes'
export { useAssetManagement } from './composables/use-asset-management'
export { useEquipmentReadings } from './composables/use-equipment-readings'
export { flattenSpaces } from './models/assets'
export type {
  AssetBuilding,
  AssetEquipment,
  AssetEquipmentDetail,
  AssetEquipmentReadings,
  AssetPointReading,
  AssetPoint,
  AssetSpace,
  AssetSystemGroup,
} from './models/assets'
