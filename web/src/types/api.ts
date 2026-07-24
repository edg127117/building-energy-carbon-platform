export type ApiResult<T> = {
  success: boolean
  code: number
  msg: string
  data: T
}

export type UserInfo = {
  uid: number
  username: string
  roles: string[]
}

