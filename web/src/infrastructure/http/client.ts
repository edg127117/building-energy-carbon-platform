import axios, { type AxiosRequestConfig } from 'axios'

export class TransportError extends Error {
  constructor(readonly kind: 'unauthorized' | 'forbidden' | 'network' | 'request', readonly status?: number) {
    super(kind)
  }
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
