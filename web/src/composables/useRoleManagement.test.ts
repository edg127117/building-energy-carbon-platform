import { describe, expect, it } from 'vitest'
import { normalizeCheckedMenuIds } from './useRoleManagement'

const tree = [{ id: 1, parentId: 0, menuName: '根', menuType: 'M' as const, path: null, component: null, perms: null,
  icon: null, visible: 1 as const, status: 1 as const, sortOrder: 0, children: [
    { id: 2, parentId: 1, menuName: '叶', menuType: 'C' as const, path: '/hvac-demo', component: null, perms: null,
      icon: null, visible: 1 as const, status: 1 as const, sortOrder: 0, children: [] },
  ] }]

describe('fixed role menu normalization', () => {
  it('adds descendants and required ancestors to a unique sorted set', () => {
    expect(normalizeCheckedMenuIds(tree, [1])).toEqual([1, 2])
    expect(normalizeCheckedMenuIds(tree, [2, 2])).toEqual([1, 2])
  })
})
