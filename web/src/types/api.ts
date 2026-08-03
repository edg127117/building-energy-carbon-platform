/** 后端统一 Result 响应包装；业务失败仍可能通过 HTTP 200 返回非 200 业务码。 */
export type ApiResult<T> = {
  success: boolean
  code: number
  msg: string
  data: T
}

/**
 * 认证 Store 持久化的最小用户视图。
 * 路由只消费角色和 Token 状态，用户标识不参与前端权限判断，最终权限仍由后端校验。
 */
export type UserInfo = {
  uid: number
  username: string
  roles: string[]
}
