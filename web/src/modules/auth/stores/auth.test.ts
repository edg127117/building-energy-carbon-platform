import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { currentUserApi, loginApi, logoutApi } from '../api/auth'
import { useAuthStore } from './auth'

vi.mock('../api/auth', () => ({
  currentUserApi: vi.fn(),
  loginApi: vi.fn(),
  logoutApi: vi.fn(),
}))

describe('platform authentication state', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('logs in only after the current user is confirmed', async () => {
    vi.mocked(loginApi).mockResolvedValue({ token: 'session-token' })
    vi.mocked(currentUserApi).mockResolvedValue({ uid: 8, username: 'admin', roles: ['PLATFORM_ADMIN'] })

    const auth = useAuthStore()
    await auth.login({ username: 'admin', password: 'secret' })

    expect(currentUserApi).toHaveBeenCalledWith('session-token')
    expect(auth.isPlatformAdmin).toBe(true)
    expect(localStorage.getItem('bec.auth.token')).toBe('session-token')
  })

  it('clears the browser state when the server cannot restore a cached session', async () => {
    localStorage.setItem('bec.auth.token', 'expired-token')
    localStorage.setItem('bec.auth.user', JSON.stringify({ uid: 8, username: 'admin', roles: ['PLATFORM_ADMIN'] }))
    vi.mocked(currentUserApi).mockRejectedValue(new Error('expired'))

    const auth = useAuthStore()
    await expect(auth.restore()).rejects.toThrow('expired')

    expect(auth.token).toBeNull()
    expect(localStorage.getItem('bec.auth.token')).toBeNull()
    expect(localStorage.getItem('bec.auth.user')).toBeNull()
  })

  it('always clears the browser state after logout, including a server failure', async () => {
    vi.mocked(logoutApi).mockRejectedValue(new Error('network'))
    const auth = useAuthStore()
    auth.persist('session-token', { uid: 8, username: 'admin', roles: ['PLATFORM_ADMIN'] })

    await expect(auth.logout()).rejects.toThrow('network')

    expect(auth.isAuthenticated).toBe(false)
    expect(localStorage.getItem('bec.auth.token')).toBeNull()
  })
})
