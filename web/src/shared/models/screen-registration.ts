import type { Component } from 'vue'
import type { RouteRecordRaw } from 'vue-router'

export interface ScreenRegistration {
  id: string
  titleKey: string
  path: string
  groupKey: string | null
  order: number | null
  load: () => Promise<{ default: Component }>
}

export function screenRoutes(registry: readonly ScreenRegistration[]): RouteRecordRaw[] {
  const paths = new Set<string>()
  const names = new Set<string>()
  return registry.map(screen => {
    if (paths.has(screen.path) || names.has(screen.id) || !screen.path.startsWith('/monitor/')) {
      throw new Error('Invalid screen registration')
    }
    paths.add(screen.path); names.add(screen.id)
    return {
      path: screen.path, name: screen.id, component: screen.load,
      meta: { titleKey: screen.titleKey, mode: 'monitor' },
    }
  })
}

// 未确认分组和排序时保留空值；展示层集中提供待分组区域，不编造业务分组。
export function screenGroups(registry: readonly ScreenRegistration[]) {
  const groups = new Map<string | null, ScreenRegistration[]>()
  for (const screen of registry) {
    const entries = groups.get(screen.groupKey) ?? []
    entries.push(screen)
    groups.set(screen.groupKey, entries)
  }
  return [...groups].map(([key, entries]) => ({
    key,
    entries: [...entries].sort((a, b) => (a.order ?? Infinity) - (b.order ?? Infinity)),
  }))
}
