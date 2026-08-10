import { describe, expect, it } from 'vitest'
import { menuParentOptions } from './useMenuManagement'
const tree = [{ id: 1, parentId: 0, menuName: '根', menuType: 'M' as const, path: null, component: null, perms: null, icon: null,
  visible: 1 as const, status: 1 as const, sortOrder: 0, children: [{ id: 2, parentId: 1, menuName: '子', menuType: 'C' as const,
    path: '/hvac-demo', component: null, perms: null, icon: null, visible: 1 as const, status: 1 as const, sortOrder: 0, children: [] }] }]
describe('menu parent candidates', () => {
  it('excludes the edited node and descendants', () => { expect(menuParentOptions(tree, 1).map((item) => item.value)).toEqual([0]) })
})
