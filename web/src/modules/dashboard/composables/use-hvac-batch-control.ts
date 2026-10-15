import { computed, onScopeDispose, ref, shallowRef, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { t } from '@/locales'
import { requestErrorCode, requestErrorMessage } from '@/shared/utils/request-error'
import { hvacControlApi, newHvacControlIdempotencyKey } from '../api/hvac-control'
import type {
  HvacControlAction,
  HvacControlBatch,
  HvacControlBatchCommand,
  HvacControlBatchPage,
  HvacControlBatchPreview,
} from '../models/hvac-control'

const HISTORY_SIZE = 10
const POLL_INTERVAL_MILLIS = 3_000
const PREVIEW_NOT_CREATED_CODES = new Set(['HVAC_CONTROL_PREVIEW_EXPIRED', 'HVAC_CONTROL_PREVIEW_NOT_FOUND'])
const emptyHistory = (): HvacControlBatchPage => ({ page: 1, size: HISTORY_SIZE, total: 0, items: [] })
type UnknownSubmission = { scopeKey: string; command: HvacControlBatchCommand }

function uniqueEquipmentIds(value: readonly string[]): string[] {
  return Array.from(new Set(value.filter(id => id.trim().length > 0))).sort()
}

/**
 * 批量预检与当前多选范围绑定。筛选或勾选变化后，迟到的预检/提交响应不能进入新的草稿；
 * 已经受理的后台批次仍保留在服务端历史中，可从历史入口继续轮询。
 */
export function useHvacBatchControl(equipmentIds: MaybeRefOrGetter<readonly string[]>) {
  const selectedIds = computed(() => uniqueEquipmentIds(toValue(equipmentIds)))
  const selectionKey = computed(() => selectedIds.value.join('\u0001'))
  const preview = shallowRef<HvacControlBatchPreview | null>(null)
  const activeBatch = shallowRef<HvacControlBatch | null>(null)
  const history = shallowRef<HvacControlBatchPage>(emptyHistory())
  const previewLoading = ref(false)
  const submitting = ref(false)
  const activeBatchLoading = ref(false)
  const historyLoading = ref(false)
  const previewError = ref<string | null>(null)
  const submitError = ref<string | null>(null)
  const batchError = ref<string | null>(null)
  const historyError = ref<string | null>(null)

  let previewRequest = 0
  let previewScopeKey = ''
  let batchRequest = 0
  let historyRequest = 0
  let pollTimer: ReturnType<typeof setTimeout> | undefined
  let disposed = false
  // 网络结果未知时保留完整旧请求。后端先按幂等键查既有批次，再检查预检是否过期；
  // 因此重新预检不能悄悄换 key，否则同一组设备可能创建第二个批次。
  const unknownSubmissions = new Map<string, UnknownSubmission>()

  function stopPolling() {
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = undefined
  }

  function ownsPreview(request: number, key: string): boolean {
    return !disposed && request === previewRequest && key === selectionKey.value
  }

  function clearPreview() {
    previewRequest++
    preview.value = null
    previewScopeKey = ''
    previewLoading.value = false
    previewError.value = null
    submitError.value = null
  }

  watch(selectionKey, clearPreview, { immediate: true })

  async function createPreview(): Promise<HvacControlBatchPreview | null> {
    const ids = selectedIds.value
    if (!ids.length || ids.length > 100) return null
    const key = selectionKey.value
    const request = ++previewRequest
    previewLoading.value = true
    previewError.value = null
    submitError.value = null
    try {
      const result = await hvacControlApi.previewBatch(ids)
      if (!ownsPreview(request, key)) return null
      preview.value = result
      previewScopeKey = key
      return result
    } catch (reason) {
      if (ownsPreview(request, key)) previewError.value = requestErrorMessage(reason)
      return null
    } finally {
      if (ownsPreview(request, key)) previewLoading.value = false
    }
  }

  function submissionKey(action: HvacControlAction, value: string): string {
    return [selectionKey.value, action, value].join('\u0001')
  }

  async function submit(action: HvacControlAction, value: string, confirmLinkedImpact: boolean): Promise<HvacControlBatch | null> {
    const currentPreview = preview.value
    const key = selectionKey.value
    if (submitting.value) return null
    const keyForCommand = submissionKey(action, value)
    const existing = unknownSubmissions.get(keyForCommand)
    const pendingForScope = Array.from(unknownSubmissions.values()).find(pending => pending.scopeKey === key)
    if (!existing && pendingForScope) {
      submitError.value = t('dashboard.daikin.batchControl.pendingUnknownSubmission')
      return null
    }
    if (!existing && (!currentPreview || previewScopeKey !== key)) return null
    const command = existing?.command ?? {
      previewId: currentPreview!.previewId,
      action,
      value,
      idempotencyKey: newHvacControlIdempotencyKey(),
      confirmLinkedImpact,
    }
    if (!existing) unknownSubmissions.set(keyForCommand, { scopeKey: key, command })
    submitting.value = true
    submitError.value = null
    try {
      const result = await hvacControlApi.createBatch(command)
      // 只有明确得到批次后才释放旧请求；网络失败时下次人工重试沿用完整旧 payload。
      unknownSubmissions.delete(keyForCommand)
      void loadHistory()
      if (disposed || key !== selectionKey.value || currentPreview !== preview.value) return result
      acceptActiveBatch(result)
      // 仅在服务端明确返回批次后丢弃预检草稿；网络结果未知仍须保留旧 payload 供人工幂等重试。
      preview.value = null
      previewScopeKey = ''
      return result
    } catch (reason) {
      const code = requestErrorCode(reason)
      // 这些错误发生在后端幂等查找之后，能证明旧 payload 没有建成批次，才允许新预检换 key。
      if (code != null && PREVIEW_NOT_CREATED_CODES.has(code)) unknownSubmissions.delete(keyForCommand)
      else void loadHistory()
      if (!disposed && key === selectionKey.value && currentPreview === preview.value) {
        submitError.value = requestErrorMessage(reason)
      }
      return null
    } finally {
      submitting.value = false
    }
  }

  function acceptActiveBatch(result: HvacControlBatch) {
    activeBatch.value = result
    batchError.value = null
    stopPolling()
    if (!result.completed) schedulePolling(result.batchId)
  }

  function schedulePolling(batchId: string) {
    stopPolling()
    pollTimer = setTimeout(() => {
      pollTimer = undefined
      void loadBatch(batchId)
    }, POLL_INTERVAL_MILLIS)
  }

  async function loadBatch(batchId: string): Promise<HvacControlBatch | null> {
    const request = ++batchRequest
    activeBatchLoading.value = true
    batchError.value = null
    try {
      const result = await hvacControlApi.batch(batchId)
      if (disposed || request !== batchRequest || result.batchId !== batchId) return null
      acceptActiveBatch(result)
      return result
    } catch (reason) {
      if (!disposed && request === batchRequest) {
        batchError.value = requestErrorMessage(reason)
        // 临时读失败不能结束服务端队列；保留当前摘要并继续有限频率读取。
        if (activeBatch.value?.batchId === batchId && !activeBatch.value.completed) schedulePolling(batchId)
      }
      return null
    } finally {
      if (!disposed && request === batchRequest) activeBatchLoading.value = false
    }
  }

  async function cancelBatch(batchId: string): Promise<HvacControlBatch | null> {
    const request = ++batchRequest
    activeBatchLoading.value = true
    batchError.value = null
    try {
      const result = await hvacControlApi.cancelBatch(batchId)
      if (disposed || request !== batchRequest || result.batchId !== batchId) return null
      acceptActiveBatch(result)
      void loadHistory()
      return result
    } catch (reason) {
      if (!disposed && request === batchRequest) batchError.value = requestErrorMessage(reason)
      return null
    } finally {
      if (!disposed && request === batchRequest) activeBatchLoading.value = false
    }
  }

  async function loadHistory(page = 1, resume = false): Promise<HvacControlBatchPage | null> {
    const request = ++historyRequest
    historyLoading.value = true
    historyError.value = null
    try {
      const result = await hvacControlApi.batches(page, HISTORY_SIZE)
      if (disposed || request !== historyRequest) return null
      history.value = result
      if (resume) {
        const unfinished = result.items.find(item => !item.completed)
        if (unfinished) void loadBatch(unfinished.batchId)
      }
      return result
    } catch (reason) {
      if (!disposed && request === historyRequest) historyError.value = requestErrorMessage(reason)
      return null
    } finally {
      if (!disposed && request === historyRequest) historyLoading.value = false
    }
  }

  function dismissCompletedBatch() {
    if (!activeBatch.value?.completed) return
    stopPolling()
    activeBatch.value = null
    batchError.value = null
  }

  onScopeDispose(() => {
    disposed = true
    stopPolling()
  })

  return {
    selectedIds,
    preview,
    activeBatch,
    history,
    previewLoading,
    submitting,
    activeBatchLoading,
    historyLoading,
    previewError,
    submitError,
    batchError,
    historyError,
    createPreview,
    submit,
    loadBatch,
    cancelBatch,
    loadHistory,
    dismissCompletedBatch,
  }
}
