import { t } from '@/locales'
import { periodBuckets, type ScreenPeriod } from './screen-period'
import type { ScreenBusinessFlowGraph } from './screen-redesign'
import type { ScreenDeviceSummaryData } from './screen-summaries'
import type { ScreenMirrorData, ScreenUsageData } from './screen-analytical'

// 用户要求先审阅有数据的完整原型。此文件是固定展示素材，不读取接口、写入数据或定义正式计算规则。
export const prototypeMetrics = { carbon: 104, intensity: 5.2, previousCarbon: 12, previousIntensity: 0.6 }
export const prototypeDevices = {
  total: 40, on: 25, off: 12, unknown: 3,
  modes: [
    { key: 'fan', label: t('largeScreen.deviceSummaryFan'), count: 5 },
    { key: 'cool', label: t('largeScreen.deviceSummaryCooling'), count: 15 },
    { key: 'heat', label: t('largeScreen.deviceSummaryHeating'), count: 5 },
  ],
  speeds: [
    { key: 'low', label: t('largeScreen.prototypeSpeedLow'), count: 8 },
    { key: 'medium', label: t('largeScreen.prototypeSpeedMedium'), count: 10 },
    { key: 'high', label: t('largeScreen.prototypeSpeedHigh'), count: 5 },
    { key: 'auto', label: t('largeScreen.prototypeSpeedAuto'), count: 2 },
  ],
}
// 固定快照按序轮播，仅用于原型动效；计数均保持开关总数及运行分类一致。
export const PROTOTYPE_DEVICE_FRAME_MS = 8000
export const prototypeDeviceFrames: readonly ScreenDeviceSummaryData[] = [
  prototypeDevices,
  { ...prototypeDevices, on: 27, off: 10,
    modes: prototypeDevices.modes.map((entry, index) => ({ ...entry, count: [6, 16, 5][index] })),
    speeds: prototypeDevices.speeds.map((entry, index) => ({ ...entry, count: [9, 10, 6, 2][index] })),
  },
  { ...prototypeDevices, on: 24, off: 13,
    modes: prototypeDevices.modes.map((entry, index) => ({ ...entry, count: [4, 15, 5][index] })),
    speeds: prototypeDevices.speeds.map((entry, index) => ({ ...entry, count: [7, 9, 6, 2][index] })),
  },
]
export const prototypeAlarms = { deviceAnomalies: 3, emissionWarnings: 2 }
export const prototypeRuntimeRanking = [612, 568, 524, 486, 442].map((hours, index) => ({
  rank: index + 1,
  room: t('largeScreen.summaryRankRoom' + (index + 1)),
  floor: t('largeScreen.summaryRankFloor' + (index + 1)),
  hours,
  percentage: [100, 93, 86, 79, 72][index],
}))
export const prototypeIntensityBenchmarks = { constraint: 56.71, baseline: 35.56, guide: 20.72 }

const purposes = ['hvac', 'redesignOffice', 'redesignLab', 'redesignMeeting', 'redesignMedia', 'redesignCorridor']
const annualRows = [[4, 6, 5, 6, 3, 2], [2, 3, 3, 4, 0, 0], [9, 9, 12, 10, 7, 7], [1, 1, 0, 1, 0, 0], [0, 0, 0, 0, 4, 0], [1, 1, 1, 1, 1, 0]]
const monthlyRows = [[0.5, 0.7, 0.6, 0.7, 0.3, 0.2], [0.2, 0.3, 0.3, 0.4, 0, 0], [1, 1.1, 1.4, 1.2, 0.9, 0.8], [0.1, 0.1, 0, 0.2, 0, 0], [0, 0, 0, 0, 0.4, 0], [0.1, 0.1, 0.1, 0.1, 0.1, 0.1]]

export function prototypeFlow(period: ScreenPeriod, mode: 'carbon' | 'energy'): ScreenBusinessFlowGraph {
  const monthly = period.kind === 'month'
  const scale = mode === 'carbon' ? 1 : 0.2
  const purposeTotals = monthly ? [3, 1.2, 6.4, 0.4, 0.4, 0.6] : [26, 12, 54, 3, 4, 5]
  const floors = monthly ? [1.9, 2.3, 2.4, 2.6, 1.7, 1.1] : [17, 20, 21, 22, 15, 9]
  const nodes: ScreenBusinessFlowGraph['nodes'] = [
    ...purposes.map((key, index) => ({ id: `U${index}`, name: t(`largeScreen.${key}`), depth: 0 as const, value: Number((purposeTotals[index] * scale).toFixed(3)) })),
    ...Array.from({ length: 6 }, (_, index) => ({ id: `F${index}`, name: t('largeScreen.redesignFloorNumber', { value: index + 1 }), depth: 1 as const, value: Number((floors[index] * scale).toFixed(3)) })),
    { id: 'TOTAL', name: t('largeScreen.redesignBuildingTotal'), depth: 2, value: monthly ? (mode === 'carbon' ? 12 : 2.4) : (mode === 'carbon' ? 104 : 20.8) },
  ]
  const rows = monthly ? monthlyRows : annualRows
  // 两个单位视图共用固定的展示比例；此缩放只生成原型素材，不是排放因子或折标煤公式。
  const links: ScreenBusinessFlowGraph['links'] = []
  rows.forEach((row, source) => row.forEach((value, target) => {
    if (value > 0) links.push({ source: `U${source}`, target: `F${target}`, value: Number((value * scale).toFixed(3)) })
  }))
  floors.forEach((value, index) => links.push({ source: `F${index}`, target: 'TOTAL', value: Number((value * scale).toFixed(3)) }))
  return { nodes, links, unit: mode === 'carbon' ? 'tCO₂e' : 'tce', total: monthly ? (mode === 'carbon' ? 12 : 2.4) : (mode === 'carbon' ? 104 : 20.8) }
}

