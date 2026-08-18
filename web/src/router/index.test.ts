import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { routes } from './index'
import { useAuthStore } from '@/store/auth'

describe('HVAC-only frontend boundaries', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('keeps only the transitional HVAC application routes', () => {
    expect(routes.find((route) => route.path === '/')?.redirect).toBe('/login')
    expect(routes.some((route) => route.path === '/dashboard')).toBe(false)
    expect(routes.some((route) => route.path === '/device')).toBe(false)
    expect(routes.some((route) => route.path === '/hvac-demo')).toBe(true)
  })

  it('registers six explicit administrator deep links without backend component loading', () => {
    const system = routes.find((route) => route.path === '/system')
    expect(system?.meta?.admin).toBe(true)
    expect(system?.children?.map((route) => [route.path, route.name])).toEqual([
      ['users', 'system-users'], ['roles', 'system-roles'], ['menus', 'system-menus'],
      ['building-access', 'system-building-access'],
      ['buildings', 'system-buildings'], ['devices', 'system-devices'],
    ])
    expect(routes.some((route) => route.path === '/system/generator')).toBe(false)
  })

  it('recognizes only the formal platform administrator role', () => {
    const auth = useAuthStore()
    auth.userInfo = {
      uid: 1,
      username: 'admin',
      roles: ['PLATFORM_ADMIN'],
    }
    expect(auth.isAdmin).toBe(true)

    auth.userInfo.roles = ['ADMIN']
    expect(auth.isAdmin).toBe(false)
  })
})
