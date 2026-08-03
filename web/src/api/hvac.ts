import { http } from '@/utils/request'
import type { ApiResult } from '@/types/api'
import type {
  Building,
  HvacLatestResponse,
  HvacSnapshotResponse,
  PageResult,
} from '@/types/hvac'

/**
 * 查询当前用户已经获权的建筑首个分页，最多返回 100 条。
 *
 * 请求经过统一拦截器注入 JWT；后端 BuildingController 依据 MySQL/Redis 中的建筑
 * 范围过滤结果。这里不使用“可申请建筑”接口，避免页面展示用户尚无权查询快照的建筑。
 */
export async function listAccessibleBuildings(): Promise<Building[]> {
  const response = await http.get<ApiResult<PageResult<Building>>>(
    '/building/list',
    { params: { page: 1, size: 100 } },
  )
  return response.data.data.records
}

/**
 * 查询所选建筑全部在线测点各自的最新冻结分钟快照。
 *
 * 后端 HvacQueryController 校验建筑权限，HvacQueryService 合并 MySQL 测点元数据与
 * TDengine 分钟行；领域映射层再从响应中提取页面冻结的 19 个展示槽位。
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
 * 查询所选建筑全部活动指标的最新计算状态。
 *
 * 后端先读 Redis，缓存未命中时回退 TDengine；领域映射层只选取页面展示的四个
 * 稳定指标编码，并保留失败原因与缺失输入。
 */
export async function getLatestHvacIndicators(
  buildingId: string,
): Promise<HvacLatestResponse> {
  const response = await http.get<ApiResult<HvacLatestResponse>>(
    `/hvac/buildings/${encodeURIComponent(buildingId)}/indicators/latest`,
  )
  return response.data.data
}
