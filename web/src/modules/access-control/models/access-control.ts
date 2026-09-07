export type FormalRoleKey =
  | 'BUILDING_OWNER'
  | 'ENERGY_MANAGER'
  | 'THIRD_PARTY'
  | 'PLATFORM_ADMIN'

export type UserStatus = 0 | 1

export type PageResult<T> = {
  records: T[]
  total: number
  size: number
  current: number
  pages?: number
}

export type UserView = {
  id: number
  username: string
  nickname: string | null
  phone: string | null
  status: UserStatus
  delFlag: UserStatus
  roles: FormalRoleKey[]
  buildingIds: string[]
  createTime: string
  updateTime: string
}

export type UserPageQuery = {
  page: number
  size: number
  keyword?: string
  status?: UserStatus
  includeDeleted: boolean
}

export type UserProfileUpdate = {
  nickname?: string | null
  phone?: string | null
}

export type OpenUserAccountCommand = {
  username: string
  nickname?: string | null
  phone?: string | null
  roleKeys: FormalRoleKey[]
  buildingIds: string[]
}

export type RoleView = {
  id: number
  roleKey: FormalRoleKey
  roleName: string
  dataScope: string
  status: UserStatus
}

export type MenuType = 'M' | 'C' | 'F'

export type MenuNode = {
  id: number
  parentId: number
  menuName: string
  menuType: MenuType
  path: string | null
  component: string | null
  perms: string | null
  icon: string | null
  visible: UserStatus
  status: UserStatus
  sortOrder: number
  children: MenuNode[]
}

export type MenuCommand = {
  parentId: number
  menuName: string
  menuType: MenuType
  path?: string | null
  component?: string | null
  perms?: string | null
  icon?: string | null
  visible: UserStatus
  status: UserStatus
  sortOrder: number
}

export type MenuUpdateCommand = MenuCommand & { id: number }

export type BuildingOption = {
  buildingId: string
  buildingName: string
  buildingCode: string | null
  buildingType?: string | null
  climateZone?: string | null
}

export type BuildingAccessStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'

export type BuildingAccessRequest = {
  id: number
  userId: number
  username: string | null
  buildingId: string
  buildingName: string | null
  reason: string
  status: BuildingAccessStatus
  reviewerId: number | null
  reviewComment: string | null
  reviewTime: string | null
  createTime: string
}

export type SensitiveChangeStatus =
  | 'DRAFT'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'EXECUTED'
  | 'REJECTED'
  | 'WITHDRAWN'
  | 'EXECUTION_FAILED'

export type SensitiveChangeOperation =
  | 'OPEN_USER_ACCOUNT'
  | 'ISSUE_PASSWORD_RESET_TOKEN'
  | 'DELETE_USER_ACCOUNT'
  | 'RESTORE_USER_ACCOUNT'
  | 'UPDATE_USER_STATUS'
  | 'REPLACE_USER_FORMAL_ROLES'
  | 'REPLACE_USER_BUILDINGS'
  | 'REVOKE_USER_BUILDING'
  | 'REPLACE_ROLE_MENUS'
  | 'CREATE_MENU'
  | 'UPDATE_MENU'
  | 'DELETE_MENU'
  | 'ENABLE_DEVICE_PRODUCT'
  | 'DISABLE_DEVICE_PRODUCT'
  | 'BIND_PENDING_DEVICE'
  | 'ACTIVATE_DEVICE_IDENTITY'
  | 'DEACTIVATE_DEVICE_IDENTITY'

export type SensitiveChange = {
  requestId: string
  operationCode: SensitiveChangeOperation
  status: SensitiveChangeStatus
  buildingId: string | null
  targetType: string
  targetId: string
  submittedBy: number
  submittedAt: string | null
  reviewerId: number | null
  reviewComment: string | null
  reviewedAt: string | null
  executedAt: string | null
  executionErrorCode: string | null
  environmentMode: string
  selfApprovalDevMode: boolean
  traceId: string | null
  oneTimeToken: string | null
  tokenPurpose: string | null
  tokenExpiresAt: string | null
}

export type MenuRouteRegistration = {
  path: string
  routeName: string
}

export type CurrentMenuItem = {
  id: number
  label: string
  path?: string
  routeName?: string
  children: CurrentMenuItem[]
}
