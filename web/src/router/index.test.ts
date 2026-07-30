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
