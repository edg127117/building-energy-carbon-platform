import { describe, expect, it } from 'vitest'
import { currentBeijingDate, currentScreenPeriod, followBeijingDay, periodBuckets, validScreenPeriod } from './screen-period'

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
  it('validates day periods as real Beijing calendar dates and provides 24 hour buckets', () => {
    expect(currentBeijingDate()).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(validScreenPeriod({ kind: 'day', value: '2000-02-29' })).toBe(true)
    expect(validScreenPeriod({ kind: 'day', value: '2200-02-29' })).toBe(false)
    expect(validScreenPeriod({ kind: 'day', value: '2026-02-30' })).toBe(false)
    expect(validScreenPeriod({ kind: 'day', value: '1999-12-31' })).toBe(false)
    expect(periodBuckets({ kind: 'day', value: '2026-09-22' })).toHaveLength(24)
    expect(followBeijingDay({ kind: 'day', value: '2026-09-22' }, '2026-09-22', '2026-09-23')).toEqual({ kind: 'day', value: '2026-09-23' })
    expect(followBeijingDay({ kind: 'day', value: '2026-09-21' }, '2026-09-22', '2026-09-23')).toEqual({ kind: 'day', value: '2026-09-21' })
  })
})
