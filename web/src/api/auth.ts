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

export async function registerApi(payload: RegisterReq) {
  const resp = await http.post<ApiResult<unknown>>('/auth/register', payload)
  return resp.data
}

export async function loginApi(payload: LoginReq) {
  const resp = await http.post<ApiResult<LoginResp>>('/auth/login', payload)
  return resp.data
}

export async function meApi() {
  const resp = await http.get<ApiResult<UserInfo>>('/auth/me')
  return resp.data
}

