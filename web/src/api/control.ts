import { http } from '@/utils/request'
import type { ApiResult } from '@/types/api'

export type IssueCommandReq = {
  deviceId: string
  commandType: number
  params: Record<string, unknown>
}

export async function issueCommandApi(payload: IssueCommandReq) {
  const resp = await http.post<ApiResult<string>>(
    `/control/issue?deviceId=${encodeURIComponent(payload.deviceId)}&commandType=${payload.commandType}`,
    payload.params,
  )
  return resp.data
}

