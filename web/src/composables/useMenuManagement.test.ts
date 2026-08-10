import { describe, expect, it } from 'vitest'
import { menuParentOptions, reconcileExpandedMenuIds } from './useMenuManagement'
const tree = [{ id: 1, parentId: 0, menuName: '根', menuType: 'M' as const, path: null, component: null, perms: null, icon: null,
  visible: 1 as const, status: 1 as const, sortOrder: 0, children: [{ id: 2, parentId: 1, menuName: '子', menuType: 'C' as const,
    path: '/hvac-demo', component: null, perms: null, icon: null, visible: 1 as const, status: 1 as const, sortOrder: 0, children: [] }] }]
describe('menu parent candidates', () => {
  it('excludes the edited node and descendants', () => { expect(menuParentOptions(tree, 1).map((item) => item.value)).toEqual([0]) })

  it('expands all directories after the first async load', () => {
    expect(reconcileExpandedMenuIds([], tree, false)).toEqual([1])
  })

  it('preserves deliberate collapse and removes keys that are no longer expandable', () => {
    expect(reconcileExpandedMenuIds([], tree, true)).toEqual([])
    expect(reconcileExpandedMenuIds([1, 999], tree, true)).toEqual([1])
  })

  it('does not offer button permissions as parent menus', () => {
    const withButton = [{ ...tree[0], children: [...tree[0].children, { ...tree[0].children[0], id: 3, menuName: '导出按钮', menuType: 'F' as const }] }]

    expect(menuParentOptions(withButton).map((item) => item.value)).toEqual([0, 1, 2])
  })
})
