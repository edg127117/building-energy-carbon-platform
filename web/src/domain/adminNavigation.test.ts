import { describe, expect, it } from 'vitest'
import type { MenuNode } from '@/types/admin'
import {
  IMPLEMENTED_MENU_ROUTES,
  buildMenuTableTree,
  buildImplementedNavigation,
  isImplementedMenuPath,
  summarizeMenuImplementation,
} from './adminNavigation'

const node = (input: Partial<MenuNode> & Pick<MenuNode, 'id' | 'menuName'>): MenuNode => ({
  id: input.id,
  parentId: input.parentId ?? 0,
  menuName: input.menuName,
  menuType: input.menuType ?? 'M',
  path: input.path ?? null,
  component: input.component ?? null,
  perms: input.perms ?? null,
  icon: input.icon ?? null,
  visible: input.visible ?? 1,
  status: input.status ?? 1,
  sortOrder: input.sortOrder ?? 0,
  children: input.children ?? [],
})

describe('controlled administration navigation', () => {
  it('uses only exact registered paths', () => {
    expect(Object.keys(IMPLEMENTED_MENU_ROUTES)).toEqual([
      '/hvac-demo',
      '/system/users',
      '/system/roles',
      '/system/menus',
      '/system/building-access',
    ])
    expect(isImplementedMenuPath('/system/users')).toBe(true)
    expect(isImplementedMenuPath('/system/users/1')).toBe(false)
    expect(isImplementedMenuPath('/system/generator')).toBe(false)
  })

  it('keeps groups only for registered visible enabled descendants without mutation', () => {
    const tree = [node({
      id: 200,
      menuName: '系统管理',
      path: '/system',
      children: [
        node({ id: 211, parentId: 200, menuName: '用户管理', menuType: 'C', path: '/system/users' }),
        node({ id: 212, parentId: 200, menuName: '未知页', menuType: 'C', path: '/system/generator' }),
        node({ id: 213, parentId: 200, menuName: '隐藏页', menuType: 'C', path: '/system/roles', visible: 0 }),
      ],
    })]
    const snapshot = structuredClone(tree)

    expect(buildImplementedNavigation(tree)).toEqual([{
      id: 200,
      label: '系统管理',
      children: [{
        id: 211,
        label: '用户管理',
        path: '/system/users',
        routeName: 'system-users',
        admin: true,
        children: [],
      }],
    }])
    expect(tree).toEqual(snapshot)
  })

  it('deduplicates repeated leaf paths and ignores buttons', () => {
    const tree = [
      node({ id: 1, menuName: '大屏', menuType: 'C', path: '/hvac-demo' }),
      node({ id: 2, menuName: '重复', menuType: 'C', path: '/hvac-demo' }),
      node({ id: 3, menuName: '按钮', menuType: 'F', path: '/system/users' }),
    ]
    expect(buildImplementedNavigation(tree).map((item) => item.path)).toEqual(['/hvac-demo'])
  })

  it('summarizes directory descendants instead of judging the directory path as a page', () => {
    const directory = node({
      id: 100,
      menuName: '中央空调调适',
      path: '/hvac',
      children: [
        node({ id: 101, parentId: 100, menuName: 'HVAC 能效大屏', menuType: 'C', path: '/hvac-demo' }),
        node({
          id: 110,
          parentId: 100,
          menuName: '单机调适',
          children: [node({ id: 111, parentId: 110, menuName: '冷机页面', menuType: 'C', path: '/single/chiller', visible: 0, status: 0 })],
        }),
      ],
    })

    expect(summarizeMenuImplementation(directory)).toEqual({
      kind: 'directory',
      implementedPageCount: 1,
      totalPageCount: 2,
    })
  })

  it('distinguishes registered pages, unregistered pages and button permissions', () => {
    expect(summarizeMenuImplementation(node({ id: 1, menuName: '大屏', menuType: 'C', path: '/hvac-demo' }))).toEqual({
      kind: 'page',
      implemented: true,
    })
    expect(summarizeMenuImplementation(node({ id: 2, menuName: '旧页面', menuType: 'C', path: '/old' }))).toEqual({
      kind: 'page',
      implemented: false,
    })
    expect(summarizeMenuImplementation(node({ id: 3, menuName: '按钮', menuType: 'F', path: '/hvac-demo' }))).toEqual({
      kind: 'permission',
    })
  })

  it('only exposes the table child field for rows that can really expand', () => {
    const tableTree = buildMenuTableTree([
      node({ id: 1, menuName: '目录', children: [node({ id: 2, parentId: 1, menuName: '页面', menuType: 'C' })] }),
    ])

    expect(tableTree[0].children).toHaveLength(1)
    expect(tableTree[0].children?.[0].children).toBeUndefined()
  })
})
