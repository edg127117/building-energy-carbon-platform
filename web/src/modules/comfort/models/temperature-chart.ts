import type { ChartOption } from '@/shared/charts/echarts'
import { formatNumber } from '@/shared/utils/format'
import type { CurrentDayWeather } from '@/modules/weather/public'
import type { ComfortCarbonHour, ComfortTemperatureBand, ComfortTrends, PmvHour } from '../api/comfort'
import copy from '../locales/zh-CN'

export function temperaturesByHour(day: string, now: number, trend?: ComfortTrends | null, weather?: CurrentDayWeather | null) {
  const start = Date.parse(`${day}T00:00:00+08:00`)
  const indoor = new Map(trend?.hours.map(hour => [Date.parse(hour.startsAt), hour.indoorTemperature?.celsius]) ?? [])
  const outdoor = new Map(weather?.date === day ? weather.hours.map(hour => [Date.parse(hour.startsAt), hour.celsius]) : [])

  // 对齐后端小时起点。未来时段、质量屏蔽和缺测都留空，0°C 仍是有效读数。
  return Array.from({ length: 24 }, (_, hour) => {
    const at = start + hour * 3600000
    return {
      label: `${String(hour).padStart(2, '0')}:00`,
      indoor: at <= now ? indoor.get(at) ?? null : null,
      outdoor: at <= now ? outdoor.get(at) ?? null : null,
    }
  })
}

function trendLine(name: string, data: (number | null)[], lineStyle: { width: number; type?: 'dashed' }) {
  const isolated = data.map((value, index) => value !== null && data[index - 1] == null && data[index + 1] == null)
  const hasIsolatedPoint = isolated.includes(true)

  return {
    name, type: 'line' as const, data, smooth: .35, connectNulls: false, lineStyle,
    showSymbol: hasIsolatedPoint,
    symbolSize: (_value: unknown, { dataIndex }: { dataIndex: number }) => !hasIsolatedPoint || isolated[dataIndex] ? 5 : 0,
  }
}

function comfortBandAxis(hours: ReturnType<typeof temperaturesByHour>, band: ComfortTemperatureBand) {
  const values = hours.flatMap(hour => [hour.indoor, hour.outdoor]).filter(value => value != null)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || Math.abs(min)
  const low = band.tLow - .5
  const high = band.tHigh + .5

  // ECharts 不用标记区间计算量程。按已有读数的跨度补足留白，保留纵轴的自动刻度。
  if (span > 0 && Number.isFinite(span)) {
    const boundaryGap: [number, number] = [Math.max(0, min - low) / span, Math.max(0, high - max) / span]
    return { boundaryGap }
  }
  return { min: Math.min(min, low), max: Math.max(max, high) }
}

export function temperatureChartOption(
  hours: ReturnType<typeof temperaturesByHour>,
  band?: ComfortTemperatureBand | null,
): ChartOption {
  return {
    animation: false,
    aria: { enabled: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { type: 'dashed' } },
      borderWidth: 0,
      valueFormatter: value => typeof value === 'number' ? `${value.toFixed(1)} °C` : '—',
    },
    legend: { top: 0, right: 4, icon: 'circle', itemWidth: 8, itemHeight: 8, selectedMode: false },
    grid: { left: 32, right: 12, top: 24, bottom: 20 },
    xAxis: { type: 'category', data: hours.map(hour => hour.label), axisLabel: { interval: 3, hideOverlap: true } },
    yAxis: {
      type: 'value', name: copy.celsius, scale: true, splitLine: { lineStyle: { opacity: .6 } },
      ...(band && comfortBandAxis(hours, band)),
    },
    series: [
      {
        ...trendLine(copy.indoors, hours.map(hour => hour.indoor), { width: 2.2 }),
        ...(band && {
          markArea: {
            silent: true,
            itemStyle: { color: 'var(--bec-color-success)', opacity: .08 },
            data: [[{ yAxis: band.tLow }, { yAxis: band.tHigh }]],
          },
        }),
      },
      trendLine(copy.outdoors, hours.map(hour => hour.outdoor), { type: 'dashed', width: 2 }),
    ],
  }
}

