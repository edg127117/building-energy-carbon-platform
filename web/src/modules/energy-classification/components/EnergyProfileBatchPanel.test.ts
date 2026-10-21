import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { TransportError } from '@/infrastructure/http/public'
import EnergyProfileBatchPanel from './EnergyProfileBatchPanel.vue'
import { energyProfileBatchApi } from '../api/energy-profile-batch'

vi.mock('../api/energy-profile-batch', () => ({
  energyProfileBatchApi: { listProfiles: vi.fn(), options: vi.fn(), batch: vi.fn() },
}))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElButton: { props: ['disabled', 'loading', 'type'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  ElCard: { template: '<section><slot name="header" /><slot /></section>' },
  ElEmpty: { props: ['description'], template: '<div>{{ description }}</div>' },
  ElFormItem: { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
  ElInput: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElSelect: {
    props: { modelValue: null, multiple: Boolean, disabled: Boolean }, emits: ['update:modelValue'],
    setup(props: { multiple?: boolean }, { emit }: { emit: (event: 'update:modelValue', value: string | string[]) => void }) {
      const update = (event: Event) => {
        const target = event.target as HTMLSelectElement
        emit('update:modelValue', props.multiple ? Array.from(target.selectedOptions).map(option => option.value) : target.value)
      }
      return { update }
    },
    template: '<select :value="modelValue" :multiple="multiple" :disabled="disabled" @change="update"><slot /></select>',
  },
  ElSkeleton: { template: '<div />' },
  ElTable: { template: '<div><slot /></div>' },
  ElTableColumn: { template: '<div />' },
  ElTag: { template: '<span><slot /></span>' },
}))

const options = {
  energyTypes: ['ELECTRICITY', 'HEAT'], electricitySubtypes: ['GRID_PURCHASED'],
  valueSemantics: ['CUMULATIVE'], reportingPeriods: ['MONTH'],
  confirmationStatuses: ['PENDING_EXPERT', 'CONFIRMED'], supportedUnits: ['kWh', 'GJ'],
}

function profile(pointId: string, revision = 2) {
  return {
    profileId: `PROFILE-${pointId}`, pointId, pointCode: `CODE-${pointId}`, buildingId: 'B-1',
    energyType: 'ELECTRICITY', energySubtype: null, valueSemantics: 'CUMULATIVE', unit: 'kWh',
    reportingPeriod: 'MONTH', annualSummary: true, confirmationStatus: 'PENDING_EXPERT', evidenceReference: '旧依据',
    configRevision: revision, createBy: 1, updateBy: 1, createTime: '2026-01-01T00:00:00', updateTime: '2026-01-01T00:00:00',
  }
}

function prepareDirectory(values: ReturnType<typeof profile>[]) {
  vi.mocked(energyProfileBatchApi.options).mockResolvedValue(options as never)
  vi.mocked(energyProfileBatchApi.listProfiles).mockImplementation((_buildingId, page, size) => Promise.resolve({
    page, size, total: values.length, items: values.slice((page - 1) * size, page * size),
  }) as never)
}

function mountPanel() {
  return mount(EnergyProfileBatchPanel, {
    props: { buildingId: 'B-1', pointOptions: [{ value: 'P-1', label: '总表 P-1' }, { value: 'P-2', label: '分表 P-2' }] },
  })
}

async function fillBatch(wrapper: ReturnType<typeof mount>) {
  await wrapper.find('[data-test="point-select"]').setValue(['P-1', 'P-2'])
  await wrapper.find('[data-test="energy-type"]').setValue('ELECTRICITY')
  await wrapper.find('[data-test="value-semantics"]').setValue('CUMULATIVE')
  await wrapper.find('[data-test="reporting-period"]').setValue('MONTH')
  await wrapper.find('[data-test="annual-summary"]').setValue('true')
  await wrapper.find('[data-test="evidence"]').setValue('本批统一专业依据')
}

