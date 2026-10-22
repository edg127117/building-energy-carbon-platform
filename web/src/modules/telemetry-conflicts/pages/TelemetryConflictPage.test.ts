import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElButton } from '@/shared/ui'
import { telemetryConflictApi } from '../api/telemetry-conflicts'
import type { ConflictView } from '../models/telemetry-conflicts'
import TelemetryConflictPage from './TelemetryConflictPage.vue'

const routeState = vi.hoisted(() => ({ query: { buildingId: 'B-01' } as Record<string, string> }))

vi.mock('vue-router', () => ({ useRoute: () => routeState }))
vi.mock('../api/telemetry-conflicts', () => ({
  telemetryConflictApi: {
    list: vi.fn(),
    detail: vi.fn(),
    owner: vi.fn(),
    assignOwner: vi.fn(),
    resolve: vi.fn(),
  },
}))

const expiredConflict: ConflictView = {
  pointId: 'POINT-1', buildingId: 'B-01', eventTime: 1735689600000, state: 'EXPIRED', revision: 3,
  original: {
    pointId: 'POINT-1', pointCode: 'P_TOTAL', value: 10, eventTime: 1735689600000, receivedTime: 1735689601000,
    sourceSystem: 'MQTT', sourcePointCode: 'P_TOTAL', sourceDeviceId: 'METER-1', dataQuality: 1,
  },
  variants: [{
    evidenceId: 'EV-1', firstSeenAt: 1735689602000, lastSeenAt: 1735689603000, attempts: 2,
    event: {
      pointId: 'POINT-1', pointCode: 'P_TOTAL', value: 12, eventTime: 1735689600000, receivedTime: 1735689603000,
      sourceSystem: 'MQTT', sourcePointCode: 'P_TOTAL', sourceDeviceId: 'METER-1', dataQuality: 1,
    },
  }],
  effectiveValue: 10, selectionVersion: 0, mode: 'DIRECT', ownerId: 7, allowedActions: ['RESOLVE'], correctionVersionId: null,
}

function render() {
  return mount(TelemetryConflictPage, {
    global: {
      stubs: {
        ElDrawer: { props: ['modelValue'], emits: ['update:modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
        ElDialog: { props: ['modelValue'], emits: ['update:modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
      },
    },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  routeState.query = { buildingId: 'B-01' }
  vi.mocked(telemetryConflictApi.list).mockResolvedValue({ page: 1, size: 20, total: 1, items: [expiredConflict] })
  vi.mocked(telemetryConflictApi.owner).mockResolvedValue({ buildingId: 'B-01', userId: 7, allowedActions: ['ASSIGN_OWNER'] })
  vi.mocked(telemetryConflictApi.detail).mockResolvedValue(expiredConflict)
})

afterEach(() => vi.restoreAllMocks())

describe('数据质量冲突处置页', () => {
  it('把过期冲突明确为待核实，不将其显示为已正确', async () => {
    const wrapper = render()
    await flushPromises()

    expect(telemetryConflictApi.list).toHaveBeenCalledWith({ buildingId: 'B-01', state: undefined, page: 1, size: 20 })
    expect(wrapper.text()).toContain('待核实（已过期）')
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看详情')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('过期不表示数值已正确')
    expect(wrapper.text()).not.toContain('确认已生效')
    wrapper.unmount()
  })

  it('能源候选只提交既有审核，并保留更正版本标识', async () => {
    const energyConflict: ConflictView = { ...expiredConflict, state: 'OPEN', mode: 'ENERGY_REVIEW' }
    const pendingReview: ConflictView = {
      ...energyConflict,
      state: 'PENDING_REVIEW',
      revision: 4,
      selectionVersion: 1,
      effectiveValue: 12,
      allowedActions: [],
      correctionVersionId: 'CORRECTION-V1',
    }
    vi.mocked(telemetryConflictApi.list).mockResolvedValue({ page: 1, size: 20, total: 1, items: [energyConflict] })
    vi.mocked(telemetryConflictApi.detail).mockResolvedValue(energyConflict)
    vi.mocked(telemetryConflictApi.resolve).mockResolvedValue(pendingReview)
    const wrapper = render()
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看详情')!.trigger('click')
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '确认处置')!.trigger('click')
    const choices = wrapper.findAll('input[type="radio"]')
    await choices.at(1)!.setValue(true)
    await wrapper.get('[data-testid="conflict-resolution-reason"]').setValue('现场复核')
    await wrapper.get('[data-testid="conflict-evidence-reference"]').setValue('EV-1')
    await wrapper.get('[data-testid="submit-conflict-resolution"]').trigger('click')
    await flushPromises()

    expect(telemetryConflictApi.resolve).toHaveBeenCalledWith('POINT-1', 1735689600000, {
      evidenceId: 'EV-1', expectedRevision: 3, reason: '现场复核', evidenceReference: 'EV-1',
    })
    expect(wrapper.text()).toContain('能源候选已提交既有审核，尚未生效')
    expect(wrapper.text()).toContain('CORRECTION-V1')
    expect(wrapper.text()).not.toContain('确认已生效')
    wrapper.unmount()
  })
})
