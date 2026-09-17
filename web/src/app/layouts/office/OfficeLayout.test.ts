import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useSession } from '@/modules/auth/public'
import { ElDrawer, ElMenu, ElMenuItem } from '@/shared/ui'
import OfficeLayout from './OfficeLayout.vue'

afterEach(() => vi.unstubAllGlobals())

async function render(narrow: boolean) {
  let resize: (event: { matches: boolean }) => void = () => undefined
  const remove = vi.fn()
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: narrow, addEventListener: (_: string, listener: typeof resize) => { resize = listener }, removeEventListener: remove })))
  const pinia = createPinia()
  setActivePinia(pinia)
  const session = useSession()
  session.user = { id: 1, username: '窄屏验收用户', roles: ['PLATFORM_ADMIN'] }
  session.menus = [{ id: 1, menuName: '', path: '/configuration/ingestion/products', menuType: 'C', status: 1, visible: 1, sortOrder: 1 }]
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/configuration/ingestion/products', component: { template: '<div />' }, meta: { system: 'configuration' } },
    { path: '/systems', component: { template: '<div />' } },
  ] })
  await router.push('/configuration/ingestion/products')
  const wrapper = mount(OfficeLayout, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, resize, remove }
}

describe('管理外壳响应式导航', () => {
  it('小屏抽屉仅显示授权菜单，选择后收起且切回桌面移除抽屉', async () => {
    const { wrapper, resize, remove } = await render(true)
    await wrapper.get('button[aria-label="打开系统菜单"]').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(true)
    expect(wrapper.findComponent(ElMenu).text()).toContain('产品与测点模板')
    expect(wrapper.findComponent(ElMenu).text()).not.toContain('待接入设备')
    await wrapper.findComponent(ElMenuItem).trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(false)
    resize({ matches: false })
    await flushPromises()
    expect(wrapper.find('aside').exists()).toBe(true)
    expect(wrapper.findComponent(ElDrawer).exists()).toBe(false)
    wrapper.unmount()
    expect(remove).toHaveBeenCalledWith('change', expect.any(Function))
  })

  it('从桌面缩窄时保留系统切换并提供更多操作入口', async () => {
    const { wrapper, resize } = await render(false)
    expect(wrapper.find('button[aria-label="当前用户"]').exists()).toBe(true)
    resize({ matches: true })
    await flushPromises()
    expect(wrapper.find('button[aria-label="切换系统"]').exists()).toBe(true)
    expect(wrapper.find('button[aria-label="更多操作"]').exists()).toBe(true)
    expect(wrapper.find('aside').exists()).toBe(false)
    wrapper.unmount()
  })
})
