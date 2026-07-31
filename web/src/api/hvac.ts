import { http } from '@/utils/request'
import type { ApiResult } from '@/types/api'
import type {
  Building,
  HvacLatestResponse,
  HvacSnapshotResponse,
  PageResult,
} from '@/types/hvac'

/**
 * 查询当前用户已经获权的建筑。
 *
 * 这里使用 `/building/list`，不使用“可申请建筑”接口，避免页面展示用户尚无权
 * 查询快照的建筑。
 */
export async function listAccessibleBuildings(): Promise<Building[]> {
  const response = await http.get<ApiResult<PageResult<Building>>>(
    '/building/list',
    { params: { page: 1, size: 100 } },
  )
  return response.data.data.records
}

/**
 * 查询所选建筑的 19 测点最新分钟快照。
 */
export async function getHvacSnapshot(
  buildingId: string,
): Promise<HvacSnapshotResponse> {
  const response = await http.get<ApiResult<HvacSnapshotResponse>>(
    `/hvac/buildings/${encodeURIComponent(buildingId)}/snapshot`,
  )
  return response.data.data
}

/**
 * 查询所选建筑的四个最新性能指标及其真实计算状态。
 */
export async function getLatestHvacIndicators(
  buildingId: string,
): Promise<HvacLatestResponse> {
  const response = await http.get<ApiResult<HvacLatestResponse>>(
    `/hvac/buildings/${encodeURIComponent(buildingId)}/indicators/latest`,
  )
  return response.data.data
}
