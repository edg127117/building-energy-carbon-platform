import { ref } from 'vue'
import {
  createUser as createUserApi,
  deleteUser as deleteUserApi,
  pageUsers,
  replaceUserBuildings as replaceUserBuildingsApi,
  replaceUserRoles as replaceUserRolesApi,
  resetUserPassword as resetUserPasswordApi,
  restoreUser as restoreUserApi,
  updateUser as updateUserApi,
  updateUserStatus,
} from '@/api/systemAdmin'
import type {
  AdminPage,
  CreateUserRequest,
  FormalRoleKey,
  UpdateUserRequest,
  UserAdminView,
  UserPageQuery,
} from '@/types/admin'

/** 用户列表与账号命令的异步所有权边界；密码只作为单次命令参数经过，不进入持久状态。 */
export function useUserManagement() {
  const query = ref<UserPageQuery>({ page: 1, size: 20, includeDeleted: false })
  const page = ref<AdminPage<UserAdminView>>({ records: [], total: 0, current: 1, size: 20 })
  const loading = ref(false)
  const error = ref<string | null>(null)
  const pending = ref(new Set<string>())
  let generation = 0

  const loadUsers = async () => {
    const owner = ++generation
    loading.value = true
    error.value = null
    try {
      const next = await pageUsers({ ...query.value })
      if (owner === generation) page.value = next
    } catch (reason) {
      if (owner === generation) error.value = messageOf(reason)
      throw reason
    } finally {
      if (owner === generation) loading.value = false
    }
  }

  const setQuery = (patch: Partial<UserPageQuery>, resetPage = true) => {
    query.value = { ...query.value, ...patch, page: resetPage ? 1 : (patch.page ?? query.value.page) }
    return loadUsers()
  }

  const runCommand = async (key: string, command: () => Promise<unknown>) => {
    if (pending.value.has(key)) return
    pending.value = new Set(pending.value).add(key)
    error.value = null
    try {
      await command()
      await loadUsers()
    } catch (reason) {
      error.value = messageOf(reason)
      throw reason
    } finally {
      const next = new Set(pending.value)
      next.delete(key)
      pending.value = next
    }
  }

  const createUser = (request: CreateUserRequest) => runCommand('create', () => createUserApi(request))
  const updateUser = (id: number, request: UpdateUserRequest) => runCommand(`update:${id}`, () => updateUserApi(id, request))
  const replaceUserRoles = (id: number, roles: FormalRoleKey[]) => runCommand(`roles:${id}`, () => replaceUserRolesApi(id, roles))
  const replaceUserBuildings = (id: number, ids: string[]) => runCommand(`buildings:${id}`, () => replaceUserBuildingsApi(id, ids))
  const resetUserPassword = (id: number, password: string) => runCommand(`password:${id}`, () => resetUserPasswordApi(id, password))
  const enableUser = (id: number) => runCommand(`status:${id}`, () => updateUserStatus(id, 1))
  const disableUser = (id: number) => runCommand(`status:${id}`, () => updateUserStatus(id, 0))
  const deleteUser = (id: number) => runCommand(`delete:${id}`, () => deleteUserApi(id))
  const restoreUser = (id: number) => runCommand(`restore:${id}`, () => restoreUserApi(id))

  return { query, page, loading, error, pending, loadUsers, setQuery, createUser, updateUser,
    replaceUserRoles, replaceUserBuildings, resetUserPassword, enableUser, disableUser, deleteUser, restoreUser }
}

function messageOf(reason: unknown) {
  return reason instanceof Error ? reason.message : '用户管理操作失败'
}
