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

  it('connects real calculation details and the unified real history panel', () => {
    expect(source).not.toContain("from 'echarts'")
    expect(source).not.toContain('formulaMap')
    expect(source).not.toContain('selectedFormula')
    expect(source).not.toContain('历史曲线将在下一迭代接入')
    expect(source).not.toContain('计算详情将在下一迭代接入')
    expect(source).toContain('useHvacCalculationDetail')
    expect(source).toContain('HvacCalculationDetailDrawer')
    expect(source).toContain('HvacHistoryPanel')
    expect(source).toContain(':building-id="selectedBuildingId"')
    expect(source).toContain(':indicators="indicatorViews"')
    expect(source).toContain(':snapshot-points="snapshot?.points ?? []"')
    expect(source).toContain('openCalculationDetail')
    expect(source).toContain('@click="openCalculationDetail(card)"')
    expect(source).not.toMatch(/class="indicator-card"[^>]*\bdisabled\b/)
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
    expect(source).toContain('card.summaryText')
    expect(source).toContain('card.supportingText')
    expect(source).toContain('formatIndicatorMinute')
    expect(source).toContain('card.statusLabel')
    expect(source).toContain('当前有效测点完整率')
    expect(source).toContain('stalePointDetail')
    expect(source).toContain('item.statusLabel')
    expect(source).toContain('item.lastDisplayValue')
    expect(source).not.toContain('测点数据完整率')
    expect(source).not.toContain("card.missingInputs.join('、')")
    expect(source).not.toContain('{{ card.reasonCode }}')
  })

  it('closes calculation evidence when the building changes or the page unmounts', () => {
    expect(source).toContain('handleBuildingChange')
    expect(source).toContain('closeCalculationDetail()')
  })

  it('uses the unified realtime lifecycle and renders the confirmed channel states', () => {
    expect(source).toContain('startRealtime()')
    expect(source).toContain('stopRealtime()')
    expect(source).not.toContain('startPolling')
    expect(source).not.toContain('stopPolling')
    expect(source).toContain("label: '实时连接中'")
    expect(source).toContain("label: '实时连接正常'")
    expect(source).toContain("label: '实时重连中，HTTP 保障'")
    expect(source).toContain("label: 'HTTP 保障中'")
    expect(source).toContain("label: '无该建筑访问权限'")
    expect(source).toContain("tone: 'blue'")
    expect(source).toContain("tone: 'green'")
    expect(source).toContain("tone: 'yellow'")
    expect(source).toContain("tone: 'red'")
  })

  it('keeps the history chart column shrinkable on narrow screens', () => {
    expect(source).toContain(
      '.bottom-grid { grid-template-columns: minmax(0, 1fr); }',
    )
  })

  it('keeps the topology compact and distinguishes missing point status', () => {
    expect(source).toContain(
      '.main-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 14px; }',
    )
    expect(source).toContain(
      '.indicators { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));',
    )
    expect(source).toContain(
      '.point-row.is-empty .point-quality { color: #687d91; }',
    )
    expect(source).toContain("'is-empty': pointViews.TOWER1_TCWin.status === 'NO_DATA'")
    expect(source).toContain('.device-state.is-empty { color: #687d91; }')
  })

  it('shows a controlled administrator entry backed by current menu state', () => {
    expect(source).toContain('useMenuStore')
    expect(source).toContain('managementPath')
    expect(source).toContain('PLATFORM_ADMIN')
    expect(source).not.toContain('menu.component')
  })
})
