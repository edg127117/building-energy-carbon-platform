import { screens } from '@/modules/large-screen/public'
import type { GrantedMenu } from '@/modules/auth/public'
export type WorkspaceId = 'monitor' | 'operations' | 'configuration'
export interface Workspace { id: WorkspaceId; titleKey: string; hintKey: string }
export interface PageEntry { id: string; system: WorkspaceId; groupKey: string; titleKey: string; path: string; legacyPath?: string; title?: string }
export const workspaces: Workspace[] = ['monitor', 'operations', 'configuration'].map(id => ({
  id: id as WorkspaceId, titleKey: `workspaces.${id}`, hintKey: `workspaces.${id}Hint`,
}))
function group(system: WorkspaceId, groupId: string, children: Array<string | [string, string]>): PageEntry[] {
  return children.map(item => {
    const [key, legacyPath] = typeof item === 'string' ? [item, undefined] : item
    return { id: `${system}-${groupId}-${key}`, system, groupKey: `workspaces.${groupId}`,
      titleKey: `workspaces.${key}`, path: `/${system}/${groupId}/${key}`, legacyPath }
  })
}
/** 注册合法页面能力；只有后端明确返回的叶子授权才能启用，目录授权不会扩展为子页面授权。 */
export const pages: PageEntry[] = [
  ...screens.map(screen => ({ id: screen.id, system: 'monitor' as const, groupKey: screen.titleKey, titleKey: screen.titleKey, path: screen.path })),
  ...group('operations', 'overview', ['running']),
  ...group('operations', 'realtime', ['hvac', 'power', 'lighting', 'renewable']),
  ...group('operations', 'energy', ['energyItems', 'energyZones', 'trend', 'baselineAnalysis', 'diagnosis']),
  ...group('operations', 'carbon', ['carbonOverview', 'carbonDetails', 'trend', 'reduction', 'assets']),
  ...group('operations', 'devices', ['businessDevices', 'meters']),
  ...group('operations', 'alarms', ['liveAlarms', 'historyAlarms']),
  ...group('operations', 'maintenance', ['plans', 'orders', 'faults']),
  ...group('operations', 'reports', ['energyReports', 'carbonReports']),
  ...group('configuration', 'ingestion', [['pendingDevices', '/system/device-onboarding'], ['points', '/system/devices'], ['products', '/system/device-products'], 'collection', 'interfaces']),
  ...group('configuration', 'space', [['buildings', '/system/buildings'], 'spaces', 'systemGroups']),
  ...group('configuration', 'indicators', ['indicatorList', 'formulas']),
  ...group('configuration', 'factors', ['emissionFactors', 'factorVersions']),
  ...group('configuration', 'rules', ['baselines', 'alarmRules']),
  ...group('configuration', 'access', [['users', '/system/users'], ['roles', '/system/roles'], ['buildingAccess', '/system/building-access']]),
  ...group('configuration', 'settings', [['menus', '/system/menus']]),
]

/** 旧路径仅一对一映射同职责入口；不将旧 HVAC 页面授权扩展成五类大屏或整个运维系统。 */
export function authorizedPages(menus: GrantedMenu[]): PageEntry[] {
  const granted = new Map<string, GrantedMenu>()
  function visit(nodes: GrantedMenu[]) {
    for (const node of nodes) {
      if (node.status !== 1 || node.visible !== 1) continue
      if (node.menuType === 'C' && node.path) {
        if (granted.has(node.path)) throw new Error('DUPLICATE_MENU_PATH')
        if (/^\/(monitor|operations|configuration)\//.test(node.path) && !pages.some(page => page.path === node.path)) throw new Error('UNREGISTERED_MENU_PATH')
        granted.set(node.path, node)
      }
      if (node.children) visit(node.children)
    }
  }
  visit(menus)
  const matched = pages.filter(page => granted.has(page.path) || Boolean(page.legacyPath && granted.has(page.legacyPath)))
  const nodeFor = (page: PageEntry) => granted.get(page.path) ?? granted.get(page.legacyPath ?? '')!
  // 系统和组别仍按批准结构；组内排序及新路径的显示名称来自服务端维护值。
  const groupOrder = [...new Set(pages.map(page => page.groupKey + page.system))]
  return matched.sort((a, b) => groupOrder.indexOf(a.groupKey + a.system) - groupOrder.indexOf(b.groupKey + b.system)
    || (nodeFor(a).sortOrder ?? 0) - (nodeFor(b).sortOrder ?? 0))
    .map(page => ({ ...page, title: granted.get(page.path)?.menuName.trim() || undefined }))
}
