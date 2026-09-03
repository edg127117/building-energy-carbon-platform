import { t } from '@/locales'
const locale = 'zh-CN'

// 时区必须由数据契约提供；未传时沿用当前浏览器，不猜测业务统计日界。
export function formatDateTime(value: Date | number | string, timeZone?: string): string {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return t('common.missing')
  return new Intl.DateTimeFormat(locale, {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, timeZone,
  }).format(date)
}

export function formatNumber(value: number | null | undefined, options?: Intl.NumberFormatOptions): string {
  return value == null || !Number.isFinite(value) ? t('common.missing') : new Intl.NumberFormat(locale, options).format(value)
}
