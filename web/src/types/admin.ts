/**
 * 最小后台的前后端契约类型。
 * 这些类型只描述 MySQL 管理数据和全量替换命令，不表达路由可执行性；菜单能否进入导航
 * 仍由前端显式注册表判断，接口访问权始终由后端 PLATFORM_ADMIN 校验决定。
 */
export type FormalRoleKey =
  | 'BUILDING_OWNER'
  | 'ENERGY_MANAGER'
  | 'THIRD_PARTY'
  | 'PLATFORM_ADMIN'

export type AdminPage<T> = {
  records: T[]
  total: number
  size: number
  current: number
  pages?: number
}

export type UserStatus = 0 | 1
export type DeleteFlag = 0 | 1

export type UserAdminView = {
  id: number
  username: string
  nickname: string | null
  phone: string | null
  status: UserStatus
  delFlag: DeleteFlag
  roles: FormalRoleKey[]
  buildingIds: string[]
  createTime: string
  updateTime: string
}

export type RoleAdminView = {
  id: number
  roleKey: FormalRoleKey
  roleName: string
  dataScope: 'ALL' | 'BUILDING' | 'SELF' | string
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

export type BuildingOption = {
  buildingId: string
  buildingName: string
  buildingCode: string | null
  buildingType?: string | null
  climateZone?: string | null
}

export type BuildingAccessStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED'

export type BuildingAccessRequestView = {
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

export type UserPageQuery = {
  page: number
  size: number
  keyword?: string
  status?: UserStatus
  includeDeleted: boolean
}

export type CreateUserRequest = {
  username: string
  password: string
  nickname?: string | null
  phone?: string | null
  roleKeys?: FormalRoleKey[]
  buildingIds?: string[]
}

export type UpdateUserRequest = {
  nickname?: string | null
  phone?: string | null
}

export type MenuCreateRequest = {
  parentId?: number
  menuName: string
  menuType: MenuType
  path?: string | null
  component?: string | null
  perms?: string | null
  icon?: string | null
  visible?: UserStatus
  status?: UserStatus
  sortOrder?: number
}

export type MenuUpdateRequest = MenuCreateRequest & { id: number }

export type BuildingPageQuery = {
  page: number
  size: number
  keyword?: string
}