describe('批量能源测点专业属性面板', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('完整分页读取目录，预检请求自动携带已有属性版本并保留新增行', async () => {
    prepareDirectory([profile('P-1'), ...Array.from({ length: 100 }, (_, index) => profile(`OTHER-${index}`))])
    vi.mocked(energyProfileBatchApi.batch).mockResolvedValue({ preview: true, count: 2, profiles: [] } as never)
    const wrapper = mountPanel()
    await vi.waitFor(() => expect(energyProfileBatchApi.listProfiles).toHaveBeenCalledTimes(2))
    await flushPromises()

    await fillBatch(wrapper)
    await wrapper.find('[data-test="preview"]').trigger('click')
    await flushPromises()

    expect(energyProfileBatchApi.listProfiles).toHaveBeenNthCalledWith(1, 'B-1', 1, 100, undefined, expect.any(AbortSignal))
    expect(energyProfileBatchApi.listProfiles).toHaveBeenNthCalledWith(2, 'B-1', 2, 100, undefined, expect.any(AbortSignal))
    expect(energyProfileBatchApi.batch).toHaveBeenCalledWith(expect.objectContaining({
      preview: true, buildingId: 'B-1', confirmationStatus: 'PENDING_EXPERT',
      rows: [{ pointId: 'P-1', profileId: 'PROFILE-P-1', expectedRevision: 2 }, { pointId: 'P-2', profileId: null, expectedRevision: null }],
    }))
    expect(wrapper.find('[data-test="write"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('字段变化使预检失效，未成功预检时不允许正式写入', async () => {
    prepareDirectory([profile('P-1')])
    vi.mocked(energyProfileBatchApi.batch).mockResolvedValue({ preview: true, count: 2, profiles: [] } as never)
    const wrapper = mountPanel()
    await vi.waitFor(() => expect(energyProfileBatchApi.listProfiles).toHaveBeenCalledTimes(1))
    await flushPromises()

    await fillBatch(wrapper)
    expect(wrapper.find('[data-test="write"]').attributes('disabled')).toBeDefined()
    await wrapper.find('[data-test="preview"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="write"]').attributes('disabled')).toBeUndefined()

    await wrapper.find('[data-test="evidence"]').setValue('变更后的依据')
    expect(wrapper.find('[data-test="write"]').attributes('disabled')).toBeDefined()
    expect(wrapper.emitted('dirty-change')?.some(([value]) => value === true)).toBe(true)
    expect(energyProfileBatchApi.batch).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('正式写入发生 409 时保留输入、作废预检且不自动重试', async () => {
    prepareDirectory([profile('P-1')])
    vi.mocked(energyProfileBatchApi.batch)
      .mockResolvedValueOnce({ preview: true, count: 2, profiles: [] } as never)
      .mockRejectedValueOnce(new TransportError('request', 409, 409, 'VERSION_CONFLICT'))
    const wrapper = mountPanel()
    await vi.waitFor(() => expect(energyProfileBatchApi.listProfiles).toHaveBeenCalledTimes(1))
    await flushPromises()

    await fillBatch(wrapper)
    await wrapper.find('[data-test="preview"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="write"]').trigger('click')
    await flushPromises()

    expect(energyProfileBatchApi.batch).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('请刷新目录并重新预检')
    expect((wrapper.find('[data-test="evidence"]').element as HTMLTextAreaElement).value).toBe('本批统一专业依据')
    expect(wrapper.find('[data-test="write"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('建筑切换后丢弃迟到预检响应，不把旧上下文变为可写入状态', async () => {
    prepareDirectory([profile('P-1')])
    let resolvePreview!: (value: { preview: boolean; count: number; profiles: [] }) => void
    vi.mocked(energyProfileBatchApi.batch).mockImplementationOnce(() => new Promise(resolve => { resolvePreview = resolve }) as never)
    const wrapper = mountPanel()
    await vi.waitFor(() => expect(energyProfileBatchApi.listProfiles).toHaveBeenCalledTimes(1))
    await flushPromises()

    await fillBatch(wrapper)
    await wrapper.find('[data-test="preview"]').trigger('click')
    await vi.waitFor(() => expect(energyProfileBatchApi.batch).toHaveBeenCalledTimes(1))
    await wrapper.setProps({ buildingId: 'B-2', pointOptions: [{ value: 'P-3', label: '新楼测点 P-3' }] })
    await vi.waitFor(() => expect(energyProfileBatchApi.listProfiles).toHaveBeenCalledTimes(2))
    resolvePreview({ preview: true, count: 2, profiles: [] })
    await flushPromises()

    expect(wrapper.find('[data-test="write"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('预检通过。当前字段、测点、属性版本和建筑均未改变时，才可正式原子写入。')
    wrapper.unmount()
  })
})
