import { http } from '@/utils/request'
import type { ApiResult } from '@/types/api'
import type {
  Building,
  HvacCalculationDetail,
  HvacIndicatorTrendResponse,
  HvacLatestResponse,
  HvacPointHistoryResponse,
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

/**
 * 一次查询所选建筑 1 至 4 个真实指标趋势。
 *
 * 后端先用 MySQL 活动指标和建筑范围校验全部 ID，再从 TDengine 成功指标表按跨度返回
 * 1/5/30 分钟窗口；前端只消费图表 DTO，不拼 `/api`、不计算指标或降采样。
 */
export async function getHvacIndicatorTrends(
  buildingId: string,
  indicatorIds: string[],
  from: number,
  to: number,
): Promise<HvacIndicatorTrendResponse> {
  const response = await http.get<ApiResult<HvacIndicatorTrendResponse>>(
    `/hvac/buildings/${encodeURIComponent(buildingId)}/indicators/trends`,
    { params: { indicatorIds: indicatorIds.join(','), from, to } },
  )
  return response.data.data
}

/**
 * 一次查询所选建筑 1 至 8 个原始测点历史。
 *
 * 后端使用 MySQL 校验当前建筑的测点身份和用户范围，再批量读取 TDengine 分钟数据；
 * 调用方只能传入页面从当前建筑冻结 19 测点中选择出的内部 ID。
 */
export async function getHvacPointHistory(
  buildingId: string,
  pointIds: string[],
  from: number,
  to: number,
): Promise<HvacPointHistoryResponse> {
  const response = await http.get<ApiResult<HvacPointHistoryResponse>>(
    `/hvac/buildings/${encodeURIComponent(buildingId)}/history`,
    { params: { pointIds: pointIds.join(','), from, to } },
  )
  return response.data.data
}

/**
 * 查询一个指标来源分钟的真实计算解释或失败审计。
 *
 * 后端先校验指标所属建筑权限；最新分钟可以来自 Redis，历史成功结果由 TDengine
 * 分钟输入按已持久化公式版本重放。前端只展示返回证据，不重新执行公式。
 */
export async function getIndicatorCalculationDetail(
  indicatorId: string,
  minuteStart: number,
): Promise<HvacCalculationDetail> {
  const response = await http.get<ApiResult<HvacCalculationDetail>>(
    `/hvac/indicators/${encodeURIComponent(indicatorId)}/calculations/${minuteStart}`,
  )
  return response.data.data
}
