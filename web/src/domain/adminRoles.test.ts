import { describe, expect, it } from 'vitest'
import { adminRoleLabel } from './adminRoles'

describe('admin role presentation', () => {
  it('translates fixed backend role keys for user-facing surfaces', () => {
    expect(adminRoleLabel('BUILDING_OWNER')).toBe('建筑业主')
    expect(adminRoleLabel('ENERGY_MANAGER')).toBe('能效管理方')
    expect(adminRoleLabel('THIRD_PARTY')).toBe('接口调用方')
    expect(adminRoleLabel('PLATFORM_ADMIN')).toBe('平台管理员')
  })
})
