import { describe, expect, it } from 'vitest'
import type { AssetPointReading } from '../../models/assets'
import {
  calculateCurrentUnbalance,
  extractSinglePhaseMetrics,
  extractThreePhaseMetrics,
  extractTrendValues,
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

  describe('extractSinglePhaseMetrics and extractThreePhaseMetrics', () => {
    const iduPoints: AssetPointReading[] = [
      { pointId: '1', pointCode: 'IDU1_EPN', pointName: '反向电能', unit: 'kWh', value: 2.59, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '2', pointCode: 'IDU1_EPP', pointName: '正向电能', unit: 'kWh', value: 0.0, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '3', pointCode: 'IDU1_F', pointName: '频率', unit: 'Hz', value: 50.01, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '4', pointCode: 'IDU1_I', pointName: '内机电流', unit: 'A', value: -1.587, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '5', pointCode: 'IDU1_P', pointName: '内机输入功率', unit: 'kW', value: 0.232, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '6', pointCode: 'IDU1_Pf', pointName: '功率因数', unit: '1', value: 0.641, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '7', pointCode: 'IDU1_U', pointName: '内机电压', unit: 'V', value: 228.335, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
    ]

    it('extracts single-phase telemetry correctly from hardware prefixed point codes', () => {
      const metrics = extractSinglePhaseMetrics(iduPoints)
      expect(metrics.power).toBe(0.232)
      expect(metrics.energy).toBe(2.59)
      expect(metrics.voltage).toBe(228.335)
      expect(metrics.current).toBe(-1.587)
      expect(metrics.powerFactor).toBe(0.641)
      expect(metrics.frequency).toBe(50.01)

      const trend = extractTrendValues(iduPoints, '1P')
      expect(trend.power).toBe(0.232)
      expect(trend.voltage).toBe(228.335)
    })

    const oduPoints: AssetPointReading[] = [
      { pointId: '1', pointCode: 'ODU1_EPN', pointName: '反向有功累计电能', unit: 'kWh', value: 13.04, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '2', pointCode: 'ODU1_EPP', pointName: '正向有功累计电能', unit: 'kWh', value: 0.0, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '3', pointCode: 'ODU1_F', pointName: '电源频率', unit: 'Hz', value: 50.0, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '4', pointCode: 'ODU1_IA', pointName: '外机A相电流', unit: 'A', value: 1.184, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '5', pointCode: 'ODU1_IB', pointName: '外机B相电流', unit: 'A', value: 1.227, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '6', pointCode: 'ODU1_IC', pointName: '外机C相电流', unit: 'A', value: 1.246, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '7', pointCode: 'ODU1_P', pointName: '外机总有功输入功率', unit: 'kW', value: -0.048, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '8', pointCode: 'ODU1_Pf', pointName: '外机总功率因数', unit: '1', value: 0.059, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '9', pointCode: 'ODU1_UA', pointName: '外机A相电压', unit: 'V', value: 224.902, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '10', pointCode: 'ODU1_UB', pointName: '外机B相电压', unit: 'V', value: 225.464, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
      { pointId: '11', pointCode: 'ODU1_UC', pointName: '外机C相电压', unit: 'V', value: 226.383, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
    ]

    it('extracts three-phase telemetry correctly from hardware prefixed point codes', () => {
      const metrics = extractThreePhaseMetrics(oduPoints)
      expect(metrics.pTotal).toBe(-0.048)
      expect(metrics.energy).toBe(13.04)
      expect(metrics.pfTotal).toBe(0.059)
      expect(metrics.uA).toBe(224.902)
      expect(metrics.uB).toBe(225.464)
      expect(metrics.uC).toBe(226.383)
      expect(metrics.iA).toBe(1.184)
      expect(metrics.iB).toBe(1.227)
      expect(metrics.iC).toBe(1.246)
      expect(metrics.frequency).toBe(50.0)

      const trend = extractTrendValues(oduPoints, '3P')
      expect(trend.power).toBe(-0.048)
      expect(trend.currentA).toBe(1.184)
      expect(trend.currentB).toBe(1.227)
      expect(trend.currentC).toBe(1.246)
    })
  })
})
