import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ScreenIntensitySummary from './ScreenIntensitySummary.vue'

vi.mock('@/locales', () => ({
  t: (key: string, params: Record<string, string | number> = {}) => {
    const messages: Record<string, string> = {
      'largeScreen.scoreSummary': '碳排放分项评分',
      'largeScreen.scoreAreaLabel': '单位建筑面积碳排放强度',
      'largeScreen.scorePersonLabel': '人均碳排放强度',
      'largeScreen.scoreRatioLabel': '比例',
      'largeScreen.scoreRatioValue': '{value}%',
      'largeScreen.scoreRatioMissing': '比例暂不可用',
      'largeScreen.scoreRatioOutOfRange': '（超出可绘制范围）',
      'largeScreen.scoreRatioAccessible': '{label}：{ratio}{state}',
      'largeScreen.scoreMissing': '—',
    }
    return (messages[key] ?? key).replace(/\{(\w+)\}/g, (_, name: string) => String(params[name] ?? ''))
  },
}))

describe('screen intensity summary', () => {
  it('B版只显示面积指标，来源和参考单位读后端，超100%只裁剪条带而保留原比例', async () => {
    const wrapper = mount(ScreenIntensitySummary, { props: {
      showBenchmarks: true, prototypeReference: false,
      data: { areaValue: 85.065, areaPercentage: 150, areaUnit: 'kgCO₂e/m²', personValue: 9, personPercentage: 50, personUnit: 'tCO₂e/人' },
      benchmarks: { constraint: 56.71, baseline: 35.56, guide: 20.72, sourceName: '隔离测试来源', documentVersion: 'v2',
        sourceReference: 'test://reference', climateLabel: '测试分区', unit: 'kgCO2/(m²·年)', mode: 'FORMAL_YEAR', reasons: [] },
    } })
    expect(wrapper.find('[data-testid="score-person-value"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="score-area-value"]').text()).toBe('85.07')
    expect(wrapper.get('[data-testid="score-area-track"]').attributes('data-raw-ratio')).toBe('150')
    expect(wrapper.get('[data-testid="score-area-bar"]').attributes('style')).toContain('100%')
    expect(wrapper.get('[data-testid="intensity-benchmark-source"]').text()).toContain('隔离测试来源 v2')
    expect(wrapper.get('[data-testid="benchmark-constraint"]').text()).toContain('kgCO2/(m²·年)')
    expect(wrapper.text()).not.toContain('江苏省公共建筑')
    await wrapper.setProps({ data: { areaValue: 0, areaPercentage: null, areaUnit: 'kgCO₂e/m²', personValue: null, personPercentage: null, personUnit: '' },
      benchmarks: { constraint: null, baseline: null, guide: null, mode: 'MONTHLY_YTD', throughMonth: 9, percentageBasis: 'YTD_SHARE_OF_ANNUAL_REFERENCE', reasons: ['YTD_NOT_ANNUAL_COMPARISON'] } })
    expect(wrapper.get('[data-testid="score-area-value"]').text()).toBe('0.00')
    expect(wrapper.find('[data-testid="score-area-bar"]').exists()).toBe(false)
    expect(wrapper.find('.benchmark-context').exists()).toBe(false)
    wrapper.unmount()
  })

  it('自动匹配的未知时间参考保留YTD比例且不增加底部说明', () => {
    const wrapper = mount(ScreenIntensitySummary, { props: {
      showBenchmarks: true,
      data: { areaValue: 28.355, areaPercentage: 50, areaUnit: 'kgCO₂/m²', personValue: null, personPercentage: null, personUnit: '' },
      benchmarks: { constraint: 56.71, baseline: 35.56, guide: 20.72, mode: 'MONTHLY_YTD', throughMonth: 9,
        percentageBasis: 'YTD_SHARE_OF_ANNUAL_REFERENCE', timeStatus: 'PENDING', reasons: ['APPLICABILITY_TIME_UNCLEAR'] },
    } })
    expect(wrapper.get('[data-testid="score-area-track"]').attributes('data-raw-ratio')).toBe('50')
    expect(wrapper.find('.benchmark-context').exists()).toBe(false)
    expect(wrapper.find('.intensity-benchmark-section').element.lastElementChild?.getAttribute('data-testid')).toBe('intensity-benchmark-source')
    wrapper.unmount()
  })

  it('preserves a real zero and independently renders a missing value with an out-of-range ratio', () => {
    const wrapper = mount(ScreenIntensitySummary, {
      props: {
        data: {
          areaValue: 0,
          areaPercentage: 0,
          areaUnit: 'kgCO₂/m²·年',
          personValue: null,
          personPercentage: 125,
          personUnit: 'tCO₂/人·年',
        },
      },
    })

    expect(wrapper.get('[data-testid="score-area-value"]').text()).toBe('0.00')
    expect(wrapper.get('[data-testid="score-area-track"]').attributes('aria-label')).toContain('0%')
    expect(wrapper.get('[data-testid="score-area-bar"]').attributes('style')).toContain('width: 0%')
    expect(wrapper.get('[data-testid="score-person-value"]').text()).toBe('—')
    expect(wrapper.get('[data-testid="score-person-track"]').attributes('data-raw-ratio')).toBe('125')
    expect(wrapper.get('[data-testid="score-person-track"]').attributes('aria-label')).toContain('125%')
    expect(wrapper.get('[data-testid="score-person-track"]').attributes('aria-label')).toContain('超出可绘制范围')
    expect(wrapper.get('[data-testid="score-person-bar"]').attributes('style')).toContain('width: 100%')
    wrapper.unmount()
  })

  it('keeps an available number visible when its proportion is unavailable', () => {
    const wrapper = mount(ScreenIntensitySummary, {
      props: {
        data: {
          areaValue: 12.5,
          areaPercentage: null,
          areaUnit: 'kgCO₂/m²·年',
          personValue: null,
          personPercentage: null,
          personUnit: 'tCO₂/人·年',
        },
      },
    })

    expect(wrapper.get('[data-testid="score-area-value"]').text()).toBe('12.50')
    expect(wrapper.get('.intensity-label-line .intensity-value').text()).toBe('12.50kgCO₂/m²·年')
    expect(wrapper.text()).not.toContain('比例—')
    expect(wrapper.get('[data-testid="score-area-track"]').attributes('data-state')).toBe('missing')
    expect(wrapper.find('[data-testid="score-area-bar"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('比例暂不可用')
    wrapper.unmount()
  })
})