export function pmvChartOption(day: string, now: number, samples: PmvHour[]): ChartOption {
  const start = Date.parse(`${day}T00:00:00+08:00`)
  const byHour = new Map(samples.map(hour => [Date.parse(hour.startsAt), hour.observation]))
  const values = Array.from({ length: 24 }, (_, hour) => {
    const point = byHour.get(start + hour * 3600000)
    return point && Date.parse(point.sampledAt) <= now ? point.value : null
  })
  const exceedsAxis = (value: number | null | undefined): value is number => value != null && Math.abs(value) > 1
  const pmvColor = 'var(--bec-ref-comfort-pmv-line)'
  const line = trendLine(copy.pmv, values, { width: 2.2 })
  const hasOverflow = values.some(exceedsAxis)
  const points = values.map(value => exceedsAxis(value) ? {
    value: Math.sign(value), symbol: 'triangle', symbolRotate: value < 0 ? 180 : 0,
    itemStyle: { color: 'var(--bec-color-error)' },
  } : value)
  return {
    animation: false,
    aria: { enabled: true },
    tooltip: {
      trigger: 'axis', axisPointer: { type: 'line', lineStyle: { type: 'dashed' } },
      valueFormatter: (_value, index) => {
        // 轴边缘的位置用于绘图，提示始终读取后端实际值。
        const actual = values[index]
        if (actual == null) return '—'
        const display = actual.toFixed(2)
        return exceedsAxis(actual) ? copy.pmvChartOverflow(display) : display
      },
    },
    grid: { left: 32, right: 12, top: 24, bottom: 20 },
    xAxis: {
      type: 'category', data: values.map((_, hour) => `${String(hour).padStart(2, '0')}:00`),
      axisLabel: { interval: 3, hideOverlap: true },
    },
    yAxis: { type: 'value', name: 'PMV', min: -1, max: 1 },
    series: [{
      ...line, data: points, showSymbol: hasOverflow || line.showSymbol, showAllSymbol: hasOverflow ? true : 'auto',
      clip: !hasOverflow,
      lineStyle: { width: 2.2, color: pmvColor },
      itemStyle: { color: pmvColor },
      symbolSize: (value, params) => exceedsAxis(values[params.dataIndex]) ? 9
        : line.showSymbol ? line.symbolSize(value, params) : 0,
      markArea: {
        silent: true, itemStyle: { color: 'var(--bec-color-success)', opacity: .11 },
        data: [[{ yAxis: -.5 }, { yAxis: .5 }]],
      },
      markLine: {
        silent: true, symbol: 'none', label: { show: false },
        lineStyle: { type: 'dashed', color: 'var(--bec-color-text-disabled)' },
        data: [{ yAxis: 0 }],
      },
    }],
  }
}

export function carbonChartOption(day: string, now: number, samples: ComfortCarbonHour[]): ChartOption {
  const start = Date.parse(`${day}T00:00:00+08:00`)
  const end = start + 86400000
  const values = samples.map(point => [Date.parse(point.observedAt), point.cumulativeKg])
    .filter(([observedAt]) => observedAt >= start && observedAt < end && observedAt <= now)
  const color = 'var(--bec-color-success)'
  const timeLabel = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
  return {
    animation: false,
    aria: { enabled: true },
    tooltip: { trigger: 'axis', valueFormatter: value => typeof value === 'number'
      ? `${formatNumber(value, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${copy.carbonUnit}` : '—' },
    legend: { top: 0, right: 4, icon: 'circle', itemWidth: 8, itemHeight: 8, selectedMode: false },
    grid: { left: 38, right: 12, top: 24, bottom: 20 },
    xAxis: { type: 'time', min: start, max: end - 1, splitNumber: 6,
      axisLabel: { formatter: time => timeLabel.format(Number(time)), hideOverlap: true } },
    yAxis: { type: 'value', min: 0, name: copy.carbonUnit },
    series: [{ name: copy.actualCarbon, type: 'line', data: values, showSymbol: values.length === 1, connectNulls: false,
      smooth: .25,
      lineStyle: { color, width: 2.2 }, itemStyle: { color },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'var(--bec-ref-comfort-area-green-start)' },
            { offset: 1, color: 'var(--bec-ref-comfort-area-green-end)' },
          ],
        },
      } }],
  }
}
