import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { TransportError } from '@/infrastructure/http/public'
import ManualPeriodPanel from './ManualPeriodPanel.vue'
import { manualPeriodsApi } from '../api/manual-periods'

vi.mock('../api/manual-periods', () => ({
  manualPeriodsApi: {
    context: vi.fn(), preview: vi.fn(), createCandidate: vi.fn(), createFormalPolicy: vi.fn(),
    approvePolicy: vi.fn(), submitLock: vi.fn(), approveLock: vi.fn(),
  },
}))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElButton: { props: ['disabled', 'loading', 'type'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  ElCard: { template: '<section><slot name="header" /><slot /></section>' },
  ElDialog: { props: ['modelValue', 'title'], emits: ['update:modelValue', 'close'], template: '<section v-if="modelValue"><h2>{{ title }}</h2><slot /><footer><slot name="footer" /></footer></section>' },
  ElEmpty: { props: ['description'], template: '<div>{{ description }}</div>' },
  ElFormItem: { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
  ElInput: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElSelect: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' },
  ElSkeleton: { template: '<div />' },
  ElTag: { template: '<span><slot /></span>' },
}))

const approvedPolicy = {
  policyId: 'POLICY', versionId: 'POLICY-1', versionNo: 2, buildingId: 'B-1', timezoneId: 'Asia/Shanghai', closingDelayHours: 24,
  lockMode: 'REVIEW_REQUIRED', status: 'APPROVED', sourceType: 'FORMAL', evidenceReference: '正式治理依据',
  effectiveFrom: '2026-01-01T00:00', effectiveTo: null, configRevision: 4, createdBy: 1, createdAt: '2026-01-01T00:00', approvedBy: 2, approvedAt: '2026-01-02T00:00',
}
const pendingPolicy = {
  ...approvedPolicy, versionId: 'POLICY-PENDING', versionNo: 3, status: 'PENDING_REVIEW', configRevision: 7, approvedBy: null, approvedAt: null,
}
const relation = {
  versionId: 'REL-1', modelId: 'MODEL-1', buildingId: 'B-1', versionNo: 5, baseVersionId: null, copiedFromVersionId: null,
  status: 'EFFECTIVE', revision: 6, submittedRevision: 6, snapshotSha256: 'hash', changeReason: '已审核关系', createdBy: 1,
  submittedBy: 1, approvedBy: 2, activatedBy: 2, createdAt: '2026-01-01T00:00', effectiveAt: '2026-01-01T00:00',
}
const reading = {
  factId: 'FACT-1', batchId: 'BATCH-1', normalizedQuantity: '12.340', createdBy: 1, batchStatus: 'CONFIRMED',
  reading: {
    pointId: 'P-1', startInclusive: '2026-08-01T00:00:00Z', endExclusive: '2026-09-01T00:00:00Z', energyItemCode: 'ELECTRICITY', unit: 'kWh',
    semantics: 'PERIOD_TOTAL', startReading: null, endReading: null, quantity: '12.340', multiplier: '1', alreadyScaled: false,
    status: 'CONFIRMED', sourceReference: '人工审核来源', timePrecision: 'EXACT', supersedesFactId: null,
  },
}

function rootContext(policies = [approvedPolicy, pendingPolicy]) {
  return { policies, relations: [relation], readings: [], projection: null, pendingLocks: [] }
}

function selectedContext() {
  return {
    policies: [approvedPolicy, pendingPolicy], relations: [relation], readings: [reading],
    projection: { projectionId: 'PROJ-CURRENT', revision: 4, status: 'OPEN', quantity: '11.000', unit: 'kWh', nature: 'FORMAL' },
    pendingLocks: [{ requestId: 'LOCK-1', projectionId: 'PROJ-CURRENT', projectionRevision: 5, submittedBy: 9, status: 'PENDING_REVIEW' }],
  }
}

function mountPanel(buildingId = 'B-1') {
  return mount(ManualPeriodPanel, {
    props: { buildingId, pointOptions: [{ value: 'P-1', label: '总表 P-1' }] },
  })
}

function configureContext() {
  vi.mocked(manualPeriodsApi.context).mockImplementation((_buildingId, _pointId, _month, policyVersionId) => Promise.resolve(policyVersionId ? selectedContext() : rootContext()) as never)
}

async function selectContext(wrapper: ReturnType<typeof mount>) {
  await wrapper.find('[data-test="point-select"]').setValue('P-1')
  await wrapper.find('[data-test="month-input"]').setValue('2026-08-01')
  await vi.waitFor(() => expect(manualPeriodsApi.context).toHaveBeenCalledWith('B-1', 'P-1', '2026-08-01', undefined, expect.any(AbortSignal)))
  await flushPromises()
}

