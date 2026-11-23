import { weatherApi } from './api/weather'

export { routes } from './routes'
export { default as messages } from './locales/zh-CN'
export type { CurrentDayWeather, Sample, WeatherSeries } from './models/weather'
export const listAccessibleWeatherBuildings = weatherApi.buildings
export const getBuildingHourlyWeather = weatherApi.hourly
export const getBuildingCurrentDayWeather = weatherApi.currentDay
export { chinaDay } from './models/presentation'
export { statusMessage as weatherStatusMessage } from './models/status'

/** 大屏只能读取既有小时天气查询，不暴露采集或位置管理命令。 */
export const screenWeatherQueries = {
  hourly: getBuildingHourlyWeather,
}
export { getTemperatureTrend } from './api/temperature-trend'
export { temperatureTrendValues, temperaturePeriodDates } from './models/temperature-trend'
export type { TemperatureTrend, TemperatureStatus, TemperaturePeriodType } from './models/temperature-trend'
