import { describe, expect, it } from 'vitest'
import { currentScreenPeriod, periodBuckets, validScreenPeriod } from './screen-period'

describe('screen year/month periods', () => {
  it('defaults to a Beijing year and rejects single-day queries', () => {
    expect(currentScreenPeriod().kind).toBe('year')
    expect(validScreenPeriod({ kind: 'month', value: '2026-10-27' })).toBe(false)
    expect(validScreenPeriod({ kind: 'year', value: '2026' })).toBe(true)
  })
  it('uses months for a year and actual calendar days for a month', () => {
    expect(periodBuckets({ kind: 'year', value: '2026' })).toHaveLength(12)
    expect(periodBuckets({ kind: 'month', value: '2024-02' })).toHaveLength(29)
    expect(periodBuckets({ kind: 'month', value: '2026-02' })).toHaveLength(28)
    expect(periodBuckets({ kind: 'month', value: '2026-13' })).toEqual([])
  })
})
