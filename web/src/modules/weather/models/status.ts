import type { Forecast, WeatherSeries } from './weather'
import { chinaDay, stateText, timeText } from './presentation'
import w from '../locales/zh-CN'

type Status = Pick<WeatherSeries, 'status' | 'updateStatus' | 'fetchedAt'>
export function statusMessage(data: Status, missing?: string): string {
  const saved = data.fetchedAt ? w.showingData.replace('{time}', timeText(data.fetchedAt)) : w.noSavedData
  const completeness = missing || (data.status === 'READY' ? '' : w.dataPrefix + stateText(data.status))
  const update = data.updateStatus === 'FAILED' ? `${w.updateFailed}，${saved}`
    : data.updateStatus === 'FRESH' ? w.updateSucceeded
      : data.updateStatus === 'OVERDUE' ? `${w.dataExpired}，${saved}` : w.updatePrefix + stateText(data.updateStatus)
  return [completeness, update].filter(Boolean).join(' · ')
}
export function forecastMessage(data: Forecast): string {
  const missing = data.days.flatMap(day => {
    if (!day.sample) return [w.missingFields.replace('{fields}', day.date)]
    const fields = [day.sample.values.temperature_2m_max == null ? w.maxTemperature : '',
      day.sample.values.temperature_2m_min == null ? w.minTemperature : '',
      day.sample.values.weather_code == null && !day.sample.text.weather?.trim() ? w.weather : ''].filter(Boolean)
    return fields.length ? [w.missingFields.replace('{fields}', `${day.date === chinaDay() ? w.today : day.date}${fields.join('、')}`)] : []
  })
  return statusMessage(data, missing.join('；'))
}
