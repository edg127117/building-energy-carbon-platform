import { http } from '@/utils/request'
import type { ApiResult } from '@/types/api'
import type {
  AdminPage,
  BuildingAccessRequestView,
  BuildingAccessStatus,
  BuildingOption,
  BuildingPageQuery,
  CreateUserRequest,
  FormalRoleKey,
  MenuCreateRequest,
  MenuNode,
  MenuUpdateRequest,
  RoleAdminView,
  UpdateUserRequest,
  UserAdminView,
  UserPageQuery,
  UserStatus,
} from '@/types/admin'

const data = <T>(response: { data: ApiResult<T> }): T => response.data.data

/** 管理 API 只适配后端 DTO；鉴权、业务错误和 401 清理由共享 HTTP 客户端负责。 */
export async function pageUsers(query: UserPageQuery): Promise<AdminPage<UserAdminView>> {
  return data(await http.get<ApiResult<AdminPage<UserAdminView>>>('/system/users', { params: query }))
}

export async function getUserDetail(id: number): Promise<UserAdminView> {
  return data(await http.get<ApiResult<UserAdminView>>(`/system/users/${encodeURIComponent(id)}`))
}

export async function createUser(request: CreateUserRequest): Promise<UserAdminView> {
  return data(await http.post<ApiResult<UserAdminView>>('/system/users', request))
}

export async function updateUser(id: number, request: UpdateUserRequest): Promise<UserAdminView> {
  return data(await http.put<ApiResult<UserAdminView>>(`/system/users/${encodeURIComponent(id)}`, request))
}

export async function updateUserStatus(id: number, status: UserStatus): Promise<void> {
  data(await http.put<ApiResult<void>>(`/system/users/${encodeURIComponent(id)}/status`, { status }))
}

export async function resetUserPassword(id: number, password: string): Promise<void> {
  data(await http.put<ApiResult<void>>(`/system/users/${encodeURIComponent(id)}/password`, { password }))
}

export async function replaceUserRoles(id: number, roleKeys: FormalRoleKey[]): Promise<void> {
  data(await http.put<ApiResult<void>>(`/system/users/${encodeURIComponent(id)}/roles`, { roleKeys }))
}

export async function replaceUserBuildings(id: number, buildingIds: string[]): Promise<void> {
  data(await http.put<ApiResult<void>>(`/system/users/${encodeURIComponent(id)}/buildings`, { buildingIds }))
}

export async function deleteUser(id: number): Promise<void> {
  data(await http.delete<ApiResult<void>>(`/system/users/${encodeURIComponent(id)}`))
}

export async function restoreUser(id: number): Promise<UserAdminView> {
  return data(await http.put<ApiResult<UserAdminView>>(`/system/users/${encodeURIComponent(id)}/restore`))
}

export async function listRoles(): Promise<RoleAdminView[]> {
  return data(await http.get<ApiResult<RoleAdminView[]>>('/system/roles'))
}

export async function getRoleMenuIds(id: number): Promise<number[]> {
  return data(await http.get<ApiResult<number[]>>(`/system/roles/${encodeURIComponent(id)}/menus`))
}

export async function replaceRoleMenus(id: number, menuIds: number[]): Promise<void> {
  data(await http.put<ApiResult<void>>(`/system/roles/${encodeURIComponent(id)}/menus`, { menuIds }))
}

export async function getAdminMenuTree(): Promise<MenuNode[]> {
  return data(await http.get<ApiResult<MenuNode[]>>('/menu/admin/tree'))
}

export async function getCurrentMenu(): Promise<MenuNode[]> {
  return data(await http.get<ApiResult<MenuNode[]>>('/menu/current'))
}

export async function addMenu(request: MenuCreateRequest): Promise<MenuNode> {
  return data(await http.post<ApiResult<MenuNode>>('/menu/add', request))
}

export async function updateMenu(request: MenuUpdateRequest): Promise<MenuNode> {
  return data(await http.put<ApiResult<MenuNode>>('/menu/update', request))
}

export async function deleteMenu(id: number): Promise<void> {
  data(await http.delete<ApiResult<void>>(`/menu/delete/${encodeURIComponent(id)}`))
}

export async function pageBuildings(query: BuildingPageQuery): Promise<AdminPage<BuildingOption>> {
  return data(await http.get<ApiResult<AdminPage<BuildingOption>>>('/building/list', { params: query }))
}

export async function listBuildingAccessRequests(status?: BuildingAccessStatus): Promise<BuildingAccessRequestView[]> {
  return data(await http.get<ApiResult<BuildingAccessRequestView[]>>(
    '/system/building-access/requests',
    { params: status ? { status } : {} },
  ))
}

export async function approveBuildingAccessRequest(id: number, comment?: string): Promise<void> {
  data(await http.put<ApiResult<void>>(
    `/system/building-access/requests/${encodeURIComponent(id)}/approve`,
    comment === undefined ? {} : { comment },
  ))
}

export async function rejectBuildingAccessRequest(id: number, comment?: string): Promise<void> {
  data(await http.put<ApiResult<void>>(
    `/system/building-access/requests/${encodeURIComponent(id)}/reject`,
    comment === undefined ? {} : { comment },
  ))
}
