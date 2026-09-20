import { t } from '@/locales'
import type { AssetPointReading } from '../../models/assets'

/**
 * 判断设备是否为电表（单相电表 / 三相电表 / 采集监测电表）。
 * 基于 typeCode、category 或 expectedProfileCode 进行判定，不依赖易变的外部名称。
 */
export function isMeterEquipment(
  equipment: { typeCode?: string | null; category?: string | null; expectedProfileCode?: string | null } | Record<string, unknown> | null | undefined,
): boolean {
  if (!equipment || typeof equipment !== 'object') return false
  const eq = equipment as Record<string, unknown>
  const type = String(eq.typeCode ?? '').toUpperCase()
  const cat = String(eq.category ?? '').toUpperCase()
  const profile = String(eq.expectedProfileCode ?? '').toUpperCase()

  return (
    type.includes('METER') ||
    type.includes('1P_') ||
    type.includes('3P_') ||
    type === '1P' ||
    type === '3P' ||
    cat.includes('METER') ||
    cat.includes('ENERGY') ||
    profile.includes('METER') ||
    profile.includes('1039')
  )
}

/**
 * 判定电表的分相类型：三相电表 ('3P') 或 单相电表 ('1P')。
 * 优先依据设备类型编码判定；若类型未指明，则依据测点特征码（如 U_A/U_B/U_C 或 P_TOTAL）进行归纳。
 */
export function getMeterPhaseType(
  equipment: { typeCode?: string | null } | Record<string, unknown> | null | undefined,
  points?: AssetPointReading[] | null,
): '3P' | '1P' {
  const eq = (equipment ?? {}) as Record<string, unknown>
  const type = String(eq.typeCode ?? '').toUpperCase()
  if (type.includes('3P') || type.includes('THREE_PHASE') || type.includes('THREEPHASE')) {
    return '3P'
  }
  if (type.includes('1P') || type.includes('SINGLE_PHASE') || type.includes('SINGLEPHASE')) {
    return '1P'
  }

  // 测点特征码推断
  if (points && points.length > 0) {
    const codes = new Set(points.map(p => (p.pointCode ?? '').toUpperCase()))
    if (codes.has('U_A') || codes.has('I_A') || codes.has('P_TOTAL') || codes.has('P_A')) {
      return '3P'
    }
  }

  return '1P'
}

export type DataQualityInfo = {
  label: string
  code: string
  tone: 'success' | 'primary' | 'info' | 'warning'
  description: string
}

/**
 * 将 TDengine / 后端返回的 Q0/Q1/Q2 数字质量代码解析为通俗中文业务标签与等级说明。
 * - Q0 (0): 现场原始实测直采（高置信度）
 * - Q1 (1): 清洗与插值补全
 * - Q2 (2): 典型工况核算参考
 */
export function getQualityInfo(dataQuality: number | null | undefined): DataQualityInfo {
  if (dataQuality === 0) {
    return {
      label: t('assetManagement.meter.q0Label'),
      code: 'Q0',
      tone: 'success',
      description: t('assetManagement.meter.q0Desc'),
    }
  }
  if (dataQuality === 1) {
    return {
      label: t('assetManagement.meter.q1Label'),
      code: 'Q1',
      tone: 'primary',
      description: t('assetManagement.meter.q1Desc'),
    }
  }
  if (dataQuality === 2) {
    return {
      label: t('assetManagement.meter.q2Label'),
      code: 'Q2',
      tone: 'info',
      description: t('assetManagement.meter.q2Desc'),
    }
  }
  return {
    label: t('assetManagement.meter.uncalibrated'),
    code: '--',
    tone: 'info',
    description: t('assetManagement.meter.uncalibratedDesc'),
  }
}

/**
 * 获取测点通俗中文展示名称。
 */
export function getPointDisplayName(
  point: { pointCode?: string | null; pointName?: string | null } | Record<string, unknown> | null | undefined,
): string {
  if (!point || typeof point !== 'object') return '--'
  const p = point as Record<string, unknown>
  const rawName = typeof p.pointName === 'string' ? p.pointName.trim() : ''
  if (rawName && !/^[A-Za-z0-9_]+$/.test(rawName)) {
    return rawName
  }
  const rawCode = typeof p.pointCode === 'string' ? p.pointCode.toUpperCase() : ''
  try {
    return t(`assetManagement.meter.pointNames.${rawCode}`)
  } catch {
    return rawName || (typeof p.pointCode === 'string' ? p.pointCode : '--')
  }
}

export type PhaseBalanceResult = {
  unbalanceRatio: number | null
  isBalanced: boolean
  label: string
  tone: 'success' | 'warning' | 'info'
}

/**
 * 测算三相电流不平衡度：
 * 不平衡度 = (max(Ia, Ib, Ic) - min(Ia, Ib, Ic)) / avg(Ia, Ib, Ic) * 100%
 * 国标一般要求低压供电三相电流不平衡度 <= 15%。
 */
export function calculateCurrentUnbalance(
  ia: number | null | undefined,
  ib: number | null | undefined,
  ic: number | null | undefined,
): PhaseBalanceResult {
  if (ia == null || ib == null || ic == null) {
    return {
      unbalanceRatio: null,
      isBalanced: true,
      label: t('assetManagement.meter.insufficientCurrentPoints'),
      tone: 'info',
    }
  }

  const currents = [ia, ib, ic]
  const maxI = Math.max(...currents)
  const minI = Math.min(...currents)
  const avgI = (ia + ib + ic) / 3

  if (avgI <= 0.1) {
    return {
      unbalanceRatio: 0,
      isBalanced: true,
      label: t('assetManagement.meter.standbyZeroCurrent'),
      tone: 'info',
    }
  }

  const unbalanceRatio = ((maxI - minI) / avgI) * 100
  const isBalanced = unbalanceRatio <= 15

  return {
    unbalanceRatio: Number(unbalanceRatio.toFixed(1)),
    isBalanced,
    label: isBalanced
      ? t('assetManagement.meter.balancedLabel', { ratio: unbalanceRatio.toFixed(1) })
      : t('assetManagement.meter.unbalancedLabel', { ratio: unbalanceRatio.toFixed(1) }),
    tone: isBalanced ? 'success' : 'warning',
  }
}
