import { http } from '@/utils/request'
import type { ApiResult, UserInfo } from '@/types/api'

export type RegisterReq = {
  username: string
  password: string
  nickname?: string
}

export type LoginReq = {
  username: string
  password: string
}

export type LoginResp = {
  token: string
}

/**
 * 调用公开的 `/auth/register` 创建账号。
 * 后端写入 MySQL、授予 BUILDING_OWNER 角色但不授予建筑；本函数不保存登录状态。
 */
export async function registerApi(payload: RegisterReq) {
  const resp = await http.post<ApiResult<unknown>>('/auth/register', payload)
  return resp.data
}

/** 调用公开的 `/auth/login` 获取 JWT；Token 的浏览器持久化由认证 Store 负责。 */
export async function loginApi(payload: LoginReq) {
  const resp = await http.post<ApiResult<LoginResp>>('/auth/login', payload)
  return resp.data
}

/**
 * 调用 `/auth/me` 读取 JWT 中的当前用户和正式角色。
 * Bearer Token 由统一请求拦截器注入，401 会触发本地认证清理和登录页跳转。
 */
export async function meApi() {
  const resp = await http.get<ApiResult<UserInfo>>('/auth/me')
  return resp.data
}
