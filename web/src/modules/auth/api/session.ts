import { createHttpClient, TransportError } from '@/infrastructure/http/client'
import type { GrantedMenu, SessionUser } from '../models/session'
interface Envelope<T> { code: number; data: T }
/** 复用现有 /api 契约，同时处理 HTTP 与业务信封的鉴权错误；不透传后端内部错误文案。 */
export function sessionApi(getToken: () => string | null) {
  const http = createHttpClient(import.meta.env.VITE_API_BASE || '/api', getToken)
  async function request<T>(url: string, method = 'GET', data?: unknown) {
    const result = await http.request<Envelope<T>>({ url, method, data })
    if (result.code !== 200) throw new TransportError(result.code === 401 ? 'unauthorized' : result.code === 403 ? 'forbidden' : 'request', result.code)
    return result.data
  }
  return {
    login: (username: string, password: string) => request<{ token: string }>('/auth/login', 'POST', { username, password }),
    me: () => request<SessionUser>('/auth/me'),
    menus: () => request<GrantedMenu[]>('/menu/current'),
    logout: () => request('/auth/logout', 'POST'),
  }
}
export { TransportError }
