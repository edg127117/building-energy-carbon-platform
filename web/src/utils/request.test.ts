import { describe, expect, it } from 'vitest'
import { requestErrorMessage } from './request'

describe('request error presentation', () => {
  it('uses the backend business message instead of exposing the HTTP status', () => {
    expect(requestErrorMessage({
      message: 'Request failed with status code 409',
      response: { status: 409, data: { msg: '不能禁用当前登录管理员' } },
    })).toBe('不能禁用当前登录管理员')
  })

  it('translates status and network failures when the backend has no message', () => {
    expect(requestErrorMessage({ response: { status: 409 } })).toBe('当前状态不允许此操作，请刷新后重试')
    expect(requestErrorMessage({ response: { status: 503 } })).toBe('服务暂时不可用，请稍后重试')
    expect(requestErrorMessage({ code: 'ECONNABORTED' })).toBe('请求超时，请稍后重试')
    expect(requestErrorMessage(new Error('Network Error'))).toBe('网络连接失败，请检查网络后重试')
  })
})
