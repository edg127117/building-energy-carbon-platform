import { configureHttpAuthentication, requestApi } from '@/infrastructure/http/public'
import type { PasswordSetupRequest } from '../models/auth'

export function configureAuthTransport(options: {
  getToken: () => string | null
  onUnauthorized: () => void
}): void {
  configureHttpAuthentication(options)
}

export function setupPasswordApi(payload: PasswordSetupRequest): Promise<string> {
  return requestApi<string>({ method: 'post', url: '/auth/password/setup', data: payload })
}