const dailyElectricity = [420, 390, 440, 470, 500, 380, 350, 430, 460, 510, 540, 520, 400, 370, 450, 490, 530, 550, 520, 410, 380, 470, 500, 540, 560, 530, 420, 390, 480, 510, 520]
const dailyTemperature = [16, 17, 18, 19, 18, 17, 16, 17, 18, 20, 21, 20, 19, 18, 17, 18, 19, 21, 22, 20, 19, 18, 17, 18, 20, 21, 20, 19, 18, 17, 18]
const dailyDynamic = [0.21, 0.2, 0.23, 0.24, 0.25, 0.19, 0.18, 0.22, 0.23, 0.26, 0.28, 0.27, 0.2, 0.19, 0.23, 0.25, 0.27, 0.28, 0.26, 0.21, 0.19, 0.24, 0.25, 0.28, 0.29, 0.27, 0.21, 0.2, 0.24, 0.26, 0.27]
const dailyStatic = [0.24, 0.22, 0.25, 0.26, 0.28, 0.21, 0.2, 0.24, 0.26, 0.29, 0.3, 0.29, 0.22, 0.21, 0.25, 0.27, 0.3, 0.31, 0.29, 0.23, 0.21, 0.26, 0.28, 0.3, 0.31, 0.3, 0.24, 0.22, 0.27, 0.29, 0.29]
const dailyFactors = [0.38, 0.46, 0.58, 0.49, 0.33, 0.25, 0.18, 0.29, 0.43, 0.61, 0.52, 0.34, 0.22, 0.16, 0.31, 0.47, 0.63, 0.55, 0.37, 0.24, 0.15, 0.28, 0.44, 0.59, 0.5, 0.32, 0.2, 0.3, 0.48, 0.62, 0.4]

export function prototypeUsage(period: ScreenPeriod): ScreenUsageData {
  const days = periodBuckets(period).length
  return {
    electricity: period.kind === 'year' ? [12500, 10700, 14300, 14300, 17900, 21400, 25000, 25000, 16100, 14300, 7100, 7100] : dailyElectricity.slice(0, days),
    temperature: period.kind === 'year' ? [4, 7, 12, 18, 23, 27, 30, 29, 24, 18, 12, 6] : dailyTemperature.slice(0, days),
    electricityUnit: 'kWh', temperatureUnit: '℃',
  }
}

export function prototypeMirror(period: ScreenPeriod): ScreenMirrorData {
  const days = periodBuckets(period).length
  return {
    dynamicEmissions: period.kind === 'year' ? [6.5, 5.8, 7.4, 7.8, 9.7, 11.2, 13.6, 13.2, 8.7, 7.6, 3.8, 3.7] : dailyDynamic.slice(0, days),
    staticEmissions: period.kind === 'year' ? [7, 6, 8, 8, 10, 12, 14, 14, 9, 8, 4, 4] : dailyStatic.slice(0, days),
    dynamicFactors: period.kind === 'year' ? [0.48, 0.6, 0.43, 0.57, 0.36, 0.22, 0.17, 0.28, 0.41, 0.55, 0.64, 0.49] : dailyFactors.slice(0, days),
    staticFactors: Array.from({ length: days }, () => 0.56),
    emissionUnit: 'tCO₂e', factorUnit: 'kgCO₂e/kWh',
  }
}

export function prototypeIntensity(period: ScreenPeriod) {
  const monthly = period.kind === 'month'
  return { areaValue: monthly ? 0.6 : 5.2, personValue: monthly ? 0.015 : 0.13, areaPercentage: 65, personPercentage: 52,
    areaUnit: t(monthly ? 'largeScreen.prototypeAreaMonthUnit' : 'largeScreen.redesignPerAreaUnit'),
    personUnit: t(monthly ? 'largeScreen.prototypePersonMonthUnit' : 'largeScreen.redesignPerPersonUnit') }
}
