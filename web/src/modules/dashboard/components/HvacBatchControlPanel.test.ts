import { flushPromises, mount } from '@vue/test-utils'
import { ref, shallowRef } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElCheckbox, ElOption, ElSelect } from '@/shared/ui'
import type {
  HvacControlAction,
  HvacControlActionOption,
  HvacControlBatch,
  HvacControlBatchItem,
  HvacControlBatchPreview,
  HvacControlBatchPreviewItem,
} from '../models/hvac-control'

const useBatchControl = vi.hoisted(() => vi.fn())
vi.mock('../composables/use-hvac-batch-control', () => ({ useHvacBatchControl: useBatchControl }))

import HvacBatchControlPanel from './HvacBatchControlPanel.vue'

const actionOption = (action: HvacControlAction, values: string[], allowed = true, reasonCode: string | null = null): HvacControlActionOption => ({
  action, allowed, reasonCode, values, minimum: action === 'TEMPERATURE' ? 18 : null, maximum: action === 'TEMPERATURE' ? 30 : null,
})
const allActions = (modeAllowed = true): HvacControlActionOption[] => [
  actionOption('ON_OFF', ['on', 'off']),
  actionOption('TEMPERATURE', ['20', '24']),
  actionOption('MODE', ['cooling'], modeAllowed),
  actionOption('AIR_FLOW', ['airFlowZero']),
  actionOption('FAN_SPEED', ['high']),
]
const previewItem = (equipmentId: string, actions: HvacControlActionOption[], impactKnown = true): HvacControlBatchPreviewItem => ({
  equipmentId,
  revision: 'r-1',
  reasonCode: null,
  actions,
  impactedEquipmentIds: equipmentId === 'A' ? ['OUT-1'] : [],
  impactedEquipmentLabels: equipmentId === 'A' ? { 'OUT-1': '东区关联内机' } : {},
  impactKnown,
})
const preview: HvacControlBatchPreview = {
  previewId: 'preview-1',
  expiresAt: 1_700_000_120_000,
  items: [
    previewItem('A', allActions()),
    previewItem('B', allActions(false).map(option => ({ ...option, allowed: false, reasonCode: 'CAPABILITY_UNKNOWN' }))),
    previewItem('C', allActions(), false),
  ],
}

function batchItem(status: HvacControlBatchItem['status'], overrides: Partial<HvacControlBatchItem> = {}): HvacControlBatchItem {
  return {
    itemId: `item-${status}-${overrides.equipmentId ?? 'A'}`,
    equipmentId: 'A',
    status,
    reasonCode: status === 'FAILED' ? 'READBACK_NOT_CONFIRMED' : null,
    operationId: null,
    observedValue: null,
    unitStatus: null,
    createdAt: 1_700_000_000_000,
    dispatchedAt: null,
    acceptedAt: null,
    readbackAt: null,
    queuedMillis: 10,
    acceptanceMillis: 20,
    readbackMillis: 30,
    ...overrides,
  }
}

function batchFor(overrides: Partial<HvacControlBatch> = {}): HvacControlBatch {
  return {
    batchId: 'batch-1',
    action: 'ON_OFF',
    value: 'off',
    createdAt: 1_700_000_000_000,
    updatedAt: 1_700_000_000_001,
    completed: false,
    total: 3,
    completedCount: 0,
    queuedCount: 1,
    activeCount: 0,
    attentionCount: 0,
    items: [batchItem('QUEUED')],
    ...overrides,
  }
}

function createControl(activeBatch: HvacControlBatch | null = null) {
  const selectedIds = ref(['A', 'B', 'C'])
  const previewRef = shallowRef<HvacControlBatchPreview | null>(null)
  const activeBatchRef = shallowRef<HvacControlBatch | null>(activeBatch)
  const history = shallowRef({ page: 1, size: 10, total: 1, items: [batchFor()] })
  const createPreview = vi.fn(async () => {
    previewRef.value = preview
    return preview
  })
  const submit = vi.fn(async (action: HvacControlAction, value: string) => batchFor({ action, value }))
  const loadHistory = vi.fn(async () => history.value)
  return {
    selectedIds,
    preview: previewRef,
    activeBatch: activeBatchRef,
    history,
    previewLoading: ref(false),
    submitting: ref(false),
    activeBatchLoading: ref(false),
    historyLoading: ref(false),
    previewError: ref<string | null>(null),
    submitError: ref<string | null>(null),
    batchError: ref<string | null>(null),
    historyError: ref<string | null>(null),
    createPreview,
    submit,
    loadBatch: vi.fn(),
    cancelBatch: vi.fn(),
    loadHistory,
    dismissCompletedBatch: vi.fn(),
  }
}

const devices = ['A', 'B', 'C'].map(equipmentId => ({
  identityId: `identity-${equipmentId}`,
  equipmentId,
  pendingId: `pending-${equipmentId}`,
  buildingId: 'BLD-1',
  spaceId: null,
  systemGroupId: null,
  deviceKind: 'INDOOR',
  mappingVersion: 1,
  active: true,
  stale: false,
  lastValidAt: 1,
  onOff: null,
  mode: null,
  unitStatus: null,
  hasActiveException: false,
  equipmentCode: equipmentId,
  equipmentName: `设备 ${equipmentId}`,
}))

