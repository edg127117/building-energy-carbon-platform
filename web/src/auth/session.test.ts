import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expireBrowserSession } from './session'

describe('expireBrowserSession', () => {
  beforeEach(() => {
    localStorage.clear()
    window.history.replaceState({}, '', '/login')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('clears the browser session once without redirecting an existing login page', () => {
    localStorage.setItem('token', 'jwt')
    localStorage.setItem('userInfo', '{"username":"operator"}')
    const removeItem = vi.spyOn(Storage.prototype, 'removeItem')

    expireBrowserSession()

    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('userInfo')).toBeNull()
    expect(removeItem).toHaveBeenCalledWith('token')
    expect(removeItem).toHaveBeenCalledWith('userInfo')
    expect(window.location.pathname).toBe('/login')
  })
})
