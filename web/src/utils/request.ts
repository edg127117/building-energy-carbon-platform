import axios, { AxiosHeaders } from 'axios'
import { message } from 'ant-design-vue'
import type { ApiResult } from '@/types/api'

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8081/api'

export const http = axios.create({
  baseURL: API_BASE,
  timeout: 15000,
})

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

http.interceptors.response.use(
  (resp) => {
    const data = resp.data as ApiResult<unknown>
    if (!data || typeof data !== 'object') return resp

    if (data.code === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      if (!location.pathname.startsWith('/login')) {
        location.href = '/login'
      }
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
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      if (!location.pathname.startsWith('/login')) {
        location.href = '/login'
      }
      return Promise.reject(new Error('未登录或登录已过期'))
    }
    message.error(error?.message || '网络异常')
    return Promise.reject(error)
  },
)
