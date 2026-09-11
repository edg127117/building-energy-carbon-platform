import { describe, expect, it } from 'vitest'
import { menuParentOptions, reconcileExpandedMenuIds } from './use-menu-management'

const tree = [{
  id: 1, parentId: 0, menuName: 'root', menuType: 'M' as const, path: null, component: null, perms: null,
  icon: null, visible: 1 as const, status: 1 as const, sortOrder: 0, children: [{
    id: 2, parentId: 1, menuName: 'page', menuType: 'C' as const, path: '/system/users', component: null,
    perms: null, icon: null, visible: 1 as const, status: 1 as const, sortOrder: 0, children: [],
  }],
}]

describe('menu parent boundary', () => {
  it('excludes the edited menu and its descendants as parent candidates', () => {
    expect(menuParentOptions(tree, 1).map(item => item.value)).toEqual([0])
  })

  it('preserves only still-expandable menu identifiers', () => {
    expect(reconcileExpandedMenuIds([1, 999], tree)).toEqual([1])
  })

  it('does not offer an operation permission as a parent menu', () => {
    const withPermission = [{ ...tree[0], children: [...tree[0].children, { ...tree[0].children[0], id: 3, menuType: 'F' as const }] }]

    expect(menuParentOptions(withPermission).map(item => item.value)).toEqual([0, 1, 2])
  })
})
