import { computed, onScopeDispose, ref, shallowRef, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { t } from '@/locales'
import { requestErrorMessage, requestErrorStatus } from '@/shared/utils/request-error'
import { hvacControlApi, newHvacControlIdempotencyKey } from '../api/hvac-control'
import {
  canHvacControlClose,
  canHvacControlConfirm,
  canHvacControlRecheck,
  canHvacControlRelease,
  isHvacControlPollingStatus,
  occupiesHvacControlSlot,
  type HvacControlAction,
  type HvacControlOperation,
  type HvacControlOperationPage,
  type HvacControlOptions,
} from '../models/hvac-control'

const HISTORY_SIZE = 20
const POLL_INTERVAL_MILLIS = 3_000
// 300 秒观察及最后核验由服务端时钟决定；前端只保留 110 次轮询上限，不按本机时间伪造超时或漏掉期限后的结果。
const MAX_STATUS_POLLS = 110
const emptyHistory = (): HvacControlOperationPage => ({ page: 1, size: HISTORY_SIZE, total: 0, items: [] })

export function useHvacControl(equipmentId: MaybeRefOrGetter<string>) {
  const options = shallowRef<HvacControlOptions | null>(null)
  const history = shallowRef<HvacControlOperationPage>(emptyHistory())
  const activeOperation = shallowRef<HvacControlOperation | null>(null)
  const loading = ref(false)
  const refreshing = ref(false)
  const error = ref<string | null>(null)
  const historyForbidden = ref(false)
  const historyLoading = ref(false)
  const pending = ref(new Set<string>())
  // 未收到 Operation 可能只是响应丢失；相同请求重试必须复用幂等键，收到 Operation 后才清理。
  const submitIdempotencyKeys = new Map<string, string>()
  let generation = 0
  let historyGeneration = 0
  let pollCount = 0
  let pollTimer: ReturnType<typeof setTimeout> | undefined
  let disposed = false

  function targetId(): string { return toValue(equipmentId) }
  function owns(owner: number, target: string): boolean {
    return !disposed && owner === generation && target === targetId()
  }
  function stopPolling() {
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = undefined
  }
  function addPending(key: string) {
    const next = new Set(pending.value)
    next.add(key)
    pending.value = next
  }
  function removePending(key: string) {
    const next = new Set(pending.value)
    next.delete(key)
    pending.value = next
  }
  function replaceOperation(next: HvacControlOperation) {
    activeOperation.value = next
    const index = history.value.items.findIndex(item => item.operationId === next.operationId)
    if (index >= 0) {
      history.value = { ...history.value, items: history.value.items.map(item => item.operationId === next.operationId ? next : item) }
      return
    }
    if (history.value.page === 1) {
      history.value = {
        ...history.value,
        total: history.value.total + 1,
        items: [next, ...history.value.items].slice(0, history.value.size),
      }
    }
  }
  function acceptOperation(value: HvacControlOperation, owner: number, target: string): HvacControlOperation | null {
    if (!owns(owner, target)) return null
    if (value.equipmentId !== target) {
      error.value = t('dashboard.daikin.control.responseMismatch')
      return null
    }
    replaceOperation(value)
    schedulePolling(value, owner, target)
    return value
  }
  function schedulePolling(operation: HvacControlOperation, owner: number, target: string) {
    stopPolling()
    if (!owns(owner, target) || !isHvacControlPollingStatus(operation.status) || pollCount >= MAX_STATUS_POLLS
      || (operation.status === 'UNKNOWN' && operation.cloudStable)) return
    pollTimer = setTimeout(() => {
      pollTimer = undefined
      if (!owns(owner, target)) return
      pollCount++
      void loadOperation(operation.operationId, owner, target)
    }, POLL_INTERVAL_MILLIS)
  }
  async function loadOperation(operationId: string, owner: number, target: string) {
    try {
      const result = await hvacControlApi.operation(operationId)
      return acceptOperation(result, owner, target)
    } catch (reason) {
      if (owns(owner, target)) {
        if (requestErrorStatus(reason) === 403) historyForbidden.value = true
        else error.value = requestErrorMessage(reason)
      }
      return null
    }
  }
  async function load(silent: boolean) {
    const owner = ++generation
    historyGeneration++
    historyLoading.value = false
    const target = targetId()
    stopPolling()
    pollCount = 0
    if (!target) {
      options.value = null
      history.value = emptyHistory()
      activeOperation.value = null
      return
    }
    if (silent) refreshing.value = true
    else {
      loading.value = true
      options.value = null
      history.value = emptyHistory()
      activeOperation.value = null
    }
    historyForbidden.value = false
    error.value = null
    const historyPage = silent ? history.value.page : 1
    try {
      const [optionResult, historyResult] = await Promise.allSettled([
        hvacControlApi.options(target),
        hvacControlApi.operations(target, historyPage, HISTORY_SIZE),
      ])
      if (!owns(owner, target)) return
      if (optionResult.status === 'fulfilled') options.value = optionResult.value
      if (historyResult.status === 'fulfilled') history.value = historyResult.value
      if (historyResult.status === 'rejected' && requestErrorStatus(historyResult.reason) === 403) historyForbidden.value = true
      const failure = optionResult.status === 'rejected'
        ? optionResult.reason
        : historyResult.status === 'rejected' && requestErrorStatus(historyResult.reason) !== 403
          ? historyResult.reason
          : null
      if (failure) error.value = requestErrorMessage(failure)

      const activeId = optionResult.status === 'fulfilled' ? optionResult.value.activeOperationId : null
      if (activeId) await loadOperation(activeId, owner, target)
      else {
        const pendingOperation = history.value.items.find(item => occupiesHvacControlSlot(item))
        if (pendingOperation) {
          activeOperation.value = pendingOperation
          schedulePolling(pendingOperation, owner, target)
        }
      }
    } finally {
      if (owns(owner, target)) {
        loading.value = false
        refreshing.value = false
      }
    }
  }
  async function loadHistory(page: number) {
    const owner = generation
    const target = targetId()
    const request = ++historyGeneration
    if (!target) return
    historyLoading.value = true
    try {
      const result = await hvacControlApi.operations(target, page, HISTORY_SIZE)
      if (!owns(owner, target) || request !== historyGeneration) return
      history.value = result
      historyForbidden.value = false
    } catch (reason) {
      if (!owns(owner, target) || request !== historyGeneration) return
      if (requestErrorStatus(reason) === 403) historyForbidden.value = true
      else error.value = requestErrorMessage(reason)
    } finally {
      if (owns(owner, target) && request === historyGeneration) historyLoading.value = false
    }
  }
  async function mutate(key: string, request: () => Promise<HvacControlOperation>) {
    const owner = generation
    const target = targetId()
    if (!target || pending.value.has(key)) return null
    addPending(key)
    error.value = null
    try {
      return acceptOperation(await request(), owner, target)
    } catch (reason) {
      if (owns(owner, target)) error.value = requestErrorMessage(reason)
      return null
    } finally {
      removePending(key)
    }
  }
  async function submit(action: HvacControlAction, value: string) {
    const capability = options.value
    const selected = capability?.actions.find(item => item.action === action)
    if (!capability?.canControl || !selected?.allowed) {
      error.value = t('dashboard.daikin.control.actionUnavailable')
      return null
    }
    if (!capability.revision) {
      error.value = t('dashboard.daikin.control.revisionMissing')
      return null
    }
    if (!value.trim()) {
      error.value = t('dashboard.daikin.control.valueRequired')
      return null
    }
    if (occupiesHvacControlSlot(activeOperation.value)) {
      error.value = t('dashboard.daikin.control.operationInProgress')
      return null
    }
    const target = targetId()
    const identity = JSON.stringify([target, action, value, capability.revision])
    const idempotencyKey = submitIdempotencyKeys.get(identity) ?? newHvacControlIdempotencyKey()
    submitIdempotencyKeys.set(identity, idempotencyKey)
    return mutate(`submit:${target}`, async () => {
      const operation = await hvacControlApi.createOperation(target, {
        action, value, idempotencyKey, revision: capability.revision!,
      })
      submitIdempotencyKeys.delete(identity)
      return operation
    })
  }
  function operationById(operationId: string): HvacControlOperation | null {
    return activeOperation.value?.operationId === operationId
      ? activeOperation.value
      : history.value.items.find(item => item.operationId === operationId) ?? null
  }
  function validReason(reason: string, invalidMessage: string): string | null {
    const normalizedReason = reason.trim()
    if (normalizedReason.length < 5 || normalizedReason.length > 500) {
      error.value = t(invalidMessage)
      return null
    }
    return normalizedReason
  }
  function recheck(operationId: string) {
    const operation = operationById(operationId)
    if (!operation || !canHvacControlRecheck(operation)) {
      error.value = t('dashboard.daikin.control.recheckUnavailable')
      return Promise.resolve(null)
    }
    stopPolling()
    pollCount = 0
    return mutate(`recheck:${operationId}`, () => hvacControlApi.recheck(operationId))
  }
  function close(operationId: string, reason: string) {
    const operation = operationById(operationId)
    if (!operation || !canHvacControlClose(operation)) {
      error.value = t('dashboard.daikin.control.closeUnavailable')
      return Promise.resolve(null)
    }
    const normalizedReason = validReason(reason, 'dashboard.daikin.control.closeReasonLength')
    if (!normalizedReason) {
      return Promise.resolve(null)
    }
    stopPolling()
    return mutate(`close:${operationId}`, () => hvacControlApi.close(operationId, normalizedReason))
  }
  function confirm(operationId: string, reason: string) {
    const operation = operationById(operationId)
    if (!operation || !canHvacControlConfirm(operation)) {
      error.value = t('dashboard.daikin.control.confirmUnavailable')
      return Promise.resolve(null)
    }
    const normalizedReason = validReason(reason, 'dashboard.daikin.control.fieldReasonLength')
    if (!normalizedReason) return Promise.resolve(null)
    stopPolling()
    return mutate(`confirm:${operationId}`, () => hvacControlApi.confirm(operationId, normalizedReason))
  }
  function release(operationId: string, reason: string) {
    const operation = operationById(operationId)
    if (!operation || !canHvacControlRelease(operation)) {
      error.value = t('dashboard.daikin.control.releaseUnavailable')
      return Promise.resolve(null)
    }
    const normalizedReason = validReason(reason, 'dashboard.daikin.control.fieldReasonLength')
    if (!normalizedReason) return Promise.resolve(null)
    stopPolling()
    return mutate(`release:${operationId}`, () => hvacControlApi.release(operationId, normalizedReason))
  }

  // 同一抽屉可切换设备；代次与目标同时匹配才采纳响应，避免迟到结果落到新设备。
  watch(() => targetId(), () => { void load(false) }, { immediate: true })
  onScopeDispose(() => {
    disposed = true
    generation++
    stopPolling()
  })

  return {
    options,
    history,
    activeOperation,
    loading,
    refreshing,
    error,
    historyForbidden,
    historyLoading,
    hasInFlightOperation: computed(() => occupiesHvacControlSlot(activeOperation.value)),
    isBusy: (key: string) => pending.value.has(key),
    busy: computed(() => pending.value.size > 0),
    refresh: () => load(true),
    loadHistory,
    submit,
    recheck,
    close,
    confirm,
    release,
  }
}
