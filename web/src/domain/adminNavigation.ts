import type { MenuNode } from '@/types/admin'

export const IMPLEMENTED_MENU_ROUTES = {
  '/hvac-demo': { routeName: 'hvac-demo', admin: false },
  '/system/users': { routeName: 'system-users', admin: true },
  '/system/roles': { routeName: 'system-roles', admin: true },
  '/system/menus': { routeName: 'system-menus', admin: true },
  '/system/building-access': {
    routeName: 'system-building-access',
    admin: true,
  },
} as const

export type ImplementedMenuPath = keyof typeof IMPLEMENTED_MENU_ROUTES

export type AdminNavigationItem = {
  id: number
  label: string
  path?: ImplementedMenuPath
  routeName?: (typeof IMPLEMENTED_MENU_ROUTES)[ImplementedMenuPath]['routeName']
  admin?: boolean
  children: AdminNavigationItem[]
}

export type MenuImplementationSummary =
  | { kind: 'directory'; implementedPageCount: number; totalPageCount: number }
  | { kind: 'page'; implemented: boolean }
  | { kind: 'permission' }

export type MenuTableNode = Omit<MenuNode, 'children'> & { children?: MenuTableNode[] }

/**
 * 只有编译期注册表中的精确路径才能成为链接。数据库菜单控制导航可见性，不提供组件
 * 加载地址，也不能扩展前端路由或替代后端权限校验。
 */
export function isImplementedMenuPath(path: string | null | undefined): path is ImplementedMenuPath {
  return Boolean(path && Object.prototype.hasOwnProperty.call(IMPLEMENTED_MENU_ROUTES, path))
}

/**
 * 目录本身只负责组织导航，因此汇总全部后代页面；页面和按钮则分别判断精确注册与不适用。
 * 显示、启停配置不参与实现统计，避免把数据库状态误报成前端页面能力。
 */
export function summarizeMenuImplementation(menu: MenuNode): MenuImplementationSummary {
  if (menu.menuType === 'C') {
    return { kind: 'page', implemented: isImplementedMenuPath(menu.path) }
  }
  if (menu.menuType === 'F') return { kind: 'permission' }

  let implementedPageCount = 0
  let totalPageCount = 0
  const visit = (items: MenuNode[]) => items.forEach((item) => {
    if (item.menuType === 'C') {
      totalPageCount += 1
      if (isImplementedMenuPath(item.path)) implementedPageCount += 1
    }
    visit(item.children ?? [])
  })
  visit(menu.children ?? [])
  return { kind: 'directory', implementedPageCount, totalPageCount }
}

/** Ant Design 只在真实父节点上接收表格子字段，避免空 children 数组生成误导性的展开按钮。 */
export function buildMenuTableTree(tree: MenuNode[]): MenuTableNode[] {
  return tree.map((menu) => {
    const { children, ...row } = menu
    const tableChildren = buildMenuTableTree(children)
    return tableChildren.length > 0 ? { ...row, children: tableChildren } : row
  })
}

/** 将后端当前菜单树裁剪为受控导航副本，不修改缓存中的原始 DTO。 */
export function buildImplementedNavigation(tree: MenuNode[]): AdminNavigationItem[] {
  const emittedPaths = new Set<ImplementedMenuPath>()

  const visit = (node: MenuNode): AdminNavigationItem | null => {
    if (node.visible !== 1 || node.status !== 1) return null

    const children = node.children
      .map(visit)
      .filter((item): item is AdminNavigationItem => item !== null)

    if (node.menuType === 'C' && isImplementedMenuPath(node.path)) {
      if (emittedPaths.has(node.path)) return null
      emittedPaths.add(node.path)
      const registration = IMPLEMENTED_MENU_ROUTES[node.path]
      return {
        id: node.id,
        label: node.menuName,
        path: node.path,
        routeName: registration.routeName,
        admin: registration.admin,
        children,
      }
    }

    if (children.length === 0) return null
    return { id: node.id, label: node.menuName, children }
  }

  return tree.map(visit).filter((item): item is AdminNavigationItem => item !== null)
}
