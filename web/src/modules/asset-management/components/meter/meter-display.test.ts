import { describe, expect, it } from 'vitest'
import {
  calculateCurrentUnbalance,
  getMeterPhaseType,
  getPointDisplayName,
  getQualityInfo,
  isMeterEquipment,
} from './meter-display'

describe('meter-display helper utilities', () => {
  describe('isMeterEquipment', () => {
    it('accurately detects single-phase and three-phase meters', () => {
      expect(isMeterEquipment({ typeCode: '3P_METER' })).toBe(true)
      expect(isMeterEquipment({ typeCode: '1P_METER' })).toBe(true)
      expect(isMeterEquipment({ typeCode: 'ELECTRIC_METER' })).toBe(true)
      expect(isMeterEquipment({ typeCode: 'AHU', category: 'METER' })).toBe(true)
      expect(isMeterEquipment({ typeCode: 'IDU', expectedProfileCode: 'PRODUCT_IDU_METER_1039' })).toBe(true)
    })

    it('returns false for conventional non-meter equipment', () => {
      expect(isMeterEquipment({ typeCode: 'AHU' })).toBe(false)
      expect(isMeterEquipment({ typeCode: 'WCP' })).toBe(false)
      expect(isMeterEquipment({ typeCode: 'WCR' })).toBe(false)
      expect(isMeterEquipment(null)).toBe(false)
    })
  })

  describe('getMeterPhaseType', () => {
    it('identifies 3P vs 1P from typeCode', () => {
      expect(getMeterPhaseType({ typeCode: '3P_METER' })).toBe('3P')
      expect(getMeterPhaseType({ typeCode: '1P_METER' })).toBe('1P')
    })

    it('infers 3P from characteristic points when typeCode is ambiguous', () => {
      expect(getMeterPhaseType({ typeCode: 'METER' }, [{ pointId: '1', pointCode: 'U_A', pointName: '', unit: 'V', value: 220, eventTime: null, receivedTime: null, dataQuality: 0, status: '', usageStatus: '', reason: null }])).toBe('3P')
      expect(getMeterPhaseType({ typeCode: 'METER' }, [{ pointId: '2', pointCode: 'POWER', pointName: '', unit: 'kW', value: 2.5, eventTime: null, receivedTime: null, dataQuality: 0, status: '', usageStatus: '', reason: null }])).toBe('1P')
    })
  })

  describe('getQualityInfo', () => {
    it('maps 0 to Q0 实测 with success tone', () => {
      const q0 = getQualityInfo(0)
      expect(q0.label).toBe('实测 (Q0)')
      expect(q0.code).toBe('Q0')
      expect(q0.tone).toBe('success')
    })

    it('maps 1 to Q1 插值 with primary tone', () => {
      const q1 = getQualityInfo(1)
      expect(q1.label).toBe('插值 (Q1)')
      expect(q1.code).toBe('Q1')
      expect(q1.tone).toBe('primary')
    })

    it('maps 2 to Q2 参考 with info tone', () => {
      const q2 = getQualityInfo(2)
      expect(q2.label).toBe('参考 (Q2)')
      expect(q2.code).toBe('Q2')
      expect(q2.tone).toBe('info')
    })

    it('handles null/undefined gracefully', () => {
      const qNull = getQualityInfo(null)
      expect(qNull.code).toBe('--')
    })
  })

  describe('getPointDisplayName', () => {
    it('translates telemetry codes to standard friendly Chinese names', () => {
      expect(getPointDisplayName({ pointCode: 'P_TOTAL' })).toBe('实时总有功功率')
      expect(getPointDisplayName({ pointCode: 'EPP' })).toBe('正向累计用电量')
      expect(getPointDisplayName({ pointCode: 'POWER' })).toBe('实时用电功率')
      expect(getPointDisplayName({ pointCode: 'VOLTAGE' })).toBe('工作电压')
      expect(getPointDisplayName({ pointCode: 'U_A' })).toBe('A相相电压')
    })

    it('preserves existing Chinese pointName if already provided', () => {
      expect(getPointDisplayName({ pointCode: 'CUSTOM', pointName: '自定义车间动力表' })).toBe('自定义车间动力表')
    })
  })

  describe('calculateCurrentUnbalance', () => {
    it('computes balance ratio within 15% as balanced', () => {
      const res = calculateCurrentUnbalance(48, 47, 49)
      expect(res.isBalanced).toBe(true)
      expect(res.tone).toBe('success')
      expect(res.unbalanceRatio).toBeLessThanOrEqual(15)
    })

    it('detects unbalance exceeding 15% as warning', () => {
      const res = calculateCurrentUnbalance(50, 30, 20)
      expect(res.isBalanced).toBe(false)
      expect(res.tone).toBe('warning')
    })

    it('handles standby/zero current state', () => {
      const res = calculateCurrentUnbalance(0.01, 0.02, 0.01)
      expect(res.isBalanced).toBe(true)
      expect(res.label).toContain('空载待机')
    })
  })
})
