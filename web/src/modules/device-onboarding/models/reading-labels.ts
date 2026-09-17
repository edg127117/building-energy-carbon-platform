import { t } from '@/locales'

const readingStatuses = new Set(['HAS_DATA', 'NO_DATA', 'DISABLED'])
const readingReasons = new Set(['POINT_DISABLED', 'NO_RAW_EVENT', 'NO_ACTIVE_POLICY_DEFAULT', 'QUALITY_NOT_ALLOWED_BY_DEFAULT', 'QUALITY_ALLOWED', 'QUALITY_NOT_ALLOWED'])
const identityTypes = new Set(['SN', 'MAC', 'DAIKIN_UNIT'])
const syncJobStatuses = new Set(['QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED'])

/** 只解释后端状态，不将有历史读数推断为在线，也不把质量使用策略与采集策略混为一谈。 */
export function readingStatusText(value: string): string {
  return t(`deviceOnboarding.readingStatus.${readingStatuses.has(value) ? value : 'unknown'}`)
}

export function readingReasonText(value: string | null): string {
  if (!value) return t('common.missing')
  return t(`deviceOnboarding.readingReason.${readingReasons.has(value) ? value : 'unknown'}`)
}

export function identityTypeText(value: string): string {
  return t(`deviceOnboarding.identityTypeName.${identityTypes.has(value) ? value : 'unknown'}`)
}

export function syncJobStatusText(value: string): string {
  return t(`deviceOnboarding.syncJobStatus.${syncJobStatuses.has(value) ? value : 'unknown'}`)
}
