import { onBeforeUnmount, onMounted, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { daikinApi, newDaikinFastWatchId } from '../api/daikin'
import type { DaikinFastWatch } from '../models/daikin'
import { useDaikinResource } from './use-daikin-resource'

const RENEW_INTERVAL_MILLIS = 5_000
const RETRY_DELAYS = [1_000, 5_000, 15_000] as const

type FastWatchContext = {
  generation: number
  equipmentId: string
  watchId: string
  retryCount: number
  renewal: Promise<void> | null
}

type FastWatchOptions = {
  equipmentId: MaybeRefOrGetter<string>
  visible: MaybeRefOrGetter<boolean | undefined>
  refresh: (equipmentId: string) => Promise<void>
  reset?: () => void
}

/**
 * 详情可见时才保持平台短期只读核验租约。POST 成功只表示服务端已登记异步取数，
 * 后续读取仍以平台 current/state-events 为准；代际令牌隔离关闭或切设备后的迟到响应。
 */
export function useDaikinFastWatch(options: FastWatchOptions) {
  const subscription = useDaikinResource<DaikinFastWatch>()
  let active: FastWatchContext | null = null
  let generation = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let disposed = false

  function currentEquipmentId(): string { return toValue(options.equipmentId) }
  function canWatch(): boolean {
    return !disposed && toValue(options.visible) !== false && !document.hidden && Boolean(currentEquipmentId())
  }
  function owns(context: FastWatchContext): boolean {
    return !disposed
      && active === context
      && context.generation === generation
      && context.equipmentId === currentEquipmentId()
      && toValue(options.visible) !== false
      && !document.hidden
  }
  function stopTimer() {
    if (timer) clearTimeout(timer)
    timer = undefined
  }
  function release(context: FastWatchContext) {
    void daikinApi.stopFastWatch(context.equipmentId, context.watchId).catch(() => undefined)
  }
  function deactivateDisabled(context: FastWatchContext) {
    if (active !== context) return
    stopTimer()
    active = null
    generation++
    subscription.clear()
    release(context)
  }
  function stopActiveWatch() {
    stopTimer()
    const context = active
    active = null
    generation++
    subscription.clear()
    if (!context) return
    release(context)
    if (context.renewal) {
      // 已发出的 POST 可能晚于首次 DELETE 到达；完成后再删除一次，避免旧租约残留。
      void context.renewal.finally(() => release(context))
    }
  }
  function schedule(context: FastWatchContext, delay: number) {
    stopTimer()
    if (!owns(context)) return
    timer = setTimeout(() => {
      timer = undefined
      startRenewal(context)
    }, delay)
  }
  function scheduleRetry(context: FastWatchContext) {
    const delay = RETRY_DELAYS[context.retryCount++]
    if (delay == null) return
    schedule(context, delay)
  }
  async function renew(context: FastWatchContext) {
    try {
      await subscription.run(() => daikinApi.startFastWatch(context.equipmentId, context.watchId), true)
      if (!owns(context)) return
      const lease = subscription.data.value
      if (subscription.error.value || !lease) {
        scheduleRetry(context)
        return
      }
      if (!lease.enabled) {
        deactivateDisabled(context)
        return
      }
      try {
        await options.refresh(context.equipmentId)
      } catch (cause) {
        if (owns(context)) {
          subscription.error.value = requestErrorMessage(cause)
          scheduleRetry(context)
        }
        return
      }
      context.retryCount = 0
      if (owns(context)) schedule(context, RENEW_INTERVAL_MILLIS)
    } finally {
      context.renewal = null
    }
  }
  function startRenewal(context: FastWatchContext) {
    if (!owns(context) || context.renewal) return
    const request = renew(context)
    context.renewal = request
    void request
  }
  function startFastWatch() {
    if (!canWatch()) return
    const equipmentId = currentEquipmentId()
    if (!active || active.equipmentId !== equipmentId) {
      if (active) stopActiveWatch()
      active = {
        generation: ++generation,
        equipmentId,
        watchId: newDaikinFastWatchId(),
        retryCount: 0,
        renewal: null,
      }
      subscription.clear()
    }
    startRenewal(active)
  }
  function visibilityChanged() {
    if (document.hidden) stopActiveWatch()
    else startFastWatch()
  }

  watch([() => currentEquipmentId(), () => toValue(options.visible)], ([nextEquipmentId], [previousEquipmentId]) => {
    const equipmentChanged = nextEquipmentId !== previousEquipmentId
    stopActiveWatch()
    if (equipmentChanged) options.reset?.()
    startFastWatch()
  })
  onMounted(() => {
    document.addEventListener('visibilitychange', visibilityChanged)
    startFastWatch()
  })
  onBeforeUnmount(() => {
    disposed = true
    document.removeEventListener('visibilitychange', visibilityChanged)
    stopActiveWatch()
  })

  return { error: subscription.error }
}
