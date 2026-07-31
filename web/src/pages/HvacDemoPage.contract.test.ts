import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(
  resolve(process.cwd(), 'src/pages/HvacDemoPage.vue'),
  'utf8',
)

describe('HvacDemoPage real-data boundary', () => {
  it('uses the dashboard composable and contains no business mock generator', () => {
    expect(source).toContain('useHvacDashboard')
    expect(source).not.toContain('Math.random')
    expect(source).not.toContain('updateMockData')
    expect(source).not.toContain('前端效果演示数据')
  })

  it('does not render fake history or fake calculation details', () => {
    expect(source).not.toContain("from 'echarts'")
    expect(source).not.toContain('formulaMap')
    expect(source).not.toContain('selectedFormula')
    expect(source).toContain('历史曲线将在下一迭代接入')
    expect(source).toContain('计算详情将在下一迭代接入')
  })

  it('shows real loading, empty, partial-error and refresh states', () => {
    expect(source).toContain('initializing')
    expect(source).toContain('buildingError')
    expect(source).toContain('snapshotError')
    expect(source).toContain('indicatorError')
    expect(source).toContain('coveragePercent')
    expect(source).toContain('selectBuilding')
    expect(source).toContain('refresh')
    expect(source).toContain('FROZEN_POINT_DEFINITIONS')
    expect(source).toContain('missingInputs.join')
  })
})
