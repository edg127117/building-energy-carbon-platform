import type { BuildingDeviceSummary, BuildingRuntimeRanking } from '../api/building-monitoring'
import type { ScreenDeviceSummaryData, ScreenRuntimeRankingEntry } from './screen-summaries'
import summaryMessages from '../locales/summaries'
import redesignMessages from '../locales/redesign'

const countValid = (value: number): boolean => Number.isSafeInteger(value) && value >= 0
const finiteNonNegative = (value: number): boolean => Number.isFinite(value) && value >= 0
const recordCountsValid = (counts: Record<string, number>) => Object.values(counts).every(countValid)
const sumCounts = (counts: Record<string, number>) => Object.values(counts).reduce((sum, value) => sum + value, 0)

function categoriesValid(value: BuildingDeviceSummary['modes']): boolean {
  return [value.observed, value.unknown, value.unsupported].every(countValid)
    && recordCountsValid(value.counts) && recordCountsValid(value.reasons)
    && countValid(sumCounts(value.counts)) && sumCounts(value.counts) === value.observed
    && countValid(sumCounts(value.reasons)) && sumCounts(value.reasons) === value.unknown
    && countValid(value.observed + value.unknown + value.unsupported)
}

export function validateBuildingDeviceSummary(value: BuildingDeviceSummary, buildingId: string): void {
  // 计数来自同一后端建筑范围；拒绝跨建筑或自相矛盾快照，避免卡片显示错范围数据。
  if (value.buildingId !== buildingId || value.scope !== 'ACTIVE_BOUND_DAIKIN_INDOOR'
    || !countValid(value.asOf) || !countValid(value.expiresAt) || value.expiresAt <= value.asOf
    || ![value.total, value.on, value.off, value.unknown].every(countValid)
    || !countValid(value.on + value.off + value.unknown) || value.on + value.off + value.unknown !== value.total
    || !countValid(value.total) || !recordCountsValid(value.unknownReasons) || sumCounts(value.unknownReasons) !== value.unknown
    || !categoriesValid(value.modes) || !countValid(value.modes.observed + value.modes.unknown + value.modes.unsupported)
    || value.modes.observed + value.modes.unknown + value.modes.unsupported !== value.on
    || !categoriesValid(value.speeds) || !countValid(value.speeds.observed + value.speeds.unknown + value.speeds.unsupported)
    || value.speeds.observed + value.speeds.unknown + value.speeds.unsupported !== value.on) {
    throw new Error('Invalid building device summary contract')
  }
}

export function mapBuildingDeviceSummary(value: BuildingDeviceSummary): ScreenDeviceSummaryData {
  const count = (entries: Record<string, number>, key: string): number | null => Object.prototype.hasOwnProperty.call(entries, key) ? entries[key] : null
  // 仅呈现已批准的协议类别；没有该键时保留为缺失，不把未映射类别并入可见行。
  return {
    total: value.total, on: value.on, off: value.off, unknown: value.unknown,
    modes: [
      { key: 'fan', label: summaryMessages.deviceSummaryFan, count: count(value.modes.counts, 'fan') },
      { key: 'cool', label: summaryMessages.deviceSummaryCooling, count: count(value.modes.counts, 'cooling') },
      { key: 'heat', label: summaryMessages.deviceSummaryHeating, count: count(value.modes.counts, 'heating') },
    ],
    speeds: [
      { key: 'low', label: redesignMessages.prototypeSpeedLow, count: count(value.speeds.counts, 'low') },
      { key: 'medium', label: redesignMessages.prototypeSpeedMedium, count: count(value.speeds.counts, 'middle') },
      { key: 'high', label: redesignMessages.prototypeSpeedHigh, count: count(value.speeds.counts, 'high') },
      { key: 'auto', label: redesignMessages.prototypeSpeedAuto, count: count(value.speeds.counts, 'automatic') },
    ],
  }
}

function monthWindow(month: string): [number, number] | null {
  const match = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(month)
  if (!match) return null
  const year = Number(match[1]), monthIndex = Number(match[2]) - 1
  const offset = 8 * 60 * 60 * 1000
  return [Date.UTC(year, monthIndex, 1) - offset, Date.UTC(year, monthIndex + 1, 1) - offset]
}

export function validateBuildingRuntimeRanking(value: BuildingRuntimeRanking, buildingId: string, month: string): void {
  const window = monthWindow(month), ids = new Set<string>(), identities = new Set<string>()
  const validItems = value.items.every((item, index) => {
    const unique = !ids.has(item.equipmentId) && !identities.has(item.identityId)
    ids.add(item.equipmentId); identities.add(item.identityId)
    return item.rank === index + 1 && !!item.equipmentId && !!item.identityId && unique
      && countValid(item.mappingVersion) && item.mappingVersion > 0
      && countValid(item.onMillis) && countValid(item.coveredMillis) && item.onMillis <= item.coveredMillis
      && item.coveredMillis <= value.elapsedMillis
      && finiteNonNegative(item.hours) && finiteNonNegative(item.percentage) && item.percentage <= 100
      && ['PARTIAL', 'COMPLETE'].includes(item.coverageStatus)
  })
  if (!window || value.buildingId !== buildingId || value.scope !== 'ACTIVE_BOUND_DAIKIN_INDOOR'
    || value.source !== 'PLATFORM_OBSERVED' || value.month !== month || value.statisticsZone !== 'Asia/Shanghai'
    || !countValid(value.asOf) || value.periodStart !== window[0] || value.periodEnd !== window[1]
    || value.asOf < value.periodStart || value.asOf >= value.periodEnd
    || !countValid(value.elapsedMillis) || value.elapsedMillis > value.periodEnd - value.periodStart
    || !countValid(value.totalDevices) || !countValid(value.rankedDevices)
    || value.items.length > 5 || value.items.length > value.rankedDevices || value.rankedDevices > value.totalDevices
    || !recordCountsValid(value.excludedReasons)
    || !validItems) {
    throw new Error('Invalid building runtime ranking contract')
  }
}

export function mapBuildingRuntimeRanking(value: BuildingRuntimeRanking): ScreenRuntimeRankingEntry[] {
  // 返回设备条目；房间只作为展示位置，排名与条形比例仍由后端按设备提供。
  return value.items.map(item => ({
    rank: item.rank,
    room: `${item.spaceName?.trim() || '—'} · ${item.equipmentName?.trim() || item.equipmentCode}`,
    floor: item.floorName?.trim() || '—',
    hours: item.hours,
    percentage: item.percentage,
    coverageStatus: item.coverageStatus,
    coveredMillis: item.coveredMillis,
    onMillis: item.onMillis,
  }))
}
