import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { TransportError } from '../api/session'
import { useSession } from './session'
const api = vi.hoisted(() => ({ login: vi.fn(), me: vi.fn(), menus: vi.fn(), logout: vi.fn() }))
vi.mock('../api/session', async importOriginal => ({ ...await importOriginal<object>(), sessionApi: () => api }))
beforeEach(() => {
  vi.resetAllMocks(); localStorage.clear(); setActivePinia(createPinia())
  api.me.mockResolvedValue({ id: 1, username: 'operator', roles: [] })
  api.menus.mockResolvedValue([])
})
describe('server-backed session', () => {
  it('restores identity from the server, not a cached user profile', async () => {
    localStorage.setItem('token', 'test-token')
    localStorage.setItem('userInfo', '{broken')
    const session = useSession()
    await session.refresh()
    expect(session.user?.username).toBe('operator')
    expect(session.menus).toEqual([])
  })
  it('clears authentication on 401', async () => {
    localStorage.setItem('token', 'expired')
    api.me.mockRejectedValue(new TransportError('unauthorized', 401))
    const session = useSession()
    await expect(session.refresh()).rejects.toThrow()
    expect(session.token).toBeNull()
    expect(localStorage.getItem('token')).toBeNull()
  })
  it('keeps permission failure distinct from no authorized menus', async () => {
    localStorage.setItem('token', 'test-token')
    api.menus.mockRejectedValue(new TransportError('network'))
    const session = useSession()
    await expect(session.refresh()).rejects.toThrow()
    expect(session.failed).toBe(true)
    expect(session.user).toBeNull()
  })
  it('does not restore a logged-out session from an in-flight response', async () => {
    localStorage.setItem('token', 'test-token')
    let resolve!: (value: unknown) => void
    api.me.mockReturnValue(new Promise(done => { resolve = done }))
    const session = useSession()
    const request = session.refresh()
    session.clear()
    resolve({ id: 1, username: 'stale', roles: [] })
    await request
    expect(session.user).toBeNull()
  })
  it('calls server logout and does not falsely succeed on failure', async () => {
    localStorage.setItem('token', 'test-token')
    const session = useSession()
    await session.refresh()
    api.logout.mockRejectedValueOnce(new TransportError('network'))
    await expect(session.logout()).rejects.toThrow()
    expect(session.token).toBe('test-token')
    api.logout.mockResolvedValue(null)
    await session.logout()
    expect(session.token).toBeNull()
  })
})
