import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { createPlatformRouter } from './index'
import App from '@/app/App.vue'
import { useShellStore } from '@/app/providers/shell-store'
import { useSession } from '@/modules/auth/public'
import { pages, authorizedPages } from '@/app/navigation/catalog'

beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()) })
function authorize() {
  const session = useSession()
  session.user = { id: 1, username: 'test-user', roles: [] }
  session.token = 'test-only'
  session.menus = pages.map((page, index) => ({ id: index + 1, menuName: '', menuType: 'C', path: page.path, visible: 1, status: 1, sortOrder: index }))
  vi.spyOn(session, 'refresh').mockResolvedValue(undefined)
  return session
}

describe('office and monitor composition', () => {
  it('keeps office content alive after a lazy route failure and recovers', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize()
    const router = createPlatformRouter(createMemoryHistory())
    await router.push('/operations/overview/running')
    const wrapper = mount(App, { global: { plugins: [pinia, router] } })
    router.addRoute({ path: '/test-unavailable', component: () => Promise.reject(new Error('internal chunk path')) })
    await expect(router.push('/test-unavailable')).rejects.toThrow()
    await flushPromises()
    expect(useShellStore(pinia).navigationFailed).toBe(true)
    expect(wrapper.text()).toContain('页面加载失败')
    expect(wrapper.text()).not.toContain('internal chunk path')
    expect(wrapper.find('[data-page-mode="office"]').exists()).toBe(true)
    await router.push('/operations/carbon/trend')
    await flushPromises()
    expect(useShellStore(pinia).navigationFailed).toBe(false)
    expect(wrapper.text()).toContain('趋势对比')
    wrapper.unmount()
    warn.mockRestore()
  })
  it('requires login and rejects direct unauthorized addresses', async () => {
    const router = createPlatformRouter(createMemoryHistory())
    await router.push('/monitor/monitoring')
    expect(router.currentRoute.value.path).toBe('/login')
    const session = authorize()
    session.menus = []
    await router.push('/monitor/monitoring')
    expect(router.currentRoute.value.path).toBe('/403')
  })
  it('registers all confirmed pages without granting them by role', () => {
    expect(pages).toHaveLength(47)
    expect(new Set(pages.map(page => page.path)).size).toBe(pages.length)
    expect(authorizedPages([])).toEqual([])
    const session = authorize()
    expect(authorizedPages(session.menus)).toHaveLength(pages.length)
    session.menus[0].visible = 0
    expect(authorizedPages(session.menus)).toHaveLength(pages.length - 1)
  })
  it('retains administrator checks for migrated management pages in addition to menu grants', async () => {
    const router = createPlatformRouter(createMemoryHistory())
    const session = authorize()
    await router.push('/configuration/access/users')
    expect(router.currentRoute.value.path).toBe('/403')
    session.user!.roles = ['PLATFORM_ADMIN']
    await router.push('/configuration/access/users')
    expect(router.currentRoute.value.path).toBe('/configuration/access/users')
  })
  it('applies the content panel only to management placeholders', () => {
    authorize()
    const router = createPlatformRouter(createMemoryHistory())
    const office = router.resolve('/operations/overview/running')
    const props = office.matched.at(-1).props.default
    expect(typeof props).toBe('function')
    if (typeof props !== 'function') throw new Error('Expected office props function')
    expect(props(office)).toMatchObject({ panel: true })
    const monitor = router.resolve('/monitor/monitoring')
    const screenProps = monitor.matched.at(-1).props.default
    if (typeof screenProps !== 'function') throw new Error('Expected monitor props function')
    expect(screenProps(monitor)).not.toHaveProperty('panel')
  })
  it('resolves all screen addresses to the shared monitor parent and loads pages lazily', () => {
    const router = createPlatformRouter(createMemoryHistory())
    for (const path of ['monitoring', 'trend', 'situation', 'status', 'analysis']) {
      const route = router.resolve('/monitor/' + path)
      expect(route.matched[0].path).toBe('/monitor')
      expect(route.meta.mode).toBe('monitor')
      expect(route.meta.screenLayout).toBe(path === 'monitoring' ? 'scene' : 'grid')
      expect(typeof route.matched.at(-1).components.default).toBe('function')
    }
    expect(router.resolve('/monitor/unknown').matched.at(-1).props.default).toEqual({ state: 'not-found' })
  })
})
