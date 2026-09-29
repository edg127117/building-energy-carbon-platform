import { ref } from 'vue'
import { getMeterCoverage, listMeterCoverageCandidates, listMeterCoverageHistory, listMeterCoverages, updateMeterCoverage } from '../api/meter-coverage'
import type { MeterCandidateQuery, MeterCoveragePage, MeterCoverageUpdate, MeterCoverageView, MeterTarget } from '../models/meter-coverage'
import { requestErrorCode, requestErrorMessage } from '@/shared/utils/request-error'
import { t } from '@/locales'

type RequestState = { message: string } | null
const emptyPage = <T>(page = 1, size = 20): MeterCoveragePage<T> => ({ page, size, total: 0, items: [] })

export function useMeterCoverage() {
  const coverage = ref<MeterCoverageView | null>(null)
  const coverageLoading = ref(false)
  const coverageError = ref<RequestState>(null)
  const history = ref(emptyPage<MeterCoverageView>())
  const historyLoading = ref(false)
  const historyError = ref<RequestState>(null)
  const candidates = ref(emptyPage<MeterTarget>())
  const candidatesLoading = ref(false)
  const candidatesError = ref<RequestState>(null)
  const listCoverages = ref<MeterCoverageView[]>([])
  const listLoading = ref(false)
  const listError = ref<RequestState>(null)
  const saveLoading = ref(false)
  const saveError = ref<RequestState>(null)
  let coverageGeneration = 0
  let historyGeneration = 0
  let candidatesGeneration = 0
  let listGeneration = 0
  let saveGeneration = 0

  async function loadCoverage(equipmentId: string) {
    const owner = ++coverageGeneration
    historyGeneration++
    candidatesGeneration++
    saveGeneration++
    coverage.value = null
    coverageLoading.value = true
    coverageError.value = null
    history.value = emptyPage()
    historyLoading.value = false
    historyError.value = null
    candidates.value = emptyPage()
    candidatesLoading.value = false
    candidatesError.value = null
    saveLoading.value = false
    saveError.value = null
    try {
      const next = await getMeterCoverage(equipmentId)
      if (owner === coverageGeneration) coverage.value = next
      return next
    } catch (reason) {
      if (owner === coverageGeneration) coverageError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === coverageGeneration) coverageLoading.value = false
    }
  }

  async function loadHistory(equipmentId: string, page = 1, size = 20) {
    const owner = ++historyGeneration
    history.value = emptyPage(page, size)
    historyLoading.value = true
    historyError.value = null
    try {
      const next = await listMeterCoverageHistory(equipmentId, { page, size })
      if (owner === historyGeneration) history.value = next
      return next
    } catch (reason) {
      if (owner === historyGeneration) historyError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === historyGeneration) historyLoading.value = false
    }
  }

  async function loadCandidates(equipmentId: string, params: MeterCandidateQuery) {
    const owner = ++candidatesGeneration
    candidates.value = emptyPage(params.page, params.size)
    candidatesLoading.value = true
    candidatesError.value = null
    try {
      const next = await listMeterCoverageCandidates(equipmentId, { ...params, keyword: params.keyword?.trim() || undefined })
      if (owner === candidatesGeneration) candidates.value = next
      return next
    } catch (reason) {
      if (owner === candidatesGeneration) candidatesError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === candidatesGeneration) candidatesLoading.value = false
    }
  }

  async function loadListCoverages(buildingId: string | undefined, equipmentIds: string[]) {
    const owner = ++listGeneration
    listCoverages.value = []
    listError.value = null
    if (!equipmentIds.length) {
      listLoading.value = false
      return []
    }
    listLoading.value = true
    try {
      const batches: string[][] = []
      for (let index = 0; index < equipmentIds.length; index += 100) batches.push(equipmentIds.slice(index, index + 100))
      const pages = await Promise.all(batches.map(batch => listMeterCoverages({ buildingId, equipmentIds: batch })))
      const next = pages.flat()
      if (owner === listGeneration) listCoverages.value = next
      return next
    } catch (reason) {
      if (owner === listGeneration) listError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === listGeneration) listLoading.value = false
    }
  }

  async function saveCoverage(equipmentId: string, data: MeterCoverageUpdate) {
    const owner = ++saveGeneration
    saveLoading.value = true
    saveError.value = null
    try {
      const saved = await updateMeterCoverage(equipmentId, data)
      if (owner === saveGeneration && coverage.value?.equipmentId === equipmentId) coverage.value = saved
      return saved
    } catch (reason) {
      if (owner === saveGeneration) {
        saveError.value = requestErrorCode(reason) === 'METER_COVERAGE_CONFLICT'
          ? { message: t('assetManagement.meterCoverage.saveConflict') }
          : requestState(reason)
      }
      throw reason
    } finally {
      if (owner === saveGeneration) saveLoading.value = false
    }
  }

  return {
    coverage, coverageLoading, coverageError, history, historyLoading, historyError,
    candidates, candidatesLoading, candidatesError, listCoverages, listLoading, listError, saveLoading, saveError,
    loadCoverage, loadHistory, loadCandidates, loadListCoverages, saveCoverage,
  }
}

function requestState(reason: unknown): RequestState { return { message: requestErrorMessage(reason) } }
