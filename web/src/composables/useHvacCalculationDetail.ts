import { ref } from 'vue'
import { getIndicatorCalculationDetail } from '@/api/hvac'
import type { HvacCalculationDetail } from '@/types/hvac'

export type HvacCalculationDetailTarget = {
  indicatorId: string | null
  indicatorCode: string
  label: string
  equipId?: string | null
  minuteStart: number | null
}

export type HvacCalculationDetailError = {
  status: number | null
  message: string
}

export type HvacCalculationDetailDependencies = {
  getDetail: (
    indicatorId: string,
    minuteStart: number,
  ) => Promise<HvacCalculationDetail>
}

const defaultDependencies: HvacCalculationDetailDependencies = {
  getDetail: getIndicatorCalculationDetail,
}

type HttpFailure = {
  response?: {
    status?: number
    data?: { msg?: string }
  }
}

/**
 * 将详情接口失败转换为用户可操作的业务文案。
 * 409 必须明确拒绝用新公式解释旧结果；503 不透出 TDengine/JDBC 连接细节。
 */
function mapDetailError(cause: unknown): HvacCalculationDetailError {
  const failure = cause as HttpFailure
  const status = failure?.response?.status ?? null
  const messages: Partial<Record<number, string>> = {
    403: '当前账号无权查看该指标详情',
    404: '指标不存在或已停用，请刷新页面',
    409: '历史公式版本暂不受当前服务支持，不能使用新公式解释旧结果',
    503: '计算详情暂不可用，请稍后重试',
  }
  const message = status === null && cause instanceof Error && cause.message
    ? cause.message
    : messages[status ?? -1] ?? '加载失败，请稍后重试'
  return { status, message }
}

/**
 * 管理单个 HVAC 指标计算详情的选择、请求和抽屉生命周期。
 *
 * 页面传入指标实例与来源分钟，状态层调用详情 API；每次打开或关闭都会递增请求版本，
 * 防止快速切换指标、切换建筑或关闭抽屉后的迟到响应覆盖当前页面状态。
 */
export function useHvacCalculationDetail(
  dependencies: HvacCalculationDetailDependencies = defaultDependencies,
) {
  const visible = ref(false)
  const selected = ref<HvacCalculationDetailTarget | null>(null)
  const detail = ref<HvacCalculationDetail | null>(null)
  const loading = ref(false)
  const error = ref<HvacCalculationDetailError | null>(null)
  const localNoData = ref(false)
  let requestVersion = 0

  /**
   * 打开目标详情并加载对应分钟证据。
   * 缺少指标实例或分钟时只展示本地空状态；有效请求返回前若目标改变，结果会被丢弃。
   */
  async function open(target: HvacCalculationDetailTarget): Promise<void> {
    const currentVersion = ++requestVersion
    visible.value = true
    selected.value = target
    detail.value = null
    error.value = null

    if (!target.indicatorId || target.minuteStart === null) {
      localNoData.value = true
      loading.value = false
      return
    }

    localNoData.value = false
    loading.value = true
    try {
      const response = await dependencies.getDetail(
        target.indicatorId,
        target.minuteStart,
      )
      if (currentVersion === requestVersion) detail.value = response
    } catch (cause) {
      if (currentVersion === requestVersion) {
        detail.value = null
        error.value = mapDetailError(cause)
      }
    } finally {
      if (currentVersion === requestVersion) loading.value = false
    }
  }

  /** 使用当前目标重新发起详情请求，并继续应用相同的竞态隔离规则。 */
  async function retry(): Promise<void> {
    if (!selected.value) return
    await open(selected.value)
  }

  /**
   * 关闭并清空所有详情状态，同时使在途请求失效。
   * 页面切换建筑和卸载时复用该入口，避免旧建筑证据停留或迟到写回。
   */
  function close(): void {
    requestVersion += 1
    visible.value = false
    selected.value = null
    detail.value = null
    error.value = null
    loading.value = false
    localNoData.value = false
  }

  return {
    visible,
    selected,
    detail,
    loading,
    error,
    localNoData,
    open,
    retry,
    close,
  }
}
