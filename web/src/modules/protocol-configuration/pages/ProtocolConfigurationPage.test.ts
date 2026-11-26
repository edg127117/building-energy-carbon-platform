import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElMessageBox, ElPagination, ElSelect, ElTree } from '@/shared/ui'
import { getDeviceProduct, listDeviceProducts } from '@/modules/device-onboarding/public'
import { inspectProtocolSample } from '../api/protocol-configuration'
import ProtocolConfigurationPage from './ProtocolConfigurationPage.vue'

const routerPush = vi.fn()
vi.mock('@/modules/access-control/api/access-control', () => ({
  newIdempotencyKey: () => 'test-idempotency',
  getChangeRequest: vi.fn(),
  getApprovalPolicy: vi.fn().mockResolvedValue({ environmentMode: 'TEST', selfApprovalAllowed: false }),
  listChangeRequests: vi.fn().mockResolvedValue({ page: 1, size: 10, total: 0, items: [] }),
}))
vi.mock('@/modules/auth/public', async importOriginal => ({
  ...(await importOriginal<typeof import('@/modules/auth/public')>()),
  useSession: () => ({ user: { id: 1 } }),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: routerPush }), useRoute: () => ({ query: {} }), onBeforeRouteLeave: vi.fn() }))
vi.mock('@/modules/device-onboarding/public', async importOriginal => ({ ...(await importOriginal()), getDeviceProduct: vi.fn(), listDeviceProducts: vi.fn() }))
vi.mock('../api/protocol-configuration', () => ({
  createProtocolConfiguration: vi.fn(),
  deleteProtocolConfiguration: vi.fn(),
  getProtocolConfiguration: vi.fn(),
  inspectProtocolSample: vi.fn(),
  listProtocolConfigurations: vi.fn(),
  previewProtocolConfiguration: vi.fn(),
  updateProtocolConfiguration: vi.fn(),
  previewDraftProtocolPublication: vi.fn(),
  requestDraftProtocolPublication: vi.fn(),
  previewProtocolPublication: vi.fn(),
  previewProtocolRollback: vi.fn(),
  getProtocolDeploymentDetail: vi.fn(),
  freezeProtocolVersion: vi.fn(),
  importProtocolVersions: vi.fn(),
  listProtocolDeploymentHistory: vi.fn(),
  listProtocolPublicationTargets: vi.fn(),
  listProtocolVersions: vi.fn(),
  registerProtocolPublicationTarget: vi.fn(),
  requestProtocolPublication: vi.fn(),
  requestProtocolRollback: vi.fn(),
}))

let wrapper: ReturnType<typeof mount>
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
const product = {
  productId: 'P1',
  productName: '电表',
  productCode: 'METER',
  expectedProfileCode: 'V1',
  identityType: 'SN',
  status: 'ENABLED',
  allowedActions: [],
  points: [{ metricCode: 'power', pointNameTemplate: '功率', suffixCode: 'P', unit: 'kW', minValue: null, maxValue: null, forCalc: true, required: true, sortOrder: 0, enabled: true }],
}