describe('HvacBatchControlPanel', () => {
  beforeEach(() => vi.clearAllMocks())

  it('当前批次未完成时仍可重新预检', async () => {
    const control = createControl(batchFor())
    useBatchControl.mockReturnValue(control)
    const wrapper = mount(HvacBatchControlPanel, { props: { equipmentIds: ['A', 'B', 'C'], devices } })

    const previewButton = wrapper.find('[data-testid="batch-preview"]')
    expect((previewButton.element as HTMLButtonElement).disabled).toBe(false)
    await previewButton.trigger('click')
    await flushPromises()
    expect(control.createPreview).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('展示五种动作、混合能力和关联设备名单，并在一次确认后提交', async () => {
    const control = createControl()
    useBatchControl.mockReturnValue(control)
    const wrapper = mount(HvacBatchControlPanel, { props: { equipmentIds: ['A', 'B', 'C'], devices } })

    await wrapper.find('[data-testid="batch-preview"]').trigger('click')
    await flushPromises()
    expect(control.createPreview).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="preview-item-table"]').exists()).toBe(false)
    expect(wrapper.findAllComponents(ElOption).slice(0, 5).map(option => option.props('value'))).toEqual([
      'ON_OFF', 'TEMPERATURE', 'MODE', 'AIR_FLOW', 'FAN_SPEED',
    ])
    expect(wrapper.findAllComponents(ElOption).find(option => option.props('value') === 'MODE')?.props('disabled')).toBe(false)

    wrapper.findAllComponents(ElSelect)[0]!.vm.$emit('update:modelValue', 'MODE')
    await flushPromises()
    wrapper.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', 'cooling')
    await flushPromises()
    expect(wrapper.text()).toContain('支持 1/3 台')
    expect(wrapper.text()).toContain('关联影响范围尚未确认')

    await wrapper.find('[data-testid="preview-detail-toggle"]').trigger('click')
    expect(wrapper.find('[data-testid="preview-item-table"]').text()).toContain('不支持')

    const showLinked = wrapper.findAll('button').find(button => button.text().includes('查看关联设备名单'))!
    await showLinked.trigger('click')
    expect(wrapper.find('[data-testid="linked-impact-equipment"]').text()).toContain('东区关联内机（OUT-1）')

    const submit = wrapper.find('[data-testid="batch-submit"]')
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)
    wrapper.findComponent(ElCheckbox).vm.$emit('update:modelValue', true)
    await flushPromises()
    await submit.trigger('click')
    expect(control.submit).toHaveBeenCalledWith('MODE', 'cooling', true)
    wrapper.unmount()
  })

  it('默认只显示批次摘要；展开后优先显示关注项并可单独显示成功项', async () => {
    const control = createControl(batchFor({
      completed: true,
      completedCount: 3,
      queuedCount: 0,
      attentionCount: 1,
      items: [
        batchItem('MATCHED', { equipmentId: 'A', observedValue: 'off', unitStatus: 'stopped' }),
        batchItem('FAILED', { equipmentId: 'B', observedValue: 'off', unitStatus: 'operating' }),
        batchItem('ACTIVE', { equipmentId: 'C', acceptedAt: null }),
        batchItem('ACTIVE', { equipmentId: 'D', acceptedAt: 1_700_000_000_010 }),
      ],
    }))
    useBatchControl.mockReturnValue(control)
    const wrapper = mount(HvacBatchControlPanel, { props: { equipmentIds: ['A', 'B', 'C'], devices } })

    expect(wrapper.find('[data-testid="batch-item-table"]').exists()).toBe(false)
    await wrapper.find('[data-testid="batch-detail-toggle"]').trigger('click')
    const detail = wrapper.find('[data-testid="batch-item-table"]')
    expect(detail.text()).toContain('执行失败')
    expect(detail.text()).toContain('已发送，等待厂家受理结果')
    expect(detail.text()).toContain('厂家已受理，等待回显')
    expect(detail.text()).toContain('关')
    expect(detail.text()).toContain('正常')
    expect(detail.text()).toContain('排队 10 ms；受理 20 ms；回显 30 ms')
    expect(detail.text()).not.toContain('回显匹配')

    const showSuccess = wrapper.findAll('button').find(button => button.text().includes('显示成功项'))!
    await showSuccess.trigger('click')
    expect(wrapper.find('[data-testid="batch-item-table"]').text()).toContain('回显匹配')
    wrapper.unmount()
  })

  it('从独立历史入口读取当前账号批次并恢复未完成批次', async () => {
    const control = createControl()
    useBatchControl.mockReturnValue(control)
    const wrapper = mount(HvacBatchControlPanel, { props: { equipmentIds: [], devices: [] } })

    await wrapper.find('[data-testid="batch-history-entry"]').trigger('click')
    await flushPromises()
    expect(control.loadHistory).toHaveBeenCalledWith(1, true)
    expect(wrapper.text()).toContain('我的批次历史')
    wrapper.unmount()
  })
})
