import w from '../locales/zh-CN'
export const dayMillis = 86400000
export function chinaDay(date = new Date()): string { return new Date(date.getTime() + 8 * 3600000).toISOString().slice(0, 10) }
export function addDays(day: string, count: number): string { return new Date(Date.parse(`${day}T00:00:00Z`) + count * dayMillis).toISOString().slice(0, 10) }
export function midnight(day: string): string { return new Date(`${day}T00:00:00+08:00`).toISOString() }
export function historyRangeValid(range: string[] | null, max = 366): boolean {
  if (!range || range.length !== 2 || !range.every(day => /^\d{4}-\d{2}-\d{2}$/.test(day))) return false
  return range[0] >= '2024-01-01' && range[1] >= range[0] && range[1] < chinaDay()
    && (Date.parse(range[1]) - Date.parse(range[0])) / dayMillis < max
}
// 日期控件给出浏览器本地日历日期；不能将其当成业务时间戳再偏移八小时。
export function calendarDay(date: Date): string { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}` }
export function historyDateDisabled(date: Date): boolean { const day = calendarDay(date); return day < '2024-01-01' || day >= chinaDay() }
export const valueText = (value: number | null | undefined, unit = '') => value == null || !Number.isFinite(value) ? w.missing : `${value.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}${unit}`
export const timeText = (value: string | number | null | undefined) => value != null ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : w.missing
export const stateText = (value: string | null | undefined) => value ? w.states[value] ?? w.states.UNKNOWN : w.missing
export const sourceText = (value: string | null | undefined) => value ? w.sourceNames[value] ?? w.missing : w.missing
export const reasonText = (value: string | null | undefined) => value ? w.errors[value] ?? w.states[value] ?? w.states.UNKNOWN : w.missing
