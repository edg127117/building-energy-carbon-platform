import { ref } from 'vue'
import { listClassificationBuildings } from '../api/buildings'
import type { AccessibleBuilding } from '../models/classification'
import { requestErrorMessage } from '@/shared/utils/request-error'

/** 建筑目录按当前账号授权分页读取，页面只保存被选中的建筑标识。 */
export function useClassificationBuildings() {
  const records = ref<AccessibleBuilding[]>([])
  const total = ref(0)
  const page = ref(1)
  const keyword = ref('')
  const loading = ref(false)
  const error = ref('')
  let generation = 0

  async function search(nextPage = 1, nextKeyword = keyword.value) {
    const owner = ++generation
    keyword.value = nextKeyword
    loading.value = true
    error.value = ''
    try {
      const result = await listClassificationBuildings(nextPage, nextKeyword)
      if (owner !== generation) return
      records.value = result.records
      total.value = result.total
      page.value = nextPage
    } catch (reason) {
      if (owner === generation) error.value = requestErrorMessage(reason)
    } finally {
      if (owner === generation) loading.value = false
    }
  }

  return { records, total, page, keyword, loading, error, search }
}
