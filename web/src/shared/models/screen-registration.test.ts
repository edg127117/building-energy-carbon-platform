import { describe, expect, it } from 'vitest'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent } from 'vue'
import { screenGroups, screenRoutes, type ScreenRegistration } from './screen-registration'
import { screens } from '@/modules/large-screen/public'
describe('single source screen registration', () => {
  const extra: ScreenRegistration = {
    id: 'test-screen', titleKey: 'common.empty', path: '/monitor/test-only', order: 1, groupKey: 'common.empty',
    load: async () => ({ default: defineComponent({ template: '<div />' }) }),
  }
  it('adds one registration to both routes and grouped navigation', async () => {
    const registry = [...screens, extra]
    const router = createRouter({ history: createMemoryHistory(), routes: screenRoutes(registry) })
    await router.push(extra.path)
    expect(router.currentRoute.value.name).toBe(extra.id)
    expect(screenGroups(registry).find(g => g.key === extra.groupKey)?.entries).toContain(extra)
    expect(screens.every(s => s.groupKey === null && s.order === null)).toBe(true)
  })
  it('rejects duplicate identities and invalid paths', () => {
    expect(() => screenRoutes([extra, extra])).toThrow()
    expect(() => screenRoutes([{ ...extra, path: '/office/test' }])).toThrow()
  })
})
