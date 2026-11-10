import { describe, expect, it } from 'vitest'
import { prototypeDeviceFrames } from './screen-prototype'
import { periodEntry, PLAN_PROTOTYPE } from './screen-redesign'

describe('prototype input scope', () => {
  it('does not retain another period when a card selection changes', () => {
    const entry = { period: { kind: 'year' as const, value: '2026' }, data: { value: 0 } }
    expect(periodEntry(entry, entry.period)).toEqual({ value: 0 })
    expect(periodEntry(entry, { kind: 'year', value: '2025' })).toBeUndefined()
    expect(periodEntry(entry, { kind: 'month', value: '2026-01' })).toBeUndefined()
  })
  it('keeps the explicitly permitted plan fixture internally consistent', () => {
    const baseline = PLAN_PROTOTYPE.baseline.reduce<number>((sum, value) => sum + (value ?? 0), 0)
    expect(baseline).toBe(160)
    expect(PLAN_PROTOTYPE.reductionPercent).toBe(15)
    const actual = PLAN_PROTOTYPE.actual.reduce<number>((sum, value) => sum + (value ?? 0), 0)
    expect(actual).toBe(104)
    expect(PLAN_PROTOTYPE.remainingQuota).toBe(32)
    expect(baseline * (1 - PLAN_PROTOTYPE.reductionPercent / 100) - actual).toBeCloseTo(PLAN_PROTOTYPE.remainingQuota!, 8)
    PLAN_PROTOTYPE.baseline.forEach((value, i) => expect(PLAN_PROTOTYPE.quota[i]).toBeCloseTo(value! * 0.85, 8))
  })
  it('keeps every animated prototype snapshot internally consistent', () => {
    for (const frame of prototypeDeviceFrames) {
      expect(frame.on! + frame.off! + frame.unknown!).toBe(frame.total)
      expect(frame.modes.reduce((sum, item) => sum + (item.count ?? 0), 0)).toBe(frame.on)
      expect(frame.speeds.reduce((sum, item) => sum + (item.count ?? 0), 0)).toBe(frame.on)
    }
  })

})
