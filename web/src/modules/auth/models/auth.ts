export type LoginCredentials = {
  username: string
  password: string
}

export type LoginResult = {
  token: string
}

export type CurrentUser = {
  uid: number
  username: string
  roles: string[]
}

export type PasswordSetupRequest = {
  token: string
  password: string
}
