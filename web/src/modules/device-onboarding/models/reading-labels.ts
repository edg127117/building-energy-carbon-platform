import { t } from '@/locales'

const readingStatuses = new Set(['HAS_DATA', 'NO_DATA', 'DISABLED'])
const readingReasons = new Set(['POINT_DISABLED', 'NO_RAW_EVENT', 'NO_ACTIVE_POLICY_DEFAULT', 'QUALITY_NOT_ALLOWED_BY_DEFAULT', 'QUALITY_ALLOWED', 'QUALITY_NOT_ALLOWED'])
const identityTypes = new Set(['SN', 'MAC', 'DAIKIN_UNIT'])
const syncJobStatuses = new Set(['QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED'])
const retryableSyncErrors = new Set([
  'DAIKIN_SYNC_CONCURRENCY_LIMIT', 'DAIKIN_SYNC_INTERRUPTED', 'DAIKIN_SYNC_LEASE_EXPIRED',
  'DAIKIN_SYNC_LEASE_LOST', 'DAIKIN_SYNC_TRANSPORT_FAILURE', 'DAIKIN_SYNC_UPSTREAM_FAILED',
])
const configurationSyncErrors = new Set([
  'DAIKIN_SYNC_CLIENT_DISABLED', 'DAIKIN_SYNC_DISABLED',
  'DAIKIN_SYNC_PROVIDER_UNAVAILABLE', 'DAIKIN_SYNC_WIRE_NOT_CONFIRMED',
])
const authorizationSyncErrors = new Set([
  'DAIKIN_SYNC_AUTHENTICATION_REQUIRED', 'DAIKIN_SYNC_INVALID_TOKEN_RESPONSE', 'DAIKIN_SYNC_REMOTE_REJECTED',
])
const invalidCatalogSyncErrors = new Set([
  'DAIKIN_SYNC_CATALOG_INVALID', 'DAIKIN_SYNC_INVALID_REQUEST',
  'DAIKIN_SYNC_INVALID_RESPONSE', 'DAIKIN_SYNC_RESPONSE_TOO_LARGE',
])
const daikinProfiles = new Set(['DAIKIN_INDOOR_V2', 'DAIKIN_OUTDOOR_V2'])

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

export function syncJobResultText(errorCode: string | null): string {
  if (!errorCode) return t('deviceOnboarding.syncJobResult.normal')
  if (retryableSyncErrors.has(errorCode)) return t('deviceOnboarding.syncJobResult.retryable')
  if (configurationSyncErrors.has(errorCode)) return t('deviceOnboarding.syncJobResult.configuration')
  if (authorizationSyncErrors.has(errorCode)) return t('deviceOnboarding.syncJobResult.authorization')
  if (invalidCatalogSyncErrors.has(errorCode)) return t('deviceOnboarding.syncJobResult.invalidCatalog')
  if (errorCode === 'DAIKIN_SYNC_PERSISTENCE_FAILED') return t('deviceOnboarding.syncJobResult.persistence')
  return t('deviceOnboarding.syncJobResult.unknown')
}

export function profileText(value: string): string {
  return t(`deviceOnboarding.profileName.${daikinProfiles.has(value) ? value : 'unknown'}`)
}
