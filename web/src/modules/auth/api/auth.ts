import { configureHttpAuthentication, requestApi } from '@/infrastructure/http/public'
import type { CurrentUser, LoginCredentials, LoginResult, PasswordSetupRequest } from '../models/auth'

export function configureAuthTransport(options: {
  getToken: () => string | null
  onUnauthorized: () => void
}): void {
  configureHttpAuthentication(options)
}

export function loginApi(credentials: LoginCredentials): Promise<LoginResult> {
  return requestApi<LoginResult>({ method: 'post', url: '/auth/login', data: credentials })
}

export function currentUserApi(token?: string | null): Promise<CurrentUser> {
  return requestApi<CurrentUser>({
    method: 'get',
    url: '/auth/me',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  })
}

export function logoutApi(token?: string | null): Promise<string> {
  return requestApi<string>({
    method: 'post',
    url: '/auth/logout',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  })
}

export function setupPasswordApi(payload: PasswordSetupRequest): Promise<string> {
  return requestApi<string>({ method: 'post', url: '/auth/password/setup', data: payload })
}
