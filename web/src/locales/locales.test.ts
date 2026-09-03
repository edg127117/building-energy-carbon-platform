import { describe, expect, it } from 'vitest'
import { t } from './index'
import { screens } from '@/modules/large-screen/public'
import { formatDateTime, formatNumber } from '@/shared/utils/format'
describe('Chinese messages and display formatting', () => {
  it('resolves all registered screen names', () => {
    for (const screen of screens) expect(t(screen.titleKey)).toMatch(/大屏$/)
  })
  it('substitutes complete sentences and rejects unknown keys', () => {
    expect(t('common.updatedAt', { time: '12:00' })).toBe('数据更新时间：12:00')
    expect(() => t('missing.key')).toThrow()
  })
  it('does not invent missing numbers or dates', () => {
    expect(formatNumber(null)).toBe('—')
    expect(formatNumber(NaN)).toBe('—')
    expect(formatNumber(1234.5)).toBe('1,234.5')
    expect(formatDateTime('invalid')).toBe('—')
    expect(formatDateTime('2026-01-01T00:00:00Z', 'Asia/Shanghai')).toContain('08:00:00')
  })
})
