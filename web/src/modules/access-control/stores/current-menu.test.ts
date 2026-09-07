import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'

vi.mock('../routes', () => ({
  accessControlMenuRoutes: [
    { path: '/system/users', routeName: 'system-users' },
    { path: '/system/roles', routeName: 'system-roles' },
  ],
}))

import { useAuthStore } from '@/modules/auth/public'
import { buildCurrentMenuNavigation, useCurrentMenuStore } from './current-menu'

describe('current menu navigation', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('only exposes enabled visible entries registered by the frontend', () => {
    const navigation = buildCurrentMenuNavigation([{
      id: 1, parentId: 0, menuName: 'system', menuType: 'M', path: null, component: null, perms: null,
      icon: null, visible: 1, status: 1, sortOrder: 0, children: [
        { id: 2, parentId: 1, menuName: 'users', menuType: 'C', path: '/system/users', component: null, perms: null, icon: null, visible: 1, status: 1, sortOrder: 0, children: [] },
        { id: 3, parentId: 1, menuName: 'unknown', menuType: 'C', path: '/unregistered', component: null, perms: null, icon: null, visible: 1, status: 1, sortOrder: 0, children: [] },
        { id: 4, parentId: 1, menuName: 'hidden', menuType: 'C', path: '/system/roles', component: null, perms: null, icon: null, visible: 0, status: 1, sortOrder: 0, children: [] },
      ],
    }])

    expect(navigation).toEqual([{
      id: 1,
      label: 'system',
      children: [{ id: 2, label: 'users', path: '/system/users', routeName: 'system-users', children: [] }],
    }])
  })

  it('clears cached navigation when the authenticated identity changes', async () => {
    const auth = useAuthStore()
    const menu = useCurrentMenuStore()
    menu.tree = [{
      id: 2, parentId: 0, menuName: 'users', menuType: 'C', path: '/system/users', component: null,
      perms: null, icon: null, visible: 1, status: 1, sortOrder: 0, children: [],
    }]
    menu.loaded = true
    auth.persist('first-session', { uid: 1, username: 'first', roles: ['PLATFORM_ADMIN'] })
    await nextTick()

    auth.persist('second-session', { uid: 2, username: 'second', roles: ['PLATFORM_ADMIN'] })
    await nextTick()

    expect(menu.tree).toEqual([])
    expect(menu.loaded).toBe(false)
  })
})
