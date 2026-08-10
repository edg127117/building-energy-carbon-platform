/**
 * 收敛 HTTP 与 WebSocket 的登录失效副作用。
 *
 * JWT 只保存在浏览器会话视图；任一受保护通道确认 401 或 4401 后都经由此处清理，
 * 避免实时连接保留已失效身份而 HTTP 已跳转登录页的状态分叉。
 */
export function expireBrowserSession(): void {
  localStorage.removeItem('token')
  localStorage.removeItem('userInfo')
  if (window.location.pathname !== '/login') window.location.href = '/login'
}
