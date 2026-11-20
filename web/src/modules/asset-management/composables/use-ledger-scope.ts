import { onScopeDispose, ref, shallowRef } from 'vue'
import { listAssetLedger } from '../api/assets'
import type { AssetLedgerPage, AssetLedgerQuery } from '../models/ledger'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { t } from '@/locales'

/** 台账分页、专业计数和状态摘要必须来自同一次后端聚合，禁止拼接旧设备分页或本地分类。 */
export function useLedgerScope() {
  const data = shallowRef<AssetLedgerPage | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const refreshFailed = ref(false)
  let generation = 0
  let disposed = false
  let loadedQuery: AssetLedgerQuery | null = null
  let refreshing = false
  async function load(query: AssetLedgerQuery) {
    if (disposed) return
    const owner = ++generation
    loadedQuery = null
    data.value = null
    error.value = null
    refreshFailed.value = false
    loading.value = true
    try {
      const response = await listAssetLedger(query)
      if (owner !== generation) return
      validateResponse(response, query)
      loadedQuery = query
      data.value = response
    } catch (reason) {
      if (owner !== generation) return
      data.value = null
      const code = reason instanceof Error ? reason.message : ''
      error.value = code === 'LEDGER_PAGE_MISMATCH' ? t('assetManagement.ledger.pageMismatch')
        : code === 'LEDGER_SCOPE_MISMATCH' ? t('assetManagement.ledger.scopeMismatch') : requestErrorMessage(reason)
    } finally { if (owner === generation) loading.value = false }
  }

  // 自动刷新沿用最近一次已提交的查询。切换范围后，迟到响应不得替换新范围。
  async function refresh() {
    const query = loadedQuery
    if (disposed || refreshing || !query) return
    const owner = generation
    refreshing = true
    try {
      const response = await listAssetLedger(query)
      if (owner !== generation) return
      validateResponse(response, query)
      data.value = response
      refreshFailed.value = false
    } catch {
      if (owner === generation) refreshFailed.value = true
    } finally {
      refreshing = false
    }
  }

  function clear() {
    generation++
    loadedQuery = null
    data.value = null
    loading.value = false
    error.value = null
    refreshFailed.value = false
  }
  onScopeDispose(() => { disposed = true; clear() })
  return { data, loading, error, refreshFailed, load, refresh, clear }
}

/** 即使 HTTP 成功，也不接受跨建筑或重复设备的聚合页，避免迟到/异常响应污染当前范围。 */
function validateResponse(response: AssetLedgerPage, query: AssetLedgerQuery): void {
  if (response.page !== query.page || response.size !== query.size) throw new Error('LEDGER_PAGE_MISMATCH')
  const equipmentIds = new Set<string>()
  for (const item of response.items) {
    const equipmentId = item?.equipment?.equipmentId
    if (!equipmentId || equipmentIds.has(equipmentId) || (query.buildingId && item.equipment.buildingId !== query.buildingId)) {
      throw new Error('LEDGER_SCOPE_MISMATCH')
    }
    equipmentIds.add(equipmentId)
  }
}
