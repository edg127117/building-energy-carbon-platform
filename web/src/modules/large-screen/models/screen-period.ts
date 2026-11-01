import { t } from '@/locales'

export interface ScreenPeriod {
  kind: 'year' | 'month'
  value: string
}

export function currentScreenPeriod(): ScreenPeriod {
  const year = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Shanghai', year: 'numeric' }).format(new Date())
  return { kind: 'year', value: year }
}

export function validScreenPeriod(period: ScreenPeriod): boolean {
  if (period.kind === 'year') return /^(20\d{2}|21\d{2}|2200)$/.test(period.value)
  return /^(20\d{2}|21\d{2}|2200)-(0[1-9]|1[0-2])$/.test(period.value)
}

/** 月视图的日刻度不是单日查询模式；所有卡片只接受年/月期间。 */
export function periodBuckets(period: ScreenPeriod): string[] {
  if (!validScreenPeriod(period)) return []
  if (period.kind === 'year') return Array.from({ length: 12 }, (_, index) => t('largeScreen.periodMonthTick', { value: index + 1 }))
  const [year, month] = period.value.split('-').map(Number)
  const days = new Date(Date.UTC(year, month, 0)).getUTCDate()
  return Array.from({ length: days }, (_, index) => t('largeScreen.periodDayTick', { value: index + 1 }))
}
