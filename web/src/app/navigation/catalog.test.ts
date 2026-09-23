import { describe, expect, it } from 'vitest'
import { authorizedPages } from './catalog'
import type { GrantedMenu } from '@/modules/auth/public'

let nextId = 1
const menu = (path: string, overrides: Partial<GrantedMenu> = {}): GrantedMenu => ({
  id: nextId++, menuName: '', menuType: 'C', path,
  visible: 1, status: 1, sortOrder: 0, children: [], ...overrides,
})

function tree(system: 'operations' | 'configuration', group: string, leaves: GrantedMenu[]): GrantedMenu[] {
  return [menu(`/${system}`, { menuType: 'M', children: [
    menu(`/${system}/${group}`, { menuType: 'M', children: leaves }),
  ] })]
}

describe('navigation authorization mapping', () => {
  it('uses only canonical paths under their declared workspace hierarchy', () => {
    expect(authorizedPages(tree('operations', 'devices', [
      menu('/operations/devices/pendingDevices'),
      menu('/operations/devices/businessDevices'),
    ])).map(page => page.path)).toEqual([
      '/operations/devices/pendingDevices',
      '/operations/devices/businessDevices',
    ])
  })

  it('does not grant removed legacy paths', () => {
    expect(authorizedPages([menu('/system/users')])).toEqual([])
    expect(authorizedPages([menu('/system/devices')])).toEqual([])
    expect(authorizedPages([menu('/hvac-demo')])).toEqual([])
  })

  it('rejects a registered page placed under the wrong database parent', () => {
    expect(() => authorizedPages(tree('configuration', 'ingestion', [
      menu('/operations/devices/businessDevices'),
    ]))).toThrow('INVALID_MENU_HIERARCHY')
  })

  it('does not treat a directory grant as child grants', () => {
    expect(authorizedPages([menu('/operations', { menuType: 'M' })])).toEqual([])
  })

  it('rejects duplicate and unregistered canonical paths', () => {
    expect(() => authorizedPages(tree('configuration', 'access', [
      menu('/configuration/access/users'),
      menu('/configuration/access/users'),
    ]))).toThrow('DUPLICATE_MENU_PATH')
    expect(() => authorizedPages(tree('configuration', 'access', [
      menu('/configuration/access/unknown'),
    ]))).toThrow('UNREGISTERED_MENU_PATH')
  })

  it('preserves database tree order and maintained leaf labels', () => {
    const result = authorizedPages(tree('configuration', 'access', [
      menu('/configuration/access/roles', { menuName: '角色授权' }),
      menu('/configuration/access/users', { menuName: '用户维护' }),
    ]))
    expect(result.map(page => page.path)).toEqual([
      '/configuration/access/roles',
      '/configuration/access/users',
    ])
    expect(result.map(page => page.title)).toEqual(['角色授权', '用户维护'])
  })

  it('does not render children of disabled or hidden directories', () => {
    expect(authorizedPages([menu('/configuration', { menuType: 'M', status: 0, children: [
      menu('/configuration/access', { menuType: 'M', children: [menu('/configuration/access/users')] }),
    ] })])).toEqual([])
  })
})