describe('协议配置 3 步向导页面', () => {
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
    vi.mocked(api.createProtocolConfiguration).mockResolvedValue({
      id: 'D-NEW',
      revision: 1,
      status: 'DRAFT',
      updatedAt: 1000,
      configuration: {
        name: '电表草稿',
        productId: 'P1',
        profileCode: 'V1',
        sourceTopic: '',
        identityType: 'SN',
        identityPath: '',
        discriminatorPath: null,
        discriminatorValue: null,
        timestampPath: null,
        mappings: [],
      },
    })
    wrapper = mount(ProtocolConfigurationPage, { attachTo: document.body })
    await flushPromises()
  })
  afterEach(() => wrapper.unmount())

  it('默认查询已启用产品、收拢为 3 步向导、隐藏空分页且支持半成品草稿暂存', async () => {
    expect(listDeviceProducts).toHaveBeenCalledWith({ page: 1, size: 20, status: 'ENABLED', keyword: undefined })
    const stepLabels = wrapper.findAll('.step-buttons button').map(button => button.text())
    expect(stepLabels).toEqual(['1. 基础配置与产品', '2. 报文映射与试解析', '3. 发布与加载状态'])
    expect(wrapper.findAllComponents(ElPagination)).toHaveLength(0)
    expect(wrapper.text()).not.toContain('不会发布到适配器')

    const api = await import('../api/protocol-configuration')
    await wrapper.find('input[maxlength="100"]').setValue('电表草稿')
    wrapper.findAllComponents(ElSelect)[1].vm.$emit('change', 'P1')
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '保存草稿')!.trigger('click')
    await flushPromises()
    expect(api.createProtocolConfiguration).toHaveBeenCalledWith(expect.objectContaining({
      name: '电表草稿',
      productId: 'P1',
      profileCode: 'V1',
      sourceTopic: '',
      identityPath: '',
      mappings: [],
    }))
  })

  it('步骤 2 同屏提取字段、配置映射并按试解析错误路径高亮定位到对应测点行', async () => {
    const api = await import('../api/protocol-configuration')
    vi.mocked(api.previewProtocolConfiguration).mockResolvedValue({
      success: false,
      errors: [{ code: 'SOURCE_UNIT_REQUIRED', path: 'mappings.power.sourceUnit', message: '请确认功率原始单位' }],
      metrics: [],
      identityType: null,
      identityValue: null,
      timeSource: null,
      eventTime: null,
    })

    wrapper.findAllComponents(ElSelect)[1].vm.$emit('change', 'P1')
    await flushPromises()
    await wrapper.findAll('.step-buttons button')[1].trigger('click')
    await wrapper.find('textarea').setValue('{"power":12.5}')
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '提取字段')!.trigger('click')
    await flushPromises()
    wrapper.findComponent(ElTree).vm.$emit('node-click', { id: '/power', label: 'power', field: { path: '/power', type: 'NUMBER', value: '12.5' } }, {}, {}, new MouseEvent('click'))
    await flushPromises()
    wrapper.find('.mapping-list').findComponent(ElSelect).vm.$emit('change', '/power')
    await flushPromises()
    expect(wrapper.text()).toContain('请确认原始单位')

    await wrapper.findAllComponents(ElButton).find(button => button.text() === '立即试解析')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请确认功率原始单位')
    expect(wrapper.find('#metric-power').classes()).toContain('mapping-row--highlighted')

    await wrapper.find('textarea').setValue('{"power":13}')
    await flushPromises()
    expect(wrapper.findComponent(ElTree).exists()).toBe(false)
  })

  it('切换产品状态后忽略旧产品详情响应', async () => {
    const pending = deferred<typeof product>()
    vi.mocked(getDeviceProduct).mockReturnValueOnce(pending.promise as never)
    wrapper.findAllComponents(ElSelect)[1].vm.$emit('change', 'P1')
    wrapper.findAllComponents(ElSelect)[0].vm.$emit('change', 'DRAFT')
    pending.resolve(product)
    await flushPromises()
    expect(wrapper.findAll('input').some(input => input.element.value === 'V1')).toBe(false)
  })

  it('打开含 INTERVAL_START 和空字段的草稿后页面正常加载，直接保存不会丢失周期量参数', async () => {
    const api = await import('../api/protocol-configuration')
    const intervalMapping = {
      valueType: 'INTERVAL_START' as const,
      metricCode: '__interval_start',
      sourcePath: '/start',
      sourceUnit: 'ms',
      targetUnit: 'ms',
      scale: '1',
      offset: '0',
      enabled: true,
      required: true,
      sortOrder: 9,
    }
    const draftItem = {
      id: 'D-INTERVAL',
      revision: 2,
      status: 'DRAFT' as const,
      updatedAt: 2000,
      configuration: {
        name: '周期量电表',
        productId: 'P1',
        profileCode: 'V1',
        sourceTopic: 'raw/meter',
        identityType: 'SN',
        identityPath: '/sn',
        discriminatorPath: null,
        discriminatorValue: null,
        timestampPath: null,
        mappings: [
          {
            metricCode: 'power',
            sourcePath: '/power',
            sourceUnit: null as unknown as string,
            targetUnit: 'kW',
            scale: null as unknown as string,
            offset: null as unknown as string,
            enabled: true,
            required: true,
            sortOrder: 0,
          },
          intervalMapping,
        ],
      },
    }
    vi.mocked(api.listProtocolConfigurations).mockResolvedValue({ page: 1, size: 20, total: 1, items: [draftItem] })
    vi.mocked(api.getProtocolConfiguration).mockResolvedValue(draftItem)
    vi.mocked(api.updateProtocolConfiguration).mockResolvedValue({ ...draftItem, revision: 3 })

    await wrapper.findAllComponents(ElButton).find(button => button.text() === '草稿箱')!.trigger('click')
    await flushPromises()
    const openBtn = [...document.body.querySelectorAll('button')].find(button => button.textContent?.trim() === '打开')!
    openBtn.click()
    await flushPromises()

    // 未送审时步骤 3 不应误提示加载成功
    await wrapper.findAll('.step-buttons button')[2].trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('目标适配器已确认加载最新配置')
    expect(wrapper.findAllComponents(ElButton).find(button => button.text() === '前往待接入设备')).toBeUndefined()

    // 直接保存时必须完整保留 INTERVAL_START 映射
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '保存草稿')!.trigger('click')
    await flushPromises()
    expect(api.updateProtocolConfiguration).toHaveBeenCalledWith(
      'D-INTERVAL',
      2,
      expect.objectContaining({
        mappings: expect.arrayContaining([expect.objectContaining({ valueType: 'INTERVAL_START', metricCode: '__interval_start' })]),
      }),
    )
  })
})
