import type { ChartOption } from '@/shared/charts/echarts'
import type { ComparisonDay, WeatherSeries } from './weather'
import w from '../locales/zh-CN'
export const metrics = [
  { key: 'temperature_2m', label: w.temperature, unit: '°C' }, { key: 'relative_humidity_2m', label: w.humidity, unit: '%' },
  { key: 'precipitation', label: w.precipitation, unit: 'mm' }, { key: 'shortwave_radiation', label: w.radiation, unit: 'W/m²' },
  { key: 'wind_speed_10m', label: w.wind, unit: 'm/s' },
]
/** 补齐的是时间槽而非气象数值，保证上游省略整点时折线仍会断开。 */
export function hourlyOption(series: WeatherSeries[], from: string, to: string, metricKey: string): ChartOption {
  const metric = metrics.find(m => m.key === metricKey) ?? metrics[0]
  const samples = new Map(series.flatMap(s => s.samples).map(s => [Date.parse(s.time), s]))
  const times: number[] = []
  for (let time = Date.parse(from); time < Date.parse(to); time += 3600000) times.push(time)
  return {
    tooltip: { trigger: 'axis' }, grid: { left: 60, right: 24, top: 36, bottom: 48 },
    xAxis: { type: 'category', data: times.map(time => new Date(time).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', hour12: false })) },
    yAxis: { type: 'value', name: metric.unit, scale: true },
    series: [{ name: metric.label, type: metricKey === 'precipitation' ? 'bar' : 'line', connectNulls: false, showSymbol: false, data: times.map(time => samples.get(time)?.values[metricKey] ?? null) }],
  }
}
export function comparisonOption(days: ComparisonDay[], metric: string): ChartOption {
  const label = metric === 'temperature' ? w.meanTemperature : w.meanHumidity
  return {
    tooltip: { trigger: 'axis' }, legend: { data: [w.exact, w.approximate, label] }, grid: { left: 64, right: 64, top: 60, bottom: 48 },
    xAxis: { type: 'category', data: days.map(d => d.date) },
    yAxis: [{ type: 'value', name: 'kWh' }, { type: 'value', name: metric === 'temperature' ? '°C' : '%', scale: true }],
    series: [
      { name: w.exact, type: 'bar', stack: 'energy', data: days.map(d => d.energy?.approximate ? null : d.energyKwh) },
      { name: w.approximate, type: 'bar', stack: 'energy', itemStyle: { decal: { symbol: 'rect', dashArrayX: [1, 0], dashArrayY: [2, 4], rotation: -Math.PI / 4 } }, data: days.map(d => d.energy?.approximate ? d.energyKwh : null) },
      { name: label, type: 'line', yAxisIndex: 1, connectNulls: false, data: days.map(d => d.weatherValue) },
    ],
  }
}
