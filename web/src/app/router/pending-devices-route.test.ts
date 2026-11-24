import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import { useSession, type GrantedMenu } from '@/modules/auth/public'
import { createPlatformRouter } from './index'

vi.mock('@/modules/dashboard/public', async original => ({
  ...await original<typeof import('@/modules/dashboard/public')>(),
  useHvacControlAccess: () => ({ testOpenControls: { value: false }, refresh: vi.fn().mockResolvedValue(undefined), clear: vi.fn() }),
}))

afterEach(() => { vi.restoreAllMocks(); localStorage.clear() })

it('redirects legacy pendingDevices path to configuration/ingestion while enforcing menu grant', async () => {
  localStorage.clear()
  setActivePinia(createPinia())
  const session = useSession()
  session.token = 'isolated-route-test'
  session.user = { id: 1, username: 'test', roles: ['PLATFORM_ADMIN'] }
  session.menus = []
  vi.spyOn(session, 'refresh').mockResolvedValue(undefined)
  const router = createPlatformRouter(createMemoryHistory())

  await router.push('/operations/devices/pendingDevices?profileCode=V1')
  expect(router.currentRoute.value.path).toBe('/403')

  const menu = (id: number, path: string, menuType: string, children?: GrantedMenu[]): GrantedMenu =>
    ({ id, path, menuType, menuName: '待接入设备', visible: 1, status: 1, sortOrder: id, children })
  session.menus = [menu(1, '/configuration', 'M', [
    menu(2, '/configuration/ingestion', 'M', [menu(3, '/configuration/ingestion/pendingDevices', 'C')]),
  ])]

  await router.push('/operations/devices/pendingDevices?profileCode=V1')
  expect(router.currentRoute.value.path).toBe('/configuration/ingestion/pendingDevices')
  expect(router.currentRoute.value.query).toEqual({ profileCode: 'V1' })
  expect(router.currentRoute.value.meta.system).toBe('configuration')
})
