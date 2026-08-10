import axios, { AxiosHeaders } from 'axios'
import { message } from 'ant-design-vue'
import { expireBrowserSession } from '@/auth/session'
import type { ApiResult } from '@/types/api'

/**
 * 全站 HTTP 客户端的认证与错误副作用边界。
 *
 * API 基地址来自 Vite 环境变量；请求统一读取 localStorage JWT，响应统一处理业务码、
 * 网络错误和 401 清理。该客户端只负责前端会话体验，不能替代后端权限校验。
 */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8081/api'

export const http = axios.create({
  baseURL: API_BASE,
  timeout: 15000,
})

/** 为每次 API 请求注入浏览器中现存的 Bearer Token；无 Token 时保持匿名请求。 */
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers = AxiosHeaders.from(
      config.headers ?? {},
    )
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

/**
 * 统一处理后端 Result 业务失败和 HTTP 网络失败。
 * 任一 401 都清除 Token/用户视图并跳转登录页；其他失败显示消息并向调用方拒绝 Promise。
 */
http.interceptors.response.use(
  (resp) => {
    const data = resp.data as ApiResult<unknown>
    if (!data || typeof data !== 'object') return resp

    if (data.code === 401) {
      expireBrowserSession()
      return Promise.reject(new Error(data.msg || '未登录或登录已过期'))
    }

    if (data.code !== 200 && data.success !== true) {
      message.error(data.msg || '请求失败')
      return Promise.reject(new Error(data.msg || '请求失败'))
    }

    return resp
  },
  (error) => {
    const status = error?.response?.status
    if (status === 401) {
      expireBrowserSession()
      return Promise.reject(new Error('未登录或登录已过期'))
    }
    message.error(error?.message || '网络异常')
    return Promise.reject(error)
  },
)
