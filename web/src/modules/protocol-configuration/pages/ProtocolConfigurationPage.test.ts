import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElSelect, ElTree, ElMessageBox } from '@/shared/ui'
import { getDeviceProduct, listDeviceProducts } from '@/modules/device-onboarding/public'
import { inspectProtocolSample } from '../api/protocol-configuration'
import ProtocolConfigurationPage from './ProtocolConfigurationPage.vue'

const routerPush = vi.fn()
vi.mock('@/modules/access-control/api/access-control', () => ({ newIdempotencyKey: () => 'test-idempotency', getApprovalPolicy: vi.fn().mockResolvedValue({ environmentMode: 'TEST', selfApprovalAllowed: false }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: routerPush }), useRoute: () => ({ query: {} }), onBeforeRouteLeave: vi.fn() }))
vi.mock('@/modules/device-onboarding/public', async importOriginal => ({ ...(await importOriginal()), getDeviceProduct: vi.fn(), listDeviceProducts: vi.fn() }))
vi.mock('../api/protocol-configuration', () => ({
  createProtocolConfiguration: vi.fn(), getProtocolConfiguration: vi.fn(), inspectProtocolSample: vi.fn(),
  listProtocolConfigurations: vi.fn(), previewProtocolConfiguration: vi.fn(), updateProtocolConfiguration: vi.fn(),
  previewProtocolPublication: vi.fn(), previewProtocolRollback: vi.fn(), getProtocolDeploymentDetail: vi.fn(), freezeProtocolVersion: vi.fn(), importProtocolVersions: vi.fn(), listProtocolDeploymentHistory: vi.fn(),
  listProtocolPublicationTargets: vi.fn(), listProtocolVersions: vi.fn(), registerProtocolPublicationTarget: vi.fn(),
  requestProtocolPublication: vi.fn(), requestProtocolRollback: vi.fn(),
}))

let wrapper: ReturnType<typeof mount>
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
const product = {
  productId: 'P1', productName: '电表', productCode: 'METER', expectedProfileCode: 'V1', identityType: 'SN', status: 'ENABLED', allowedActions: [],
  points: [{ metricCode: 'power', pointNameTemplate: '功率', suffixCode: 'P', unit: 'kW', minValue: null, maxValue: null, forCalc: true, required: true, sortOrder: 0, enabled: true }],
}

describe('协议配置页面', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    vi.mocked(listDeviceProducts).mockResolvedValue({ page: 1, size: 20, total: 1, items: [product] } as never)
    vi.mocked(getDeviceProduct).mockResolvedValue(product as never)
    vi.mocked(inspectProtocolSample).mockResolvedValue({ fields: [{ path: '/power', type: 'NUMBER', value: '12.5' }] })
    const api = await import('../api/protocol-configuration')
    vi.mocked(api.listProtocolConfigurations).mockResolvedValue({ page: 1, size: 20, total: 0, items: [] })
    vi.mocked(api.listProtocolPublicationTargets).mockResolvedValue([])
    vi.mocked(api.listProtocolVersions).mockResolvedValue([])
    wrapper = mount(ProtocolConfigurationPage)
    await flushPromises()
  })
  afterEach(() => wrapper.unmount())

  it('从产品详情固定契约，并只允许数字叶节点创建映射', async () => {
    expect(listDeviceProducts).toHaveBeenCalledWith({ page: 1, size: 20, status: 'DRAFT', keyword: undefined })
    wrapper.findAllComponents(ElSelect)[1].vm.$emit('change', 'ENABLED')
    await flushPromises()
    expect(listDeviceProducts).toHaveBeenLastCalledWith({ page: 1, size: 20, status: 'ENABLED', keyword: undefined })
    wrapper.findAllComponents(ElSelect)[2].vm.$emit('change', 'P1')
    await flushPromises()
    const inputs = wrapper.findAll('input')
    expect(inputs.some(input => input.element.value === 'V1')).toBe(true)
    expect(inputs.some(input => input.element.value === 'SN')).toBe(true)
    await wrapper.find('textarea').setValue('{"power":12.5}')
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '识别字段')!.trigger('click')
    await flushPromises()
    wrapper.findComponent(ElTree).vm.$emit('node-click', { id: '/power', label: 'power', field: { path: '/power', type: 'NUMBER', value: '12.5' } }, {}, {}, new MouseEvent('click'))
    await flushPromises()
    const mapping = wrapper.find('.mapping-list').findComponent(ElSelect)
    mapping.vm.$emit('change', '/power')
    await flushPromises()
    expect(wrapper.text()).toContain('/power')
    expect(wrapper.text()).toContain('请确认来源单位')
    await wrapper.find('textarea').setValue('{"power":13}')
    await flushPromises()
    expect(wrapper.findComponent(ElTree).exists()).toBe(false)
  })

  it('切换产品状态后忽略旧产品详情响应', async () => {
    const pending = deferred<typeof product>()
    vi.mocked(getDeviceProduct).mockReturnValueOnce(pending.promise as never)
    wrapper.findAllComponents(ElSelect)[2].vm.$emit('change', 'P1')
    wrapper.findAllComponents(ElSelect)[1].vm.$emit('change', 'ENABLED')
    pending.resolve(product)
    await flushPromises()
    expect(wrapper.findAll('input').some(input => input.element.value === 'V1')).toBe(false)
  })

  it('检查解析完成后直接展示结果步骤，包括语义校验失败', async () => {
    const api = await import('../api/protocol-configuration')
    vi.mocked(api.getProtocolConfiguration).mockResolvedValue({
      id: 'draft', revision: 1, status: 'DRAFT', updatedAt: 0,
      configuration: { name: '电表', productId: 'P1', profileCode: 'V1', sourceTopic: 'raw/meter', identityType: 'SN', identityPath: '/sn', timestampPath: null, discriminatorPath: null, discriminatorValue: null,
        mappings: [{ metricCode: 'power', sourcePath: '/power', sourceUnit: 'kW', targetUnit: 'kW', scale: '1', offset: '0', enabled: true, required: true, sortOrder: 0 }] },
    })
    vi.mocked(api.previewProtocolConfiguration).mockResolvedValue({ success: false, errors: [{ code: 'MISSING', path: '/power', message: '缺少功率字段' }], metrics: [], identityType: null, identityValue: null, timeSource: null, eventTime: null })
    wrapper.findAllComponents(ElSelect)[0].vm.$emit('change', 'draft')
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '2.报文与映射')!.trigger('click')
    await wrapper.find('textarea').setValue('{"sn":"fixture"}')
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '检查解析')!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('h2').find(heading => heading.text() === '解析预览')?.isVisible()).toBe(true)
    expect(wrapper.text()).toContain('缺少功率字段')
  })

  it('按当前协议标识跳转到三系统待接入列表', async () => {
    wrapper.findAllComponents(ElSelect)[2].vm.$emit('change', 'P1')
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看匹配待接入设备')!.trigger('click')
    expect(routerPush).toHaveBeenCalledWith({ path: '/operations/devices/pendingDevices', query: { view: 'general', profileCode: 'V1', draftId: undefined } })
  })
})
