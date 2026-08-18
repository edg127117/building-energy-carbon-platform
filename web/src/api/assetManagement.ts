import { http } from '@/utils/request'
import type { ApiResult } from '@/types/api'
import type {
  AssetBuildingDetail,
  AssetBuildingCreateRequest,
  AssetBuildingQuery,
  AssetBuildingUpdateRequest,
  AssetBuildingView,
  AssetDataPointUpdateRequest,
  AssetDataPointView,
  AssetEquipmentDetail,
  AssetEquipmentCreateRequest,
  AssetEquipmentQuery,
  AssetEquipmentUpdateRequest,
  AssetEquipmentView,
  AssetPageResponse,
  AssetSpaceCreateRequest,
  AssetSpaceUpdateRequest,
  AssetSpaceView,
  AssetSystemGroupQuery,
  AssetSystemGroupCreateRequest,
  AssetSystemGroupUpdateRequest,
  AssetSystemGroupView,
} from '@/types/assets'

const BASE_PATH = '/v1/assets'
const data = <T>(response: { data: ApiResult<T> }): T => response.data.data
const pathId = (id: string) => encodeURIComponent(id)

/**
 * 资产管理 API 只承接 V1 DTO，与旧实体型接口完全隔离。
 * 鉴权、HTTP 错误和业务失败由共享客户端统一处理，页面不会接触 Axios 响应对象。
 */
export async function pageAssetBuildings(query: AssetBuildingQuery): Promise<AssetPageResponse<AssetBuildingView>> {
  return data(await http.get<ApiResult<AssetPageResponse<AssetBuildingView>>>(`${BASE_PATH}/buildings`, { params: query }))
}

export async function getAssetBuilding(buildingId: string): Promise<AssetBuildingDetail> {
  return data(await http.get<ApiResult<AssetBuildingDetail>>(`${BASE_PATH}/buildings/${pathId(buildingId)}`))
}

export async function createAssetBuilding(request: AssetBuildingCreateRequest): Promise<AssetBuildingView> {
  return data(await http.post<ApiResult<AssetBuildingView>>(`${BASE_PATH}/buildings`, request))
}

export async function updateAssetBuilding(buildingId: string, request: AssetBuildingUpdateRequest): Promise<AssetBuildingView> {
  return data(await http.put<ApiResult<AssetBuildingView>>(`${BASE_PATH}/buildings/${pathId(buildingId)}`, request))
}

export async function deleteAssetBuilding(buildingId: string): Promise<void> {
  data(await http.delete<ApiResult<void>>(`${BASE_PATH}/buildings/${pathId(buildingId)}`))
}

export async function listAssetSpaces(buildingId: string): Promise<AssetSpaceView[]> {
  return data(await http.get<ApiResult<AssetSpaceView[]>>(`${BASE_PATH}/spaces`, { params: { buildingId } }))
}

export async function createAssetSpace(request: AssetSpaceCreateRequest): Promise<AssetSpaceView> {
  return data(await http.post<ApiResult<AssetSpaceView>>(`${BASE_PATH}/spaces`, request))
}

export async function updateAssetSpace(spaceId: string, request: AssetSpaceUpdateRequest): Promise<AssetSpaceView> {
  return data(await http.put<ApiResult<AssetSpaceView>>(`${BASE_PATH}/spaces/${pathId(spaceId)}`, request))
}

export async function deleteAssetSpace(spaceId: string): Promise<void> {
  data(await http.delete<ApiResult<void>>(`${BASE_PATH}/spaces/${pathId(spaceId)}`))
}

export async function pageAssetSystemGroups(query: AssetSystemGroupQuery): Promise<AssetPageResponse<AssetSystemGroupView>> {
  return data(await http.get<ApiResult<AssetPageResponse<AssetSystemGroupView>>>(`${BASE_PATH}/system-groups`, { params: query }))
}

export async function createAssetSystemGroup(request: AssetSystemGroupCreateRequest): Promise<AssetSystemGroupView> {
  return data(await http.post<ApiResult<AssetSystemGroupView>>(`${BASE_PATH}/system-groups`, request))
}

export async function updateAssetSystemGroup(systemGroupId: string, request: AssetSystemGroupUpdateRequest): Promise<AssetSystemGroupView> {
  return data(await http.put<ApiResult<AssetSystemGroupView>>(`${BASE_PATH}/system-groups/${pathId(systemGroupId)}`, request))
}

export async function deleteAssetSystemGroup(systemGroupId: string): Promise<void> {
  data(await http.delete<ApiResult<void>>(`${BASE_PATH}/system-groups/${pathId(systemGroupId)}`))
}

export async function pageAssetEquipment(query: AssetEquipmentQuery): Promise<AssetPageResponse<AssetEquipmentView>> {
  return data(await http.get<ApiResult<AssetPageResponse<AssetEquipmentView>>>(`${BASE_PATH}/equipment`, { params: query }))
}

export async function getAssetEquipment(equipmentId: string): Promise<AssetEquipmentDetail> {
  return data(await http.get<ApiResult<AssetEquipmentDetail>>(`${BASE_PATH}/equipment/${pathId(equipmentId)}`))
}

export async function createAssetEquipment(request: AssetEquipmentCreateRequest): Promise<AssetEquipmentView> {
  return data(await http.post<ApiResult<AssetEquipmentView>>(`${BASE_PATH}/equipment`, request))
}

export async function updateAssetEquipment(equipmentId: string, request: AssetEquipmentUpdateRequest): Promise<AssetEquipmentView> {
  return data(await http.put<ApiResult<AssetEquipmentView>>(`${BASE_PATH}/equipment/${pathId(equipmentId)}`, request))
}

export async function deleteAssetEquipment(equipmentId: string): Promise<void> {
  data(await http.delete<ApiResult<void>>(`${BASE_PATH}/equipment/${pathId(equipmentId)}`))
}

export async function listAssetEquipmentPoints(equipmentId: string): Promise<AssetDataPointView[]> {
  return data(await http.get<ApiResult<AssetDataPointView[]>>(`${BASE_PATH}/equipment/${pathId(equipmentId)}/points`))
}

export async function updateAssetEquipmentPoint(
  equipmentId: string,
  pointId: string,
  request: AssetDataPointUpdateRequest,
): Promise<AssetDataPointView> {
  return data(await http.put<ApiResult<AssetDataPointView>>(
    `${BASE_PATH}/equipment/${pathId(equipmentId)}/points/${pathId(pointId)}`,
    request,
  ))
}

export async function deleteAssetEquipmentPoint(equipmentId: string, pointId: string): Promise<void> {
  data(await http.delete<ApiResult<void>>(`${BASE_PATH}/equipment/${pathId(equipmentId)}/points/${pathId(pointId)}`))
}
