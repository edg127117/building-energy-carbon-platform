import { ref } from 'vue'
import {
  getHvacIndicatorTrends,
  getHvacPointHistory,
} from '@/api/hvac'
import {
  adaptIndicatorTrends,
  adaptPointTrends,
  buildHistoryPointOptions,
  presetRange,
  validateHistoryRange,
  type HvacHistoryMode,
  type HvacHistoryPreset,
  type HvacHistoryPointOption,
  type HvacTrendGroup,
} from '@/domain/hvacHistoryTrends'
import type { SnapshotPoint } from '@/types/hvac'

export type HvacHistoryDependencies = {
  getIndicatorTrends: typeof getHvacIndicatorTrends
  getPointHistory: typeof getHvacPointHistory
  now: () => number
}

export type HvacHistoryBuildingContext = {
  buildingId: string
  indicatorIds: string[]
  points: SnapshotPoint[]
}

export type HvacHistoryError = {
  status: number | null
  message: string
}

type HistoryRequest = {
  mode: HvacHistoryMode
  buildingId: string
  ids: string[]
  from: number
  to: number
  scopeKey: string
}

type HttpFailure = {
  response?: {
    status?: number
    data?: { msg?: string }
  }
}

const defaultDependencies: HvacHistoryDependencies = {
  getIndicatorTrends: getHvacIndicatorTrends,
  getPointHistory: getHvacPointHistory,
  now: Date.now,
}

/**
 * 将历史接口失败转换为面向运行人员的恢复提示。
 * 403/404/503 使用稳定脱敏文案；400 优先保留后端业务校验信息，网络错误保留可读消息。
 */
function mapHistoryError(cause: unknown): HvacHistoryError {
  const failure = cause as HttpFailure
  const status = failure?.response?.status ?? null
  const messages: Partial<Record<number, string>> = {
    403: '当前账号无权查看该建筑历史',
    404: '建筑或历史对象不存在，请刷新页面',
    503: '历史趋势暂不可用，请稍后重试',
  }
  let message = messages[status ?? -1]
  if (status === 400) {
    message = failure.response?.data?.msg || '历史查询条件无效'
  } else if (!message && status === null && cause instanceof Error) {
    message = cause.message
  }
  return { status, message: message || '加载历史趋势失败，请稍后重试' }
}

/**
 * 编排 HVAC 历史模式、时间条件、按建筑测点记忆和请求竞态。
 *
 * 页面只把当前建筑指标 ID 与冻结测点快照传入；本层调用两类历史 API、适配统一图表
 * 模型并持久化有效测点选择。每次条件变化递增请求版本并清除不匹配数据；相对范围的
 * 时间戳会随刷新移动，但同一展示条件刷新失败仍保留上次成功曲线并标记过期。
 */
