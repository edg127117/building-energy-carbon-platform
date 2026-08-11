import { ref } from 'vue'
import { approveBuildingAccessRequest, listBuildingAccessRequests, pageBuildings, pageUsers, rejectBuildingAccessRequest, replaceUserBuildings } from '@/api/systemAdmin'
import type { BuildingAccessRequestView, BuildingAccessStatus, BuildingOption, UserAdminView } from '@/types/admin'

/** 直接授权与申请审核保持两组命令；代次隔离筛选切换，失败保留最近成功申请列表。 */
export function useBuildingAccessManagement() {
  const requests = ref<BuildingAccessRequestView[]>([]); const users = ref<UserAdminView[]>([]); const buildings = ref<BuildingOption[]>([])
  const status = ref<BuildingAccessStatus | 'ALL'>('PENDING'); const loading = ref(false); const error = ref<string | null>(null); const pending = ref(false)
  let generation = 0
  const load = async () => {
    const owner = ++generation; loading.value = true; error.value = null
    try { const next = await listBuildingAccessRequests(status.value === 'ALL' ? undefined : status.value); if (owner === generation) requests.value = next }
    catch (reason) { if (owner === generation) error.value = messageOf(reason); throw reason }
    finally { if (owner === generation) loading.value = false }
  }
  const loadOptions = async () => {
    const [userPage, buildingPage] = await Promise.all([pageUsers({ page: 1, size: 100, includeDeleted: false }), pageBuildings({ page: 1, size: 100 })])
    users.value = userPage.records; buildings.value = buildingPage.records
  }
  const command = async (action: () => Promise<unknown>) => {
    if (pending.value) return; pending.value = true; error.value = null
    try { await action(); await Promise.all([load(), loadOptions()]) }
    catch (reason) { error.value = messageOf(reason); throw reason }
    finally { pending.value = false }
  }
  const assignBuildings = (userId: number, ids: string[]) => command(() => replaceUserBuildings(userId, ids))
  const approve = (id: number, comment?: string) => command(() => approveBuildingAccessRequest(id, comment))
  const reject = (id: number, comment?: string) => command(() => rejectBuildingAccessRequest(id, comment))
  return { requests, users, buildings, status, loading, error, pending, load, loadOptions, assignBuildings, approve, reject }
}
function messageOf(reason: unknown) { return reason instanceof Error ? reason.message : '建筑授权操作失败' }
