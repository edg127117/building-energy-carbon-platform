import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElCheckbox, ElPagination, ElSelect } from '@/shared/ui'
import type { HvacControlOperation, HvacControlOperationStatus } from '../models/hvac-control'

const api = vi.hoisted(() => ({
  options: vi.fn(), createOperation: vi.fn(), operation: vi.fn(), operations: vi.fn(), recheck: vi.fn(), close: vi.fn(), confirm: vi.fn(), release: vi.fn(),
}))
vi.mock('../api/hvac-control', () => ({
  hvacControlApi: api,
  newHvacControlIdempotencyKey: () => 'component-control-key',
}))

import HvacControlPanel from './HvacControlPanel.vue'

const options = (canControl = true) => ({
  equipmentId: 'IDU-1', canControl, reasonCode: canControl ? null : 'DISABLED', revision: canControl ? 'revision-1' : null, activeOperationId: null,
  actions: [
    { action: 'ON_OFF', allowed: canControl, reasonCode: canControl ? null : 'DISABLED', values: ['on', 'off'], minimum: null, maximum: null },
    { action: 'TEMPERATURE', allowed: false, reasonCode: 'TEMPERATURE_RANGE_UNKNOWN', values: [], minimum: 18, maximum: 30 },
  ],
})
const operation = (
  status: HvacControlOperationStatus,
  overrides: Partial<HvacControlOperation> = {},
): HvacControlOperation => ({
  operationId: 'OP-1', equipmentId: 'IDU-1', action: 'ON_OFF' as const, value: 'on', status,
  reasonCode: null, vendorCode: null, observedValue: null, createdAt: 1_700_000_000_000, updatedAt: 1_700_000_001_000, closedReason: status === 'CLOSED' ? '人工核验结束' : null,
  deadlineAt: 1_700_000_300_000, stableCount: 0, stableSince: null, lastReadAt: 1_700_000_001_000, unitStatus: 'operating',
  isolated: !['OBSERVED_MATCH', 'CONFIRMED', 'REJECTED', 'NOT_SENT'].includes(status), cloudStable: status === 'OBSERVED_STABLE' || status === 'CONFIRMED',
  ...overrides,
})

