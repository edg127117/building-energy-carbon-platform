import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ChartView from '@/shared/charts/ChartView.vue'
import type { CarbonClassificationReport, EnergyFlowView } from '@/modules/energy-classification/public'
import ScreenFlow from './ScreenFlow.vue'
vi.mock('@/shared/charts/ChartView.vue', () => ({ default: { props: ['option', 'accessibleLabel'], template: '<div />' } }))
describe('screen flow authority', () => {
  it('renders backend TCE links and its explicit total without summing path edges', () => {
    const energyReport: EnergyFlowView = { buildingId: 'A', month: '2026-09', unit: 'TCE', state: 'READY', reason: null, resultNature: 'SOURCE_LABELLED_STATISTICS', classificationVersionId: 'C', relationVersionId: 'R', resultVersion: 'HASH', parameterVersionIds: ['P'], formulaVersionIds: ['F'], standardCoalVersionIds: [], sourceEvidenceIds: ['SOURCE'], conversionOptions: [], flows: [{ schemeCode: 'USE', available: true, unavailableReason: null, total: '0', unallocated: '0', residual: '0', nodes: [{ id: 'E', type: 'ENERGY', code: 'E', name: '电力' }, { id: 'T', type: 'TOTAL', code: 'T', name: '建筑合计' }], links: [{ source: 'E', target: 'T', value: '0' }] }] }
    const wrapper = mount(ScreenFlow, { props: { mode: 'energy', report: null, energyReport, loading: false, failed: false } })
    expect(wrapper.findComponent(ChartView).props('option')).toMatchObject({ series: [{ links: [{ source: 'E', target: 'T', value: 0 }] }] })
    expect(wrapper.get('.flow-note').text()).toContain('0 tce')
    expect(wrapper.find('.flow-columns').exists()).toBe(false)
    wrapper.unmount()
  })
  it('uses backend carbon links unchanged and never relabels them as an energy flow', async () => {
    const report: CarbonClassificationReport = { buildingId: 'A', batchId: 'B', status: 'COMPLETED_COMPLETE', unit: 'KG_CO2E', totalKgCo2e: '0', classificationComplete: true, nodes: [], periods: [], excludedSources: [], warnings: [], flows: [{ schemeCode: 'FORMAL_SCHEME', available: true, unavailableReason: null, nodes: [{ id: 'T', type: 'TOTAL', code: 'TOTAL', name: '建筑合计' }, { id: 'S', type: 'SCOPE', code: 'SCOPE_1', name: '范围一' }], links: [{ source: 'T', target: 'S', value: '0' }] }] }
    const wrapper = mount(ScreenFlow, { props: { mode: 'energy', report, loading: false, failed: false } })
    expect(wrapper.findComponent(ChartView).exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无同口径能流数据')
    await wrapper.setProps({ mode: 'carbon' })
    expect(wrapper.findComponent(ChartView).props('option')).toMatchObject({ series: [{ links: [{ source: 'T', target: 'S', value: 0 }] }] })
    expect(wrapper.get('.scheme-picker').text()).toContain('FORMAL_SCHEME')
    expect(wrapper.find('.flow-columns').exists()).toBe(false)
    expect(wrapper.find('.flow-head').exists()).toBe(false)
    wrapper.unmount()
  })
})
