import { House, Activity, Zap, Leaf, Cpu, Bell, Wrench, ChartNoAxesCombined,
  Cable, Network, Sigma, Layers, SlidersHorizontal, ShieldCheck, Settings2, Monitor } from '@/shared/ui'

/** 图标只表达已有分组，不参与菜单归属或授权判断。 */
export const groupIcons = {
  'workspaces.overview': House, 'workspaces.realtime': Activity,
  'workspaces.energy': Zap, 'workspaces.carbon': Leaf, 'workspaces.devices': Cpu,
  'workspaces.alarms': Bell, 'workspaces.maintenance': Wrench, 'workspaces.reports': ChartNoAxesCombined,
  'workspaces.ingestion': Cable, 'workspaces.space': Network, 'workspaces.indicators': Sigma,
  'workspaces.factors': Layers, 'workspaces.rules': SlidersHorizontal,
  'workspaces.access': ShieldCheck, 'workspaces.settings': Settings2,
}
export const workspaceIcons = { monitor: Monitor, operations: Settings2, configuration: Layers }
