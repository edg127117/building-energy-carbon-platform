import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createAuthGuard, expireBrowserSession, installPlatformAuthentication } from './router'
import { useAuthStore } from './stores/auth'

describe('authentication route boundary', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('redirects an anonymous visitor to login and preserves the destination', async () => {
    const guard = createAuthGuard()
    const result = await (guard as unknown as (to: unknown, from: unknown) => Promise<unknown>)(
      { meta: {}, fullPath: '/system/users' },
      {},
    )

    expect(result).toEqual({ path: '/login', query: { redirect: '/system/users' } })
  })

  it('blocks a signed-in non-administrator from platform administration routes', async () => {
    const auth = useAuthStore()
    auth.persist('session-token', { uid: 9, username: 'operator', roles: ['ENERGY_MANAGER'] })
    const guard = createAuthGuard()

    const result = await (guard as unknown as (to: unknown, from: unknown) => Promise<unknown>)(
      { meta: { requiresPlatformAdmin: true }, fullPath: '/system/users' },
      {},
    )

    expect(result).toEqual({ path: '/403' })
  })

  it('clears the new session and uses the installed router for a login redirect', async () => {
    const replace = vi.fn().mockResolvedValue(undefined)
    const router = {
      beforeEach: vi.fn(),
      currentRoute: { value: { path: '/system/users', fullPath: '/system/users?page=2' } },
      replace,
    }
    installPlatformAuthentication(router as never)
    const auth = useAuthStore()
    auth.persist('session-token', { uid: 9, username: 'operator', roles: ['ENERGY_MANAGER'] })

    expireBrowserSession()

    expect(auth.token).toBeNull()
    expect(localStorage.getItem('bec.auth.token')).toBeNull()
    expect(replace).toHaveBeenCalledWith({ path: '/login', query: { redirect: '/system/users?page=2' } })
  })
})
