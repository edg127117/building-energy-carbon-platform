import type { ChartOption } from '@/shared/charts/echarts'
import type { AverageHour, ComfortOccupancy, ContactSummary, CountSlot, DoorVisualState } from '../api/comfort'
import copy from '../locales/zh-CN'

const beijingClock = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

export function contactState(open: boolean | null | undefined): DoorVisualState {
  return open == null ? 'unknown' : open ? 'open' : 'closed'
}

export function contactTotal(summary?: ContactSummary | null) {
  return summary ? summary.open + summary.closed + summary.unknown : 0
}

export function contactSummaryText(summary: ContactSummary) {
  if (contactTotal(summary) === 1) {
    return copy.contactState[summary.open ? 'open' : summary.closed ? 'closed' : 'unknown']
  }
  return (['open', 'closed', 'unknown'] as const).filter(key => summary[key] > 0)
    .map(key => copy.contactCount[key](summary[key])).join(' · ')
}

export function beijingMinute(value: string) {
  return beijingClock.format(new Date(value))
}

export function formatDoorElapsed(seconds: number) {
  const wholeSeconds = Math.floor(seconds)
  const hours = Math.floor(wholeSeconds / 3600)
  const minutes = Math.floor(wholeSeconds / 60) % 60
  const remaining = wholeSeconds % 60
  return [
    hours ? copy.elapsedHours(hours) : '',
    minutes ? copy.elapsedMinutes(minutes) : '',
    remaining || wholeSeconds === 0 ? copy.elapsedSeconds(remaining) : '',
  ].join('')
}

export function peopleAxisMax(data: Pick<ComfortOccupancy, 'series1m' | 'series15m' | 'hourlyAvg'> | null) {
  const highest = Math.max(
    6,
    ...(data?.series1m ?? []).map(item => item.count ?? 0),
    ...(data?.series15m ?? []).map(item => item.count ?? 0),
    ...(data?.hourlyAvg ?? []).map(item => item.averageCount ?? 0),
  )
  return Math.ceil(highest / 2) * 2
}

function countAxis(max: number) {
  return { type: 'value', min: 0, minInterval: 1, splitNumber: 6, max } as const
}

export function peopleTrendOption(samples: CountSlot[], compact: boolean, axisMax: number): ChartOption {
  return {
    animation: false,
    tooltip: { trigger: 'axis' },
    grid: { left: 28, right: 18, top: 12, bottom: 20 },
    xAxis: {
      type: 'category', data: samples.map(item => beijingMinute(item.startsAt)), boundaryGap: false,
      axisLabel: { interval: compact ? 1 : 23, hideOverlap: true },
    },
    yAxis: countAxis(axisMax),
    series: [{
      type: 'line', name: copy.occupancyCount, data: samples.map(item => item.count),
      connectNulls: false, smooth: .35, lineStyle: { width: 2 },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'var(--bec-ref-comfort-area-blue-start)' },
            { offset: 1, color: 'var(--bec-ref-comfort-area-blue-end)' },
          ],
        },
      },
      symbolSize: (_value, { dataIndex }) => samples[dataIndex - 1]?.count == null && samples[dataIndex + 1]?.count == null ? 5 : 0,
    }],
  }
}

export function peopleHourlyOption(hours: AverageHour[], axisMax: number): ChartOption {
  return {
    animation: false,
    tooltip: { trigger: 'axis' },
    grid: { left: 28, right: 12, top: 12, bottom: 20 },
    xAxis: {
      type: 'category', data: hours.map(item => beijingMinute(item.startsAt).slice(0, 2)),
      axisLabel: { interval: 2, hideOverlap: true },
    },
    yAxis: countAxis(axisMax),
    series: [{
      type: 'bar', name: copy.averageOccupancy, data: hours.map(item => item.averageCount), barWidth: '58%',
      itemStyle: { borderRadius: [3, 3, 0, 0] },
    }],
  }
}

export function doorDistributionOption(data: { name: string; value: number }[], unit: string): ChartOption {
  return {
    animation: false,
    tooltip: { trigger: 'item', formatter: `{b}<br/>{c} ${unit}（{d}%）` },
    series: [{
      type: 'pie', radius: ['54%', '82%'], center: ['50%', '50%'],
      itemStyle: { borderColor: 'var(--bec-color-surface)', borderWidth: 2 },
      label: { show: false }, stillShowZeroSum: false, padAngle: 2, data,
    }],
  }
}
