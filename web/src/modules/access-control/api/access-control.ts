import { requestApi } from '@/infrastructure/http/public'
import type {
  BuildingAccessRequest,
  BuildingAccessStatus,
  BuildingOption,
  FormalRoleKey,
  MenuCommand,
  MenuNode,
  MenuUpdateCommand,
  OpenUserAccountCommand,
  PageResult,
  RoleView,
  SensitiveChange,
  SensitiveChangeOperation,
  UserPageQuery,
  UserProfileUpdate,
  UserView,
} from '../models/access-control'

export function listUsers(query: UserPageQuery): Promise<PageResult<UserView>> {
  return requestApi<PageResult<UserView>>({ method: 'get', url: '/system/users', params: query })
}

export function updateUserProfile(userId: number, payload: UserProfileUpdate): Promise<UserView> {
  return requestApi<UserView>({ method: 'put', url: `/system/users/${encodeURIComponent(userId)}`, data: payload })
}

export function listRoles(): Promise<RoleView[]> {
  return requestApi<RoleView[]>({ method: 'get', url: '/system/roles' })
}

export function getRoleMenuIds(roleId: number): Promise<number[]> {
  return requestApi<number[]>({ method: 'get', url: `/system/roles/${encodeURIComponent(roleId)}/menus` })
}

export function getAdminMenuTree(): Promise<MenuNode[]> {
  return requestApi<MenuNode[]>({ method: 'get', url: '/menu/admin/tree' })
}

export function getCurrentMenu(): Promise<MenuNode[]> {
  return requestApi<MenuNode[]>({ method: 'get', url: '/menu/current' })
}

export function listBuildings(): Promise<PageResult<BuildingOption>> {
  return requestApi<PageResult<BuildingOption>>({
    method: 'get',
    url: '/building/list',
    params: { page: 1, size: 100 },
  })
}

export function listBuildingAccessRequests(status?: BuildingAccessStatus): Promise<BuildingAccessRequest[]> {
  return requestApi<BuildingAccessRequest[]>({
    method: 'get',
    url: '/system/building-access/requests',
    params: status ? { status } : undefined,
  })
}

export function approveBuildingAccessRequest(requestId: number, comment?: string): Promise<void> {
  return requestApi<void>({
    method: 'put',
    url: `/system/building-access/requests/${encodeURIComponent(requestId)}/approve`,
    data: comment ? { comment } : {},
  })
}

export function rejectBuildingAccessRequest(requestId: number, comment?: string): Promise<void> {
  return requestApi<void>({
    method: 'put',
    url: `/system/building-access/requests/${encodeURIComponent(requestId)}/reject`,
    data: comment ? { comment } : {},
  })
}

export function createChangeRequest(
  operationCode: SensitiveChangeOperation,
  command: Record<string, unknown>,
  idempotencyKey: string,
): Promise<SensitiveChange> {
  return requestApi<SensitiveChange>({
    method: 'post',
    url: '/v1/backoffice/change-requests',
    data: { operationCode, command, idempotencyKey },
  })
}

export function getChangeRequest(requestId: string): Promise<SensitiveChange> {
  return requestApi<SensitiveChange>({
    method: 'get',
    url: `/v1/backoffice/change-requests/${encodeURIComponent(requestId)}`,
  })
}

export function submitChangeRequest(requestId: string): Promise<SensitiveChange> {
  return requestApi<SensitiveChange>({ method: 'post', url: `/v1/backoffice/change-requests/${encodeURIComponent(requestId)}/submit` })
}

export function withdrawChangeRequest(requestId: string): Promise<SensitiveChange> {
  return requestApi<SensitiveChange>({ method: 'post', url: `/v1/backoffice/change-requests/${encodeURIComponent(requestId)}/withdraw` })
}

export function approveChangeRequest(requestId: string, comment: string): Promise<SensitiveChange> {
  return requestApi<SensitiveChange>({
    method: 'post',
    url: `/v1/backoffice/change-requests/${encodeURIComponent(requestId)}/approve`,
    data: { comment },
  })
}

export function rejectChangeRequest(requestId: string, comment: string): Promise<SensitiveChange> {
  return requestApi<SensitiveChange>({
    method: 'post',
    url: `/v1/backoffice/change-requests/${encodeURIComponent(requestId)}/reject`,
    data: { comment },
  })
}

export function executeChangeRequest(requestId: string): Promise<SensitiveChange> {
  return requestApi<SensitiveChange>({ method: 'post', url: `/v1/backoffice/change-requests/${encodeURIComponent(requestId)}/execute` })
}

export function newIdempotencyKey(): string {
  const uuid = globalThis.crypto?.randomUUID?.()
  return uuid ?? `web-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export type { FormalRoleKey, MenuCommand, MenuUpdateCommand, OpenUserAccountCommand }