export function useHvacHistoryTrends(
  dependencies: HvacHistoryDependencies = defaultDependencies,
) {
  const mode = ref<HvacHistoryMode>('indicators')
  const preset = ref<HvacHistoryPreset>('24h')
  const customFrom = ref<number | null>(null)
  const customTo = ref<number | null>(null)
  const pointOptions = ref<HvacHistoryPointOption[]>([])
  const selectedPointIds = ref<string[]>([])
  const groups = ref<HvacTrendGroup[]>([])
  const responseRange = ref<{ from: number; to: number } | null>(null)
  const resolutionMinutes = ref<number | null>(null)
  const loading = ref(false)
  const error = ref<HvacHistoryError | null>(null)
  const stale = ref(false)
  const updatedAt = ref<number | null>(null)

  let buildingId: string | null = null
  let indicatorIds: string[] = []
  let requestVersion = 0
  let activeScopeKey: string | null = null
  let disposed = false

  /** 清空只属于上一个建筑或查询条件的响应，同时使在途请求失效。 */
  function invalidateCondition(): void {
    requestVersion += 1
    activeScopeKey = null
    groups.value = []
    responseRange.value = null
    resolutionMinutes.value = null
    updatedAt.value = null
    loading.value = false
    error.value = null
    stale.value = false
  }

  /**
   * 读取当前模式的完整请求条件；原始测点未选择或自定义范围非法时不生成请求。
   */
  function buildCurrentRequest(): HistoryRequest | null {
    if (!buildingId) return null
    const ids = mode.value === 'indicators'
      ? indicatorIds
      : selectedPointIds.value
    if (ids.length === 0) return null
    const range = preset.value === 'custom'
      ? { from: customFrom.value, to: customTo.value }
      : presetRange(preset.value, dependencies.now())
    if (range.from === null || range.to === null) return null
    if (validateHistoryRange(range.from, range.to)) return null
    const scopeKey = JSON.stringify({
      mode: mode.value,
      buildingId,
      ids,
      preset: preset.value,
      customFrom: preset.value === 'custom' ? range.from : null,
      customTo: preset.value === 'custom' ? range.to : null,
    })
    return {
      mode: mode.value,
      buildingId,
      ids: [...ids],
      from: range.from,
      to: range.to,
      scopeKey,
    }
  }

  /**
   * 执行当前历史查询并以版本号隔离迟到响应。
   * 只有同一展示条件的刷新失败会保留旧图；移动的相对时间戳不改变展示条件身份。
   */
  async function runQuery(preserveCurrent = false): Promise<void> {
    if (disposed) return
    const request = buildCurrentRequest()
    if (!request) {
      loading.value = false
      return
    }
    const scopeKey = request.scopeKey
    const version = ++requestVersion
    loading.value = true
    error.value = null
    if (!preserveCurrent || activeScopeKey !== scopeKey) {
      groups.value = []
      responseRange.value = null
      resolutionMinutes.value = null
      updatedAt.value = null
      stale.value = false
    }
    try {
      let nextGroups: HvacTrendGroup[]
      let nextRange: { from: number; to: number }
      let nextResolution: number
      if (request.mode === 'indicators') {
        const response = await dependencies.getIndicatorTrends(
          request.buildingId,
          request.ids,
          request.from,
          request.to,
        )
        nextGroups = adaptIndicatorTrends(response)
        nextRange = { from: response.from, to: response.to }
        nextResolution = response.resolutionMinutes
      } else {
        const response = await dependencies.getPointHistory(
          request.buildingId,
          request.ids,
          request.from,
          request.to,
        )
        nextGroups = adaptPointTrends(response, pointOptions.value)
        nextRange = { from: response.from, to: response.to }
        nextResolution = response.resolutionMinutes
      }
      if (version !== requestVersion || disposed) return
      groups.value = nextGroups
      responseRange.value = nextRange
      resolutionMinutes.value = nextResolution
      activeScopeKey = scopeKey
      stale.value = false
      updatedAt.value = dependencies.now()
    } catch (cause) {
      if (version !== requestVersion || disposed) return
      error.value = mapHistoryError(cause)
      stale.value = preserveCurrent && activeScopeKey === scopeKey
    } finally {
      if (version === requestVersion && !disposed) loading.value = false
    }
  }

  /**
   * 切换建筑上下文时立即清除旧响应和选择，再恢复新建筑仍有效的最多八个测点 ID。
   * 指标模式有可用实例时自动加载；测点模式只有恢复到选择时才发请求。
   */
  async function setBuildingContext(
    context: HvacHistoryBuildingContext,
  ): Promise<void> {
    disposed = false
    invalidateCondition()
    buildingId = context.buildingId
    indicatorIds = [...new Set(context.indicatorIds.filter(Boolean))].slice(0, 4)
    pointOptions.value = buildHistoryPointOptions(context.points)
    selectedPointIds.value = restorePointSelection(
      context.buildingId,
      pointOptions.value,
    )
    await runQuery()
  }

  /** 切换指标/测点模式并查询当前模式；空测点选择保持明确空状态且不发请求。 */
  async function setMode(nextMode: HvacHistoryMode): Promise<void> {
    if (mode.value === nextMode) return
    mode.value = nextMode
    invalidateCondition()
    await runQuery()
  }

  /**
   * 选择快捷时间范围并立即查询；范围结束时间在实际请求时取当前整分钟。
   * 首次进入自定义模式只等待日期输入，不把“尚未选择”误报为查询失败。
   */
  async function setPreset(nextPreset: HvacHistoryPreset): Promise<void> {
    if (preset.value === nextPreset) return
    preset.value = nextPreset
    invalidateCondition()
    if (nextPreset === 'custom') {
      if (customFrom.value === null || customTo.value === null) return
      const validation = validateHistoryRange(customFrom.value, customTo.value)
      if (validation) {
        error.value = { status: null, message: validation }
        return
      }
    }
    await runQuery()
  }

  /**
   * 保存本地时区控件转换后的 Unix 毫秒范围并查询；非法范围只显示本地错误，不访问 API。
   */
  async function setCustomRange(
    from: number | null,
    to: number | null,
  ): Promise<void> {
    customFrom.value = from
    customTo.value = to
    preset.value = 'custom'
    invalidateCondition()
    const validation = validateHistoryRange(from, to)
    if (validation) {
      error.value = { status: null, message: validation }
      return
    }
    await runQuery()
  }

  /**
   * 只保留当前建筑可选 ID 的首次顺序和前八项，写入按建筑隔离的 localStorage 后查询。
   */
  async function setSelectedPointIds(ids: string[]): Promise<void> {
    const available = new Set(pointOptions.value.map((option) => option.pointId))
    selectedPointIds.value = [...new Set(ids)]
      .filter((id) => available.has(id))
      .slice(0, 8)
    if (buildingId) {
      savePointSelection(buildingId, selectedPointIds.value)
    }
    if (mode.value !== 'points') return
    invalidateCondition()
    await runQuery()
  }

  /** 使用当前完整条件手动刷新；同条件失败时旧结果保留并标记为过期。 */
  async function refresh(): Promise<void> {
    if (preset.value === 'custom') {
      const validation = validateHistoryRange(customFrom.value, customTo.value)
      if (validation) {
        error.value = { status: null, message: validation }
        return
      }
    }
    await runQuery(true)
  }

  /** 页面卸载时使在途请求永久失效并结束加载状态，不继续写回已销毁组件。 */
  function dispose(): void {
    disposed = true
    requestVersion += 1
    loading.value = false
  }

  return {
    mode,
    preset,
    customFrom,
    customTo,
    pointOptions,
    selectedPointIds,
    groups,
    responseRange,
    resolutionMinutes,
    loading,
    error,
    stale,
    updatedAt,
    setBuildingContext,
    setMode,
    setPreset,
    setCustomRange,
    setSelectedPointIds,
    refresh,
    dispose,
  }
}

