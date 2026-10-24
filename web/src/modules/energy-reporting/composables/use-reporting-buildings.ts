import { ref } from 'vue'
import { listReportingBuildings, type ReportingAccessibleBuilding } from '../api/buildings'
import { requestErrorMessage } from '@/shared/utils/request-error'

export function useReportingBuildings() {
  const records = ref<ReportingAccessibleBuilding[]>([])
  const total = ref(0)
  const page = ref(1)
  const keyword = ref('')
  const loading = ref(false)
  const error = ref('')
  let generation = 0

  async function search(nextPage = 1, nextKeyword = keyword.value): Promise<void> {
    const owner = ++generation
    keyword.value = nextKeyword
    loading.value = true
    error.value = ''
    try {
      const response = await listReportingBuildings(nextPage, nextKeyword)
      if (owner !== generation) return
      records.value = response.records
      total.value = response.total
      page.value = nextPage
    } catch (reason) {
      if (owner === generation) error.value = requestErrorMessage(reason)
    } finally {
      if (owner === generation) loading.value = false
    }
  }

  return { records, total, page, keyword, loading, error, search }
}