describe('大金内机离线控制面板', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.options.mockResolvedValue(options())
    api.operations.mockResolvedValue({ page: 1, size: 20, total: 0, items: [] })
    api.createOperation.mockResolvedValue(operation('ACCEPTED'))
    api.operation.mockResolvedValue(operation('ACCEPTED'))
    api.recheck.mockResolvedValue(operation('ACCEPTED'))
    api.close.mockResolvedValue(operation('CLOSED', { isolated: true }))
    api.confirm.mockResolvedValue(operation('CONFIRMED', { isolated: false }))
    api.release.mockResolvedValue(operation('CLOSED', { isolated: false, cloudStable: true }))
  })

  it('选择目标值并明确确认后才创建操作，刷新只重取后端状态', async () => {
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()
    expect(api.createOperation).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('控制权限默认关闭')

    const actionSelect = wrapper.findAllComponents(ElSelect)[0]!
    actionSelect.vm.$emit('update:modelValue', 'ON_OFF')
    await flushPromises()
    const valueSelect = wrapper.findAllComponents(ElSelect)[1]!
    valueSelect.vm.$emit('update:modelValue', 'on')
    await flushPromises()
    wrapper.findComponent(ElCheckbox).vm.$emit('update:modelValue', true)
    await flushPromises()
    const submit = wrapper.find('[data-testid="submit-control"]')
    expect(submit.attributes('disabled')).toBeUndefined()
    valueSelect.vm.$emit('update:modelValue', 'off')
    await flushPromises()
    expect(submit.attributes('disabled')).toBeDefined()
    valueSelect.vm.$emit('update:modelValue', 'on')
    await flushPromises()
    wrapper.findComponent(ElCheckbox).vm.$emit('update:modelValue', true)
    await flushPromises()
    await submit.trigger('click')
    await flushPromises()

    expect(api.createOperation).toHaveBeenCalledWith('IDU-1', {
      action: 'ON_OFF', value: 'on', idempotencyKey: 'component-control-key', revision: 'revision-1',
    })
    expect(wrapper.text()).toContain('厂家已受理')
    expect(wrapper.text()).toContain('尚未确认设备状态')
    expect(submit.attributes('disabled')).toBeDefined()

    await wrapper.setProps({ refreshTick: 1 })
    await flushPromises()
    expect(api.options).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('未知且云端稳定仍显示为未知，关闭记录明确不解除隔离', async () => {
    api.options.mockResolvedValue(options(false))
    api.operations.mockResolvedValue({ page: 1, size: 20, total: 1, items: [operation('UNKNOWN', { observedValue: 'on', cloudStable: true, stableCount: 3 })] })
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()

    expect(wrapper.text()).toContain('请求结果未知；虽当前云端读取已稳定，仍不代表原请求成功')
    expect(wrapper.text()).toContain('结果未知')
    expect(wrapper.text()).toContain('控制默认关闭')
    const close = wrapper.findAllComponents(ElButton).find(button => button.text() === '关闭记录')!
    await close.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('更不会解除隔离')
    const submitClose = wrapper.findAllComponents(ElButton).find(button => button.text() === '确认关闭记录')!
    expect(submitClose.attributes('disabled')).toBeDefined()
    const reason = wrapper.find('textarea')
    await reason.setValue('人工核验结束')
    const checkboxes = wrapper.findAllComponents(ElCheckbox)
    checkboxes.at(-1)!.vm.$emit('update:modelValue', true)
    await flushPromises()
    await submitClose.trigger('click')
    await flushPromises()

    expect(api.close).toHaveBeenCalledWith('OP-1', '人工核验结束')
    expect(wrapper.text()).toContain('隔离仍保留')
    wrapper.unmount()
  })

  it('云端稳定待现场确认显示稳定进度和读取时间，并以现场说明调用确认端点', async () => {
    api.options.mockResolvedValue(options(false))
    api.operations.mockResolvedValue({
      page: 1, size: 20, total: 1,
      items: [operation('OBSERVED_STABLE', { stableCount: 3, stableSince: 1_700_000_100_000, lastReadAt: 1_700_000_200_000, cloudStable: true })],
    })
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()

    expect(wrapper.text()).toContain('云端稳定待现场确认')
    expect(wrapper.text()).toContain('至少 3 次／60 秒，最短观察 180 秒，期限 300 秒')
    expect(wrapper.text()).toContain('最后云端读取时间')
    expect(wrapper.text()).toContain('设备采样时间未知；此处仅显示云端读取时间')
    const confirm = wrapper.findAllComponents(ElButton).find(button => button.text() === '现场确认')!
    await confirm.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('现场确认只记录人工核验和审计，不向厂家发送控制命令')
    await wrapper.find('textarea').setValue('现场核对设备状态稳定')
    wrapper.findComponent(ElCheckbox).vm.$emit('update:modelValue', true)
    await flushPromises()
    const submitConfirm = wrapper.findAllComponents(ElButton).find(button => button.text() === '确认现场核验')!
    await submitConfirm.trigger('click')
    await flushPromises()

    expect(api.confirm).toHaveBeenCalledWith('OP-1', '现场核对设备状态稳定')
    expect(wrapper.text()).toContain('已现场确认')
    wrapper.unmount()
  })

  it('操作记录总数超过 20 条时支持翻页', async () => {
    api.operations.mockImplementation((_equipmentId: string, page = 1) => Promise.resolve({
      page, size: 20, total: 21, items: [],
    }))
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()

    const pagination = wrapper.findComponent(ElPagination)
    expect(pagination.exists()).toBe(true)
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(api.operations).toHaveBeenLastCalledWith('IDU-1', 2, 20)
    wrapper.unmount()
  })
})
