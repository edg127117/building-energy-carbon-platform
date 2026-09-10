import { describe, expect, it } from 'vitest'
import { authorizedPages } from './catalog'
import type { GrantedMenu } from '@/modules/auth/public'
const menu = (path: string, overrides: Partial<GrantedMenu> = {}): GrantedMenu => ({
  id: 1, menuName: '', menuType: 'C', path, visible: 1, status: 1, sortOrder: 0, ...overrides,
})
describe('navigation authorization mapping', () => {
  it('maps legacy access one-to-one, not to every configuration page', () => {
    expect(authorizedPages([menu('/system/users')]).map(page => page.path)).toEqual(['/configuration/access/users'])
    expect(authorizedPages([menu('/hvac-demo')])).toEqual([])
  })
  it('does not treat a directory grant as child grants', () => {
    expect(authorizedPages([menu('/operations', { menuType: 'M' })])).toEqual([])
  })
  it('rejects duplicate and unregistered new paths', () => {
    expect(() => authorizedPages([menu('/monitor/trend'), menu('/monitor/trend')])).toThrow('DUPLICATE_MENU_PATH')
    expect(() => authorizedPages([menu('/monitor/unknown')])).toThrow('UNREGISTERED_MENU_PATH')
  })
  it('reflects maintained new-path labels and leaf order', () => {
    const result = authorizedPages([
      menu('/configuration/access/users', { menuName: 'custom-label', sortOrder: 2 }),
      menu('/configuration/access/roles', { sortOrder: 1 }),
    ])
    expect(result.map(page => page.path)).toEqual(['/configuration/access/roles', '/configuration/access/users'])
    expect(result[1].title).toBe('custom-label')
  })
  it('does not render children of disabled or hidden directories', () => {
    expect(authorizedPages([menu('/configuration', { menuType: 'M', status: 0, children: [menu('/configuration/access/users')] })])).toEqual([])
  })
})
