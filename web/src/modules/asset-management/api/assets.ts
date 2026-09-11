import { requestApi } from '@/infrastructure/http/public'
import type {
  AssetBuilding,
  AssetBuildingForm,
  AssetEquipment,
  AssetEquipmentDetail,
  AssetEquipmentForm,
  AssetEquipmentQuery,
  AssetPage,
  AssetPoint,
  AssetPointUpdate,
  AssetSpace,
  AssetSpaceForm,
  AssetSystemGroup,
  AssetSystemGroupForm,
} from '../models/assets'

const basePath = '/v1/assets'
const encoded = (value: string) => encodeURIComponent(value)

export function listBuildings(params: { page: number; size: number; keyword?: string }) {
  return requestApi<AssetPage<AssetBuilding>>({ method: 'get', url: `${basePath}/buildings`, params })
}

export function getBuilding(buildingId: string) {
  return requestApi<AssetBuilding>({ method: 'get', url: `${basePath}/buildings/${encoded(buildingId)}` })
}

export function createBuilding(data: AssetBuildingForm) {
  return requestApi<AssetBuilding>({ method: 'post', url: `${basePath}/buildings`, data })
}

export function updateBuilding(buildingId: string, data: AssetBuildingForm) {
  return requestApi<AssetBuilding>({ method: 'put', url: `${basePath}/buildings/${encoded(buildingId)}`, data })
}

export function deleteBuilding(buildingId: string) {
  return requestApi<void>({ method: 'delete', url: `${basePath}/buildings/${encoded(buildingId)}` })
}

export function listSpaces(buildingId: string) {
  return requestApi<AssetSpace[]>({ method: 'get', url: `${basePath}/spaces`, params: { buildingId } })
}

export function createSpace(data: AssetSpaceForm) {
  return requestApi<AssetSpace>({ method: 'post', url: `${basePath}/spaces`, data })
}

export function updateSpace(spaceId: string, data: Omit<AssetSpaceForm, 'buildingId'>) {
  return requestApi<AssetSpace>({ method: 'put', url: `${basePath}/spaces/${encoded(spaceId)}`, data })
}

export function deleteSpace(spaceId: string) {
  return requestApi<void>({ method: 'delete', url: `${basePath}/spaces/${encoded(spaceId)}` })
}

export function listSystemGroups(params: { page: number; size: number; buildingId?: string; keyword?: string }) {
  return requestApi<AssetPage<AssetSystemGroup>>({ method: 'get', url: `${basePath}/system-groups`, params })
}

export function createSystemGroup(data: AssetSystemGroupForm) {
  return requestApi<AssetSystemGroup>({ method: 'post', url: `${basePath}/system-groups`, data })
}

export function updateSystemGroup(systemGroupId: string, data: Omit<AssetSystemGroupForm, 'buildingId' | 'systemCode'>) {
  return requestApi<AssetSystemGroup>({ method: 'put', url: `${basePath}/system-groups/${encoded(systemGroupId)}`, data })
}

export function deleteSystemGroup(systemGroupId: string) {
  return requestApi<void>({ method: 'delete', url: `${basePath}/system-groups/${encoded(systemGroupId)}` })
}

export function listEquipment(params: AssetEquipmentQuery) {
  return requestApi<AssetPage<AssetEquipment>>({ method: 'get', url: `${basePath}/equipment`, params })
}

export function getEquipment(equipmentId: string) {
  return requestApi<AssetEquipmentDetail>({ method: 'get', url: `${basePath}/equipment/${encoded(equipmentId)}` })
}

export function createEquipment(data: AssetEquipmentForm) {
  return requestApi<AssetEquipment>({ method: 'post', url: `${basePath}/equipment`, data })
}

export function updateEquipment(equipmentId: string, data: Omit<AssetEquipmentForm, 'typeCode' | 'productId'>) {
  return requestApi<AssetEquipment>({ method: 'put', url: `${basePath}/equipment/${encoded(equipmentId)}`, data })
}

export function deleteEquipment(equipmentId: string) {
  return requestApi<void>({ method: 'delete', url: `${basePath}/equipment/${encoded(equipmentId)}` })
}

export function listEquipmentPoints(equipmentId: string) {
  return requestApi<AssetPoint[]>({ method: 'get', url: `${basePath}/equipment/${encoded(equipmentId)}/points` })
}

export function updateEquipmentPoint(equipmentId: string, pointId: string, data: AssetPointUpdate) {
  return requestApi<AssetPoint>({
    method: 'put',
    url: `${basePath}/equipment/${encoded(equipmentId)}/points/${encoded(pointId)}`,
    data,
  })
}

export function deleteEquipmentPoint(equipmentId: string, pointId: string) {
  return requestApi<void>({
    method: 'delete',
    url: `${basePath}/equipment/${encoded(equipmentId)}/points/${encoded(pointId)}`,
  })
}
