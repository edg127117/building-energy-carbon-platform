import type { ScreenRedesignData } from './screen-redesign'
import { periodEntry } from './screen-redesign'
import { validScreenPeriod, type ScreenPeriod } from './screen-period'

/** 电量保留原后端结果；只按相同期间合入已验证温度，月电量不均摊为日电量。 */
export function withOutdoorTemperature(data: ScreenRedesignData, period: ScreenPeriod, temperatures?: readonly (number | null)[]): ScreenRedesignData {
  if (!validScreenPeriod(period)) return data
  const [year, month] = period.value.split('-').map(Number)
  const length = period.kind === 'year' ? 12 : new Date(Date.UTC(year, month, 0)).getUTCDate()
  const base = periodEntry(data.usage, period)
  const temperature = temperatures?.length === length ? [...temperatures] : Array<number | null>(length).fill(null)
  const electricity = base?.electricity.length === length ? [...base.electricity] : Array<number | null>(length).fill(null)
  return { ...data, usage: { period, data: { electricity, temperature, electricityUnit: base?.electricityUnit ?? 'kWh', temperatureUnit: '°C' } } }
}
