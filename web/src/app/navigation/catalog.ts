import { screens } from '@/modules/large-screen/public'
import type { GrantedMenu } from '@/modules/auth/public'
export type WorkspaceId = 'monitor' | 'operations' | 'configuration'
export interface Workspace { id: WorkspaceId; titleKey: string; hintKey: string }
export interface PageEntry { id: string; system: WorkspaceId; groupKey: string; titleKey: string; path: string; title?: string }
export const workspaces: Workspace[] = ['monitor', 'operations', 'configuration'].map(id => ({
  id: id as WorkspaceId, titleKey: `workspaces.${id}`, hintKey: `workspaces.${id}Hint`,
}))
function group(system: WorkspaceId, groupId: string, children: string[]): PageEntry[] {
  return children.map(key => ({ id: `${system}-${groupId}-${key}`, system, groupKey: `workspaces.${groupId}`,
    titleKey: `workspaces.${key}`, path: `/${system}/${groupId}/${key}` }))
}
/** 注册合法页面能力；只有后端明确返回的叶子授权才能启用，目录授权不会扩展为子页面授权。 */
export const pages: PageEntry[] = [
  ...screens.map(screen => ({ id: screen.id, system: 'monitor' as const, groupKey: screen.titleKey, titleKey: screen.titleKey, path: screen.path })),
  ...group('operations', 'overview', ['running']),
  ...group('operations', 'realtime', ['hvac', 'power', 'lighting', 'renewable']),
  ...group('operations', 'energy', ['energyItems', 'energyZones', 'trend', 'baselineAnalysis', 'diagnosis']),
  ...group('operations', 'carbon', ['carbonOverview', 'carbonDetails', 'trend', 'reduction', 'assets']),
  ...group('operations', 'devices', ['businessDevices', 'pendingDevices', 'meters']),
  ...group('operations', 'alarms', ['liveAlarms', 'historyAlarms']),
  ...group('operations', 'maintenance', ['plans', 'orders', 'faults']),
  ...group('operations', 'reports', ['energyReports', 'carbonReports']),
  ...group('configuration', 'ingestion', ['products', 'protocols', 'collection', 'interfaces']),
  ...group('configuration', 'space', ['buildings', 'spaces', 'systemGroups', 'equipmentSpaces']),
  ...group('configuration', 'indicators', ['indicatorList', 'formulas']),
  ...group('configuration', 'factors', ['emissionFactors', 'factorVersions']),
  ...group('configuration', 'rules', ['baselines', 'alarmRules']),
  ...group('configuration', 'access', ['users', 'roles', 'buildingAccess', 'changeRequests']),
  ...group('configuration', 'settings', ['menus']),
]

/** 数据库树决定授权、分组和顺序；页面注册表只负责校验规范路径及提供前端组件元数据。 */
export function authorizedPages(menus: GrantedMenu[]): PageEntry[] {
  const registry = new Map(pages.map(page => [page.path, page]))
  const matched: PageEntry[] = []
  const grantedPaths = new Set<string>()
  function visit(nodes: GrantedMenu[], workspace?: WorkspaceId, groupPath?: string) {
    for (const node of nodes) {
      if (node.status !== 1 || node.visible !== 1) continue
      const root = node.menuType === 'M' && /^\/(monitor|operations|configuration)$/.exec(node.path ?? '')
      const currentWorkspace = root ? root[1] as WorkspaceId : workspace
      const directory = node.menuType === 'M' && currentWorkspace
        && new RegExp(`^/${currentWorkspace}/[^/]+$`).test(node.path ?? '')
      const currentGroupPath = root ? node.path! : directory ? node.path! : groupPath
      if (node.menuType === 'C' && node.path) {
        if (grantedPaths.has(node.path)) throw new Error('DUPLICATE_MENU_PATH')
        const page = registry.get(node.path)
        if (!page) {
          if (/^\/(monitor|operations|configuration)\//.test(node.path)) throw new Error('UNREGISTERED_MENU_PATH')
          continue
        }
        const expectedGroupPath = page.path.split('/').slice(0, -1).join('/')
        if (currentWorkspace !== page.system || currentGroupPath !== expectedGroupPath) throw new Error('INVALID_MENU_HIERARCHY')
        grantedPaths.add(node.path)
        matched.push({ ...page, title: node.menuName.trim() || undefined })
      }
      if (node.children) visit(node.children, currentWorkspace, currentGroupPath)
    }
  }
  visit(menus)
  return matched
}
