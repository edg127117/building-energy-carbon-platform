import { t } from '@/locales'

export interface ScreenPeriod {
  kind: 'year' | 'month' | 'day'
  value: string
}

export function currentScreenPeriod(): ScreenPeriod {
  const year = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Shanghai', year: 'numeric' }).format(new Date())
  return { kind: 'year', value: year }
}

export function currentBeijingDate(): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
  return parts
}

export function followBeijingDay(period: ScreenPeriod, previousToday: string, today: string): ScreenPeriod {
  return period.kind === 'day' && period.value === previousToday ? { kind: 'day', value: today } : period
}

export function validScreenPeriod(period: ScreenPeriod): boolean {
  if (period.kind === 'year') return /^(20\d{2}|21\d{2}|2200)$/.test(period.value)
  if (period.kind === 'month') return /^(20\d{2}|21\d{2}|2200)-(0[1-9]|1[0-2])$/.test(period.value)
  if (!/^(20\d{2}|21\d{2}|2200)-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/.test(period.value)) return false
  const [year, month, day] = period.value.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day)).toISOString().slice(0, 10) === period.value
}

/** 月视图的日刻度仍表示自然日；只有两个 B 版趋势卡使用 day 作为独立小时查询。 */
export function periodBuckets(period: ScreenPeriod): string[] {
  if (!validScreenPeriod(period)) return []
  if (period.kind === 'year') return Array.from({ length: 12 }, (_, index) => t('largeScreen.periodMonthTick', { value: index + 1 }))
  if (period.kind === 'day') return Array.from({ length: 24 }, (_, hour) => `${String(hour).padStart(2, '0')}:00`)
  const [year, month] = period.value.split('-').map(Number)
  const days = new Date(Date.UTC(year, month, 0)).getUTCDate()
  return Array.from({ length: days }, (_, index) => t('largeScreen.periodDayTick', { value: index + 1 }))
}