async function selectManualInputs(wrapper: ReturnType<typeof mount>) {
  await selectContext(wrapper)
  await wrapper.find('[data-test="policy-select"]').setValue('POLICY-1')
  await vi.waitFor(() => expect(manualPeriodsApi.context).toHaveBeenCalledWith('B-1', 'P-1', '2026-08-01', 'POLICY-1', expect.any(AbortSignal)))
  await flushPromises()
  await wrapper.find('[data-test="relation-select"]').setValue('REL-1')
  await wrapper.find('[data-test="fact-FACT-1"]').setValue(true)
  await wrapper.find('[data-test="candidate-reason"]').setValue('已审核人工来源连续覆盖整月')
}

describe('正式人工月量与封账面板', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    configureContext()
    vi.mocked(manualPeriodsApi.preview).mockResolvedValue({
      projectionId: null, revision: 4, buildingId: 'B-1', pointId: 'P-1', startInclusive: '2026-07-31T16:00:00Z', endExclusive: '2026-08-31T16:00:00Z',
      quantity: '12.340', unit: 'kWh', evidenceHash: 'preview-hash', status: 'PREVIEW_ONLY',
    } as never)
    vi.mocked(manualPeriodsApi.createCandidate).mockResolvedValue({
      projectionId: 'PROJ-1', revision: 5, buildingId: 'B-1', pointId: 'P-1', startInclusive: '2026-07-31T16:00:00Z', endExclusive: '2026-08-31T16:00:00Z',
      quantity: '12.340', unit: 'kWh', evidenceHash: 'candidate-hash', status: 'PENDING_INDEPENDENT_LOCK',
    } as never)
    vi.mocked(manualPeriodsApi.submitLock).mockResolvedValue({} as never)
    vi.mocked(manualPeriodsApi.approveLock).mockResolvedValue({
      snapshotId: 'SNAP-1', projectionId: 'PROJ-CURRENT', buildingId: 'B-1', pointId: 'P-1', nativeQuantityStatus: 'COMPLETE', tceStatus: 'NOT_CALCULATED',
    } as never)
  })

  it('将未清空的选择和保存过程通知为切楼脏状态，清空后解除阻止', async () => {
    const wrapper = mountPanel()
    expect(wrapper.emitted('dirty-change')?.at(-1)).toEqual([false])
    await wrapper.find('[data-test="point-select"]').setValue('P-1')
    expect(wrapper.emitted('dirty-change')?.at(-1)).toEqual([true])
    await wrapper.find('[data-test="clear-draft"]').trigger('click')
    expect(wrapper.emitted('dirty-change')?.at(-1)).toEqual([false])
    wrapper.unmount()
  })

  it('只从后端上下文选择口径、关系和事实，预检成功后才生成候选并提交封账申请', async () => {
    const wrapper = mountPanel()
    await selectManualInputs(wrapper)

    expect(wrapper.find('[data-test="create-candidate"]').attributes('disabled')).toBeDefined()
    await wrapper.find('[data-test="preview"]').trigger('click')
    await flushPromises()

    expect(manualPeriodsApi.preview).toHaveBeenCalledWith('B-1', {
      pointId: 'P-1', month: '2026-08-01', policyVersionId: 'POLICY-1', relationVersionId: 'REL-1',
      factIds: ['FACT-1'], expectedProjectionRevision: 4, reason: '已审核人工来源连续覆盖整月',
    })
    expect(vi.mocked(manualPeriodsApi.preview).mock.calls[0]?.[1]).not.toHaveProperty('quantity')
    expect(wrapper.find('[data-test="preview-amount"]').text()).toContain('12.340 kWh')
    expect(wrapper.find('[data-test="create-candidate"]').attributes('disabled')).toBeUndefined()

    await wrapper.find('[data-test="create-candidate"]').trigger('click')
    await flushPromises()
    expect(manualPeriodsApi.createCandidate).toHaveBeenCalledWith('B-1', expect.objectContaining({ factIds: ['FACT-1'], expectedProjectionRevision: 4 }))
    expect(wrapper.find('[data-test="candidate-amount"]').text()).toContain('12.340 kWh')

    await wrapper.find('[data-test="lock-reason"]').setValue('人工正式月量申请封账')
    await wrapper.find('[data-test="lock-evidence"]').setValue('LOCK-EVIDENCE-1')
    await wrapper.find('[data-test="submit-lock"]').trigger('click')
    await flushPromises()

    expect(manualPeriodsApi.submitLock).toHaveBeenCalledWith({
      projectionId: 'PROJ-1', expectedRevision: 5, reason: '人工正式月量申请封账', evidenceReference: 'LOCK-EVIDENCE-1',
    })
    expect(manualPeriodsApi.approveLock).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('字段冲突会保留输入、作废预检且不会自动重试覆盖', async () => {
    vi.mocked(manualPeriodsApi.preview).mockRejectedValue(new TransportError('request', 409, 409, 'VERSION_CONFLICT'))
    const wrapper = mountPanel()
    await selectManualInputs(wrapper)
    await wrapper.find('[data-test="preview"]').trigger('click')
    await flushPromises()

    expect(manualPeriodsApi.preview).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('系统不会自动重试或覆盖')
    expect((wrapper.find('[data-test="candidate-reason"]').element as HTMLTextAreaElement).value).toBe('已审核人工来源连续覆盖整月')
    expect(wrapper.find('[data-test="create-candidate"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('跨楼后丢弃迟到上下文，旧建筑的政策和来源不能进入新建筑', async () => {
    let resolveOld!: (value: ReturnType<typeof rootContext>) => void
    vi.mocked(manualPeriodsApi.context).mockImplementation((building, _point, _month, policyVersionId) => {
      if (building === 'B-1' && !policyVersionId) return new Promise(resolve => { resolveOld = resolve }) as never
      return Promise.resolve(rootContext()) as never
    })
    const wrapper = mountPanel()
    await wrapper.find('[data-test="point-select"]').setValue('P-1')
    await wrapper.find('[data-test="month-input"]').setValue('2026-08-01')
    await vi.waitFor(() => expect(manualPeriodsApi.context).toHaveBeenCalledWith('B-1', 'P-1', '2026-08-01', undefined, expect.any(AbortSignal)))

    await wrapper.setProps({ buildingId: 'B-2', pointOptions: [{ value: 'P-2', label: '新楼总表 P-2' }] })
    resolveOld(rootContext())
    await flushPromises()

    expect(wrapper.find('[data-test="policy-select"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('Asia/Shanghai')
    wrapper.unmount()
  })

  it('没有已审核口径时只允许明确创建待审核 FORMAL 口径，不能自动选用', async () => {
    vi.mocked(manualPeriodsApi.context).mockImplementation(() => Promise.resolve(rootContext([])) as never)
    vi.mocked(manualPeriodsApi.createFormalPolicy).mockResolvedValue({ ...pendingPolicy, versionId: 'POLICY-NEW' } as never)
    const wrapper = mountPanel()
    await selectContext(wrapper)
    await wrapper.find('[data-test="specialist-toggle"]').trigger('click')
    await wrapper.find('[data-test="specialist-timezone"]').setValue('Asia/Shanghai')
    await wrapper.find('[data-test="specialist-delay"]').setValue('24')
    await wrapper.find('[data-test="specialist-effective-from"]').setValue('2026-08-01T00:00')
    await wrapper.find('[data-test="specialist-evidence"]').setValue('正式周期口径治理依据')
    await wrapper.find('[data-test="specialist-create"]').trigger('click')
    await flushPromises()

    expect(manualPeriodsApi.createFormalPolicy).toHaveBeenCalledWith({
      buildingId: 'B-1', timezoneId: 'Asia/Shanghai', closingDelayHours: 24, lockMode: 'REVIEW_REQUIRED', sourceType: 'FORMAL',
      evidenceReference: '正式周期口径治理依据', effectiveFrom: '2026-08-01T00:00', effectiveTo: null,
    })
    expect(wrapper.find('[data-test="policy-select"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('封账审核必须独立提交意见，并仅根据后端快照显示原生量完整、折标未计算', async () => {
    const wrapper = mountPanel()
    await selectContext(wrapper)
    await wrapper.find('[data-test="policy-select"]').setValue('POLICY-1')
    await vi.waitFor(() => expect(manualPeriodsApi.context).toHaveBeenCalledWith('B-1', 'P-1', '2026-08-01', 'POLICY-1', expect.any(AbortSignal)))
    await flushPromises()
    await wrapper.find('[data-test="lock-review-LOCK-1"]').trigger('click')
    expect(wrapper.find('[data-test="review-submit"]').attributes('disabled')).toBeDefined()
    await wrapper.find('[data-test="review-comment"]').setValue('已完成独立封账审核')
    await wrapper.find('[data-test="review-submit"]').trigger('click')
    await flushPromises()

    expect(manualPeriodsApi.approveLock).toHaveBeenCalledWith('LOCK-1', { reviewComment: '已完成独立封账审核' })
    expect(wrapper.find('[data-test="lock-snapshot-status"]').text()).toContain('原生量完整，折标未计算')
    wrapper.unmount()
  })
})
