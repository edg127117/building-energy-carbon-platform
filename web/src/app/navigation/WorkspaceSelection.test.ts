import { describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { mount, flushPromises } from '@vue/test-utils'
import { useSession } from '@/modules/auth/public'
import { createPlatformRouter } from '@/app/router'
import WorkspaceSelection from './WorkspaceSelection.vue'

async function render(paths: string[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const session = useSession()
  session.user = { id: 1, username: 'account-with-a-long-name', roles: [] }
  session.menus = paths.map((path, index) => ({ id: index, menuName: '', path, menuType: 'C', status: 1, visible: 1, sortOrder: index }))
  vi.spyOn(session, 'refresh').mockResolvedValue(undefined)
  const router = createPlatformRouter(createMemoryHistory())
  await router.push('/systems')
  const wrapper = mount(WorkspaceSelection, { global: { plugins: [pinia, router] } })
  return { wrapper, session, router }
}

describe('workspace entry cards', () => {
  const paths = ['/monitor/monitoring', '/operations/overview/running', '/configuration/access/users']
  it.each([1, 2, 3])('renders only %i authorized links with one focus target per card', async count => {
    const { wrapper } = await render(paths.slice(0, count))
    const cards = wrapper.findAll('.system-link')
    expect(cards).toHaveLength(count)
    expect(cards.map(card => card.attributes('href'))).toEqual(paths.slice(0, count))
    expect(wrapper.find('.current-system').exists()).toBe(false)
    expect(wrapper.text()).toContain('公司 LOGO')
    for (const card of cards) {
      expect(card.findAll('button,a,input')).toHaveLength(0)
      expect(card.text()).toContain('进入系统')
    }
    wrapper.unmount()
  })
  it('retains no-access and permission-failure states instead of showing cards', async () => {
    const { wrapper, session } = await render([])
    expect(wrapper.text()).toContain('暂无可访问的系统')
    session.failed = true
    await flushPromises()
    expect(wrapper.text()).toContain('访问权限加载失败')
    expect(wrapper.findAll('.system-link')).toHaveLength(0)
    wrapper.unmount()
  })
  it('keeps logout failure visible and does not navigate away', async () => {
    const { wrapper, session, router } = await render(paths)
    vi.spyOn(session, 'logout').mockRejectedValue(new Error('offline'))
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('安全退出失败')
    expect(router.currentRoute.value.path).toBe('/systems')
    wrapper.unmount()
  })
})
