import { requestApi } from '@/infrastructure/http/public'
import type { DailyTrendDay, DailyStaticSource } from './daily-trends'

/** A malformed new pointer must not silently fall back to an unrelated legacy HOUR batch. */
export function dailyStaticSource(day: DailyTrendDay): DailyStaticSource | null {
  const source = day.staticSource ?? (day.hourlyBatchId ? {
    grain: 'HOUR' as const, calculationBatchId: day.hourlyBatchId, revision: day.hourlyRevision,
    evidenceHash: day.hourlyEvidenceHash, declaredSourceNature: day.hourlySourceNature, sourceVersion: null,
  } : null)
  if (!source || !['DAY', 'HOUR'].includes(source.grain)
      || typeof source.calculationBatchId !== 'string' || !/^[A-Za-z0-9_-]+$/.test(source.calculationBatchId)) return null
  return source
}

/** Preserve the complete API response text; only envelope metadata is parsed for normal error handling.
 * Never stringify parsed evidence: integral Java long values can exceed JavaScript's safe integer range.
 */
export function preserveEvidenceResponse(raw: unknown) {
  if (typeof raw !== 'string') throw new Error('DAILY_STATIC_EVIDENCE_INVALID_RESPONSE')
  const envelope = JSON.parse(raw) as { code?: number; errorCode?: string; data?: unknown } | null
  if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope)
      || (envelope.code === 200 && (!envelope.data || typeof envelope.data !== 'object' || Array.isArray(envelope.data)))) {
    throw new Error('DAILY_STATIC_EVIDENCE_INVALID_RESPONSE')
  }
  return { code: envelope.code, errorCode: envelope.errorCode, data: raw }
}

export function readDailyStaticEvidence(day: DailyTrendDay, signal?: AbortSignal): Promise<string> {
  const source = dailyStaticSource(day)
  if (!source) return Promise.reject(new Error('DAILY_STATIC_EVIDENCE_POINTER_MISSING'))
  const collection = source.grain === 'DAY' ? 'daily-static-calculations' : 'hourly-calculations'
  // The normal HTTP client supplies authorization. Never navigate to an unauthenticated raw API URL.
  return requestApi<string>({ method: 'get', url: `/v1/carbon-management/${collection}/${encodeURIComponent(source.calculationBatchId)}/evidence`, signal, responseType: 'text', transformResponse: preserveEvidenceResponse })
}
