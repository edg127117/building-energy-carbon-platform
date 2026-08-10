import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/store/auth'
import { useMenuStore } from '@/store/menu'
import ManagementLayout from './ManagementLayout.vue'

const push = vi.fn()
vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')
  return { ...actual, useRoute: () => ({ path: '/system/users' }), useRouter: () => ({ push }) }
})

describe('management layout', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    push.mockReset()
  })

  it('loads controlled navigation and keeps one nested outlet', async () => {
    const auth = useAuthStore()
    auth.userInfo = { uid: 1, username: 'admin', roles: ['PLATFORM_ADMIN'] }
    const menu = useMenuStore()
    menu.navigation = [{ id: 200, label: '系统管理', children: [{
      id: 211, label: '用户管理', path: '/system/users', routeName: 'system-users', admin: true, children: [],
    }] }]
    menu.loaded = true
    const ensureLoaded = vi.spyOn(menu, 'ensureLoaded').mockResolvedValue()
    const wrapper = mount(ManagementLayout, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' }, RouterView: { template: '<main data-test="outlet" />' } } },
    })
    await wrapper.vm.$nextTick()
    expect(ensureLoaded).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('用户管理')
    expect(wrapper.text()).toContain('平台管理员')
    expect(wrapper.text()).not.toContain('PLATFORM_ADMIN')
    expect(wrapper.findAll('[data-test="outlet"]')).toHaveLength(1)
  })

  it('clears auth and menu before redirecting on logout', async () => {
    const auth = useAuthStore()
    auth.token = 'token'
    auth.userInfo = { uid: 1, username: 'admin', roles: ['PLATFORM_ADMIN'] }
    const menu = useMenuStore()
    vi.spyOn(menu, 'ensureLoaded').mockResolvedValue()
    const clear = vi.spyOn(menu, 'clear')
    const wrapper = mount(ManagementLayout, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' }, RouterView: true } },
    })
    await wrapper.get('[data-test="logout"]').trigger('click')
    expect(auth.token).toBeNull()
    expect(clear).toHaveBeenCalled()
    expect(push).toHaveBeenCalledWith('/login')
  })
})
