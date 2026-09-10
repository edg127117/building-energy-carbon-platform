export interface SessionUser { id: number; username: string; roles: string[] }
/** 后端当前菜单树；导航可见性不替代接口角色与建筑范围鉴权。 */
export interface GrantedMenu {
  id: number; menuName: string; menuType: string; path: string | null
  visible: number; status: number; sortOrder: number | null; children?: GrantedMenu[]
}