/** 从按建筑存储中恢复仍可用的首次顺序，损坏或非数组值按首次进入处理。 */
function restorePointSelection(
  buildingId: string,
  options: HvacHistoryPointOption[],
): string[] {
  try {
    // 建筑刚选中时快照可能尚未返回；此时不能用空候选覆盖已保存的有效选择。
    if (options.length === 0) return []
    const stored = JSON.parse(
      localStorage.getItem(pointSelectionKey(buildingId)) ?? '[]',
    )
    if (!Array.isArray(stored)) return []
    const available = new Set(options.map((option) => option.pointId))
    const restored = [...new Set(stored.filter(
      (id): id is string => typeof id === 'string',
    ))].filter((id) => available.has(id)).slice(0, 8)
    savePointSelection(buildingId, restored)
    return restored
  } catch {
    return []
  }
}

/** 将有效测点 ID 写入当前建筑专属键；浏览器存储不可用时不阻断真实历史查询。 */
function savePointSelection(buildingId: string, ids: string[]): void {
  try {
    localStorage.setItem(pointSelectionKey(buildingId), JSON.stringify(ids))
  } catch {
    // 隐私模式或存储配额失败只影响选择记忆，不能阻止用户当前查询。
  }
}

/** 生成按建筑隔离的测点选择键，避免切换建筑后恢复其他建筑内部 ID。 */
function pointSelectionKey(buildingId: string): string {
  return `hvac:history-point-ids:${buildingId}`
}
