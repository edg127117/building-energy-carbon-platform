import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import { useSession, type GrantedMenu } from '@/modules/auth/public'
import CarbonPlanPage from '@/modules/energy-classification/pages/CarbonPlanPage.vue'
import { createPlatformRouter } from './index'

vi.mock('@/modules/dashboard/public', async original => ({
  ...await original<typeof import('@/modules/dashboard/public')>(),
  useHvacControlAccess: () => ({ testOpenControls: { value: false }, refresh: vi.fn().mockResolvedValue(undefined), clear: vi.fn() }),
}))

afterEach(() => { vi.restoreAllMocks(); localStorage.clear() })

it('loads the annual plan only in configuration and requires its existing explicit menu grant', async () => {
  localStorage.clear()
  setActivePinia(createPinia())
  const session = useSession()
  session.token = 'isolated-route-test'
  session.user = { id: 1, username: 'test', roles: ['ENERGY_MANAGER'] }
  session.menus = []
  vi.spyOn(session, 'refresh').mockResolvedValue(undefined)
  const router = createPlatformRouter(createMemoryHistory())
  const path = '/configuration/rules/baselines'
  await router.push(path)
  expect(router.currentRoute.value.path).toBe('/403')
  const menu = (id: number, path: string, menuType: string, children?: GrantedMenu[]): GrantedMenu =>
    ({ id, path, menuType, menuName: 'test', visible: 1, status: 1, sortOrder: id, children })
  session.menus = [menu(1, '/configuration', 'M', [menu(2, '/configuration/rules', 'M', [menu(3, path, 'C')])])]
  await router.push(path)
  expect(router.currentRoute.value.path).toBe(path)
  expect(router.currentRoute.value.meta.system).toBe('configuration')
  expect(router.currentRoute.value.matched.at(-1)?.components?.default).toBe(CarbonPlanPage)
  expect(router.getRoutes().filter(route => route.components?.default === CarbonPlanPage).map(route => route.path)).toEqual([path])
})
