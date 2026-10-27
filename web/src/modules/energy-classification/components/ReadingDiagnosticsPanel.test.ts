import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReadingDiagnosticsPanel from './ReadingDiagnosticsPanel.vue'
import { ElPagination } from '@/shared/ui'
import type { ReadingView } from '../models/classification'

function row(index = 0): ReadingView {
  return {
    factId: `fact-${index}`, batchId: 'batch', createdBy: 1, batchStatus: 'APPROVED', normalizedQuantity: '0',
    reading: {
      pointId: `P-${index}`, startInclusive: '2026-01-01T00:00:01Z', endExclusive: '2026-01-02T00:00:02Z',
      energyItemCode: 'ELECTRICITY', unit: 'kWh', semantics: 'CUMULATIVE',
      startReading: '9007199254740993.00000001', endReading: '9007199254740993.00000001', quantity: null,
      multiplier: '1', alreadyScaled: false, status: 'CONFIRMED', sourceReference: 'synthetic ledger', timePrecision: 'EXACT', supersedesFactId: null,
    },
    unchangedReading: {
      pointId: `P-${index}`, energyItemCode: 'ELECTRICITY', unit: 'kWh',
      startInclusive: '2026-01-01T00:00:01Z', endExclusive: '2026-01-02T00:00:02Z', intervalCount: 2,
      counterValue: '9007199254740993.00000001',
      sources: [{ factId: `fact-${index}`, batchId: 'batch', sourceReference: '<script>invalid()</script>' }],
    },
  }
}

describe('人工累计读数只读疑似提示', () => {
  it('展示服务端区间、次数、精确原值和来源，标识留在折叠详情且来源按文本转义', async () => {
    const input = row()
    const wrapper = mount(ReadingDiagnosticsPanel, { props: { readings: [input], pointNames: { 'P-0': '照明总表' } } })
    await flushPromises()
    expect(wrapper.text()).toContain('读数未变化，待核对')
    expect(wrapper.text()).toContain('不改变质量等级或封账规则')
    expect(wrapper.text()).toContain('照明总表')
    expect(wrapper.text()).toContain('9007199254740993.00000001 kWh')
    expect(wrapper.text()).toContain('08:00:01')
    expect(wrapper.text()).toContain('08:00:02')
    expect(wrapper.find('details').attributes('open')).toBeUndefined()
    expect(wrapper.find('details').text()).toContain('P-0')
    expect(wrapper.find('details').text()).toContain('<script>invalid()</script>')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(input.normalizedQuantity).toBe('0')
    expect(input.batchStatus).toBe('APPROVED')
    expect(wrapper.emitted()).toEqual({})
    wrapper.unmount()
  })

  it('兼容旧服务端字段缺失，不根据零增量自行生成提示或宣称正常', () => {
    const input = row()
    delete input.unchangedReading
    const wrapper = mount(ReadingDiagnosticsPanel, { props: { readings: [input] } })
    expect(wrapper.find('[data-test="reading-diagnostics"]').exists()).toBe(false)
    expect(wrapper.text()).toBe('')
    wrapper.unmount()
  })

  it('对服务端提示分页并在更换查询结果后回到首页，未提供名称时不将内部标识冒充名称', async () => {
    const wrapper = mount(ReadingDiagnosticsPanel, { props: { readings: Array.from({ length: 11 }, (_, i) => row(i)) } })
    await flushPromises()
    expect(wrapper.findAll('details')).toHaveLength(10)
    expect(wrapper.text()).toContain('测点名称未提供')
    wrapper.findComponent(ElPagination).vm.$emit('update:current-page', 2)
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('details')).toHaveLength(1)
    expect(wrapper.find('details').text()).toContain('P-10')
    await wrapper.setProps({ readings: [row(20)] })
    expect(wrapper.findAll('details')).toHaveLength(1)
    expect(wrapper.find('details').text()).toContain('P-20')
    wrapper.unmount()
  })
})
