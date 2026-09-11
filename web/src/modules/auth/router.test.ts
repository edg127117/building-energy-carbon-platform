import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { expireBrowserSession, installPlatformAuthentication } from './router'
import { useSession } from './stores/session'

describe('single authentication transport bridge', () => {
  beforeEach(() => { setActivePinia(createPinia()); localStorage.clear() })
  it('clears the authoritative session and does not install a second navigation guard', () => {
    const router = { beforeEach: vi.fn(), currentRoute: { value: { path: '/configuration/access/users' } }, replace: vi.fn().mockResolvedValue(undefined) }
    installPlatformAuthentication(router as never)
    const session = useSession()
    session.token = 'test-session'
    localStorage.setItem('token', 'test-session')
    expireBrowserSession()
    expect(session.token).toBeNull()
    expect(localStorage.getItem('token')).toBeNull()
    expect(router.replace).toHaveBeenCalledWith('/login')
    expect(router.beforeEach).not.toHaveBeenCalled()
  })
})
