import { computed, ref } from 'vue'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { listBuildings, listUsers, updateUserProfile } from '../api/access-control'
import type {
  BuildingOption,
  OpenUserAccountCommand,
  PageResult,
  UserPageQuery,
  UserProfileUpdate,
  UserView,
} from '../models/access-control'
import { useSensitiveChange } from './use-sensitive-change'

const emptyPage: PageResult<UserView> = { records: [], total: 0, size: 20, current: 1 }

/** 查询竞争由代次隔离；敏感写入只创建并提交审批申请，不假设其已经生效。 */
export function useUserManagement() {
  const query = ref<UserPageQuery>({ page: 1, size: 20, includeDeleted: false })
  const page = ref<PageResult<UserView>>(emptyPage)
  const buildings = ref<BuildingOption[]>([])
  const loading = ref(false)
  const loadingBuildings = ref(false)
  const localError = ref<string | null>(null)
  const directPending = ref(new Set<string>())
  const changes = useSensitiveChange()
  let generation = 0

  async function load() {
    const owner = ++generation
    loading.value = true
    localError.value = null
    try {
      const next = await listUsers({ ...query.value })
      if (owner === generation) page.value = next
    } catch (reason) {
      if (owner === generation) localError.value = requestErrorMessage(reason)
      throw reason
    } finally {
      if (owner === generation) loading.value = false
    }
  }

  async function loadBuildings() {
    loadingBuildings.value = true
    try {
      buildings.value = (await listBuildings()).records
    } catch (reason) {
      localError.value = requestErrorMessage(reason)
      throw reason
    } finally {
      loadingBuildings.value = false
    }
  }

  function setQuery(patch: Partial<UserPageQuery>, resetPage = true) {
    query.value = {
      ...query.value,
      ...patch,
      page: resetPage ? 1 : (patch.page ?? query.value.page),
    }
    return load()
  }

  async function updateProfile(userId: number, payload: UserProfileUpdate) {
    const key = `profile:${userId}`
    if (directPending.value.has(key)) return undefined
    directPending.value = new Set(directPending.value).add(key)
    localError.value = null
    try {
      await updateUserProfile(userId, payload)
      await load()
      return true
    } catch (reason) {
      localError.value = requestErrorMessage(reason)
      throw reason
    } finally {
      const next = new Set(directPending.value)
      next.delete(key)
      directPending.value = next
    }
  }

  function openAccount(command: OpenUserAccountCommand) {
    return changes.start('OPEN_USER_ACCOUNT', command, `open:${command.username}`)
  }

  function requestPasswordReset(userId: number) {
    return changes.start('ISSUE_PASSWORD_RESET_TOKEN', { userId }, `password:${userId}`)
  }

  function updateStatus(userId: number, status: 0 | 1) {
    return changes.start('UPDATE_USER_STATUS', { userId, status }, `status:${userId}`)
  }

  function replaceRoles(userId: number, roleKeys: string[]) {
    return changes.start('REPLACE_USER_FORMAL_ROLES', { userId, roleKeys }, `roles:${userId}`)
  }

  function replaceBuildings(userId: number, buildingIds: string[]) {
    return changes.start('REPLACE_USER_BUILDINGS', { userId, buildingIds }, `buildings:${userId}`)
  }

  function remove(userId: number) {
    return changes.start('DELETE_USER_ACCOUNT', { userId }, `delete:${userId}`)
  }

  function restore(userId: number) {
    return changes.start('RESTORE_USER_ACCOUNT', { userId }, `restore:${userId}`)
  }

  return {
    query,
    page,
    buildings,
    loading,
    loadingBuildings,
    error: computed(() => localError.value ?? changes.error.value),
    directPending,
    load,
    loadBuildings,
    setQuery,
    updateProfile,
    openAccount,
    requestPasswordReset,
    updateStatus,
    replaceRoles,
    replaceBuildings,
    remove,
    restore,
    changes,
  }
}
