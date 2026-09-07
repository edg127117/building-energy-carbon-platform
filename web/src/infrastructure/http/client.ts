import axios, { type AxiosRequestConfig } from 'axios'

export type ApiResult<T> = {
  success: boolean
  code: number
  msg: string
  data: T
}

export class TransportError extends Error {
  constructor(
    readonly kind: 'unauthorized' | 'forbidden' | 'network' | 'request',
    readonly status?: number,
    readonly businessCode?: number,
  ) {
    super(kind)
  }
}

type AuthenticationBridge = {
  getToken: () => string | null
  onUnauthorized: () => void
}

const authenticationBridge: AuthenticationBridge = {
  getToken: () => localStorage.getItem('token'),
  onUnauthorized: () => undefined,
}

/** 应用层在 Pinia 和 Router 就绪后注入认证桥接，传输层不反向依赖业务模块。 */
export function configureHttpAuthentication(bridge: AuthenticationBridge): void {
  authenticationBridge.getToken = bridge.getToken
  authenticationBridge.onUnauthorized = bridge.onUnauthorized
}

/** 传输层不导入页面、文案或全局认证状态；契约解析和业务错误标识映射归对应模块。 */
export function createHttpClient(baseURL: string, getToken: () => string | null) {
  const client = axios.create({ baseURL })
  client.interceptors.request.use(config => {
    const token = getToken()
    if (token) config.headers.set('Authorization', `Bearer ${token}`)
    return config
  })
  return {
    async request<T>(config: AxiosRequestConfig): Promise<T> {
      try {
        return (await client.request<T>(config)).data
      } catch (reason) {
        if (axios.isCancel(reason)) throw reason
        const status = axios.isAxiosError(reason) ? reason.response?.status : undefined
        const kind = status === 401 ? 'unauthorized' : status === 403 ? 'forbidden' : status ? 'request' : 'network'
        // 不向展示层透传服务端异常原文，避免泄漏内部信息。
        throw new TransportError(kind, status)
      }
    },
  }
}

const platformClient = createHttpClient(
  import.meta.env.VITE_API_BASE ?? 'http://localhost:8081/api',
  () => authenticationBridge.getToken(),
)

function classifyBusinessFailure(code: number): TransportError {
  const kind = code === 401 ? 'unauthorized' : code === 403 ? 'forbidden' : 'request'
  return new TransportError(kind, code, code)
}

/**
 * 请求平台统一 Result 契约并只返回 data。
 *
 * 后端异常原文不会穿透到页面；401 只通过注入桥接清理会话，最终权限仍由后端判定。
 */
export async function requestApi<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const result = await platformClient.request<ApiResult<T>>(config)
    if (!result || typeof result !== 'object') throw new TransportError('request')
    if (result.code === 200 || result.success === true) return result.data
    throw classifyBusinessFailure(result.code)
  } catch (reason) {
    if (reason instanceof TransportError && reason.kind === 'unauthorized') {
      authenticationBridge.onUnauthorized()
    }
    throw reason
  }
}
