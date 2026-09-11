import { computed, ref } from 'vue'
import { requestErrorMessage } from '@/shared/utils/request-error'
import {
  approveBuildingAccessRequest,
  listBuildingAccessRequests,
  listBuildings,
  listUsers,
  rejectBuildingAccessRequest,
} from '../api/access-control'
import type {
  BuildingAccessRequest,
  BuildingAccessStatus,
  BuildingOption,
  UserView,
} from '../models/access-control'
import { useSensitiveChange } from './use-sensitive-change'

/** 建筑访问审核保留领域状态机；范围替换则进入通用敏感变更审批。 */
export function useBuildingAccessManagement() {
  const requests = ref<BuildingAccessRequest[]>([])
  const users = ref<UserView[]>([])
  const buildings = ref<BuildingOption[]>([])
  const status = ref<BuildingAccessStatus | 'ALL'>('PENDING')
  const loading = ref(false)
  const localError = ref<string | null>(null)
  const reviewPending = ref(new Set<string>())
  const changes = useSensitiveChange()
  let generation = 0

  async function load() {
    const owner = ++generation
    loading.value = true
    localError.value = null
    try {
      const next = await listBuildingAccessRequests(status.value === 'ALL' ? undefined : status.value)
      if (owner === generation) requests.value = next
    } catch (reason) {
      if (owner === generation) localError.value = requestErrorMessage(reason)
      throw reason
    } finally {
      if (owner === generation) loading.value = false
    }
  }

  async function loadOptions() {
    try {
      const [userPage, buildingPage] = await Promise.all([
        listUsers({ page: 1, size: 100, includeDeleted: false }),
        listBuildings(),
      ])
      users.value = userPage.records
      buildings.value = buildingPage.records
    } catch (reason) {
      localError.value = requestErrorMessage(reason)
      throw reason
    }
  }

  async function review(
    requestId: number,
    action: 'approve' | 'reject',
    comment?: string,
  ) {
    const key = `review:${requestId}`
    if (reviewPending.value.has(key)) return undefined
    reviewPending.value = new Set(reviewPending.value).add(key)
    localError.value = null
    try {
      if (action === 'approve') await approveBuildingAccessRequest(requestId, comment)
      else await rejectBuildingAccessRequest(requestId, comment)
      await Promise.all([load(), loadOptions()])
      return true
    } catch (reason) {
      localError.value = requestErrorMessage(reason)
      throw reason
    } finally {
      const next = new Set(reviewPending.value)
      next.delete(key)
      reviewPending.value = next
    }
  }

  function requestBuildingScope(userId: number, buildingIds: string[]) {
    return changes.start('REPLACE_USER_BUILDINGS', { userId, buildingIds }, `building-scope:${userId}`)
  }

  return {
    requests,
    users,
    buildings,
    status,
    loading,
    error: computed(() => localError.value ?? changes.error.value),
    reviewPending,
    load,
    loadOptions,
    review,
    requestBuildingScope,
    changes,
  }
}
