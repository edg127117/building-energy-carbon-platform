import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import CarbonImpactPreview from './CarbonImpactPreview.vue'
import { carbonProjectApi, type CarbonProjectImpact } from '../api/carbon-project'

vi.mock('../api/carbon-project', () => ({ carbonProjectApi: { impact: vi.fn() } }))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElButton: { template: '<button><slot /></button>' },
  ElFormItem: { template: '<div><slot /></div>' },
  ElTable: { template: '<div><slot /></div>' },
  ElTableColumn: { template: '<div />' },
}))

const props = { buildingId: 'B', versionId: 'V', revision: 3, dirty: false }
const impact = (): CarbonProjectImpact => ({ ...props, startInclusive: '', endExclusive: '', ready: true, calculationStatus: 'NOT_CALCULATED', lines: [], issues: [] })

async function setRange(wrapper: ReturnType<typeof mount>) {
  const inputs = wrapper.findAll('input')
  await inputs[0]!.setValue('2026-08-01T00:00')
  await inputs[1]!.setValue('2026-09-01T00:00')
}

describe('封账排除预览', () => {
  it('有未保存修改时禁用预览，不提交浏览器中的草稿或用量', async () => {
    vi.mocked(carbonProjectApi.impact).mockClear()
    const wrapper = mount(CarbonImpactPreview, { props: { ...props, dirty: true } })
    await setRange(wrapper)
    await wrapper.find('button').trigger('click')
    expect(carbonProjectApi.impact).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('未保存')
    wrapper.unmount()
  })

  it('期间变动后不显示迟到返回的旧预览', async () => {
    let resolve!: (result: CarbonProjectImpact) => void
    vi.mocked(carbonProjectApi.impact).mockImplementation(() => new Promise(done => { resolve = done }))
    const wrapper = mount(CarbonImpactPreview, { props })
    await setRange(wrapper)
    await wrapper.find('button').trigger('click')
    await wrapper.findAll('input')[1]!.setValue('2026-10-01T00:00')
    resolve(impact())
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('服务端版本变化时要求重新读取，而不展示过期修订的成功信息', async () => {
    vi.mocked(carbonProjectApi.impact).mockResolvedValue({ ...impact(), revision: 4 })
    const wrapper = mount(CarbonImpactPreview, { props })
    await setRange(wrapper)
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('版本已变化')
    expect(wrapper.text()).not.toContain('本月份来源分解与排除量已核验')
    wrapper.unmount()
  })
})
