import { TransportError } from '@/infrastructure/http/public'
import errorMessages from '@/locales/zh-CN/error'

/** 将经过脱敏的传输失败映射为可操作中文，不向用户暴露后端内部异常。 */
export function requestErrorMessage(reason: unknown): string {
  if (!(reason instanceof TransportError)) return errorMessages.request
  if (reason.kind === 'unauthorized') return errorMessages.unauthorized
  if (reason.kind === 'forbidden') return errorMessages.forbidden
  if (reason.kind === 'network') return errorMessages.network
  if (reason.status === 409) return errorMessages.conflict
  if (reason.status === 429) return errorMessages.tooManyRequests
  if (reason.status === 504) return errorMessages.timeout
  return errorMessages.request
}

export function requestErrorStatus(reason: unknown): number | null {
  return reason instanceof TransportError ? reason.status ?? null : null
}
