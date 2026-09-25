import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElInput } from '@/shared/ui'
import { getDeviceProduct, getLatestHvacTemperatureBatchJob, getOperationsPendingConnection, getOperationsPendingDevice, listDeviceProducts, listOperationsPendingDevices, listPendingDevices, listPointNamingRules, previewHvacTemperatureBindings, submitOperationsBinding, submitOperationsIdentityStatus } from '../api/onboarding'
import { createChangeRequest } from '@/modules/access-control/api/access-control'
import { TransportError } from '@/infrastructure/http/public'
import BindingDraftDialog from '../components/BindingDraftDialog.vue'
import TemperatureBatchDialog from '../components/TemperatureBatchDialog.vue'
import PendingDevicePage from './PendingDevicePage.vue'
const routeState = vi.hoisted(() => ({ view: 'general', profileCode: 'INDOOR', draftId: 'draft-1' }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: routeState }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))
vi.mock('@/modules/auth/public', async original => ({ ...(await original()), useSession: () => ({ user: { id: 1, roles: ['PLATFORM_ADMIN'] } }) }))
vi.mock('../api/onboarding', async original => ({
  ...(await original()),
  listPendingDevices: vi.fn().mockResolvedValue({ page: 1, size: 20, total: 0, items: [] }),
  listOperationsPendingDevices: vi.fn().mockResolvedValue({ page: 1, size: 20, total: 0, items: [] }),
  getOperationsPendingConnection: vi.fn(),
  getOperationsPendingDevice: vi.fn(),
  getLatestHvacTemperatureBatchJob: vi.fn().mockResolvedValue(null),
  getHvacTemperatureBindingOptions: vi.fn().mockResolvedValue({ templates: [], numericSources: [], rules: [] }),
  previewHvacTemperatureBindings: vi.fn(),
  submitOperationsBinding: vi.fn(),
  submitOperationsIdentityStatus: vi.fn(),
  listDaikinSources: vi.fn().mockResolvedValue([]),
  listDaikinDirectorySyncJobs: vi.fn().mockResolvedValue({ page: 1, size: 10, total: 0, items: [] }),
  listDeviceProducts: vi.fn(),
  getDeviceProduct: vi.fn(),
  listPointNamingRules: vi.fn().mockResolvedValue([]),
  getPendingDevice: vi.fn().mockResolvedValue({ pendingId: 'D1', status: 'DISCOVERED', identityType: 'SN', profileCode: 'V1', allowedActions: ['BIND'] }),
  getPendingDeviceConnection: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/modules/access-control/api/access-control', () => ({ getApprovalPolicy: vi.fn().mockResolvedValue({ environmentMode: 'TEST', selfApprovalAllowed: false }), listChangeRequests: vi.fn().mockResolvedValue({ page: 1, size: 10, total: 0, items: [] }), createChangeRequest: vi.fn(), newIdempotencyKey: () => 'test-key' }))
describe('待接入范围筛选', () => {
  beforeEach(() => {
    routeState.view = 'general'
    vi.clearAllMocks()
    vi.mocked(listPointNamingRules).mockResolvedValue([])
  })

  it('产品模板加载完成后显示实际绑定表单', async () => {
    vi.mocked(listPendingDevices).mockResolvedValueOnce({
      page: 1, size: 20, total: 1,
      items: [{ pendingId: 'D1', status: 'DISCOVERED', identityType: 'SN', profileCode: 'V1', allowedActions: ['BIND'] }],
    } as never)
    vi.mocked(listDeviceProducts).mockResolvedValueOnce({
      page: 1, size: 20, total: 1,
      items: [{ productId: 'P1', productName: '测试电表', productCode: 'METER_V1', status: 'ENABLED', expectedProfileCode: 'V1', identityType: 'SN' }],
    } as never)
    vi.mocked(getDeviceProduct).mockResolvedValueOnce({
      productId: 'P1', productName: '测试电表', productCode: 'METER_V1', status: 'ENABLED', expectedProfileCode: 'V1', identityType: 'SN', points: [],
    } as never)

    const wrapper = mount(PendingDevicePage)
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看详情')!.trigger('click')
    await flushPromises()
    await wrapper.findAllComponents(ElButton).filter(button => button.text() === '准备绑定').at(-1)!.trigger('click')
    await flushPromises()

    expect(wrapper.findComponent(BindingDraftDialog).props('open')).toBe(true)
    expect(wrapper.text()).toContain('绑定前需要准备什么')
    wrapper.unmount()
  })

  it('前置配置加载失败时留在详情中显示错误，不渲染未完成的绑定表单', async () => {
    vi.mocked(listPendingDevices).mockResolvedValueOnce({
      page: 1, size: 20, total: 1,
      items: [{ pendingId: 'D1', status: 'DISCOVERED', identityType: 'SN', profileCode: 'V1', allowedActions: ['BIND'] }],
    } as never)
    vi.mocked(listDeviceProducts).mockRejectedValueOnce(new TransportError('request', 500))

    const wrapper = mount(PendingDevicePage, { global: { stubs: { BindingDraftDialog: true } } })
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看详情')!.trigger('click')
    await flushPromises()
    await wrapper.findAllComponents(ElButton).filter(button => button.text() === '准备绑定').at(-1)!.trigger('click')
    await flushPromises()

    const form = wrapper.findComponent(BindingDraftDialog)
    expect(form.props('open')).toBe(false)
    expect(wrapper.text()).toContain('无法加载绑定所需配置')
    wrapper.unmount()
  })

  it('一般绑定排除厂家专用字段，并将数据源前置错误保留在表单', async () => {
    vi.mocked(listPendingDevices).mockResolvedValueOnce({ page: 1, size: 20, total: 1, items: [{ pendingId: 'D1', status: 'DISCOVERED', identityType: 'SN', allowedActions: [] }] } as never)
    vi.mocked(createChangeRequest).mockRejectedValueOnce(new TransportError('request', 409, undefined, 'ONBOARDING_SOURCE_REQUIRED'))
    const wrapper = mount(PendingDevicePage, { global: { stubs: { BindingDraftDialog: true } } })
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看详情')!.trigger('click')
    await flushPromises()
    const form = wrapper.findComponent(BindingDraftDialog)
    form.vm.$emit('submit', { productId: 'P1', buildingId: 'B1', spaceId: 'S1', systemGroupId: 'G1', existingEquipmentId: 'E1', newEquipment: null, pointBindings: [{ metricCode: 'power', existingPointId: 'POINT1' }], numericSourceId: null })
    await flushPromises()
    expect(createChangeRequest).toHaveBeenCalled()
    expect(JSON.stringify(vi.mocked(createChangeRequest).mock.calls.at(-1))).not.toContain('numericSourceId')
    expect(form.props('submitError')).toContain('还没有已启用的接入数据源')
    wrapper.unmount()
  })
  it('身份查询和重置保留流程协议范围，显式查看全部才移除', async () => {
    const wrapper = mount(PendingDevicePage)
    await flushPromises()
    expect(listPendingDevices).toHaveBeenLastCalledWith(expect.objectContaining({ profileCode: 'INDOOR' }))
    const input = wrapper.find('.filter-bar').findComponent(ElInput)
    input.vm.$emit('update:modelValue', '  device-1  ')
    await wrapper.vm.$nextTick()
    await input.find('input').trigger('keyup.enter')
    await flushPromises()
    expect(listPendingDevices).toHaveBeenLastCalledWith(expect.objectContaining({ identity: 'device-1', profileCode: 'INDOOR', page: 1 }))
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '清除筛选')!.trigger('click')
    await flushPromises()
    expect(listPendingDevices).toHaveBeenLastCalledWith(expect.objectContaining({ identity: undefined, profileCode: 'INDOOR' }))
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看全部设备')!.trigger('click')
    await flushPromises()
    expect(listPendingDevices).toHaveBeenLastCalledWith(expect.objectContaining({ profileCode: undefined }))
    wrapper.unmount()
  })

  it('待接入列表显示身份状态，批量启用只允许选择已停用身份', async () => {
    routeState.view = 'daikin'
    const rows = ['D1', 'D2', 'D3'].map((pendingId, index) => ({
      pendingId, status: 'BOUND', identityType: 'DAIKIN_UNIT', profileCode: 'DAIKIN_INDOOR_V2',
      identityStatus: pendingId === 'D2' ? 'ACTIVE' : 'INACTIVE',
      maskedIdentityValue: '****', location: { roomCode: `B303-${index + 1}` },
    }))
    vi.mocked(listOperationsPendingDevices).mockResolvedValue({ page: 1, size: 20, total: 3, items: rows } as never)
    vi.mocked(getOperationsPendingConnection).mockImplementation(async pendingId => ({
      pendingId, identityId: pendingId, identityStatus: pendingId === 'D2' ? 'ACTIVE' : 'INACTIVE',
    }) as Awaited<ReturnType<typeof getOperationsPendingConnection>>)
    vi.mocked(submitOperationsIdentityStatus).mockImplementation(async pendingId => {
      if (pendingId === 'D3') throw new TransportError('request', 409)
      return { pendingId, requestId: `request-${pendingId}`, status: 'PENDING_REVIEW', errorCode: null }
    })
    const wrapper = mount(PendingDevicePage, { attachTo: document.body })
    await flushPromises()
    const table = wrapper.findAllComponents({ name: 'ElTable' }).find(item => item.props('data')?.length === 3)!
    expect(wrapper.text()).toContain('身份状态')
    expect(wrapper.text()).toContain('已启用')
    expect(wrapper.text()).toContain('已停用')
    const selection = wrapper.findAllComponents({ name: 'ElTableColumn' }).find(column => column.props('type') === 'selection')!
    const selectable = selection.props('selectable') as (row: typeof rows[number]) => boolean
    expect(selectable(rows[0]!)).toBe(true)
    expect(selectable(rows[1]!)).toBe(true)
    table.vm.$emit('selection-change', rows)
    await wrapper.vm.$nextTick()
    const activate = wrapper.findAllComponents(ElButton).find(button => button.text() === '批量提交身份启用申请')!
    expect(activate.attributes('disabled')).toBeDefined()
    table.vm.$emit('selection-change', [rows[0], rows[2]])
    await wrapper.vm.$nextTick()
    expect(activate.attributes('disabled')).toBeUndefined()
    expect(wrapper.findAllComponents(ElButton).find(button => button.text() === '补齐温度测点')!.attributes('disabled')).toBeUndefined()
    await activate.trigger('click')
    await flushPromises()
    const dialog = document.querySelector('.el-dialog') as HTMLElement
    expect(dialog.textContent).toContain('本次申请启用的设备数：2')
    expect(dialog.textContent).toContain('B303-1 · 身份已停用')
    ;([...dialog.querySelectorAll('button')].find(button => button.textContent?.includes('批量提交身份启用申请')) as HTMLButtonElement).click()
    await flushPromises()
    expect(getOperationsPendingConnection).toHaveBeenCalledTimes(2)
    expect(submitOperationsIdentityStatus).toHaveBeenCalledTimes(2)
    expect(submitOperationsIdentityStatus).not.toHaveBeenCalledWith('D2', 'ACTIVE', expect.any(String))
    expect(wrapper.text()).toContain('已提交启用申请，等待审核和执行')
    expect(wrapper.text()).toContain('当前状态已变化，请刷新后重试。')
    wrapper.unmount()
  })

  it('提交前身份已在别处启用时重新核对并跳过', async () => {
    routeState.view = 'daikin'
    const rows = ['D1', 'D2'].map(pendingId => ({
      pendingId, status: 'BOUND', identityStatus: 'INACTIVE', identityType: 'DAIKIN_UNIT',
      profileCode: 'DAIKIN_INDOOR_V2', maskedIdentityValue: '****',
    }))
    vi.mocked(listOperationsPendingDevices).mockResolvedValue({ page: 1, size: 20, total: 2, items: rows } as never)
    vi.mocked(getOperationsPendingConnection).mockImplementation(async pendingId => ({
      pendingId, identityId: pendingId, identityStatus: pendingId === 'D2' ? 'ACTIVE' : 'INACTIVE',
    }) as Awaited<ReturnType<typeof getOperationsPendingConnection>>)
    vi.mocked(submitOperationsIdentityStatus).mockResolvedValue({ pendingId: 'D1', requestId: 'R1', status: 'PENDING_REVIEW', errorCode: null })
    const wrapper = mount(PendingDevicePage, { attachTo: document.body })
    await flushPromises()
    wrapper.findAllComponents({ name: 'ElTable' }).find(item => item.props('data')?.length === 2)!.vm.$emit('selection-change', rows)
    await wrapper.vm.$nextTick()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '批量提交身份启用申请')!.trigger('click')
    await flushPromises()
    ;([...document.querySelector('.el-dialog')!.querySelectorAll('button')]
      .find(button => button.textContent?.includes('批量提交身份启用申请')) as HTMLButtonElement).click()
    await flushPromises()
    expect(submitOperationsIdentityStatus).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('身份已启用，未重复申请')
    wrapper.unmount()
  })

  it('打开温度补齐时恢复当前设备最近的持久任务', async () => {
    routeState.view = 'daikin'
    const row = {
      pendingId: 'D1', status: 'BOUND', identityType: 'DAIKIN_UNIT', profileCode: 'DAIKIN_INDOOR_V2',
      identityStatus: 'ACTIVE', maskedIdentityValue: '****', lastProfileVersion: 2, reportCount: 1,
      firstSeenTime: 0, lastSeenTime: 0, sampleTruncated: false,
    }
    vi.mocked(listOperationsPendingDevices).mockResolvedValue({ page: 1, size: 20, total: 1, items: [row] } as never)
    vi.mocked(getLatestHvacTemperatureBatchJob).mockResolvedValue({
      jobId: 'JOB-1', items: [{ pendingId: 'D1', requestId: 'R1', configurationStatus: 'PENDING_REVIEW', message: null, samplingStatus: 'WAITING_CONFIGURATION' }],
    } as never)
    const wrapper = mount(PendingDevicePage)
    await flushPromises()
    wrapper.findAllComponents({ name: 'ElTable' }).find(item => item.props('data')?.length === 1)!.vm.$emit('selection-change', [row])
    await wrapper.vm.$nextTick()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '补齐温度测点')!.trigger('click')
    await flushPromises()
    expect(getLatestHvacTemperatureBatchJob).toHaveBeenCalledWith('D1')
    expect(wrapper.findComponent(TemperatureBatchDialog).props('job')).toMatchObject({ jobId: 'JOB-1' })
    wrapper.unmount()
  })

  it('大金新设备只提交服务端预览返回的温度摘要和来源', async () => {
    routeState.view = 'daikin'
    const row = {
      pendingId: 'D1', status: 'DISCOVERED', identityType: 'DAIKIN_UNIT', profileCode: 'DAIKIN_INDOOR_V2',
      identityStatus: 'UNBOUND', maskedIdentityValue: '****', lastProfileVersion: 2, reportCount: 1,
      firstSeenTime: 0, lastSeenTime: 0, sampleTruncated: false,
    }
    const binding = {
      productId: 'P1', buildingId: 'B1', spaceId: 'S1', systemGroupId: 'G1', existingEquipmentId: null,
      newEquipment: { equipmentName: '大金内机-B303', manufacturer: '大金' }, pointBindings: [], autoCreatePoints: false,
      numericSourceId: null, temperatureMode: 'AUTO' as const, temperatureExistingPointIds: null,
    }
    vi.mocked(listOperationsPendingDevices).mockResolvedValue({ page: 1, size: 20, total: 1, items: [row] } as never)
    vi.mocked(getOperationsPendingDevice).mockResolvedValue({
      pending: { ...row, identityValue: 'site/unit', boundIdentityId: null, latestEventTime: 0, latestTimeSource: null, latestMetrics: {}, allowedActions: ['BIND'] },
      directory: { pendingId: 'D1', sourceId: 'SRC', siteId: 'SITE', controllerId: 'CTRL', kind: 'INDOOR', unitId: 'UNIT', siteName: null, deviceName: null, equipmentId: null, buildingId: 'B1', missing: false, observedAt: '2026-09-25T00:00:00Z' },
    } as never)
    vi.mocked(previewHvacTemperatureBindings).mockResolvedValue([{
      pendingId: 'D1', buildingId: 'B1', mode: 'AUTO', templateProductId: 'T1', templateName: '大金双温度模板',
      numericSourceId: 'SOURCE-1', status: 'READY', message: null, digest: 'digest-1', expiresAt: 1, points: [],
    }] as never)
    vi.mocked(submitOperationsBinding).mockResolvedValue({ pendingId: 'D1', requestId: 'R1', status: 'PENDING_REVIEW', errorCode: null } as never)

    const wrapper = mount(PendingDevicePage)
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看详情')!.trigger('click')
    await flushPromises()
    const form = wrapper.findComponent(BindingDraftDialog)
    form.vm.$emit('temperature-preview', { pendingId: 'D1', mode: 'AUTO', binding })
    await flushPromises()
    expect(previewHvacTemperatureBindings).toHaveBeenCalledWith([{ pendingId: 'D1', mode: 'AUTO', binding }])
    form.vm.$emit('submit', binding)
    await flushPromises()
    expect(submitOperationsBinding).toHaveBeenCalledWith('D1', expect.objectContaining({
      temperatureMode: 'AUTO', temperaturePlanDigest: 'digest-1', numericSourceId: 'SOURCE-1',
    }), expect.any(String))
    expect(vi.mocked(submitOperationsBinding).mock.calls.at(-1)?.[1]).not.toHaveProperty('temperatureTemplateProductId')
    wrapper.unmount()
  })
})
